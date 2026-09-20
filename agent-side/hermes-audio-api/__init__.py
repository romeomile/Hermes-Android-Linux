"""hermes-audio-api — the agent's own speech on the api_server.

Why this exists
---------------
The api_server (``gateway/platforms/api_server.py``) advertises ``audio_api: false`` and implements
no ``/api/audio/*`` routes; those routes live in the desktop dashboard backend
(``hermes_cli/web_routers/audio.py``). So a plain OpenAI-compatible client that wants "the agent's
voice" gets **HTTP 404 at /api/audio/voice-config** even when ``tts:`` / ``stt:`` are fully
configured on this host — the config is fine, the endpoint simply is not served.

This plugin registers the same three contracts on the api_server's aiohttp app through the supported
plugin hook (``ctx.register_platform_handler``), so the routes live in your plugins dir instead of a
patched core file and therefore survive ``hermes update``:

    POST /api/audio/speak         {"text"}                  -> {"ok", "data_url", "mime_type", "provider"}
    POST /api/audio/transcribe    {"data_url","mime_type?"} -> {"ok", "transcript", "provider"}
    GET  /api/audio/voice-config                            -> {"ok", "stt": {...}, "tts": {...}}

The provider, voice and credential come from this agent's own ``tts:`` / ``stt:`` config, so no
speech key is ever stored on the client. Auth is the api_server's own bearer token
(``API_SERVER_KEY``), the same one the chat endpoint uses.

Install: copy this directory to ``~/.hermes/plugins/hermes-audio-api/`` and restart the gateway.
Already-patched hosts are safe: routes that exist are skipped, never re-registered.
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import tempfile
from contextlib import suppress
from typing import Any, Dict, Optional, Set, Tuple

logger = logging.getLogger(__name__)

# Platform key the gateway hands this factory to (gateway/config.py: Platform.API_SERVER).
PLATFORM = "api_server"

_TTS_MIME_BY_EXT = {
    ".mp3": "audio/mpeg", ".ogg": "audio/ogg", ".opus": "audio/ogg",
    ".wav": "audio/wav", ".flac": "audio/flac",
}

_TRANSCRIBE_MIME_EXT = {
    "audio/aac": ".aac", "audio/flac": ".flac", "audio/m4a": ".m4a", "audio/mp3": ".mp3",
    "audio/mp4": ".mp4", "audio/mpeg": ".mp3", "audio/ogg": ".ogg", "audio/wav": ".wav",
    "audio/wave": ".wav", "audio/webm": ".webm", "audio/x-m4a": ".m4a", "audio/x-wav": ".wav",
    "video/webm": ".webm",
}

_MAX_TRANSCRIBE_BYTES = 25 * 1024 * 1024

ROUTES: Tuple[Tuple[str, str], ...] = (
    # Dashboard parity: the paths Hermes' desktop/dashboard audio API uses.
    ("POST", "/api/audio/speak"),
    ("POST", "/api/audio/transcribe"),
    ("GET", "/api/audio/voice-config"),
    # Proxy-friendly mirror: deployments that expose only the OpenAI-style `/v1` prefix to a client
    # (a reverse proxy with `location /v1/`, for example) never see `/api/...`, so the same three
    # contracts are served here too. Clients try `/api/audio/...` first and fall back to this.
    ("POST", "/v1/audio/speak"),
    ("POST", "/v1/audio/transcribe"),
    ("GET", "/v1/audio/voice-config"),
)


# ---------------------------------------------------------------------------- blocking halves
# Imports stay inside the functions: a build without one of these modules degrades to a clear HTTP
# error on that route instead of failing the plugin load (and with it the whole platform wiring).

def _speak_sync(text: str) -> Tuple[bytes, str, Optional[str]]:
    """Run the agent's own TTS chain; return (audio, mime, provider)."""
    from tools.tts_tool import text_to_speech_tool

    result = text_to_speech_tool(text)
    if isinstance(result, str):
        result = json.loads(result)
    if not isinstance(result, dict) or not result.get("success"):
        raise RuntimeError(str((result or {}).get("error") or "Speech synthesis failed"))
    path = str(result.get("file_path") or "")
    if not path or not os.path.isfile(path):
        raise RuntimeError("Audio file missing")
    mime = _TTS_MIME_BY_EXT.get(os.path.splitext(path)[1].lower(), "audio/mpeg")
    try:
        with open(path, "rb") as handle:
            audio = handle.read()
    finally:
        with suppress(OSError):
            os.unlink(path)
    return audio, mime, result.get("provider")


def _transcribe_sync(raw: bytes, mime_type: str) -> Tuple[str, Optional[str]]:
    """Run the agent's own STT chain; return (transcript, provider)."""
    from tools.voice_mode import transcribe_recording

    suffix = _TRANSCRIBE_MIME_EXT.get(mime_type.split(";", 1)[0].strip().lower(), ".webm")
    temp_path = ""
    try:
        with tempfile.NamedTemporaryFile(
            prefix="hermes-api-voice-", suffix=suffix, delete=False
        ) as tmp:
            tmp.write(raw)
            temp_path = tmp.name
        result = transcribe_recording(temp_path) or {}
    finally:
        if temp_path:
            with suppress(OSError):
                os.unlink(temp_path)

    if not result.get("success"):
        error = str(result.get("error") or "Transcription failed")
        # "No speech detected" is a normal outcome for a continuous voice loop, not an error.
        if "empty transcript" in error.lower():
            return "", result.get("provider")
        raise RuntimeError(error)
    return str(result.get("transcript") or "").strip(), result.get("provider")


def _voice_config_sync() -> Dict[str, Any]:
    """The active profile's STT/TTS config for client-direct voice (falls back to relay)."""
    from tools.voice_client_config import resolve_client_voice_config

    return resolve_client_voice_config()


