"""Network faults for live reconnect scenarios: a TCP proxy that can sever or silently stall every
client socket, and a control endpoint that client scenarios call to inject those faults or restart
the tagged server."""

from __future__ import annotations

import json
import queue
import random
import socket
import struct
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

REPORT_KINDS = ("methods", "events", "server_requests", "rest", "errors")


@dataclass(frozen=True)
class NetworkProfile:
    """How the proxy shapes each connection, in both directions.

    ``latency`` delays every chunk by the one-way delay plus up to ``jitter`` (never reordering, as
    TCP does not); ``bandwidth`` caps throughput in bytes per second; ``segment`` splits writes so
    frames arrive in pieces; ``reset_after`` resets each connection after a random lifetime with that
    mean (seconds), as a flapping mobile link does.
    """

    latency: float = 0.0
    jitter: float = 0.0
    bandwidth: float | None = None
    segment: int = 65536
    reset_after: float | None = None


PROFILES = {
    "none": NetworkProfile(),
    "lte": NetworkProfile(latency=0.035, jitter=0.015, bandwidth=2_500_000, segment=16384),
    "3g": NetworkProfile(latency=0.150, jitter=0.050, bandwidth=90_000, segment=1400),
    "edge": NetworkProfile(latency=0.400, jitter=0.150, bandwidth=15_000, segment=512),
    "flaky": NetworkProfile(latency=0.080, jitter=0.080, bandwidth=250_000, segment=1400, reset_after=4.0),
}


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
        self.profile = PROFILES["none"]
        self.resets = 0
        self._random = random.Random(20260927)
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
                profile = self.profile
                lifetime = (self._random.expovariate(1 / profile.reset_after)
                            if profile.reset_after else None)
            for source, target in ((client, upstream), (upstream, client)):
                threading.Thread(target=self._pump, args=(source, target, pair), daemon=True).start()
            if lifetime is not None:
                threading.Timer(lifetime, self._reset_pair, args=(pair,)).start()

    def set_profile(self, name: str) -> None:
        """Shape every connection, open or new, with a named profile. A profile that resets
        connections gives each open one a fresh random lifetime too."""
        with self._lock:
            self.profile = PROFILES[name]
            timers = ([(self._random.expovariate(1 / self.profile.reset_after), pair) for pair in self._pairs]
                      if self.profile.reset_after else [])
        for lifetime, pair in timers:
            threading.Timer(lifetime, self._reset_pair, args=(pair,)).start()

    def _reset_pair(self, pair: tuple[socket.socket, socket.socket]) -> None:
        with self._lock:
            # A lifetime drawn under a resetting profile lapses once the link is healthy again.
            if pair not in self._pairs or not self.profile.reset_after:
                return
            self._pairs.discard(pair)
            self.resets += 1
        for sock in pair:
            _reset(sock)

    def _pump(self, source: socket.socket, target: socket.socket, pair: tuple[socket.socket, socket.socket]) -> None:
        # Reading and delivering run apart, so latency delays data without throttling the reader.
        line: queue.Queue[tuple[float, bytes] | None] = queue.Queue()
        deliver = threading.Thread(target=self._deliver, args=(line, target, pair), daemon=True)
        deliver.start()
        last_due = 0.0
        try:
            while data := source.recv(65536):
                with self._lock:
                    stalled = pair in self._stalled
                    profile = self.profile
                if stalled:
                    continue
                if profile.latency or profile.jitter:
                    due = time.monotonic() + profile.latency + self._random.uniform(0, profile.jitter)
                    last_due = max(last_due, due)  # never reorder
                else:
                    last_due = 0.0
                line.put((last_due, data))
        except OSError:
            pass
        finally:
            line.put(None)
            deliver.join(timeout=30)
            self._close_pair(pair)

    def _deliver(self, line: queue.Queue[tuple[float, bytes] | None], target: socket.socket,
                 pair: tuple[socket.socket, socket.socket]) -> None:
        try:
            while (item := line.get()) is not None:
                due, data = item
                if (wait := due - time.monotonic()) > 0:
                    time.sleep(wait)
                with self._lock:
                    profile = self.profile
                    if pair not in self._pairs:
                        return
                for start in range(0, len(data), profile.segment):
                    piece = data[start:start + profile.segment]
                    target.sendall(piece)
                    if profile.bandwidth:
                        time.sleep(len(piece) / profile.bandwidth)
        except OSError:
            pass
        finally:
            self._close_pair(pair)

    def _close_pair(self, pair: tuple[socket.socket, socket.socket]) -> None:
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
    ``POST /conditions?profile=NAME`` shapes traffic with a network profile (see ``PROFILES``),
    ``POST /restart`` replaces the server process, ``POST /report`` receives the JSON summary of
    what a passing client run exercised on the wire, ``GET /rest-scenario`` serves the REST calls
    every client runs through its generated operations, and ``GET /stress`` describes the stress
    dataset while ``POST /metrics`` receives a stress run's timings in milliseconds."""

    def __init__(self, proxy: FaultProxy, restart: Callable[[], None], rest_scenario: dict | None = None,
                 stress: dict | None = None) -> None:
        self.restarts = 0
        self.report: dict[str, list[str]] | None = None
        self.metrics: dict[str, float] | None = None
        documents = {"/rest-scenario": json.dumps(rest_scenario or {"calls": []}).encode("utf-8"),
                     "/stress": json.dumps(stress or {}).encode("utf-8")}
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, _format: str, *_args: object) -> None:
                pass

            def do_GET(self) -> None:
                document = documents.get(urlparse(self.path).path)
                if document is None:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(document)))
                self.end_headers()
                self.wfile.write(document)

            def do_POST(self) -> None:
                url = urlparse(self.path)
                try:
                    if url.path == "/drop":
                        hold_ms = int(parse_qs(url.query).get("hold_ms", ["0"])[0])
                        proxy.drop(hold_ms / 1000)
                    elif url.path == "/blackhole":
                        proxy.blackhole()
                    elif url.path == "/conditions":
                        proxy.set_profile(parse_qs(url.query).get("profile", ["none"])[0])
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
                    elif url.path == "/metrics":
                        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
                        if not isinstance(body, dict) or not all(
                                isinstance(name, str) and isinstance(value, (int, float)) for name, value in body.items()):
                            raise ValueError("Malformed stress metrics")
                        owner.metrics = {name: float(value) for name, value in body.items()}
                    else:
                        self.send_error(404)
                        return
                except Exception as error:  # noqa: BLE001 - the client scenario reports the failure
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
