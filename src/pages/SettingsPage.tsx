/**
 * 设置页：外观 / 提醒 / 周次校准 / 数据与备份 / 关于。
 *
 * 这一页有个特殊职责：**把「Lite 与原版共用同一份数据」这件事说清楚**。
 * 用户选了直连原版库，好处是两边立刻同步，风险是「导入备份」会真的改到原版的数据。
 * 所以备份区把「整表替换」四个字写在按钮旁边，而不是藏在帮助文档里。
 */

import { useEffect, useRef, useState } from "react";
import { PageTopBar, type AppContextValue } from "../App";
import { Dialog, Field, Select, Toggle } from "../components/ui";
import type { PageKey } from "../components/Chrome";
import { usePresence } from "../components/Presence";
import { IconFolder, IconTrash } from "../components/Icons";
import { api, errorText } from "../api";
import { currentWeek, mondayOf } from "../model/weekSchedule";
import { decideReminder } from "../model/reminder";
import { parseTimetablePdf, type ParsedCourse } from "../model/timetablePdf";
import { EMPTY_CLASS } from "../model/types";

const MODULE_LABELS: Record<string, string> = {
  courses: "课程表",
  notes: "便签",
  knowledge: "知识库",
  settings: "设置",
  recurring: "循环提醒",
};

/** 数据库里存英文星期名，界面上显示中文 */
const DAY_LABEL_CN: Record<string, string> = {
  Monday: "周一",
  Tuesday: "周二",
  Wednesday: "周三",
  Thursday: "周四",
  Friday: "周五",
  Saturday: "周六",
  Sunday: "周日",
};

