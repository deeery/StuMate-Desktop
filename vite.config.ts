import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";
import fs from "node:fs";

// Tauri 会把前端当静态站点加载，所以这里固定 5173 端口并让 Tauri 的 devUrl 指向它。
// clearScreen=false：Rust 端的编译告警不要被 Vite 清屏冲掉，两边日志要能一起看。
//
// ⚠ 5173 这个数字**不要随手改**：它已经被编译进 dev 构建的 stumate-lite.exe
// （WebviewUrl::default() 在 dev 下替换成 devUrl，是编译期常量）。改这里的端口
// 而不重新构建 exe，exe 仍会去 5173 找页面，结果就是「localhost 拒绝连接」。
// 要换端口必须同时改 tauri.conf.json 的 build.devUrl 并重跑一次构建。

/**
 * 请求日志（仅 STUMATE_TRACE=1 时启用）。
 *
 * WebView2 是黑盒：窗口抓不到画面时，"它到底有没有来取页面"这件事没有任何别的
 * 办法能确认。这个中间件把每次请求记到文件里，用来区分「页面没被请求」和
 * 「页面取了但前端崩了」——这两种情况的排查方向完全不同。
 */
function requestTrace() {
  const log = path.resolve(__dirname, "design-preview/vite-requests.log");
  return {
    name: "stumate-request-trace",
    configureServer(server: any) {
      server.middlewares.use((req: any, _res: any, next: any) => {
        try {
          fs.appendFileSync(
            log,
            `${new Date().toISOString()} ${req.method} ${req.url}\n`,
          );
        } catch {
          /* 日志写不进去不该影响服务 */
        }
        next();
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), ...(process.env.STUMATE_TRACE ? [requestTrace()] : [])],
  resolve: {
    alias: { "@": path.resolve(__dirname, "src") },
  },
  clearScreen: false,
  server: {
    port: 5173,
    strictPort: true,
    watch: { ignored: ["**/src-tauri/**"] },
  },
  build: {
    // WebView2 是 Chromium，可以直接用较新的语法，不必降到 ES5
    target: "chrome110",
    outDir: "dist",
    sourcemap: false,
  },
});
