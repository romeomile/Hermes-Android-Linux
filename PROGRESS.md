# Hermes Android Linux — handoff state

## What this app is

One app that both **chats with Hermes** and **hosts Hermes locally**: a QEMU-accelerated Linux VM
(Alpine) runs inside the app with Hermes preinstalled, and the app's own chat UI talks to the agent's
API server over device loopback. No root, no Termux, no PC, no server to rent.

- Repo: `romeomile/Hermes-Android-Linux` (private)
- Package: `com.romirmile.hermeslinux` (deliberately different from the other Hermes app, so both can
  be installed side by side), namespace `com.romirmile.hermes`, versionCode **1**, versionName **1.0.0**
- minSdk 26, target/compile 35, **arm64-v8a only** (the QEMU binaries are arm64)

## Layout

```
app/src/main/java/com/romirmile/hermes/
├── MainActivity.kt            launches the UI, starts the engine when autostart is on
├── data/ ui/ …                the chat client (copied from the standalone Hermes chat app)
├── vm/
│   ├── EngineStore.kt         engine prefs + the device-generated token, ports, provider list
│   ├── VmManager.kt           asset extraction, overlay creation, QEMU launch, serial log
│   ├── VmApiClient.kt         client for the guest control API (HttpURLConnection, no new deps)
│   ├── VmService.kt           foreground service that keeps the VM alive
│   └── EngineController.kt    ordered startup (VM → control API → agent), state + progress log
└── ui/EngineScreen.kt         the Engine page: status, gateway, resources, model setup, shell, log

image/
├── build_guest_image.sh       host-side guest image builder (x86_64 host, no ARM hardware)
└── guest/
    ├── api_server.py          control API inside the guest (health/exec/agent lifecycle/config)
    ├── init_bootstrap.sh      every-boot setup: token → agent config → start services
    ├── start_agent.sh         idempotent agent launcher
    └── hermes-bootstrap.initd OpenRC service for the above
```

## Design decisions

- **The Flutter UI of the VM-layer base was dropped**; the app is Kotlin/Compose only, and the chat
  client is the existing Hermes chat app's source. The VM layer's Kotlin (asset extraction, overlay,
  QEMU launch, foreground service) is reused from **Pockr** (MIT — see `NOTICE.md`).
- **Docker is not in the guest.** Hermes does not need it, and it cost minutes of first-boot setup
  plus a lot of image size. The guest is a plain Alpine root with `apk`, `pip`, `git`, `bash`,
  `curl`, `rg`.
- **Hermes is baked into the image**, so first launch does not run a long `pip install` under
  emulation.
- **One device-generated token** is passed by kernel cmdline and used for both the control API and
  `API_SERVER_KEY`; nothing host-specific lives in the repo.
- **Two forwarded ports**: 7080 (control API) and 8642 (agent API server).
- The guest image is built **without ARM emulation of the app**: `apk` cross-installs with
  `--arch aarch64`, and the achroot steps run the guest's own tools through `binfmt_misc`
  (`qemu-user`). Build it on any Linux host.

## State

- [x] Private repo, base imported, rebranded, chat client integrated
- [x] Engine layer: VmManager / VmApiClient / VmService / EngineController / EngineScreen
- [x] Guest side: control API, bootstrap, OpenRC service, image builder
- [x] Guest image built and **boot-tested on the host** (see evidence below)
- [x] APK built with the guest image inside
- [ ] On-device test by the user: VM boot time, agent readiness time, first chat turn, battery
- [ ] Release signing with a permanent keystore (the delivered APK is debug-signed)

## Host boot test — evidence (`image/test_guest_image.sh`)

Booting the shipped image under `qemu-system-aarch64` on a desktop Linux host:

- guest boots; OpenRC runs the bootstrap; the token arrives on the kernel command line
- control API answers `/health` **~30 s** after boot (`{"status":"ok","agent":"running"}`)
- guest listens on `0.0.0.0:7080` (control) and `0.0.0.0:8642` (agent)
- over the forwarded port the agent answers
  `GET /v1/models` → `HTTP 200 {"object":"list","data":[{"id":"hermes-agent",...}]}`
