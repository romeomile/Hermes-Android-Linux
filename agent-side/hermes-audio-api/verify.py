"""Self-test for the hermes-audio-api plugin — run it on the AGENT host, not on a phone.

Starts the plugin's routes on a scratch aiohttp server (a free loopback port, nothing to do with the
running gateway) and exercises the three contracts over real HTTP, printing PASS/FAIL per check.

    <hermes-install>/venv/bin/python verify.py     # e.g. ~/hermes-agent/venv/bin/python
    <hermes-install>/venv/bin/python verify.py /path/to/hermes-agent   # if auto-detection fails

Exit code 0 = every check passed. The plugin does not need the gateway to be running for this.
"""

import asyncio
import base64
import importlib.util
import json
import os
import pathlib
import socket
import subprocess
import sys
import urllib.error
import urllib.request
import uuid

HERE = pathlib.Path(__file__).resolve().parent
KEY = "verify-" + uuid.uuid4().hex[:12]


def _ensure_hermes_importable() -> bool:
    """Put the Hermes install dir on sys.path so gateway/tools imports resolve."""
    try:
        import gateway.platforms.api_server  # noqa: F401

        return True
    except Exception:
        pass

    candidates = [os.environ.get("HERMES_AGENT_DIR"), *(sys.argv[1:2])]
    try:
        out = subprocess.run(["hermes", "--version"], capture_output=True, text=True, timeout=30).stdout
        for line in out.splitlines():
            if "install directory" in line.lower() and ":" in line:
                candidates.append(line.split(":", 1)[1].strip())
    except Exception:
        pass
    for candidate in candidates:
        if not candidate:
            continue
        path = pathlib.Path(candidate).expanduser()
        if (path / "gateway" / "platforms" / "api_server.py").is_file():
            sys.path.insert(0, str(path))
            try:
                import gateway.platforms.api_server  # noqa: F401

                return True
            except Exception as exc:
                print(f"found {path} but could not import it: {exc}")
    return False


def _load_plugin():
    spec = importlib.util.spec_from_file_location("hermes_audio_api", HERE / "__init__.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


RESULTS = []


def check(name: str, ok: bool, detail: str = "") -> None:
    RESULTS.append(ok)
    print(f"{'PASS' if ok else 'FAIL'}  {name}{(' — ' + detail) if detail else ''}")


def main() -> int:
    if not _ensure_hermes_importable():
        print("Could not import hermes-agent. Pass its install directory:")
        print("  <venv>/bin/python verify.py /path/to/hermes-agent")
        return 2

    from aiohttp import web

    plugin = _load_plugin()
    port = _free_port()

    class FakeAdapter:
        def _check_auth(self, request):
            if request.headers.get("Authorization", "") == f"Bearer {KEY}":
                return None
            return web.json_response({"error": {"message": "Invalid API key"}}, status=401)

    async def run() -> None:
        app = web.Application()

        def wire() -> list:
            plugin.wire(app, FakeAdapter())
            return sorted(str(r.canonical) for r in app.router.resources())

        paths = wire()
        check("routes register on the api_server app (both prefixes)",
              {"/api/audio/speak", "/api/audio/transcribe", "/api/audio/voice-config",
               "/v1/audio/speak", "/v1/audio/transcribe", "/v1/audio/voice-config"} <= set(paths),
              ", ".join(paths))

        # A host that already serves these routes must not crash on a second wiring.
        try:
            wire()
            check("re-wiring is safe (no duplicate-route crash)", True)
        except Exception as exc:
            check("re-wiring is safe (no duplicate-route crash)", False, str(exc))

        runner = web.AppRunner(app)
        await runner.setup()
        await web.TCPSite(runner, "127.0.0.1", port).start()

        def call(method, path, body=None, auth=True):
            req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", method=method)
            if auth:
                req.add_header("Authorization", f"Bearer {KEY}")
            data = None
            if body is not None:
                data = json.dumps(body).encode()
                req.add_header("Content-Type", "application/json")
            try:
                with urllib.request.urlopen(req, data, timeout=180) as resp:
                    return resp.status, resp.read()
            except urllib.error.HTTPError as exc:
                return exc.code, exc.read()

        async def hit(*args, **kwargs):
            return await asyncio.to_thread(call, *args, **kwargs)

        status, _ = await hit("GET", "/api/audio/voice-config", auth=False)
        check("unauthenticated request is rejected", status == 401, f"HTTP {status}")

        status, body = await hit("GET", "/api/audio/voice-config")
        config = {}
        try:
            config = json.loads(body)
        except Exception:
            pass
        tts = config.get("tts") or {}
        check("GET /api/audio/voice-config answers", status == 200, f"HTTP {status}")

        status, _ = await hit("GET", "/v1/audio/voice-config")
        check("GET /v1/audio/voice-config answers (mirror for /v1-only proxies)",
              status == 200, f"HTTP {status}")
        print(f"      tts mode: {tts.get('mode')}"
              + (f" ({tts.get('reason')})" if tts.get("reason") else "")
              + f" | provider: {tts.get('provider') or 'agent-side'}")

        status, body = await hit("POST", "/api/audio/speak", {"text": "Speech check from verify.py."})
        if status == 200:
            payload = json.loads(body)
            raw = base64.b64decode(payload["data_url"].split(",", 1)[1])
            out = pathlib.Path(os.environ.get("TMPDIR", "/tmp")) / "hermes-audio-verify.mp3"
            out.write_bytes(raw)
            check("POST /api/audio/speak returns audio", len(raw) > 1000,
                  f"{len(raw)} bytes via {payload.get('provider')} → {out}")
        else:
            check("POST /api/audio/speak returns audio", False,
                  f"HTTP {status}: {body[:200].decode('utf-8', 'replace')}")

        status, _ = await hit("POST", "/api/audio/speak", {"text": "  "})
        check("empty text is rejected", status == 400, f"HTTP {status}")

        await runner.cleanup()

    asyncio.run(run())

    passed, total = sum(RESULTS), len(RESULTS)
    print(f"\n{passed}/{total} checks passed")
    if passed != total:
        print("If speak failed: check `tts.provider` in config.yaml and that the agent can reach it.")
    return 0 if passed == total else 1


if __name__ == "__main__":
    raise SystemExit(main())
