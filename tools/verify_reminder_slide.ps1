# 提醒动效验收：滑入轨迹 + 真实窗口截图（v1.7.28）
#
# 用法：
#   pwsh -NoProfile -File tools\verify_reminder_slide.ps1 -Mode 0 -Tag global
#   pwsh -NoProfile -File tools\verify_reminder_slide.ps1 -Mode 1 -Tag inline
#   pwsh -NoProfile -File tools\verify_reminder_slide.ps1 -Mode 2 -Tag off
#
# 为什么要外部测量：动效发生在**窗口管理器**那一层（Rust 挪窗口）和
# CSS 那一层（横幅关键帧），应用自己不会输出「第几帧在哪」。
# 这里从外面按 20ms 采 GetWindowRect，才拿得到真实的 y 序列 ——
# 光看一张终态截图，分不清「滑下来」和「直接出现」。
param(
  [int]$Mode = 0,
  [string]$Tag = "global",
  [int]$WaitSeconds = 40
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public class WX {
  public delegate bool EnumProc(IntPtr h, IntPtr p);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr p);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint f);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
  public static List<long> All(uint target) {
    var list = new List<long>();
    EnumWindows((h, p) => {
      uint pid; GetWindowThreadProcessId(h, out pid);
      if (pid == target) list.Add(h.ToInt64());
      return true;
    }, IntPtr.Zero);
    return list;
  }
  public static string Title(long h) { var sb = new StringBuilder(256); GetWindowTextW((IntPtr)h, sb, 256); return sb.ToString(); }
  public static int[] Rect(long h) { RECT r; GetWindowRect((IntPtr)h, out r); return new int[]{ r.L, r.T, r.R - r.L, r.B - r.T }; }
  public static bool Visible(long h) { return IsWindowVisible((IntPtr)h); }
}
'@
[void][WX]::SetProcessDPIAware()

$root = Split-Path -Parent $PSScriptRoot
$exe = "D:\stumate-lite-target\release\stumate-lite.exe"
$settings = Join-Path $env:APPDATA "StuMate\settings.lite.json"
$shots = Join-Path $root "design-preview\preview\slide"
New-Item -ItemType Directory -Force -Path $shots | Out-Null

# 1) 写入提醒方式（直接改设置文件：设置页要点鼠标，验收脚本不该依赖 UI 自动化）
$json = Get-Content $settings -Raw -Encoding utf8 | ConvertFrom-Json
$json | Add-Member -NotePropertyName reminderMode -NotePropertyValue $Mode -Force
($json | ConvertTo-Json -Depth 10) | Set-Content -Path $settings -Encoding utf8
"settings.reminderMode = $Mode"

# 2) 干净启动
Get-Process stumate-lite -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 600
$proc = Start-Process $exe -PassThru
$appPid = $proc.Id
"PID=$appPid"

# 3) 找目标窗口：浮窗 460x176（带边框约 473x184），主窗口约 1213x808
function Find-Window([int]$pidValue, [string]$kind) {
  foreach ($h in [WX]::All([uint32]$pidValue)) {
    $r = [WX]::Rect($h)
    if ($kind -eq "overlay" -and $r[2] -ge 450 -and $r[2] -le 510 -and $r[3] -ge 150 -and $r[3] -le 215) { return $h }
    if ($kind -eq "main" -and $r[2] -ge 900 -and $r[3] -ge 600) { return $h }
  }
  return 0
}

$overlay = 0
$main = 0
$deadline = (Get-Date).AddSeconds($WaitSeconds)
while ((Get-Date) -lt $deadline) {
  if ($Mode -eq 0) { $overlay = Find-Window $appPid "overlay"; if ($overlay -ne 0) { break } }
  else {
    $main = Find-Window $appPid "main"
    if ($main -ne 0) { break }
  }
  Start-Sleep -Milliseconds 40
}

if ($Mode -eq 0) {
  if ($overlay -eq 0) { "✗ 在 ${WaitSeconds}s 内没等到浮窗窗口"; exit 2 }

  # 4) 采样窗口轨迹（20ms 一拍，1.6s）。注意：窗口隐藏时也在这里被枚举到，
  #    所以起点 y 是「屏外上方」，能看到整段下降。
  $t0 = Get-Date
  $rows = New-Object System.Collections.Generic.List[string]
  $frameIndex = 0
  $lastY = [int]::MinValue
  while (((Get-Date) - $t0).TotalMilliseconds -lt 1600) {
    $r = [WX]::Rect($overlay)
    $ms = [int]((Get-Date) - $t0).TotalMilliseconds
    $vis = [WX]::Visible($overlay)
    $rows.Add(("t={0,4}ms  y={1,5}  x={2,4}  w={3}  h={4}  visible={5}" -f $ms, $r[1], $r[0], $r[2], $r[3], $vis))
    if ($r[1] -ne $lastY) {
      $lastY = $r[1]
      if ($vis) {
        # 屏幕区域直接抓：卡片在最上层，截出来的就是用户看到的样子
        $bmp = New-Object System.Drawing.Bitmap(700, 260)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.CopyFromScreen(0, 0, 0, 0, (New-Object System.Drawing.Size(700, 260)))
        $g.Dispose()
        $bmp.Save((Join-Path $shots ("{0}-{1:d2}-y{2}.png" -f $Tag, $frameIndex, $r[1])), [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
        $frameIndex++
      }
    }
    Start-Sleep -Milliseconds 20
  }
  $rows -join "`n"
  "帧数=$frameIndex  输出目录=$shots"
}
elseif ($Mode -eq 1) {
  if ($main -eq 0) { "✗ 在 ${WaitSeconds}s 内没等到主窗口"; exit 2 }
  # 主窗口横幅：用 PrintWindow 连拍（即使被别的窗口遮住也抓得到内容）
  Start-Sleep -Seconds 4   # 提醒在启动后约 5s 触发，提前一点开始连拍
  $t0 = Get-Date
  for ($i = 0; $i -lt 26; $i++) {
    $r = [WX]::Rect($main)
    $bmp = New-Object System.Drawing.Bitmap($r[2], $r[3])
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $dc = $g.GetHdc()
    [void][WX]::PrintWindow([IntPtr]$main, $dc, 2)
    $g.ReleaseHdc($dc); $g.Dispose()
    $bmp.Save((Join-Path $shots ("{0}-{1:d2}.png" -f $Tag, $i)), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Start-Sleep -Milliseconds 40
  }
  "连拍 26 帧，用时 $([int]((Get-Date) - $t0).TotalMilliseconds)ms  输出目录=$shots"
}
else {
  # 不提醒：等过提醒触发点，确认没有任何浮窗窗口
  Start-Sleep -Seconds 12
  $hit = Find-Window $appPid "overlay"
  if ($hit -eq 0) { "✓ 不提醒：${WaitSeconds}s 内没有出现浮窗窗口" } else { "✗ 不提醒模式下仍然弹了浮窗 hwnd=$hit"; exit 3 }
}
