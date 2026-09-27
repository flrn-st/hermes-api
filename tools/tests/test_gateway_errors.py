"""Gateway errors are read from the tagged handlers and generated only as reviewed."""

from __future__ import annotations

import importlib
import json
import sys
import textwrap
from pathlib import Path

import pytest
import yaml

from tools import _render_gateway_errors as render
from tools.gateway_errors import load, reviewed_hash
from tools.gen_gateway_api import _error_lines, _swift_literal
from tools.ref_policy import current_release

CURRENT = current_release()

SERVER = '''
    LIMIT = 4064


    def _err(rid, code, msg, data=None):
        return {"id": rid, "error": {"code": code, "message": msg}}


    def _missing(rid, code=4001):
        return _err(rid, code, "session not found")


    def _refused(rid, code, message):
        return _err(rid, code, message)


    def _with_session(fn):
        def handler(rid, params):
            if not params.get("session_id"):
                return _missing(rid)
            return fn(rid, params)
        return handler


    def _failing(code):
        def deco(fn):
            def handler(rid, params):
                try:
                    return fn(rid, params)
                except Exception as exc:
                    return _err(rid, code, str(exc))
            return handler
        return deco


    @_with_session
    def submit(rid, params):
        if params.get("busy"):
            return _err(rid, 4009, "session busy")
        return _refused(rid, 5019 if params.get("remote") else 4030, f"denied: {params['who']}")


    @_failing(5061)
    def profiles(rid, params):
        return _err(rid, LIMIT, "profile '" + params["name"] + "' not found")


    def dispatch(req):
        if "method" not in req:
            return _err(None, -32600, "invalid request")
        return _err(req.get("id"), -32601, f"unknown method: {req['method']}")


    METHODS = {"session.submit": submit, "profiles.list": profiles}
'''


