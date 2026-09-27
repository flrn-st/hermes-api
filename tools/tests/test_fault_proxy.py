"""The fault proxy shapes traffic like the named network profiles, without corrupting or reordering it."""

from __future__ import annotations

import socket
import threading
import time

from harness.faults import PROFILES, FaultProxy, NetworkProfile


def _echo_server() -> tuple[socket.socket, int]:
    server = socket.create_server(("127.0.0.1", 0))

    def serve() -> None:
        while True:
            try:
                connection, _ = server.accept()
            except OSError:
                return

            def echo(sock: socket.socket = connection) -> None:
                with sock:
                    try:
                        while data := sock.recv(65536):
                            sock.sendall(data)
                    except OSError:
                        pass  # the proxy reset the connection

            threading.Thread(target=echo, daemon=True).start()

    threading.Thread(target=serve, daemon=True).start()
    return server, server.getsockname()[1]


def _round_trip(proxy: FaultProxy, payload: bytes) -> tuple[bytes, float]:
    started = time.monotonic()
    with socket.create_connection(("127.0.0.1", proxy.port), timeout=20) as sock:
        sock.sendall(payload)
        received = b""
        while len(received) < len(payload):
            chunk = sock.recv(65536)
            if not chunk:
                break
            received += chunk
    return received, time.monotonic() - started


def test_profiles_delay_and_throttle_without_corrupting() -> None:
    server, port = _echo_server()
    proxy = FaultProxy(port)
    try:
        payload = bytes(range(256)) * 200  # 51,200 bytes
        received, fast = _round_trip(proxy, payload)
        assert received == payload
        PROFILES["test"] = NetworkProfile(latency=0.1, jitter=0.05, bandwidth=200_000, segment=700)
        proxy.set_profile("test")
        received, shaped = _round_trip(proxy, payload)
        assert received == payload
        # Both one-way delays plus 51 KB at 200 KB/s; the echo streams back while it receives, so the
        # two directions overlap.
        assert shaped >= 0.2 + len(payload) / 200_000 * 0.9
        assert shaped > fast
    finally:
        PROFILES.pop("test", None)
        proxy.close()
        server.close()


def test_flaky_profile_resets_connections() -> None:
    server, port = _echo_server()
    proxy = FaultProxy(port)
    try:
        with socket.create_connection(("127.0.0.1", proxy.port), timeout=5) as sock:
            time.sleep(0.1)
            # Connections opened before the link turns flaky are reset too.
            PROFILES["test"] = NetworkProfile(reset_after=0.05)
            proxy.set_profile("test")
            deadline = time.monotonic() + 5
            closed = False
            while time.monotonic() < deadline and not closed:
                try:
                    sock.sendall(b"ping")
                    closed = sock.recv(16) == b""
                except OSError:
                    closed = True
                time.sleep(0.02)
        assert closed and proxy.resets >= 1
    finally:
        PROFILES.pop("test", None)
        proxy.close()
        server.close()
