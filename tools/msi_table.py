# -*- coding: utf-8 -*-
"""
只读导出 MSI 的表内容（走 msi.dll 的 MSI API）。

## 为什么不用 ctypes 之外的路
`inspect_msi.py` 已经改走 dark + XML。但 dark 有个**致命盲区**：
它对 WixUIExtension 引入的 Dialog 会报 DARK1059「ControlEvent 引用了
不存在的 Control」，并**丢弃**这些行 —— 于是 dark 输出的 WXS 里
`Control` 表是不完整的，拿它判断 2819 会得出错误结论。
要确定「某控件到底在不在 Control 表里」，必须直读原始表。

## 🔴 踩过的坑：`MsiRecordGetStringW` 返回空不是 handle 的问题
它的第 4 参 `pcchValueBuf` 是 **in-out**：
  - **入参**：buffer 的字符数（必须给对）
  - **出参**：实际需要的字符数（不含结尾 null）
我先前传了个初值为 0 的 DWORD 进去 → 引擎认为 buffer 长度 0 →
一个字符都不写，脚本却当成「查询成功但值为空」。
症状极具迷惑性：**行列数都对，就是没有一个非空字符串**。
（另一次症状是 `access violation reading 0x400`。）

所以：入参必须给 buffer 真实容量，出参才拿得到长度。

用法:
  python tools/msi_table.py <msi> "SELECT * FROM Control"
列名若是 MSI SQL 保留字（Name/Target/Value/Condition/Sequence/Icon/File…）
要用反引号包起来，否则报 1615 ERROR_BAD_QUERYTABLE。
"""
import ctypes
import sys
from ctypes import wintypes, POINTER, byref, c_void_p

_msi = ctypes.WinDLL("msi.dll")

_msi.MsiOpenDatabaseW.restype = wintypes.UINT
_msi.MsiOpenDatabaseW.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR,
                                  POINTER(c_void_p)]
_msi.MsiDatabaseOpenViewW.restype = wintypes.UINT
_msi.MsiDatabaseOpenViewW.argtypes = [c_void_p, wintypes.LPCWSTR,
                                      POINTER(c_void_p)]
_msi.MsiViewExecute.restype = wintypes.UINT
_msi.MsiViewExecute.argtypes = [c_void_p, c_void_p]
_msi.MsiViewFetch.restype = wintypes.UINT
_msi.MsiViewFetch.argtypes = [c_void_p, POINTER(c_void_p)]
_msi.MsiRecordGetStringW.restype = wintypes.UINT
_msi.MsiRecordGetStringW.argtypes = [c_void_p, wintypes.UINT, wintypes.LPWSTR,
                                     POINTER(wintypes.DWORD)]
_msi.MsiRecordGetFieldCount.restype = wintypes.UINT
_msi.MsiRecordGetFieldCount.argtypes = [c_void_p]
_msi.MsiCloseHandle.restype = wintypes.UINT
_msi.MsiCloseHandle.argtypes = [c_void_p]

BUF = 4096


def query(msi_path, sql):
    """执行一条 MSI SQL，返回 list[list[str]]（每格取字符串形式）。"""
    hdb = c_void_p()
    rc = _msi.MsiOpenDatabaseW(msi_path, None, byref(hdb))
    if rc != 0:
        raise SystemExit("MsiOpenDatabaseW rc=%d (1615=SQL 语法错,1619=服务被占)"
                         % rc)
    hv = c_void_p()
    rc = _msi.MsiDatabaseOpenViewW(hdb, sql, byref(hv))
    if rc != 0:
        _msi.MsiCloseHandle(hdb)
        raise SystemExit("MsiDatabaseOpenViewW rc=%d\n"
                         "  列名若是保留字(Name/Target/Value/Condition/"
                         "Sequence/Icon/File...)要用反引号包起来。" % rc)
    rc = _msi.MsiViewExecute(hv, None)
    if rc != 0:
        _msi.MsiCloseHandle(hv)
        _msi.MsiCloseHandle(hdb)
        raise SystemExit("MsiViewExecute rc=%d" % rc)

    rows = []
    while True:
        rec = c_void_p()
        rc = _msi.MsiViewFetch(hv, byref(rec))
        if rc == 259:      # ERROR_NO_MORE_ITEMS
            break
        if rc != 0:
            break
        n = _msi.MsiRecordGetFieldCount(rec)
        cells = []
        for i in range(1, n + 1):
            buf = ctypes.create_unicode_buffer(BUF)
            need = wintypes.DWORD(BUF)          # 🔴 入参 = buffer 容量
            r2 = _msi.MsiRecordGetStringW(rec, i, buf, byref(need))
            if r2 == 0:
                cells.append(buf.value)
            elif r2 == 1:
                cells.append("")# ERROR_INVALID_PARAMETER = 该列为整数或 NULL
            elif r2 == 3:
                cells.append(buf.value)         # ERROR_MORE_DATA，值已截断但可用
            else:
                cells.append("")
        rows.append(cells)
        _msi.MsiCloseHandle(rec)

    _msi.MsiCloseHandle(hv)
    _msi.MsiCloseHandle(hdb)
    return rows


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    rows = query(sys.argv[1], sys.argv[2])
    for r in rows:
        print(" | ".join(r))
    print("(%d 行)" % len(rows))


if __name__ == "__main__":
    main()
