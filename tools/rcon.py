#!/usr/bin/env python3
"""Minimal Minecraft RCON client used by the mwguard smoke test."""
import socket
import struct
import sys


def send(sock, request_id, packet_type, payload):
    data = payload.encode("utf-8") + b"\x00\x00"
    packet = struct.pack("<ii", request_id, packet_type) + data
    sock.sendall(struct.pack("<i", len(packet)) + packet)


def recv(sock):
    raw_len = sock.recv(4)
    if len(raw_len) < 4:
        return None, None, ""
    (length,) = struct.unpack("<i", raw_len)
    body = b""
    while len(body) < length:
        chunk = sock.recv(length - len(body))
        if not chunk:
            break
        body += chunk
    request_id, packet_type = struct.unpack("<ii", body[:8])
    return request_id, packet_type, body[8:-2].decode("utf-8", "replace")


def main():
    host = "127.0.0.1"
    port = 25585
    password = "mwtest"
    commands = sys.argv[1:]

    sock = socket.create_connection((host, port), timeout=10)
    send(sock, 1, 3, password)
    rid, rtype, _ = recv(sock)
    if rid == -1:
        print("RCON auth failed")
        return 1
    for command in commands:
        send(sock, 2, 2, command)
        _, _, response = recv(sock)
        print(f"> {command}\n{response}\n")
    sock.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