export function SettingsPage({
  ctx,
  onNavigate,
}: {
  ctx: AppContextValue;
  /** v1.7.16：给「循环提醒」指路入口用，独立页由 App 统一切 */
  onNavigate?: (page: PageKey) => void;
}) {
  const [backupOpen, setBackupOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  // null = 还没读到注册表。开关先按「关」渲染，读到真值再纠正 ——
  // 反过来（先当开）会让用户在读盘慢的时候看到开关自己跳一下。
  const [autoStart, setAutoStart] = useState<boolean | null>(null);
  // 课表 PDF 导入：解析结果先给用户看一眼再落库，避免"点一下就把课表灌满了"
  const [pdfBusy, setPdfBusy] = useState(false);
  const [pdfCourses, setPdfCourses] = useState<ParsedCourse[] | null>(null);
  const [pdfSaving, setPdfSaving] = useState(false);

  /**
   * 三个浮层的退场保留（备份 / 导入 / PDF 解析结果）。
   * 关闭时状态同帧置空，节点还要再画 120ms —— 用 ref 记住最后一份可渲染的值。
   */
  const backupPresence = usePresence(backupOpen);
  const importPresence = usePresence(importOpen);
  const pdfPresence = usePresence(pdfCourses !== null);
  const retainedPdf = useRef<ParsedCourse[] | null>(null);
  if (pdfCourses) retainedPdf.current = pdfCourses;
  const shownPdf = pdfCourses ?? retainedPdf.current;

  const prefs = ctx.prefs;

  const setNumber = (key: string, value: number) => void ctx.setPref(key, value);

  /**
   * 预览提醒浮窗。
   *
   * 有今天的课就预览「真课」，没有就造一节 10 分钟后开始的示例课 ——
   * 用户点这个按钮是想看"浮窗长什么样、弹在哪块屏"，不是想验证今天有没有课，
   * 所以宁可给示例也不能什么都不弹。
   *
   * 提前量传 24 小时：预览时不该受「提前提醒」设置限制，
   * 否则用户把提前量设成 5 分钟、今天最后一节课已经上完了，就什么都看不到。
   */
  async function previewOverlay() {
    const now = Date.now();
    const decision = decideReminder({
      classes: ctx.classes,
      currentWeek: currentWeek(prefs.week1Monday, now),
      now,
      advanceMinutes: 24 * 60,
    });
    const alert = decision.alert ?? {
      classId: -1,
      title: "示例课程（预览）",
      room: "A101",
      startMillis: now + 10 * 60_000,
      endMillis: now + 105 * 60_000,
      ongoing: false,
    };
    try {
      await api.overlayShow(alert);
    } catch (err) {
      ctx.toast(`浮窗打不开：${errorText(err)}`, "error");
    }
  }

  // 自启状态以注册表为准，不以偏好为准（用户可能在任务管理器里关过启动项）
  useEffect(() => {
    let alive = true;
    api
      .autostartGet()
      .then((v) => {
        if (alive) setAutoStart(v);
      })
      .catch(() => {
        if (alive) setAutoStart(false);
      });
    return () => {
      alive = false;
    };
  }, []);

  const toggleAutoStart = async (next: boolean) => {
    setAutoStart(next); // 乐观更新，避免开关按下后卡住不动
    try {
      const ok = await api.autostartSet(next);
      if (!ok) {
        setAutoStart(!next); // 写注册表失败，回滚到真实状态
        ctx.toast(
          next ? "写入开机自启失败：请检查注册表权限" : "取消开机自启失败：请检查注册表权限",
          "error",
        );
      }
    } catch (err) {
      setAutoStart(!next);
      ctx.toast(errorText(err), "error");
    }
  };

  /** 选 PDF → 读字节 → 前端解析 → 先预览 */
  const importPdf = async () => {
    setPdfBusy(true);
    try {
      const path = await api.pickPdfPath();
      if (!path) return; // 用户取消
      const bytes = await api.readFileBytes(path);
      const courses = await parseTimetablePdf(Uint8Array.from(bytes));
      if (courses.length === 0) {
        ctx.toast(
          "没解析出课程。这个解析器只认教务系统导出的 iText 课表 PDF（文字可选中，不是扫描件）",
          "error",
        );
        return;
      }
      setPdfCourses(courses);
    } catch (err) {
      ctx.toast(errorText(err), "error");
    } finally {
      setPdfBusy(false);
    }
  };

  /** 确认导入：逐条写库，然后刷新课表 */
  const confirmImport = async () => {
    if (!pdfCourses) return;
    setPdfSaving(true);
    let ok = 0;
    try {
      for (const course of pdfCourses) {
        // id=0 让后端当成新增；uid 留空由后端生成
        await api.saveClass({ ...EMPTY_CLASS, ...course });
        ok++;
      }
      await ctx.reloadClasses();
      ctx.toast(`已导入 ${ok} 门课`);
      setPdfCourses(null);
    } catch (err) {
      ctx.toast(`导入中断（已写入 ${ok} 条）：${errorText(err)}`, "error");
    } finally {
      setPdfSaving(false);
    }
  };

  return (
    <>
      <PageTopBar title="设置" subtitle="Lite 版" />
      <div className="page-content" style={{ maxWidth: 760 }}>
        <Section title="外观">
          <Row label="主题">
            <Select<number>
              value={prefs.themeMode}
              options={[
                { value: 0, label: "跟随系统" },
                { value: 1, label: "浅色" },
                { value: 2, label: "深色" },
              ]}
              onChange={(v) => void setNumber("themeMode", v)}
            />
          </Row>
          <Toggle
            label="课表默认用网格视图"
            hint="关掉后用按天分组的列表，小屏或只关心某几天时更清楚"
            checked={prefs.weekGrid}
            onChange={(v) => void ctx.setPref("weekGrid", v)}
          />
        </Section>

        <Section title="提醒">
          <Row label="提前提醒" hint="离上课还有这么多分钟时开始提示">
            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <input
                className="input"
                style={{ width: 84 }}
                type="number"
                min={1}
                max={180}
                value={prefs.advanceMinutes}
                onChange={(e) => {
                  const v = Number.parseInt(e.target.value, 10);
                  if (Number.isFinite(v)) {
                    void setNumber("advanceMinutes", Math.min(Math.max(v, 1), 180));
                  }
                }}
              />
              <span style={{ fontSize: 12, color: "var(--on-surface-faint)" }}>分钟</span>
            </div>
          </Row>
          <Toggle
            label="允许通知"
            hint="桌面端的通知只是用户意愿，不涉及系统权限"
            checked={prefs.notifyEnabled}
            onChange={(v) => void ctx.setPref("notifyEnabled", v)}
          />
          {/* v1.7.15 补上的「有实现、无 UI」开关：showPopup 一直在 default_prefs
              里，useReminder 也一直消费它，但此前用户根本配不了。 */}
          <Toggle
            label="显示置顶提醒卡片"
            hint="关掉后只发系统通知；全屏游戏或演示模式下两种通道都会被抑制"
            checked={prefs.showPopup}
            onChange={(v) => void ctx.setPref("showPopup", v)}
          />
          <Row
            label="预览提醒浮窗"
            hint="不用等上课，立刻看一眼浮窗长什么样、弹在哪块屏"
          >
            <button className="btn" onClick={() => void previewOverlay()}>
              预览
            </button>
          </Row>
          {/* v1.7.16：循环提醒升为侧栏一级页，这里只留指路入口 ——
              规则列表不再在设置页渲染第二遍（两处同时写会互相刷新）。 */}
          <Row label="循环提醒" hint="规则管理已移到侧栏「循环提醒」页">
            <button className="btn" onClick={() => onNavigate?.("recurring")}>
              打开
            </button>
          </Row>
        </Section>

        <Section title="启动与托盘">
          {/* 另一个「有实现、无 UI」的键。此前只能靠关闭确认框里的
              「下次不再提问」隐式写入，最小化到托盘这条路径没有入口。 */}
          <Row label="关闭按钮行为" hint="最小化到托盘后提醒继续在后台运行">
            <Select<number>
              value={prefs.closeAction}
              options={[
                { value: 0, label: "每次询问" },
                { value: 1, label: "最小化到托盘" },
                { value: 2, label: "直接退出" },
              ]}
              onChange={(v) => void setNumber("closeAction", v)}
            />
          </Row>
          <Toggle
            label="开机自启"
            hint="写入 HKCU\Software\Microsoft\Windows\CurrentVersion\Run"
            checked={autoStart ?? false}
            onChange={(v) => void toggleAutoStart(v)}
          />
          <div
            style={{
              fontSize: 11,
              color: "var(--on-surface-faint)",
              padding: "2px 0 8px",
              lineHeight: 1.6,
            }}
          >
            提醒依赖应用进程常驻：关闭窗口会按「关闭按钮行为」处理，
            最小化到托盘后继续在后台运行，从托盘菜单「退出」才会真正停止。
          </div>
        </Section>

        <Section title="周次校准">
          <Row
            label="第 1 周的周一"
            hint="不校准时课表不按周次过滤（所有周都显示）"
          >
            <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
              <input
                className="input"
                type="date"
                value={prefs.week1Monday ? toDateInput(prefs.week1Monday) : ""}
                onChange={(e) => {
                  const v = e.target.value;
                  if (!v) return;
                  const millis = new Date(`${v}T00:00:00`).getTime();
                  if (Number.isFinite(millis)) void setNumber("week1Monday", mondayOf(millis));
                }}
              />
              <button
                className="btn"
                onClick={() => void setNumber("week1Monday", mondayOf(Date.now()))}
              >
                以本周为第 1 周
              </button>
              {prefs.week1Monday ? (
                <button className="btn ghost" onClick={() => void setNumber("week1Monday", 0)}>
                  清除
                </button>
              ) : null}
            </div>
          </Row>
        </Section>

        <Section title="课表导入">
          <Row
            label="从教务系统 PDF 导入"
            hint="识别 iText 导出的课表 PDF；导入后可在课表里逐条改时间与周次"
          >
            <button className="btn" disabled={pdfBusy} onClick={() => void importPdf()}>
              {pdfBusy ? "解析中…" : "选择 PDF"}
            </button>
          </Row>
          <div
            style={{
              fontSize: 11,
              color: "var(--on-surface-faint)",
              padding: "2px 0 8px",
              lineHeight: 1.6,
            }}
          >
            PDF 里只有「第几节」没有具体时刻，导入时按常见作息换算（1-2 节 08:00 起）。
            导入只会新增课程，不会删除已有课程。
          </div>
        </Section>

        <Section title="数据">
          <Row label="数据目录" hint={ctx.info.dataDir}>
            <button className="btn" onClick={() => void api.openDataDir().catch(() => undefined)}>
              <IconFolder size={13} /> 打开
            </button>
          </Row>
          <Row label="数据库" hint={ctx.info.dbPath}>
            <span className="chip">schema v{ctx.info.schemaVersion}</span>
          </Row>
          <Row label="备份" hint="导出为 JSON，与原版 Compose 版格式互通">
            <div style={{ display: "flex", gap: 8 }}>
              <button className="btn" onClick={() => setBackupOpen(true)}>
                导出
              </button>
              <button className="btn" onClick={() => setImportOpen(true)}>
                导入
              </button>
            </div>
          </Row>
          <Row
            label="知识库目录"
            hint={
              prefs.vaultRoot
                ? `库在 ${prefs.vaultRoot}\\Stumate\\，一篇笔记就是一个 .md 文件。换库时会先看新库里有没有笔记，空的才会把旧数据铺过去`
                : "还没设置。知识库页会引导你选一个目录；选一个已经在用网盘同步的目录，同步就不用再管了"
            }
          >
            <div style={{ display: "flex", gap: 8 }}>
              <button className="btn" onClick={() => void pickVaultRoot(ctx)}>
                选择目录…
              </button>
              {prefs.vaultRoot ? (
                <button
                  className="btn"
                  onClick={() => void api.openExternal(`${prefs.vaultRoot}\\Stumate`)}
                >
                  打开
                </button>
              ) : null}
            </div>
          </Row>
          <Row
            label="清空数据"
            hint="物理删除三张表的全部行，不可撤销。附件与导图不受影响"
          >
            <button className="btn danger" onClick={() => void clearAll(ctx)}>
              <IconTrash size={13} /> 清空
            </button>
          </Row>
        </Section>

        <Section title="关于">
          <Row label="版本">
            <span style={{ fontFamily: "var(--font-mono)", fontSize: 12 }}>
              {ctx.info.version} <span className="chip">LITE</span>
            </span>
          </Row>
          <Row label="运行时" hint="Tauri 2 · WebView2 · Rust 后端">
            <span className="chip">Lite 重构版</span>
          </Row>
          <Row
            label="与原版的关系"
            hint={
              ctx.info.sharedWithOriginal
                ? `共用同一个数据库文件${ctx.info.originalPresent ? "（检测到原版数据）" : ""}；偏好设置独立保存在 settings.lite.json`
                : "独立数据目录"
            }
          >
            <span className="chip">{ctx.info.sharedWithOriginal ? "共用数据" : "独立"}</span>
          </Row>
          <div style={{ fontSize: 11, color: "var(--on-surface-faint)", lineHeight: 1.7, paddingTop: 6 }}>
            两个版本可以同时打开：SQLite 走 WAL，一边读一边写不互斥。
            但**同时改同一条记录**时以最后一次写入为准，不会合并。
          </div>
        </Section>
      </div>

      {backupPresence.mounted ? (
        <BackupDialog
          leaving={backupPresence.leaving}
          ctx={ctx}
          onClose={() => setBackupOpen(false)}
        />
      ) : null}
      {importPresence.mounted ? (
        <ImportDialog
          leaving={importPresence.leaving}
          ctx={ctx}
          onClose={() => setImportOpen(false)}
        />
      ) : null}
      {pdfPresence.mounted && shownPdf ? (
        <Dialog
          leaving={pdfPresence.leaving}
          title={`解析出 ${shownPdf.length} 门课`}
          subtitle="确认后会逐条写入课表（新增，不覆盖已有课程）"
          width={560}
          onClose={() => (pdfSaving ? undefined : setPdfCourses(null))}
          actions={
            <>
              <button
                className="btn ghost"
                disabled={pdfSaving}
                onClick={() => setPdfCourses(null)}
              >
                取消
              </button>
              <button className="btn primary" disabled={pdfSaving} onClick={() => void confirmImport()}>
                {pdfSaving ? "导入中…" : `导入 ${shownPdf.length} 门课`}
              </button>
            </>
          }
        >
          <div style={{ maxHeight: 320, overflow: "auto", marginTop: 4 }}>
            {shownPdf.map((c, i) => (
              <div
                key={`${c.title}-${c.dayOfWeek}-${c.startTime}-${i}`}
                style={{
                  display: "flex",
                  gap: 10,
                  alignItems: "baseline",
                  padding: "7px 0",
                  borderBottom: "1px solid var(--outline)",
                  fontSize: 13,
                }}
              >
                <span style={{ flex: 1, minWidth: 0, overflow: "hidden", textOverflow: "ellipsis" }}>
                  {c.title}
                </span>
                <span style={{ fontSize: 11, color: "var(--on-surface-faint)", flex: "0 0 auto" }}>
                  {DAY_LABEL_CN[c.dayOfWeek] ?? c.dayOfWeek} {c.startTime}-{c.endTime}
                  {c.room ? ` · ${c.room}` : ""}
                  {c.weeks ? ` · ${c.weeks}` : ""}
                </span>
              </div>
            ))}
          </div>
        </Dialog>
      ) : null}
    </>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="card" style={{ padding: "14px 16px 10px", marginBottom: 12 }}>
      <div
        style={{
          fontSize: 12,
          fontWeight: 600,
          color: "var(--on-surface-variant)",
          marginBottom: 6,
          letterSpacing: 0.2,
        }}
      >
        {title}
      </div>
      {children}
    </section>
  );
}

function Row({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        gap: 16,
        padding: "9px 0",
        borderBottom: "1px solid var(--outline)",
      }}
    >
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 13 }}>{label}</div>
        {hint ? (
          <div
            style={{
              fontSize: 11,
              color: "var(--on-surface-faint)",
              marginTop: 2,
              overflow: "hidden",
              textOverflow: "ellipsis",
              whiteSpace: "nowrap",
            }}
            title={hint}
          >
            {hint}
          </div>
        ) : null}
      </div>
      {children}
    </div>
  );
}

