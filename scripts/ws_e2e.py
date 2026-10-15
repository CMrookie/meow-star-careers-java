#!/usr/bin/env python3
"""极简 WebSocket 客户端（RFC6455）+ /ws 协议端到端验证。

只用标准库（本机 python3 无 websockets 依赖）。
覆盖：握手鉴权 401、ping/pong、非法报文、未知类型、send 入库+双方推送、面试信令转发。
"""
from __future__ import annotations

import base64
import json
import os
import socket
import struct
import sys
import time

TIMEOUT = 6.0

failures: list[str] = []
passes = 0


def check(name: str, ok: bool, detail: str = "") -> None:
    global passes
    if ok:
        passes += 1
        print(f"  \033[32m✓\033[0m {name}")
    else:
        failures.append(name)
        print(f"  \033[31m✗\033[0m {name} {detail}")


class WsClient:
    def __init__(self, host: str, port: int, token: str):
        self.host = host
        self.port = port
        self.token = token
        self.sock: socket.socket | None = None
        self.buffer = b""

    def handshake(self) -> tuple[int, dict[str, str]]:
        """返回 (状态码, 响应头)；101 表示升级成功。"""
        self.sock = socket.create_connection((self.host, self.port), timeout=TIMEOUT)
        key = base64.b64encode(os.urandom(16)).decode()
        request = (
            f"GET /ws?token={self.token} HTTP/1.1\r\n"
            f"Host: {self.host}:{self.port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        self.sock.sendall(request.encode())
        raw = b""
        while b"\r\n\r\n" not in raw:
            chunk = self.sock.recv(4096)
            if not chunk:
                break
            raw += chunk
        head, _, rest = raw.partition(b"\r\n\r\n")
        self.buffer = rest
        lines = head.decode("utf-8", "replace").split("\r\n")
        status = int(lines[0].split(" ")[1]) if len(lines[0].split(" ")) > 1 else 0
        headers = {}
        for line in lines[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                headers[k.strip().lower()] = v.strip()
        return status, headers

    def send_text(self, payload: str) -> None:
        assert self.sock is not None
        data = payload.encode()
        header = bytearray([0x81])
        length = len(data)
        if length < 126:
            header.append(0x80 | length)
        elif length < 65536:
            header.append(0x80 | 126)
            header += struct.pack(">H", length)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", length)
        mask = os.urandom(4)
        header += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        self.sock.sendall(bytes(header) + masked)

    def _read_exact(self, n: int) -> bytes:
        assert self.sock is not None
        while len(self.buffer) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("连接已关闭")
            self.buffer += chunk
        out, self.buffer = self.buffer[:n], self.buffer[n:]
        return out

    def recv_text(self, timeout: float = TIMEOUT) -> str | None:
        """读取一条文本报文（自动跳过 ping/pong，处理关闭帧）。超时返回 None。"""
        assert self.sock is not None
        deadline = time.time() + timeout
        while True:
            remaining = deadline - time.time()
            if remaining <= 0:
                return None
            self.sock.settimeout(remaining)
            try:
                first = self._read_exact(2)
            except (socket.timeout, TimeoutError):
                return None
            opcode = first[0] & 0x0F
            masked = bool(first[1] & 0x80)
            length = first[1] & 0x7F
            if length == 126:
                length = struct.unpack(">H", self._read_exact(2))[0]
            elif length == 127:
                length = struct.unpack(">Q", self._read_exact(8))[0]
            mask = self._read_exact(4) if masked else None
            data = self._read_exact(length) if length else b""
            if mask:
                data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
            if opcode == 0x1:
                return data.decode("utf-8", "replace")
            if opcode == 0x8:
                return None
            if opcode == 0x9:
                continue
            # 其它（pong/二进制/续帧）忽略
        return None

    def close(self) -> None:
        if self.sock is not None:
            try:
                self.sock.close()
            except OSError:
                pass
            self.sock = None


def json_of(raw: str | None):
    if raw is None:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return None


def collect(client: WsClient, wanted_types: set[str], limit: int = 12, timeout: float = TIMEOUT):
    """收齐指定 type 的报文，返回 {type: [payload, ...]}。"""
    found: dict[str, list] = {}
    for _ in range(limit):
        if wanted_types.issubset(found.keys()):
            break
        raw = client.recv_text(timeout)
        if raw is None:
            break
        payload = json_of(raw)
        if payload is None:
            continue
        kind = payload.get("type")
        if kind in wanted_types:
            found.setdefault(kind, []).append(payload)
    return found


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--seeker-token", required=True)
    parser.add_argument("--recruiter-token", required=True)
    parser.add_argument("--conversation-id", required=True)
    parser.add_argument("--interview-id", required=True)
    args = parser.parse_args()

    print("\n[WebSocket 协议验证]")

    # 1) 无效 token 必须 401 且拒绝升级
    bad = WsClient(args.host, args.port, "deadbeef" * 4)
    try:
        status, _ = bad.handshake()
        check("无效 token 握手返回 401", status == 401, f"实际 {status}")
    except Exception as ex:  # noqa: BLE001
        check("无效 token 握手返回 401", False, str(ex))
    finally:
        bad.close()

    # 2) 双方在线
    seeker = WsClient(args.host, args.port, args.seeker_token)
    recruiter = WsClient(args.host, args.port, args.recruiter_token)
    try:
        s_status, _ = seeker.handshake()
        r_status, _ = recruiter.handshake()
        check("求职者握手 101", s_status == 101, f"实际 {s_status}")
        check("招聘者握手 101", r_status == 101, f"实际 {r_status}")
        if s_status != 101 or r_status != 101:
            return 1

        # 3) ping -> pong
        seeker.send_text(json.dumps({"type": "ping"}))
        got = collect(seeker, {"pong"})
        check("ping -> pong", "pong" in got)

        # 4) 非法 JSON
        seeker.send_text("not-json")
        got = collect(seeker, {"error"})
        check("非法报文 -> error(bad_request)",
              any(e.get("code") == "bad_request" for e in got.get("error", [])))

        # 5) 未知类型
        seeker.send_text(json.dumps({"type": "nope"}))
        got = collect(seeker, {"error"})
        check("未知类型 -> error 提示类型名",
              any("未知报文类型" in (e.get("message") or "") for e in got.get("error", [])))

        # 6) send 缺字段
        seeker.send_text(json.dumps({"type": "send"}))
        got = collect(seeker, {"error"})
        check("send 缺字段 -> error", "error" in got)

        # 7) 正常发消息：发送者得到 message + ack，接收者得到 message
        body = "ws-e2e-" + str(int(time.time()))
        seeker.send_text(json.dumps({"type": "send", "conversationId": args.conversation_id, "body": body}))
        s_got = collect(seeker, {"ack", "message"})
        check("发送者收到 ack", "ack" in s_got)
        check("发送者收到 message", "message" in s_got)
        r_got = collect(recruiter, {"message"})
        check("接收者收到 message", "message" in r_got)
        if r_got.get("message"):
            payload = r_got["message"][0]
            check("消息内容一致",
                  payload.get("message", {}).get("content") == body
                  and payload.get("conversationId") == args.conversation_id,
                  json.dumps(payload, ensure_ascii=False)[:200])

        # 8) 面试信令转发
        signal = {"kind": "offer", "sdp": "v=0"}
        seeker.send_text(json.dumps({"type": "signal", "interviewId": args.interview_id, "payload": signal}))
        r_got = collect(recruiter, {"interview-signal"})
        check("对端收到 interview-signal", "interview-signal" in r_got)
        if r_got.get("interview-signal"):
            payload = r_got["interview-signal"][0]
            check("信令字段完整",
                  payload.get("interviewId") == args.interview_id
                  and payload.get("payload") == signal
                  and payload.get("from"),
                  json.dumps(payload, ensure_ascii=False)[:200])

        # 9) 非参与者发信令 -> forbidden
        seeker.send_text(json.dumps({"type": "signal",
                                     "interviewId": "00000000-0000-0000-0000-000000000000",
                                     "payload": {"kind": "offer"}}))
        got = collect(seeker, {"error"})
        check("非参与者信令 -> error(forbidden)",
              any(e.get("code") == "forbidden" for e in got.get("error", [])),
              json.dumps(got, ensure_ascii=False)[:200])
    finally:
        seeker.close()
        recruiter.close()

    print(f"\n[WebSocket] 通过 {passes} 项，失败 {len(failures)} 项")
    if failures:
        for item in failures:
            print(f"  - 失败: {item}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