@pytest.fixture
def package(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[Path, object]:
    root = tmp_path / "fixturegateway"
    root.mkdir()
    (root / "__init__.py").write_text("", encoding="utf-8")
    (root / "server.py").write_text(textwrap.dedent(SERVER), encoding="utf-8")
    monkeypatch.syspath_prepend(str(tmp_path))
    sys.modules.pop("fixturegateway.server", None)
    sys.modules.pop("fixturegateway", None)
    return root, importlib.import_module("fixturegateway.server")


def _codes(entries: list[dict[str, object]]) -> dict[object, list[str]]:
    return {entry["code"]: entry["messages"] for entry in entries}


def test_extraction_follows_decorators_helpers_and_forwarded_codes(package: tuple[Path, object]) -> None:
    root, server = package
    result = render.analyze(root, server.METHODS, server.dispatch)
    submit = _codes(result["methods"]["session.submit"])
    # The decorator's helper, a literal, and a code forwarded through a helper's parameter.
    assert submit == {4001: ["session not found"], 4009: ["session busy"],
                      4030: ["denied: {…}"], 5019: ["denied: {…}"]}
    profiles = _codes(result["methods"]["profiles.list"])
    # A module constant, and the code the decorator factory was built with.
    assert profiles == {4064: ["profile '{…}' not found"], 5061: []}
    assert _codes(result["common"]) == {-32601: ["unknown method: {…}"], -32600: ["invalid request"]}


def test_extraction_is_deterministic(package: tuple[Path, object]) -> None:
    root, server = package
    first = render.analyze(root, server.METHODS, server.dispatch)
    assert json.dumps(first, sort_keys=True) == json.dumps(
        render.analyze(root, server.METHODS, server.dispatch), sort_keys=True)


def _extracted(tmp_path: Path, methods: dict[str, list[dict]], common: list[dict] | None = None) -> Path:
    path = tmp_path / "gateway-errors.json"
    path.write_text(json.dumps({"ref": CURRENT, "commit": "c", "common": common or [], "methods": methods}),
                    encoding="utf-8")
    return path


def _review(tmp_path: Path, document: dict) -> Path:
    path = tmp_path / "gateway-errors.yaml"
    path.write_text(yaml.safe_dump(document, allow_unicode=True), encoding="utf-8")
    return path


def _error(code: object, *messages: str) -> dict:
    return {"code": code, "messages": list(messages), "sites": ["tui_gateway/x.py:1"]}


METHODS = {"session.submit": [_error(4001, "session not found", "invalid pcm: {…}"),
                              _error(4009, "session busy"), _error("dynamic")]}
TEMPLATES = {4001: {"session not found", "invalid pcm: {…}"}, 4009: {"session busy"}}


def _codes_review(overrides: dict | None = None) -> dict:
    codes = {
        4001: {"kind": "invalidRequest", "messages": {"session not found": "notFound"},
               "reviewed": reviewed_hash(TEMPLATES[4001])},
        4009: {"kind": "busy", "reviewed": reviewed_hash(TEMPLATES[4009])},
    }
    codes.update(overrides or {})
    return codes


NAMES = {"sessionNotFound": {"summary": "No such session.", "match": [{"code": 4001, "message": "session not found"}]},
         "sessionBusy": {"summary": "Busy.", "match": [{"code": 4009}]}}


def test_reviewed_catalog_loads(tmp_path: Path) -> None:
    catalog = load(CURRENT, extracted_path=_extracted(tmp_path, METHODS),
                   review_path=_review(tmp_path, {"codes": _codes_review(), "names": NAMES}))
    assert [(code.code, code.kind, code.messages) for code in catalog.codes] == [
        (4001, "invalidRequest", (("session not found", "notFound"),)), (4009, "busy", ())]
    assert {name.name: name.kind for name in catalog.names} == {"sessionBusy": "busy", "sessionNotFound": "notFound"}
    lines = _error_lines(catalog.methods["session.submit"], catalog)
    assert lines == ['- 4001 invalidRequest: "invalid pcm: {…}"; notFound: "session not found"',
                     '- 4009 busy: "session busy"',
                     "- codes relayed unchanged from a compute host, plugin or connector service"]


@pytest.mark.parametrize(("codes", "names", "problem"), [
    ({4001: _codes_review()[4001]}, NAMES, "code 4009 is new"),
    (_codes_review({4010: {"kind": "busy", "reviewed": "x"}}), NAMES, "code 4010 is no longer answered"),
    (_codes_review({4009: {"kind": "busy", "reviewed": "stale"}}), NAMES, "code 4009 changed since review"),
    (_codes_review({4009: {"kind": "sleepy", "reviewed": reviewed_hash(TEMPLATES[4009])}}), NAMES, "kind 'sleepy'"),
    (_codes_review({4009: {"kind": "busy", "messages": {"gone": "notFound"},
                             "reviewed": reviewed_hash(TEMPLATES[4009])}}), NAMES, "overrides 'gone'"),
    (_codes_review(), {**NAMES, "other": {"summary": "x", "match": [{"code": 4009}]}}, "both match 4009"),
    (_codes_review(), {"mixed": {"summary": "x", "match": [{"code": 4001, "message": "session not found"},
                                                           {"code": 4009}]}}, "spans kinds"),
    (_codes_review(), {"missing": {"summary": "x", "match": [{"code": 4001, "message": "nope"}]}},
     "which code 4001 never carries"),
])
def test_review_must_agree_with_the_extraction(tmp_path: Path, codes: dict, names: dict, problem: str) -> None:
    with pytest.raises(ValueError, match=problem):
        load(CURRENT, extracted_path=_extracted(tmp_path, METHODS),
             review_path=_review(tmp_path, {"codes": codes, "names": names}))


def test_committed_review_matches_the_pinned_release() -> None:
    catalog = load(CURRENT)
    assert {name.name for name in catalog.names} >= {"sessionNotFound", "sessionSettling", "backendRetiring"}


def test_swift_literals_use_braced_unicode_escapes() -> None:
    assert _swift_literal("a — b") == '"a \\u{2014} b"'
    # A backslash in the message stays text.
    assert _swift_literal("x\\u1234") == '"x\\\\u1234"'
