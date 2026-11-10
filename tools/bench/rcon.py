"""Minimal RCON client for the bench server. Usage: python rcon.py "<command>" ["<command>" ...]"""
import os
import socket
import struct
import sys

HOST, PORT, PASSWORD = "127.0.0.1", int(os.environ.get("RCON_PORT", "25584")), "aibench"


def packet(req_id, kind, body):
    data = struct.pack("<ii", req_id, kind) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(data)) + data


def read(sock):
    raw = b""
    while len(raw) < 4:
        chunk = sock.recv(4 - len(raw))
        if not chunk:
            raise ConnectionError("closed")
        raw += chunk
    (length,) = struct.unpack("<i", raw)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise ConnectionError("closed")
        data += chunk
    req_id, kind = struct.unpack("<ii", data[:8])
    return req_id, kind, data[8:-2].decode("utf-8", "replace")


def main():
    with socket.create_connection((HOST, PORT), timeout=120) as s:
        s.sendall(packet(1, 3, PASSWORD))
        rid, _, _ = read(s)
        if rid == -1:
            sys.exit("rcon auth failed")
        for i, cmd in enumerate(sys.argv[1:], start=2):
            s.sendall(packet(i, 2, cmd))
            _, _, body = read(s)
            print(body)


if __name__ == "__main__":
    main()
