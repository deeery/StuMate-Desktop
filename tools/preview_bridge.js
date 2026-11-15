/**
 * 预览桥：在普通浏览器里顶替 Tauri 的 IPC，让打包出来的 `dist` 能脱离 exe 跑起来。
 *
 * 为什么需要它：验收截图原本靠 `shoot_cdp.py` 驱动 release exe，但这台机器上
 * 没有 Rust 工具链（cargo/rustc 都不在），改了前端却重建不了 exe。于是换成
 * 「把 dist 拷成 dist-preview、注入本文件、用 Edge 无头模式截」这条链路，
 * 好处是**改完 CSS/TSX 立刻能看**，坏处是数据来自下面的 fixtures 而不是真库。
 *
 * 用法：`python tools/shoot_preview.py [theme] [page]`，由它负责注入与截图。
 * 手工调试：把本文件放进 dist-preview/，并在 index.html 的入口 script 之前
 * 加一行 `<script src="/preview-bridge.js"></script>`，然后起个静态服务打开。
 *
 * URL 参数：
 *   ?page=today|week|notes|knowledge|recurring|settings  初始页（应用自己就支持）
 *   ?theme=light|dark                          主题（应用自己就支持，走 startTheme）
 *   ?at=10:05                                  把「现在」钉在当天这个时刻，
 *                                              用来演示「正在上课」与课表当前时间线
 */
