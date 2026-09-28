"""A TLS front with a throwaway self-signed certificate, so live scenarios check that clients reach Hermes over
HTTPS and WSS with the app's own trust policy (a pinned certificate), as apps talking to a self-hosted
dashboard do."""

from __future__ import annotations

import base64
import socket
import ssl
import subprocess
import tempfile
import threading
from pathlib import Path


class TLSFront:
    """Terminate TLS on 127.0.0.1:<port> and forward plaintext to the Hermes server."""

    def __init__(self, upstream_port: int) -> None:
        self.upstream_port = upstream_port
        self._directory = tempfile.TemporaryDirectory(prefix="hermes-api-tls-")
        key = Path(self._directory.name) / "key.pem"
        certificate = Path(self._directory.name) / "certificate.pem"
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
             "-keyout", str(key), "-out", str(certificate), "-subj", "/CN=127.0.0.1",
             "-addext", "subjectAltName=IP:127.0.0.1"],
            check=True, capture_output=True,
        )
        self.certificate_der = ssl.PEM_cert_to_DER_cert(certificate.read_text(encoding="ascii"))
        self._context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self._context.load_cert_chain(certificate, key)
        self._listener = socket.create_server(("127.0.0.1", 0))
        self.port = int(self._listener.getsockname()[1])
        self.url = f"https://127.0.0.1:{self.port}"
        self._closed = False
        self._thread = threading.Thread(target=self._accept, daemon=True)
        self._thread.start()

    @property
    def certificate(self) -> str:
        """The certificate in DER, base64-encoded, as the clients pin it."""
        return base64.b64encode(self.certificate_der).decode("ascii")

    def _accept(self) -> None:
        while not self._closed:
            try:
                client, _ = self._listener.accept()
            except OSError:
                return
            threading.Thread(target=self._serve, args=(client,), daemon=True).start()

    def _serve(self, client: socket.socket) -> None:
        try:
            client.settimeout(10)
            secure = self._context.wrap_socket(client, server_side=True)
            secure.settimeout(None)
            upstream = socket.create_connection(("127.0.0.1", self.upstream_port), timeout=5)
            upstream.settimeout(None)
        except (OSError, ssl.SSLError):
            # A client that refuses the certificate ends the handshake; that is expected.
            client.close()
            return
        for source, target in ((secure, upstream), (upstream, secure)):
            threading.Thread(target=self._pump, args=(source, target), daemon=True).start()

    @staticmethod
    def _pump(source: socket.socket, target: socket.socket) -> None:
        try:
            while data := source.recv(65536):
                target.sendall(data)
        except (OSError, ssl.SSLError):
            pass
        finally:
            for sock in (source, target):
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                sock.close()

    def close(self) -> None:
        self._closed = True
        self._listener.close()
        self._thread.join(timeout=5)
        self._directory.cleanup()