- guest toolchain: Alpine 3.19.1, Python 3.11.14, Hermes Agent v0.19.0, apk-tools 2.14.0, ripgrep 14.0.3

Two real defects were found only by that test, and both are fixed:

1. **The control API never listened** — the module defined the FastAPI app but never called
   `uvicorn.run`, so the process exited immediately. The bootstrap now also prints the failing
   component's log to the console instead of appearing to hang.
2. **The agent's API server bound loopback**, so nothing answered on the forwarded port. The
   adapter resolves its address from `platforms.api_server.extra.{host,port,key}` (falling back to
   `API_SERVER_*`), **not** from a top-level `api_server:` block — which is silently accepted but
   ignored. The guest bootstrap now writes both shapes plus the env vars.

Also fixed: `hermes` installs into `/usr/bin` (not `/usr/local/bin`), so the guest scripts resolve
the launcher instead of assuming a path.

## Release

- **Hermes Linux 1.0.0** — https://github.com/romeomile/Hermes-Android-Linux/releases/tag/v1.0.0
- Asset: `HermesLinux-1.0.0-debug.apk`, 163,761,884 bytes,
  sha256 `037d55e713c8e1cd0a6ab3e655c0af5b44de7bb41aa497bd1b05b85dc37c5ad8`
  (verified by downloading the published asset back and comparing hashes)
- `versionCode 1`, `versionName 1.0.0`, package `com.romirmile.hermeslinux`, label "Hermes Linux",
  `arm64-v8a`, debug-signed.
- Local copy handed over: `/root/hermes-android-linux-dist/HermesLinux-1.0.0-debug.apk`

## Known limits / follow-ups

- **No KVM on Android**, so the guest is TCG-emulated: expect slow CPU work, normal network work.
- Replacing the base image invalidates the writable overlay, so guest state (provider keys, installed
  packages) is recreated by the next boot. Mounting the agent's home from app storage would preserve
  it across image updates — worth doing if the image changes often.
- The startup path assumes `arm64-v8a`; other ABIs have no QEMU binaries.
- The app ships the audio-routes plugin (`agent-side/hermes-audio-api`) inside the guest image so the
  agent's own voice engine has `/api/audio/*` routes.

## Build

```sh
./image/build_guest_image.sh          # guest disk -> app/src/main/assets/vm/base.qcow2.gz
./scripts/build_apk.sh                # -> app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` (gitignored) needs `sdk.dir=<android-sdk>`; toolchain pinned to AGP 8.7.3,
Kotlin 2.0.21, compose-bom 2024.12.01, Gradle 8.9, JDK 17.


## Image v6 — the dashboard in the guest (Hermes v2026.9.14 / 0.21.3)

The agent's OpenAI-compatible `api_server` on `0.0.0.0:8642` is **gone**
(`image/guest/start_agent.sh` deleted). The app's UI surface is now the guest's own **Hermes
dashboard**, reached through a stdlib TCP relay:

- `127.0.0.1:9128` — `hermes dashboard --host 127.0.0.1 --port 9128 --no-open --skip-build`
  (`image/guest/start_dashboard.sh`, log `/var/log/hermes-dashboard.log`, pidfile
  `/var/run/hermes-dashboard.pid`). Loopback-only on purpose: a public bind redirects `/` to `/login`.
- `0.0.0.0:9129` — `image/guest/relay.py`, a stdlib byte pipe to `127.0.0.1:9128`; WebSocket
  upgrades pass through untouched. This is the port the app forwards
  (`EngineStore.DASHBOARD_PORT = 9129`, `VmManager` hostfwd).
- `0.0.0.0:7080` — unchanged control API (`image/guest/api_server.py`; its "agent" endpoints now
  describe the dashboard).
- The device token doubles as the dashboard's session token (`HERMES_DASHBOARD_SESSION_TOKEN`), so
  `/` carries `__HERMES_SESSION_TOKEN__="<device token>"` — the token the app already holds.

Built from upstream source rather than a PyPI wheel:

