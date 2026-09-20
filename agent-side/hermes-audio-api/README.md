# Agent side: give a Hermes agent the speech endpoints the app asks for

The app's **Agent voice (Hermes)** mode speaks through the agent, so the provider, voice and key
configured on the agent do the speaking and no speech credential is stored on the phone. It needs
three routes on the agent's `api_server`:

```
POST /api/audio/speak         {"text"}                  -> {"ok", "data_url", "mime_type", "provider"}
POST /api/audio/transcribe    {"data_url","mime_type?"} -> {"ok", "transcript", "provider"}
GET  /api/audio/voice-config                            -> {"ok", "stt": {...}, "tts": {...}}
```

Those routes exist in the Hermes **desktop/serve** backend, but the **`api_server` gateway ships
none of them** — it advertises `"audio_api": false` and implements no `/api/audio/*` handler (true of
current upstream `main`). A phone pointed at a plain `hermes gateway` therefore gets
`HTTP 404 at /api/audio/voice-config`: the agent's `tts:` config is fine, the endpoint simply isn't
served.

This plugin adds them to the `api_server` through the supported plugin hook
(`ctx.register_platform_handler`), so it lives in your plugins directory instead of a patched core
file and keeps working after `hermes update`.

## Install (on the agent host)

```bash
mkdir -p ~/.hermes/plugins/hermes-audio-api
cp plugin.yaml __init__.py ~/.hermes/plugins/hermes-audio-api/
hermes plugins enable hermes-audio-api
hermes plugins doctor hermes-audio-api     # expect: runtime discovery, import, registration passed
hermes gateway restart                     # from a shell OUTSIDE the running gateway
```

`hermes gateway restart` is required — the routes are added when the api_server connects. Run it in
a normal terminal, not from inside an agent session (the gateway kills child processes on restart).

## Verify

Run it with the agent's own venv python (or any python that has `aiohttp`):

```bash
<hermes-install>/venv/bin/python verify.py                     # e.g. ~/hermes-agent/venv/bin/python
```

Expect 7 PASS lines — registration of both prefixes, auth, the voice-config answer, the `/v1` mirror,
a real synthesis, and the empty-text rejection.

## If the app still reports no voice endpoint

The app asks for `/api/audio/voice-config` and falls back to `/v1/audio/voice-config`; a 404 on both
means the request never reaches these handlers. Check the two layers separately:

```bash
# 1) on the agent host, straight at the listener (bypasses any proxy)
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer <API_SERVER_KEY>" \
  http://127.0.0.1:<port>/api/audio/voice-config

# 2) through the URL the app is configured with
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer <API_SERVER_KEY>" \
  https://<app-base-url>/api/audio/voice-config
```

- **404 in (1) too** — the plugin is not loaded: `hermes plugins list --plain --no-bundled | grep audio`
  must say `enabled`, `hermes plugins doctor hermes-audio-api` must pass, and the plugin has to sit in
  the plugins directory of the profile the *gateway* runs with (`~/.hermes/plugins/` for the default
  profile, `~/.hermes/profiles/<name>/plugins/` otherwise — check with `echo $HERMES_HOME` as the user
  that runs the gateway). The routes are added when the api_server connects, so the gateway restart has
  to happen *after* the enable.
- **200 in (1) but 404 in (2)** — a reverse proxy in front of the gateway. A proxy that only forwards
  `/v1/*` (common, because that is all a chat client needs) hides `/api/*`; that is what the
  `/v1/audio/*` mirror is for, so the app works unchanged once it is served. If the mirror still 404s,
  the proxy forwards a fixed set of paths — add `/v1/audio/` (or `/api/audio/`) to it.
- **401** — the key in the app does not match `API_SERVER_KEY`.

## What the app does with each answer

- `mode: "relay"` — the agent's provider can only run on its host (Edge TTS, local Whisper, command
  providers): the app posts text to `/api/audio/speak` and the agent speaks.
- `mode: "direct"` — the agent handed over an OpenAI-compatible speech endpoint plus a credential, so
  the app can pick the model itself (OpenRouter's speech catalogue is used when the endpoint is
  OpenRouter). The credential stays in memory for the session and is never written to disk.

Set `tts.provider` (and voice) in the agent's `config.yaml` — e.g. Edge TTS needs no key, or point
`tts.openai.base_url` at an OpenAI-compatible speech endpoint the agent already has a key for.

## Notes

- **No secrets or host values live here.** The plugin reads the agent's own `tts:`/`stt:` config at
  request time and authenticates with the existing `API_SERVER_KEY`, same as the chat endpoint.
- **Safe to install anywhere.** Before adding a route it checks what the app already serves, so a
  build that ships these routes (or a host patched by hand) is left alone instead of shadowed.
- **Named profiles:** the routes are registered on the default profile paths; a `/p/<profile>/…`
  client should keep pointing at that profile's chat URL instead.
- **Remove** with `hermes plugins disable hermes-audio-api` (or `hermes plugins remove
  hermes-audio-api`) plus a gateway restart.
- If `speak` answers 500, the agent's TTS chain is the problem, not the route: check `tts.provider`
  in `config.yaml` and the matching credential, then re-run `verify.py`.
