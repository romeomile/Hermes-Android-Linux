# Hermes Android Linux — dashboard-UI port (plan + verified facts)

Goal: the app's UI becomes the **Hermes dashboard** rendered in a WebView, with the mobile
shell from `HermesMobile_merged_v0.5.0` on top (replacing the Compose chat UI inherited from
the other project). The Linux VM stays — it is the only way to run the dashboard backend
(SPA + `/api/*` + `/api/ws` + `/api/pty`) on a phone.

## What the mobile shell actually is

- `www/index.html` (~3 KB) is a **splash page only**; it loads `www/hermes-mobile-bridge.js`
  (~219 KB) and then the WebView is navigated into the dashboard SPA served by the backend,
  e.g. `/chat?fresh=1&channel=android-mobile-terminal&locale=…`.
- So most of the visible UI = the dashboard SPA of the *backend's own* Hermes version.
- Shell transport: `X-Hermes-Session-Token` header, `ws://127.0.0.1:9129/api/ws?token=…`,
  and the backend injects `window.__HERMES_SESSION_TOKEN__` into its own pages.
- The native bridge is `MainActivity.java` (`addJavascriptInterface(..., "HermesAndroid")`,
  26 `@JavascriptInterface` methods) — contract extracted in `docs/bridge-contract.md`.

## Verified facts about `hermes dashboard` (measured on this host, v0.21.1)

| Situation | Result |
|---|---|
| `--host 127.0.0.1 --port <p> --no-open --skip-build` | starts; logs `HERMES_DASHBOARD_READY port=<p>` |
| Loopback bind, `GET /` | **200**, SPA served, `__HERMES_SESSION_TOKEN__="…"`, `__HERMES_AUTH_REQUIRED__=false` |
| Non-loopback bind (`0.0.0.0`), `GET /` | **302 → `/login?next=%2F`** ("a public bind always requires an auth provider") |
| `Host: 127.0.0.1:<any-port>` | **accepted** (only the hostname is compared, not the port) |
| `Host: 10.0.2.15:9129` | **rejected**: `Invalid Host header. Dashboard requests must use the bound hostname…` |
| `GET /api/status` on a loopback bind | 200 JSON, no token required |

Consequence (this is the crux of the design):

- The dashboard **must stay on the guest's loopback** (otherwise the login gate appears), and
  SLIRP delivers host-forwarded traffic to the guest's `eth0` (10.0.2.15), never to loopback.
- Therefore an **in-guest TCP relay** bridges the two: dashboard on `127.0.0.1:9128`, relay on
  `0.0.0.0:9129` (pure byte pipe, so WebSocket upgrades pass through). The browser addresses
  `http://127.0.0.1:9129`, so its `Host` is `127.0.0.1:9129` — accepted, because the port is
  not part of the check.
- Device side: QEMU `hostfwd=tcp:127.0.0.1:9129-:9129`, so the app/WebView uses the same
  port as the mobile app it is modelled on.

## Guest image (v6)

- Alpine aarch64 rootfs, built on this x86_64 host via binfmt + chroot + `mke2fs -d`.
- Hermes Agent installed **from upstream tag `v2026.9.14`** (internal version 0.21.3 — the
  version the mobile shell targets) instead of the PyPI 0.19.0 wheel, with the **SPA built on
  the host** (node/npm) and baked into the installed package so `--skip-build` serves it.
- Services at boot: control API on `0.0.0.0:7080`, dashboard on `127.0.0.1:9128`, relay on
  `0.0.0.0:9129`. The old OpenAI-compatible api_server on 8642 is dropped (the dashboard
  supersedes it for the UI and the phone's RAM is better spent elsewhere).
- No host values, no credentials: `API_SERVER_KEY`/control token is generated on-device at
  boot; the model provider/key is entered by the user (in the dashboard's own config UI).

## App side

- `app/src/main/assets/www/` = the shell (English only — upstream shell strings are Chinese
  and must be translated, never shipped).
- Compose shell keeps: engine start/stop + per-step boot progress; the WebView takes over
  once the dashboard answers on `127.0.0.1:9129`.
- Force English in the WebView (`Accept-Language: en` and the `locale=en` query parameter) so
  a Chinese device locale cannot pull a Chinese UI.
- No host values in the repo or the APK: the app is a generic frontend.

## Open runtime checks (to run in the boot test)

1. `/` through the relay → 200 + token marker.
2. `/api/status` through the relay → 200.
3. Raw WebSocket handshake `/api/ws?token=…` through the relay → `101`.
4. Dashboard not reachable on the guest's `0.0.0.0:9128`, and the relay not bound anywhere
   but `0.0.0.0:9129`/`127.0.0.1:9128`.