# ---------------------------------------------------------------------------- wiring
def _existing_paths(app: Any) -> Set[str]:
    """Paths already served by the app, so a patched/build-in audio API is never shadowed."""
    paths: Set[str] = set()
    try:
        for resource in app.router.resources():
            canonical = getattr(resource, "canonical", None)
            if canonical:
                paths.add(str(canonical))
    except Exception:  # pragma: no cover - diagnostics only
        logger.debug("hermes-audio-api: could not read existing routes", exc_info=True)
    return paths


def wire(native: Any, adapter: Any) -> None:
    """``factory(native, adapter)`` invoked by the gateway at api_server connect().

    ``native`` is the api_server's ``aiohttp.web.Application``; ``adapter`` is the live
    ``APIServerAdapter`` (used only for its bearer-token check).
    """
    if native is None:
        logger.warning("hermes-audio-api: no aiohttp app handed over — audio routes not added")
        return

    try:
        from aiohttp import web

        from gateway.platforms.api_server import _error_response
    except Exception:
        logger.exception("hermes-audio-api: aiohttp/api_server imports failed — audio routes not added")
        return

    def _auth_failure(request: Any) -> Optional[Any]:
        checker = getattr(adapter, "_check_auth", None)
        if not callable(checker):
            return None
        return checker(request)

    async def _handle_speak(request: Any) -> Any:
        auth_err = _auth_failure(request)
        if auth_err is not None:
            return auth_err
        try:
            payload = await request.json()
        except Exception:
            return _error_response("Invalid JSON body", 400)
        text = str((payload or {}).get("text") or "").strip()
        if not text:
            return _error_response("text is required", 400)
        try:
            audio, mime_type, provider = await asyncio.to_thread(_speak_sync, text)
        except Exception as exc:
            logger.exception("POST /api/audio/speak failed")
            return _error_response(f"Speech synthesis failed: {exc}", 500, err_type="server_error")
        encoded = base64.b64encode(audio).decode("ascii")
        return web.json_response({
            "ok": True, "data_url": f"data:{mime_type};base64,{encoded}",
            "mime_type": mime_type, "provider": provider,
        })

    async def _handle_transcribe(request: Any) -> Any:
        auth_err = _auth_failure(request)
        if auth_err is not None:
            return auth_err
        try:
            payload = await request.json()
        except Exception:
            return _error_response("Invalid JSON body", 400)
        data_url = str((payload or {}).get("data_url") or "").strip()
        if not data_url.startswith("data:") or "," not in data_url:
            return _error_response("Invalid audio payload", 400)
        header, encoded = data_url.split(",", 1)
        if ";base64" not in header:
            return _error_response("Audio payload must be base64 encoded", 400)
        mime_type = str(
            (payload or {}).get("mime_type") or header[5:].split(";", 1)[0] or "audio/webm"
        ).strip()
        normalized = mime_type.split(";", 1)[0].lower()
        if not (normalized.startswith("audio/") or normalized == "video/webm"):
            return _error_response("Payload must be an audio recording", 400)
        try:
            raw = base64.b64decode(encoded, validate=True)
        except Exception:
            return _error_response("Audio payload is not valid base64", 400)
        if not raw:
            return _error_response("Audio recording is empty", 400)
        if len(raw) > _MAX_TRANSCRIBE_BYTES:
            return _error_response("Audio recording is too large", 413)
        try:
            transcript, provider = await asyncio.to_thread(_transcribe_sync, raw, mime_type)
        except Exception as exc:
            logger.exception("POST /api/audio/transcribe failed")
            return _error_response(f"Transcription failed: {exc}", 400)
        return web.json_response({"ok": True, "transcript": transcript, "provider": provider})

    async def _handle_voice_config(request: Any) -> Any:
        auth_err = _auth_failure(request)
        if auth_err is not None:
            return auth_err
        try:
            result = await asyncio.to_thread(_voice_config_sync)
        except Exception:
            logger.exception("GET /api/audio/voice-config failed")
            fallback = {"mode": "relay", "reason": "resolution error"}
            return web.json_response({"ok": True, "stt": fallback, "tts": dict(fallback)})
        return web.json_response({"ok": True, **result})

    handlers = {
        "/api/audio/speak": _handle_speak,
        "/api/audio/transcribe": _handle_transcribe,
        "/api/audio/voice-config": _handle_voice_config,
        "/v1/audio/speak": _handle_speak,
        "/v1/audio/transcribe": _handle_transcribe,
        "/v1/audio/voice-config": _handle_voice_config,
    }

    existing = _existing_paths(native)
    added, skipped = [], []
    for method, path in ROUTES:
        if path in existing:
            skipped.append(path)
            continue
        try:
            native.router.add_route(method, path, handlers[path])
            added.append(path)
        except Exception:
            logger.exception("hermes-audio-api: could not register %s %s", method, path)

    # Advertise the capability too: clients that feature-detect before probing see the truth.
    try:
        from gateway.platforms.api_server import _STATIC_FEATURE_FLAGS

        if isinstance(_STATIC_FEATURE_FLAGS, dict):
            _STATIC_FEATURE_FLAGS["audio_api"] = True
    except Exception:
        logger.debug("hermes-audio-api: capability flag not updated", exc_info=True)

    if added:
        logger.info("[api_server] hermes-audio-api: registered %s", ", ".join(added))
    if skipped:
        logger.info("[api_server] hermes-audio-api: already served, left alone: %s", ", ".join(skipped))


def register(ctx: Any) -> None:
    """Plugin entry point — hook the audio routes into the api_server's aiohttp app."""
    ctx.register_platform_handler(PLATFORM, wire)
