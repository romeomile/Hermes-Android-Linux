#!/usr/bin/env python3
"""Transparent TCP relay: 0.0.0.0:9129 -> 127.0.0.1:9128 (inside the guest).

Why this exists
---------------
QEMU SLIRP delivers host-forwarded traffic to the guest's eth0 (10.0.2.15), never
to the guest's loopback. `hermes dashboard` refuses to be useful on a public bind
(0.0.0.0): it answers `/` with a 302 to /login because a non-loopback bind always
requires an auth provider, and on a loopback bind it serves the SPA with the
session token injected. So the dashboard stays on 127.0.0.1:9128 and this relay is
the only thing facing eth0: it accepts on 0.0.0.0:9129 and pipes raw bytes to the
loopback listener.

It is a pure byte pipe on purpose: nothing is parsed, rewritten or terminated, so
an HTTP request, a WebSocket upgrade (`GET /api/ws?token=...` + Upgrade: websocket)
and every frame after it pass through untouched.

Endpoints are configurable (flags win over environment):
    --listen-host   RELAY_LISTEN_HOST   default 0.0.0.0
    --listen-port   RELAY_LISTEN_PORT   default 9129
    --target-host   RELAY_TARGET_HOST   default 127.0.0.1
    --target-port   RELAY_TARGET_PORT   default 9128

Stdlib only, no third-party imports: it must run in a bare Alpine guest.
"""

import argparse
import os
import signal
import socket
import sys
import threading
import time

DEFAULT_LISTEN_HOST = "0.0.0.0"
DEFAULT_LISTEN_PORT = 9129
DEFAULT_TARGET_HOST = "127.0.0.1"
DEFAULT_TARGET_PORT = 9128

BUFFER_SIZE = 65536
CONNECT_TIMEOUT = 10.0

_LOG_LOCK = threading.Lock()


def log(message):
    """One line per event on stdout (the service's log file)."""
    with _LOG_LOCK:
        print("[relay] %s" % message, flush=True)


def _env(name, default):
    value = os.environ.get(name, "").strip()
    return value or default


def _env_int(name, default):
    try:
        return int(_env(name, str(default)))
    except ValueError:
        return default


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Relay TCP bytes from a public listener to a loopback service."
    )
    parser.add_argument("--listen-host", default=_env("RELAY_LISTEN_HOST", DEFAULT_LISTEN_HOST))
    parser.add_argument("--listen-port", type=int,
                        default=_env_int("RELAY_LISTEN_PORT", DEFAULT_LISTEN_PORT))
    parser.add_argument("--target-host", default=_env("RELAY_TARGET_HOST", DEFAULT_TARGET_HOST))
    parser.add_argument("--target-port", type=int,
                        default=_env_int("RELAY_TARGET_PORT", DEFAULT_TARGET_PORT))
    return parser.parse_args(argv)


def _tune(sock):
    """Latency over throughput: the payload is chatty HTTP/WS, not bulk transfer."""
    try:
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    except OSError:
        pass


def _pump(src, dst, label):
    """Copy one direction until EOF, then half-close so the peer sees the EOF."""
    try:
        while True:
            data = src.recv(BUFFER_SIZE)
            if not data:
                break
            dst.sendall(data)
    except OSError as exc:
        # A reset/aborted peer is normal traffic, not an error worth dying for.
        log("%s: %s" % (label, exc.__class__.__name__))
    finally:
        # Half-close the write side: the peer keeps reading whatever is still in
        # flight and sees EOF, instead of the whole connection disappearing.
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass
        try:
            src.shutdown(socket.SHUT_RD)
        except OSError:
            pass


def _handle(client, addr, target):
    peer = "%s:%s" % (addr[0], addr[1])
    try:
        upstream = socket.create_connection(target, timeout=CONNECT_TIMEOUT)
    except OSError as exc:
        log("connect %s -> %s:%s FAILED (%s)" % (peer, target[0], target[1], exc))
        try:
            client.close()
        except OSError:
            pass
        return

    upstream.settimeout(None)
    _tune(client)
    _tune(upstream)
    log("connect %s -> %s:%s" % (peer, target[0], target[1]))

    # One thread per direction: two independent half-closes, so a client that
    # shuts down its write side does not kill the response coming back.
    to_target = threading.Thread(target=_pump, args=(client, upstream, "%s -> target" % peer))
    to_client = threading.Thread(target=_pump, args=(upstream, client, "target -> %s" % peer))
    for thread in (to_target, to_client):
        thread.daemon = True
        thread.start()
    for thread in (to_target, to_client):
        thread.join()

    # Both directions are done: close both sockets, in both directions.
    for sock in (client, upstream):
        try:
            sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            sock.close()
        except OSError:
            pass
    log("closed %s" % peer)


def _serve(client, addr, target):
    """Thread body: one failed connection must never take the relay down."""
    try:
        _handle(client, addr, target)
    except Exception as exc:  # noqa: BLE001 - last-resort guard for one connection
        log("connection %s:%s error: %r" % (addr[0], addr[1], exc))
        try:
            client.close()
        except OSError:
            pass


def main(argv=None):
    args = parse_args(sys.argv[1:] if argv is None else argv)
    target = (args.target_host, args.target_port)

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((args.listen_host, args.listen_port))
    server.listen(128)
    _tune(server)
    log("listening on %s:%d -> %s:%d" % (args.listen_host, args.listen_port,
                                         target[0], target[1]))

    stop = threading.Event()

    def _stop(signum, _frame):
        log("signal %d, shutting down" % signum)
        stop.set()
        try:
            server.close()
        except OSError:
            pass

    signal.signal(signal.SIGTERM, _stop)
    signal.signal(signal.SIGINT, _stop)

    while not stop.is_set():
        try:
            client, addr = server.accept()
        except OSError as exc:
            if stop.is_set():
                break
            log("accept failed (%s), retrying" % exc)
            time.sleep(0.2)
            continue
        thread = threading.Thread(target=_serve, args=(client, addr, target))
        thread.daemon = True
        thread.start()

    log("stopped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