function BackupDialog({
  ctx,
  leaving,
  onClose,
}: {
  ctx: AppContextValue;
  leaving?: boolean;
  onClose: () => void;
}) {
  const [modules, setModules] = useState<Set<string>>(
    new Set(["courses", "notes", "knowledge", "settings", "recurring"]),
  );
  const [busy, setBusy] = useState(false);

  const toggle = (key: string) => {
    const next = new Set(modules);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    setModules(next);
  };

  const run = async () => {
    if (modules.size === 0) return;
    setBusy(true);
    try {
      const name = `StuMate-backup-${new Date().toISOString().slice(0, 10)}.json`;
      const path = await api.pickSavePath(name);
      if (!path) return;
      const saved = await api.backupExport([...modules], path);
      ctx.toast(`已导出到 ${saved}`);
      onClose();
    } catch (err) {
      ctx.toast(errorText(err), "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog
      leaving={leaving}
      title="导出备份"
      subtitle="与原版 Compose 版的备份文件互通"
      onClose={onClose}
      actions={
        <>
          <button className="btn" onClick={onClose}>
            取消
          </button>
          <button className="btn primary" disabled={busy || modules.size === 0} onClick={() => void run()}>
            {busy ? "导出中…" : "选择位置并导出"}
          </button>
        </>
      }
    >
      <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
        {Object.entries(MODULE_LABELS).map(([key, label]) => (
          <label key={key} style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 13 }}>
            <input type="checkbox" checked={modules.has(key)} onChange={() => toggle(key)} />
            {label}
          </label>
        ))}
      </div>
    </Dialog>
  );
}