(function () {
  var params = new URLSearchParams(location.search);
  var theme = params.get("theme") === "dark" ? "dark" : "light";

  // ---- 把「现在」钉住（只影响截图，真实运行时这一整段都不存在） ----
  var at = params.get("at");
  if (at) {
    var parts = at.split(":");
    var pinned = new Date();
    pinned.setHours(Number(parts[0]) || 0, Number(parts[1]) || 0, 0, 0);
    var FIXED = pinned.getTime();
    var RealDate = Date;
    var FixedDate = function () {
      var args = Array.prototype.slice.call(arguments);
      if (args.length === 0) args = [FIXED];
      return new (Function.prototype.bind.apply(RealDate, [null].concat(args)))();
    };
    FixedDate.prototype = RealDate.prototype;
    FixedDate.now = function () {
      return FIXED;
    };
    FixedDate.parse = RealDate.parse;
    FixedDate.UTC = RealDate.UTC;
    window.Date = FixedDate;
  }

  // ?wash=6% 之类的调参口子：不改源码、不重打包就能比「色块洗多淡」这类取值，
  // 定下来之后再写回 tokens.css
  var wash = params.get("wash");
  if (wash !== null) document.documentElement.style.setProperty("--class-wash", wash);

  var now = Date.now();
  var TODAY = new Date(now);
  /** 当天 00:00 的时间戳，用来造「今天几点截止」这类 fixture */
  function todayAt(hour, minute) {
    var d = new Date(TODAY.getTime());
    d.setHours(hour, minute || 0, 0, 0);
    return d.getTime();
  }

  // ---- fixtures：数值照着 acceptance 截图里的真实数据抄，观感才对得上 ----
  var classes = [
    { id: 1, title: "概率论与数理统计", dayOfWeek: "Monday", startTime: "09:50", endTime: "12:15", room: "A420", teacher: "王丽华" },
    { id: 2, title: "数字经济学", dayOfWeek: "Monday", startTime: "13:30", endTime: "16:10", room: "A317", teacher: "陈立" },
    { id: 3, title: "计算机与人工智能应用", dayOfWeek: "Monday", startTime: "18:30", endTime: "21:05", room: "A124", teacher: "李宏" },
    { id: 4, title: "大学生职业发展规划", dayOfWeek: "Tuesday", startTime: "08:00", endTime: "09:35", room: "A408", teacher: "赵敏" },
    { id: 5, title: "大数据基础", dayOfWeek: "Tuesday", startTime: "09:50", endTime: "12:15", room: "A216", teacher: "周涛" },
    { id: 6, title: "创业基础实训(理)", dayOfWeek: "Tuesday", startTime: "15:30", endTime: "17:15", room: "A323", teacher: "孙文" },
    { id: 7, title: "数据结构实验", dayOfWeek: "Tuesday", startTime: "18:30", endTime: "20:55", room: "信息楼217", teacher: "周涛" },
    { id: 8, title: "马克思主义基本原理", dayOfWeek: "Wednesday", startTime: "09:50", endTime: "12:15", room: "B331", teacher: "潘惠香" },
    { id: 9, title: "解锁DeepSeek：从基础到创意实践", dayOfWeek: "Wednesday", startTime: "18:30", endTime: "21:05", room: "C204", teacher: "童建锋" },
    { id: 10, title: "体育保健(一)", dayOfWeek: "Thursday", startTime: "08:00", endTime: "09:35", room: "体育中心三楼健身房3-12", teacher: "刘刚" },
    { id: 11, title: "运筹学及其应用", dayOfWeek: "Thursday", startTime: "09:50", endTime: "12:15", room: "A227", teacher: "何静" },
    { id: 12, title: "产品思维", dayOfWeek: "Friday", startTime: "08:00", endTime: "09:35", room: "经济225", teacher: "吴悠" },
    { id: 13, title: "数据结构", dayOfWeek: "Friday", startTime: "09:50", endTime: "12:15", room: "经济11", teacher: "周涛" },
    // 临时提醒：date 非空 → 只有一个具体日期，色条走 warning
    { id: 14, title: "补课：线性代数（临时）", dayOfWeek: "Friday", startTime: "15:00", endTime: "16:40", room: "B201", teacher: "何静" },
  ].map(function (c, i) {
    return {
      id: c.id,
      title: c.title,
      dayOfWeek: c.dayOfWeek,
      startTime: c.startTime,
      endTime: c.endTime,
      room: c.room || "",
      notes: "",
      teacher: c.teacher || "",
      weeks: "",
      // 最后一条钉在今天，其余是每周重复的长期课
      date: c.id === 14 ? todayKey() : "",
      uid: "cls-" + c.id,
      updatedAt: now - i * 1000,
      deletedAt: 0,
    };
  });

  function todayKey() {
    var d = new Date(now);
    var m = String(d.getMonth() + 1).padStart(2, "0");
    var day = String(d.getDate()).padStart(2, "0");
    return d.getFullYear() + "-" + m + "-" + day;
  }

  var notes = [
    {
      id: 1,
      title: "运筹学作业",
      content: "第三章 6、9、12 题，下周一上课前交",
      position: 0,
      createdAt: now - 86400000,
      colorIndex: 0,
      typeIndex: 4, // Deadline
      customLabel: "",
      deadlineAt: todayAt(23, 0),
      uid: "note-1",
      updatedAt: now - 3600000,
      deletedAt: 0,
    },
    {
      id: 2,
      title: "睡前吃匹伐他汀钙片x1 晚餐时吃盐酸二甲双胍x1 易善复三餐前x1",
      content: "二餐易善夏 晚餐另外一个什么什么片",
      position: 1,
      createdAt: now - 172800000,
      colorIndex: 3,
      typeIndex: 5, // 自定义
      customLabel: "服药",
      deadlineAt: 0,
      uid: "note-2",
      updatedAt: now - 7200000,
      deletedAt: 0,
    },
  ];

  var knowledge = [
    {
      id: 1,
      subject: "大学物理",
      title: "第一章 力学",
      content: "牛顿三定律\n惯性系与非惯性系",
      position: 0,
      createdAt: now - 259200000,
      uid: "kb-1",
      updatedAt: now - 86400000,
      deletedAt: 0,
    },
  ];

  var VAULT_BASE = "C:\\Users\\Administrator\\Documents";
  var VAULT_DIR = VAULT_BASE + "\\Stumate";
  var vaultFiles = {
    "大学物理/第一章 力学.md": "牛顿三定律\n惯性系与非惯性系\n",
    "大学物理/例题/斜面滑块.md": "斜面滑块：\n- 沿斜面重力分量 mg·sinθ\n",
    "高数/极限.md": "夹逼准则\n两个重要极限\n",
  };
  var vaultScan = [
    { path: "大学物理", isDir: true, mtime: now, size: 0 },
    { path: "大学物理/例题", isDir: true, mtime: now, size: 0 },
    { path: "大学物理/例题/斜面滑块.md", isDir: false, mtime: now, size: 32 },
    { path: "大学物理/第一章 力学.md", isDir: false, mtime: now, size: 16 },
    { path: "大学物理/放.md", isDir: false, mtime: now, size: 0 },
    { path: "大学物理/泰斯特.md", isDir: false, mtime: now, size: 3 },
    { path: "高数", isDir: true, mtime: now, size: 0 },
    { path: "高数/极限.md", isDir: false, mtime: now, size: 18 },
  ];

  // ?empty=1 把所有列表清空，用来验收空状态（图标 / 标题 / 提示三段式）
  if (params.get("empty")) {
    classes = [];
    notes = [];
    knowledge = [];
    vaultScan = [];
  }

  var prefs = {
    advanceMinutes: 30,
    autoStart: false,
    showPopup: true,
    firstRun: false,
    themeMode: theme === "dark" ? 2 : 1,
    // 第 1 周的周一：截图里的 2026/09/28
    week1Monday: new Date(2026, 8, 28).getTime(),
    weekGrid: true,
    sidebarCollapsed: false,
    closeAction: 1,
    notifyEnabled: true,
    autoCheckUpdate: true,
    notifyUpdate: true,
    vaultRoot: VAULT_BASE,
    vaultMigrated: true,
  };

  var APP_INFO = {
    version: "1.7.26",
    flavor: "lite",
    dataDir: "C:\\Users\\Administrator\\AppData\\Roaming\\StuMate",
    dbPath: "C:\\Users\\Administrator\\AppData\\Roaming\\StuMate\\class_reminder.db",
    schemaVersion: 10,
    sharedWithOriginal: true,
    originalPresent: true,
    settingsPath: "C:\\Users\\Administrator\\AppData\\Roaming\\StuMate\\settings.lite.json",
    startPage: params.get("page") || null,
    startView: null,
    startTheme: theme,
  };

  var saveAck = function (entity) {
    return entity;
  };

  /** 命令名 → 返回值。没列到的命令一律返回 null（够本页渲染就行） */
  function handle(cmd) {
    switch (cmd) {
      case "app_info":
        return APP_INFO;
      case "prefs_get":
        return prefs;
      case "pref_set":
        return prefs;
      case "classes_list":
        return classes;
      case "class_save":
        return saveAck(arguments[1] && arguments[1].entity);
      case "notes_list":
        return notes;
      case "note_save":
        return saveAck(arguments[1] && arguments[1].entity);
      case "notes_reorder":
        return null;
      case "knowledge_list":
        return knowledge;
      case "knowledge_counts":
        return [["大学物理", 4], ["高数", 1]];
      case "links_load":
        return { version: 1, links: [] };
      case "mind_load":
        return { version: 1, nodes: [] };
      case "vault_root":
        return VAULT_DIR;
      case "vault_scan":
        return vaultScan;
      case "vault_rev":
        return "rev-preview-1";
      case "vault_read": {
        var rel = (arguments[1] && arguments[1].rel) || "";
        return { content: vaultFiles[rel] || "", mtime: now };
      }
      case "vault_write": {
        var a = arguments[1] || {};
        return { conflict: false, mtime: now, path: a.rel || "" };
      }
      case "vault_grep": {
        var needle = ((arguments[1] && arguments[1].needle) || "").toLowerCase();
        var hits = [];
        Object.keys(vaultFiles).forEach(function (path) {
          var body = vaultFiles[path];
          if (!needle || body.toLowerCase().indexOf(needle) >= 0) {
            hits.push({ path: path, count: 1, preview: body.split("\n")[0] });
          }
        });
        return hits;
      }
      case "autostart_get":
        return false;
      case "notify_blocked":
        return null;
      case "overlay_state":
      case "tray_status":
      case "close_resolve":
      case "overlay_hide":
      case "open_knowledge_window":
      case "open_markdown_window":
      case "open_external":
      case "open_data_dir":
        return null;
      default:
        return null;
    }
  }

  var callbackSeq = 0;
  var internals = window.__TAURI_INTERNALS__ || {};
  internals.invoke = function (cmd, args) {
    try {
      return Promise.resolve(handle(cmd, args));
    } catch (err) {
      return Promise.reject(err);
    }
  };
  // 事件订阅：返回一个 id 就行，回调存起来不发事件（预览里没有后端推事件）
  internals.transformCallback = function (cb) {
    callbackSeq += 1;
    window["_preview_cb_" + callbackSeq] = cb;
    return callbackSeq;
  };
  internals.convertFileSrc = function (p) {
    return p;
  };
  internals.metadata = {
    currentWindow: { label: "main" },
    currentWebview: { label: "main", windowLabel: "main" },
  };
  window.__TAURI_INTERNALS__ = internals;
})();
