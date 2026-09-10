"""Minimal Minecraft RCON client. usage: python rcon.py "<command>" ["<command>" ...]"""
import socket, struct, sys

HOST, PORT, PASSWORD = "127.0.0.1", 25598, "localtest"
LOGIN, COMMAND, RESPONSE = 3, 2, 0


def _send(sock, req_id, kind, payload):
    body = struct.pack("<ii", req_id, kind) + payload.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(body)) + body)


def _recv(sock):
    (length,) = struct.unpack("<i", sock.recv(4))
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise ConnectionError("socket closed")
        data += chunk
    req_id, kind = struct.unpack("<ii", data[:8])
    return req_id, kind, data[8:-2].decode("utf-8", "replace")


def run(commands):
    with socket.create_connection((HOST, PORT), timeout=10) as s:
        _send(s, 1, LOGIN, PASSWORD)
        rid, _, _ = _recv(s)
        if rid == -1:
            raise SystemExit("rcon auth failed")
        out = []
        for i, cmd in enumerate(commands, start=10):
            _send(s, i, COMMAND, cmd)
            _, _, resp = _recv(s)
            out.append((cmd, resp))
        return out


if __name__ == "__main__":
    for cmd, resp in run(sys.argv[1:]):
        print(f"> {cmd}\n{resp}")