- `HERMES_TAG=v2026.9.14` (internal 0.21.3) shallow-cloned into `$WORK_DIR/hermes-src`;
- the SPA is built **on the host** (`web/`: `npm install && npm run build` -> `hermes_cli/web_dist`);
- runtime wheels resolved on the host for `musllinux_1_1_aarch64` + `musllinux_1_2_aarch64`, py3.11;
- in the guest the tag's own build backend is installed first (`setuptools==83.0.0`, `wheel`):
  Alpine 3.19's setuptools 70.3.0 cannot parse the 0.21.3 PEP 639 `license`/`license-files`
  metadata and pip dies with `metadata-generation-failed`;
- then `HERMES_NIX_BUILD=1 pip install --no-index --find-links=/wheels --no-build-isolation
  --no-compile /hermes-src` — `setup.py` refuses a wheel build outside Nix without that env var;
- the wheel ships no bundled assets, so `skills/`, `optional-skills/`, `optional-mcps/`, `locales/`
  and the built `web_dist` are copied into the installed package root
  (`/usr/lib/python3.11/site-packages`), where the runtime defaults look.

Verified by booting the packed image under host QEMU (`image/test_guest_image.sh`, exit 0):

- `Hermes Agent v0.21.3 (2026.9.14)`, Alpine 3.19.1, Python 3.11.14, launcher `/usr/bin/hermes`;
- bootstrap readiness: control-api 11s, dashboard 34s, relay 0s; `netstat`: `127.0.0.1:9128`,
  `0.0.0.0:9129`, `0.0.0.0:7080`;
- `GET /` through the relay -> `HTTP 200` with `__HERMES_SESSION_TOKEN__="<token>"` and
  `__HERMES_AUTH_REQUIRED__=false`; also `HTTP 200` with the app's `Host: 127.0.0.1:9129`;
- `/api/status` -> `HTTP 200`, valid JSON, `"version":"0.21.3"`;
- raw WebSocket handshake `GET /api/ws?token=...` -> `HTTP/1.1 101 Switching Protocols`;
- in the guest: `connect_ex 10.0.2.15:9128 -> 111` (refused, loopback-only),
  `connect_ex 127.0.0.1:9128 -> 0`, `connect_ex 10.0.2.15:9129 -> 0`.

Asset: `app/src/main/assets/vm/base.qcow2.gz` — 148,860,393 bytes (~142 MiB),
sha256 `b2b091b64df5a311c933fd970d71057c59d6ecf710f61ad6184e7b1b8e56e1b2`; the gunzipped image hashes
`2ced18a28c8a80a45a46839022512b75fcfd460fc437681bfef9823dc23ce451`, identical to the qcow2 the boot
test ran against. Build log: `/root/hermes-android-linux-build/bootstrap-image-6.log`.

Chat paths, measured on the booted image (both probed with a raw WebSocket client):

- **`/api/ws` — the JSON-RPC chat path the vendored mobile shell uses — works with no Node.** Upgrade
  answers `HTTP/1.1 101`, the server pushes `{"jsonrpc": "2.0", "method": "event", "params": {"type":
  "gateway.ready", ...}}` (skin payload), and `{"jsonrpc":"2.0","id":1,"method":"gateway.ping"}`
  comes back as `{"jsonrpc": "2.0", "result": {"ok": true}, "id": 1}`.
- **`/api/pty` — the SPA's own terminal pane — does not.** The upgrade is `101`, then the child
  prints `Chat unavailable: 1`, and `/var/log/hermes-dashboard.log` shows
  `Error: the TUI workspace is missing from this Hermes checkout. Expected directory:
  /usr/lib/python3.11/site-packages/ui-tui` (`command -v node npm` → `rc=127`).

Fixing the terminal pane needs Node plus a built `hermes_cli/tui_dist` bundle: Node is 45.1 MiB
installed (`nodejs-current` 21.7.2 in the Alpine 3.19 community index) and `ui-tui/` is 4.7 MiB of
source (its `node_modules` are build-time only — the image ships the bundle, not the tree). That
growth would push the 142 MiB artifact past its current ~150 MB ceiling, so it is a size decision,
not a mechanical fix. Everything else — SPA, `/api/status`, `/api/ws` — is verified working.
