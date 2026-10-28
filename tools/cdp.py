"""极简 Chrome DevTools Protocol 客户端（只有 WebSocket 握手与收发）。

为什么自己写：这台机器上没有 `websockets` / `websocket-client`，而装一个包只为
在验收时点一下按钮不值当。协议本身只用到最普通的一条路径（客户端发文本帧、
服务端回文本帧），两百行够了。

用途见 `e2e_knowledge.py`：Tauri 用 WebView2，`STUMATE_LITE_BROWSER_ARGS` 传
`--remote-debugging-port=xxxx` 就能开 CDP，于是「点按钮 → 落盘 → 再读回来」
这条链路可以真跑一遍，而不是只证明它编译通过。
"""
from __future__ import annotations

import base64
import json
import os
import socket
import struct
import urllib.request


class CdpError(RuntimeError):
    pass


class WebSocket:
    def __init__(self, url: str, timeout: float = 10.0):
        # ws://127.0.0.1:9333/devtools/page/XXXX
        assert url.startswith("ws://"), url
        rest = url[len("ws://"):]
        host_port, _, path = rest.partition("/")
        host, _, port = host_port.partition(":")
        self.sock = socket.create_connection((host, int(port or 80)), timeout=timeout)
        self.sock.settimeout(timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            f"GET /{path} HTTP/1.1\r\n"
            f"Host: {host_port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        self.sock.sendall(req.encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise CdpError("握手失败：连接被关闭")
            buf += chunk
        head, _, tail = buf.partition(b"\r\n\r\n")
        if b" 101 " not in head.split(b"\r\n")[0]:
            raise CdpError(f"握手失败：{head[:120]!r}")
        self._rest = tail

    # ---- 帧 ----

    def send_text(self, text: str) -> None:
        payload = text.encode()
        header = bytearray([0x81])  # FIN + text
        n = len(payload)
        if n < 126:
            header.append(0x80 | n)
        elif n < 65536:
            header.append(0x80 | 126)
            header += struct.pack(">H", n)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", n)
        mask = os.urandom(4)
        header += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def _read_exact(self, n: int) -> bytes:
        out = self._rest[:n]
        self._rest = self._rest[n:]
        while len(out) < n:
            chunk = self.sock.recv(n - len(out))
            if not chunk:
                raise CdpError("连接已关闭")
            out += chunk
        return out

    def recv_text(self) -> str:
        while True:
            b0, b1 = self._read_exact(2)
            opcode = b0 & 0x0F
            masked = b1 & 0x80
            n = b1 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._read_exact(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._read_exact(8))[0]
            mask = self._read_exact(4) if masked else b""
            data = self._read_exact(n)
            if masked:
                data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
            if opcode == 0x9:  # ping
                self.sock.sendall(b"\x8a\x80" + os.urandom(4))
                continue
            if opcode == 0x8:
                raise CdpError("服务端关闭了连接")
            if opcode in (0x1, 0x2):
                return data.decode("utf-8", "replace")

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


class Session:
    """一层薄封装：`eval()` 跑 JS 并拿回值。"""

    def __init__(self, ws_url: str):
        self.ws = WebSocket(ws_url)
        self.seq = 0

    def call(self, method: str, params: dict | None = None) -> dict:
        self.seq += 1
        msg = {"id": self.seq, "method": method, "params": params or {}}
        self.ws.send_text(json.dumps(msg))
        while True:
            reply = json.loads(self.ws.recv_text())
            if reply.get("id") == self.seq:
                if "error" in reply:
                    raise CdpError(f"{method}: {reply['error']}")
                return reply.get("result", {})
            # 事件（Runtime.consoleAPICalled 之类）直接丢掉

    def eval(self, expression: str, await_promise: bool = True):
        res = self.call(
            "Runtime.evaluate",
            {
                "expression": expression,
                "returnByValue": True,
                "awaitPromise": await_promise,
                "userGesture": True,
            },
        )
        if res.get("exceptionDetails"):
            raise CdpError(f"JS 异常：{res['exceptionDetails'].get('text')} {expression[:80]}")
        return res.get("result", {}).get("value")

    def close(self) -> None:
        self.ws.close()


def find_page(port: int, contains: str = "", timeout: float = 20.0) -> str:
    """轮询 `/json`，返回第一个匹配页面的 webSocketDebuggerUrl。

    生产构建的页面 URL 是 `http://tauri.localhost/`（不是 `index.html`），
    所以默认不按 URL 过滤，只取 type=page 的第一条。
    """
    import time

    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        try:
            raw = urllib.request.urlopen(f"http://127.0.0.1:{port}/json", timeout=2).read()
            for t in json.loads(raw):
                url = t.get("webSocketDebuggerUrl", "")
                if t.get("type") == "page" and url and contains in t.get("url", ""):
                    return url
        except Exception as e:  # 端口还没起来
            last = str(e)
        time.sleep(0.3)
    raise CdpError(f"找不到调试页面（{last}）")
