"""Network faults for live reconnect scenarios: a TCP proxy that can sever or silently stall every
client socket, and a control endpoint that client scenarios call to inject those faults or restart
the tagged server."""

from __future__ import annotations

import json
import socket
import struct
import threading
import time
import traceback
from collections.abc import Callable
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

REPORT_KINDS = ("methods", "events", "server_requests")


def _reset(sock: socket.socket) -> None:
    """Close with RST, as a dropped network path looks to the peer."""
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
    except OSError:
        pass
    try:
        sock.close()
    except OSError:
        pass


class FaultProxy:
    """Forward 127.0.0.1:<port> to the Hermes server until told to drop every connection."""

    def __init__(self, upstream_port: int) -> None:
        self.upstream_port = upstream_port
        self.drops = 0
        self.blackholes = 0
        self._lock = threading.Lock()
        self._pairs: set[tuple[socket.socket, socket.socket]] = set()
        self._stalled: set[tuple[socket.socket, socket.socket]] = set()
        self._refuse_until = 0.0
        self._listener = socket.create_server(("127.0.0.1", 0))
        self.port = int(self._listener.getsockname()[1])
        self._closed = False
        self._thread = threading.Thread(target=self._accept, daemon=True)
        self._thread.start()

    def _accept(self) -> None:
        while not self._closed:
            try:
                client, _ = self._listener.accept()
            except OSError:
                return
            with self._lock:
                refusing = time.monotonic() < self._refuse_until
            if refusing:
                _reset(client)
                continue
            try:
                upstream = socket.create_connection(("127.0.0.1", self.upstream_port), timeout=5)
                upstream.settimeout(None)
            except OSError:
                _reset(client)
                continue
            pair = (client, upstream)
            with self._lock:
                self._pairs.add(pair)
            for source, target in ((client, upstream), (upstream, client)):
                threading.Thread(target=self._pump, args=(source, target, pair), daemon=True).start()

    def _pump(self, source: socket.socket, target: socket.socket, pair: tuple[socket.socket, socket.socket]) -> None:
        try:
            while data := source.recv(65536):
                with self._lock:
                    stalled = pair in self._stalled
                if not stalled:
                    target.sendall(data)
        except OSError:
            pass
        finally:
            with self._lock:
                owned = pair in self._pairs
                self._pairs.discard(pair)
                self._stalled.discard(pair)
            if owned:
                for sock in pair:
                    try:
                        sock.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                    sock.close()

    def drop(self, hold_seconds: float) -> int:
        """Reset every proxied connection and refuse new ones for ``hold_seconds``."""
        with self._lock:
            self._refuse_until = time.monotonic() + hold_seconds
            pairs = list(self._pairs)
            self._pairs.clear()
            self.drops += 1
        for client, upstream in pairs:
            _reset(client)
            _reset(upstream)
        return len(pairs)

    def blackhole(self) -> int:
        """Discard all traffic on every open connection without closing it, as a dead network path
        does. Only the client's own liveness check can notice; new connections work normally."""
        with self._lock:
            self._stalled.update(self._pairs)
            self.blackholes += 1
            return len(self._pairs)

    def close(self) -> None:
        self._closed = True
        self._listener.close()
        self.drop(0)
        self._thread.join(timeout=5)


class ControlServer:
    """``POST /drop?hold_ms=N`` severs client sockets, ``POST /blackhole`` stalls them silently,
    ``POST /restart`` replaces the server process, and ``POST /report`` receives the JSON summary of
    what a passing client run exercised on the wire."""

    def __init__(self, proxy: FaultProxy, restart: Callable[[], None]) -> None:
        self.restarts = 0
        self.report: dict[str, list[str]] | None = None
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, _format: str, *_args: object) -> None:
                pass

            def do_POST(self) -> None:
                url = urlparse(self.path)
                try:
                    if url.path == "/drop":
                        hold_ms = int(parse_qs(url.query).get("hold_ms", ["0"])[0])
                        proxy.drop(hold_ms / 1000)
                    elif url.path == "/blackhole":
                        proxy.blackhole()
                    elif url.path == "/restart":
                        proxy.drop(0)
                        restart()
                        owner.restarts += 1
                    elif url.path == "/report":
                        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
                        if set(body) != set(REPORT_KINDS) or not all(
                                isinstance(body[kind], list) and all(isinstance(name, str) for name in body[kind])
                                for kind in REPORT_KINDS):
                            raise ValueError("Malformed live report")
                        owner.report = {kind: sorted(set(body[kind])) for kind in REPORT_KINDS}
                    else:
                        self.send_error(404)
                        return
                except Exception as error:  # noqa: BLE001 - the client scenario reports the failure
                    # Clients only learn that the call failed; the harness log keeps why.
                    traceback.print_exc()
                    self.send_error(500, str(error))
                    return
                self.send_response(204)
                self.end_headers()

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = int(self.server.server_port)
        self._thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self._thread.start()

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self._thread.join(timeout=5)