interface InspectResult {
  format?: string;
  schema?: number;
  app?: string;
  modules?: Record<string, { count?: number; present?: boolean }>;
}

function ImportDialog({
  ctx,
  leaving,
  onClose,
}: {
  ctx: AppContextValue;
  leaving?: boolean;
  onClose: () => void;
}) {
  const [path, setPath] = useState<string | null>(null);
  const [info, setInfo] = useState<InspectResult | null>(null);
  const [modules, setModules] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);

  const pick = async () => {
    try {
      const chosen = await api.pickOpenPath();
      if (!chosen) return;
      const described = (await api.backupInspect(chosen)) as InspectResult;
      setPath(chosen);
      setInfo(described);
      setModules(new Set(Object.keys(described.modules ?? {})));
    } catch (err) {
      ctx.toast(errorText(err), "error");
    }
  };

  useEffect(() => {
    void pick();
    // 打开就弹选择框，省一次点击
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const run = async () => {
    if (!path || modules.size === 0) return;
    setBusy(true);
    try {
      const message = await api.backupImport(path, [...modules]);
      await Promise.all([
        ctx.reloadClasses(),
        ctx.reloadNotes(),
        ctx.reloadKnowledge(),
        // 循环提醒是整表替换，导入后内存里的规则必须跟着换，否则下一次
        // 轮询会用旧规则的 lastFiredAt 覆盖刚导入的存档
        ctx.reloadRecurring(),
      ]);
      ctx.toast(message);
      onClose();
    } catch (err) {
      ctx.toast(errorText(err), "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog
      leaving={leaving}
      title="导入备份"
      subtitle={path ? path : "选择一个 .json 备份文件"}
      onClose={onClose}
      actions={
        <>
          <button className="btn" onClick={() => void pick()}>
            重新选择
          </button>
          <button
            className="btn primary"
            disabled={!path || busy || modules.size === 0}
            onClick={() => void run()}
          >
            {busy ? "导入中…" : "导入选中模块"}
          </button>
        </>
      }
    >
      {info?.modules ? (
        <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
          {Object.entries(info.modules).map(([key, value]) => (
            <label key={key} style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 13 }}>
              <input
                type="checkbox"
                checked={modules.has(key)}
                onChange={() => {
                  const next = new Set(modules);
                  if (next.has(key)) next.delete(key);
                  else next.add(key);
                  setModules(next);
                }}
              />
              {MODULE_LABELS[key] ?? key}
              <span style={{ fontSize: 11, color: "var(--on-surface-faint)" }}>
                {value.present ? "（设置）" : `${value.count ?? 0} 条`}
              </span>
            </label>
          ))}
          <div style={{ fontSize: 11, color: "var(--error)", marginTop: 6, lineHeight: 1.6 }}>
            导入是**整表替换**：选中的模块会先清空再写入文件里的内容。
            由于 Lite 与原版共用同一个库，这也会改到原版看到的数据。
          </div>
        </div>
      ) : (
        <div className="empty" style={{ padding: 20 }}>
          还没有选文件
        </div>
      )}
    </Dialog>
  );
}

/**
 * 设置知识库的库根。
 *
 * 顺带把 `vaultMigrated` 重置 —— 迁移只在「新库里一篇笔记都没有」时才真的写，
 * 所以重置是安全的：换到一个空目录会把旧条目铺过去，换到已有的库则什么都不做
 * （不然「刚导出的那批」会在切库时被覆盖一遍）。
 */
async function pickVaultRoot(ctx: AppContextValue) {
  try {
    const dir = await api.pickVaultDir();
    if (!dir) return;
    await ctx.setPref("vaultRoot", dir);
    await ctx.setPref("vaultMigrated", false);
    ctx.toast("已设置知识库目录");
  } catch (err) {
    ctx.toast(errorText(err), "error");
  }
}

async function clearAll(ctx: AppContextValue) {  if (!window.confirm("确定清空课程表、便签、知识库的全部数据？此操作不可撤销。")) return;
  try {
    await api.clearClasses();
    await api.clearNotes();
    await api.clearKnowledge();
    await Promise.all([ctx.reloadClasses(), ctx.reloadNotes(), ctx.reloadKnowledge()]);
    ctx.toast("已清空");
  } catch (err) {
    ctx.toast(errorText(err), "error");
  }
}

function toDateInput(millis: number): string {
  const d = new Date(millis);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
