import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { OverlayApp } from "./OverlayApp";
import "./styles/app.css";

/**
 * 同一个 SPA 两个入口，靠查询参数分流：
 *   · `index.html`              → 主窗口
 *   · `index.html?overlay=1`    → 上课提醒卡片（独立无边框小窗，见 src-tauri/src/overlay.rs）
 *
 * 用查询参数而不是再配一个 Vite 入口，是为了不把公共依赖（React / 样式 / model 层）
 * 打成第二份包 —— 提醒卡只用得到其中很小一部分，但重复打包会让两份代码有漂移的机会。
 */
const isOverlay = new URLSearchParams(window.location.search).get("overlay") === "1";

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>{isOverlay ? <OverlayApp /> : <App />}</React.StrictMode>,
);
