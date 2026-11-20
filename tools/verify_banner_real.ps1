# 真机验证「窗口内提醒」：改设置 → 重启 → 等提醒触发 → 用 PrintWindow 抓主窗口
#
# 为什么用 PrintWindow 而不是 CopyFromScreen：本机桌面常驻多层置顶窗口
# （资源管理器、浏览器、WorkBuddy…），屏幕截图会被盖住；PrintWindow
# 直接问 WebView2 要画面，只要窗口没最小化就能拿到完整内容。
#
# 用法： .\tools\verify_banner_real.ps1 -Mode 1 -Tag b1
param(
    [ValidateSet(0, 1, 2)][int]$Mode = 1,
    [string]$Tag = 'b1',
    [string]$Shots = '6,8,10,12',
    # 抓主窗口画面（PrintWindow）。实测在对主窗口 PrintWindow 期间，浮窗
    # 偶尔会被「滑出」收走，所以想安心看浮窗存续时用 -NoGrab 只读矩形。
    [switch]$NoGrab
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$exe = 'D:\stumate-lite-target\release\stumate-lite.exe'
$settingsPath = Join-Path $env:APPDATA 'StuMate\settings.lite.json'
$outDir = Join-Path $root 'design-preview\preview'

Add-Type -AssemblyName System.Drawing
$src = @'
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public class VB {
  public delegate bool EnumProc(IntPtr h, IntPtr p);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr p);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  // 必须 CharSet.Unicode：默认按 ANSI 编组时，宽字符里的 \0 会把标题截成
  // 首字母（就是「title=S」那种诡异输出），后面按标题认窗口的业务全线失灵。
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassNameW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint f);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
  public struct Info { public long H; public int W; public int Ht; public string Title; public string Cls; public bool Vis; }
  // 返回「一行一个窗口」的字符串而不是结构体：结构体里的 StringBuilder 字段
  // 跨 P/Invoke 回来会被截断成首字符，标题和 class 就废了。字符串最稳。
  public static List<string> All(uint target) {
    var list = new List<string>();
    EnumWindows((h, p) => {
      uint pid; GetWindowThreadProcessId(h, out pid);
      if (pid != target) return true;
      RECT r; GetWindowRect(h, out r);
      var t = new StringBuilder(256); GetWindowTextW(h, t, 256);
      var c = new StringBuilder(256); GetClassNameW(h, c, 256);
      list.Add(String.Join("|", new string[] {
        h.ToInt64().ToString(),
        (r.R - r.L).ToString(),
        (r.B - r.T).ToString(),
        r.L.ToString(),
        r.T.ToString(),
        IsWindowVisible(h) ? "1" : "0",
        c.ToString(),
        t.ToString()
      }));
      return true;
    }, IntPtr.Zero);
    return list;
  }
  public static bool Grab(long hwnd, string path) {
    RECT r; if (!GetWindowRect((IntPtr)hwnd, out r)) return false;
    int w = r.R - r.L, ht = r.B - r.T;
    if (w <= 0 || ht <= 0) return false;
    using (var bmp = new System.Drawing.Bitmap(w, ht))
    using (var g = System.Drawing.Graphics.FromImage(bmp)) {
      IntPtr dc = g.GetHdc();
      bool ok = PrintWindow((IntPtr)hwnd, dc, 2);
      g.ReleaseHdc(dc);
      if (!ok) return false;
      bmp.Save(path, System.Drawing.Imaging.ImageFormat.Png);
    }
    return true;
  }
}
'@
Add-Type -TypeDefinition $src -ReferencedAssemblies System.Drawing
[VB]::SetProcessDPIAware() | Out-Null

# 窗口尺寸会随 DPI 虚拟化翻倍（473×184 可能报成 946×367），所以按**标题**
# 认窗口，不按尺寸：浮窗标题是「StuMate Lite 提醒」，主窗是「StuMate Lite」。
function Get-Wins([uint32]$procId) {
    [VB]::All($procId) | ForEach-Object {
        $f = $_ -split '\|'
        [pscustomobject]@{
            H     = [int64]$f[0]
            W     = [int]$f[1]
            Ht    = [int]$f[2]
            X     = [int]$f[3]
            Y     = [int]$f[4]
            Vis   = ($f[5] -eq '1')
            Cls   = $f[6]
            Title = $f[7]
        }
    }
}

# --- 1. 写设置 ---
$raw = Get-Content -Path $settingsPath -Raw -Encoding UTF8 | ConvertFrom-Json
$raw | Add-Member -NotePropertyName reminderMode -NotePropertyValue $Mode -Force
# 必须无 BOM：Rust 侧 serde_json 不认 BOM，带 BOM 会整份读取失败 → 回落默认值
# （默认 reminderMode 是 0，会让你以为「mode 1 不生效」）。
$json = $raw | ConvertTo-Json -Depth 10
[System.IO.File]::WriteAllText($settingsPath, $json, (New-Object System.Text.UTF8Encoding($false)))
"设置已写入 reminderMode=$Mode（无 BOM）"

# --- 2. 重启 ---
Get-Process stumate-lite -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 600
$p = Start-Process -FilePath $exe -PassThru
"已启动 PID=$($p.Id)"

# --- 3. 分时抓帧（横幅 14s 后自动消失，抓一次容易错过） ---
$started = Get-Date
foreach ($t in ($Shots -split ',')) {
    $sec = [double]$t
    $left = $sec - ((Get-Date) - $started).TotalSeconds
    if ($left -gt 0) { Start-Sleep -Milliseconds ([int]($left * 1000)) }

    $wins = Get-Wins([uint32]$p.Id)
    $overlay = $wins | Where-Object { $_.Title -like '*提醒*' -and $_.Vis }
    $main = $wins | Where-Object { $_.Title -eq 'StuMate Lite' -and $_.Vis } | Sort-Object { $_.W * $_.Ht } -Descending | Select-Object -First 1
    $png = Join-Path $outDir "banner-real-$Tag-t$t.png"
    if ($main) {
        $okShot = if ($NoGrab) { 'skipped' } else { [VB]::Grab($main.H, $png) }
        $ovText = if ($overlay) { ($overlay | ForEach-Object { "$($_.W)x$($_.Ht)@$($_.X),$($_.Y)" }) -join ',' } else { '无' }
        "t=${t}s 浮窗=$ovText 主窗=$($main.W)x$($main.Ht) 截图=$okShot"
    } else {
        "t=${t}s 浮窗=$(if ($overlay) { '有' } else { '无' }) 找不到主窗口"
    }
}

# --- 4. 最终窗口清单 ---
"---- 窗口清单 ----"
Get-Wins([uint32]$p.Id) | ForEach-Object { "  {0,-8} {1,5}x{2,-5}@{3},{4} vis={5,-5} class={6,-20} title={7}" -f $_.H, $_.W, $_.Ht, $_.X, $_.Y, $_.Vis, $_.Cls, $_.Title }