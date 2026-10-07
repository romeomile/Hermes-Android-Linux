# Hermes Android Linux — handoff state

## What this app is

One app that both **chats with Hermes** and **hosts Hermes locally**: a QEMU-accelerated Linux VM
(Alpine) runs inside the app with Hermes preinstalled, and the app's own chat UI talks to the agent's
API server over device loopback. No root, no Termux, no PC, no server to rent.

- Repo: `romeomile/Hermes-Android-Linux` (**public** since 2026-10-07)
- Package: `com.romirmile.hermeslinux` (deliberately different from the other Hermes app, so both can
  be installed side by side), namespace `com.romirmile.hermes`, versionCode **11**, versionName **1.0.9**
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

- **Hermes Linux 1.0.4** — https://github.com/romeomile/Hermes-Android-Linux/releases/tag/v1.0.4
- Asset: `HermesLinux-1.0.4-debug.apk`, 224,011,436 bytes,
  sha256 `95f9692f10cb900f8ee8a9affd743189a07a2823044cca6830c7aefd285e14aa` (verified by downloading the
  published asset back: same sha256 as the local build; `aapt2 dump badging` reads `versionCode='5'
  versionName='1.0.4'`; the guest disk inside it is image v11 — sha256
  `5c4b0072a25f502066f64987587565a7324cea3c46c296575e362fbb0be4f4dc`; the dex carries the image's
  sha256 literal and the runtime marker prefix, with no numeric counter left). `versionCode 5`,
  `versionName 1.0.4`.
- Fixes the reason 1.0.3 changed nothing on a device that had installed 1.0.2: the extraction marker
  was a hand-bumped constant (`assets_extracted.1` in 1.0.1, `.2` in 1.0.2 AND 1.0.3), while 1.0.3
  shipped a different image — so the app concluded its disk was current and kept the old guest. The
  marker is now derived from the image itself (`BuildConfig.GUEST_IMAGE_SHA256` computed by
  `app/build.gradle.kts`, surfaced as `com.romirmile.hermes.vm.GuestImage`), so a different image
  always re-extracts.
- **Never hand-maintain a guest-image marker again**: hash the asset at build time and derive the
  marker from it (the reference file records the failure mode and the fix).
- Local copies: `HermesLinux-1.0.4-debug.apk` and
  `RELEASE_NOTES-1.0.4.md`.
- **Hermes Linux 1.0.3** — https://github.com/romeomile/Hermes-Android-Linux/releases/tag/v1.0.3
- Asset: `HermesLinux-1.0.3-debug.apk`, 224,010,520 bytes,
  sha256 `7f175c83f74be1339cf775a00abdbfd43f74415dbe1b31803ebe8c0f66782c59` (verified by downloading
  the published asset back: same sha256 as the local build, `aapt2 dump badging` reads
  `versionCode='4' versionName='1.0.3'`, and the guest disk inside it is image v11 — sha256
  `5c4b0072a25f502066f64987587565a7324cea3c46c296575e362fbb0be4f4dc`). `versionCode 4`,
  `versionName 1.0.3`, package `com.romirmile.hermeslinux`, debug-signed.
- Carries: the dashboard's chat working inside the guest (Node + prebuilt TUI + ESM marker + the
  gateway budget + lazy installs off), the user-sized guest disk (20 GB default), the guest-image
  marker bump, and the raised device-side waits. See the section above for the receipts.
- Local copies: `HermesLinux-1.0.3-debug.apk` and
  `RELEASE_NOTES-1.0.3.md`.
- **Hermes Linux 1.0.2** — https://github.com/romeomile/Hermes-Android-Linux/releases/tag/v1.0.2
- Asset: `HermesLinux-1.0.2-debug.apk`, 202,885,388 bytes,
  sha256 `675e3c5d5cc7ef87516b970b84d301693a92e2fbbe3a1ab13adb9d00693694a8` (verified by downloading
  the published asset back: same sha256 as the local build, `aapt2 dump badging` reads
  `versionCode='3' versionName='1.0.2'`). `versionCode 3`, `versionName 1.0.2`, package
  `com.romirmile.hermeslinux`, debug-signed. Installs over 1.0.0/1.0.1 as an update.
- Fixes the reported "the dashboard does not start" after updating from 1.0.0 (guest image marker
  `1` -> `2`; see the section below) and adds the user-sized guest disk (20 GB default, >5 GB usable).
- Local copies: `HermesLinux-1.0.2-debug.apk` and
  `RELEASE_NOTES-1.0.2.md`.
- **Hermes Linux 1.0.1** — https://github.com/romeomile/Hermes-Android-Linux/releases/tag/v1.0.1
- Asset: `HermesLinux-1.0.1-debug.apk`, 202,111,488 bytes,
  sha256 `262d3590f03294964073ace7816e0b121c81cb9fb19911e388d54b3d4151307c`
  (verified by downloading the published asset back: same sha256 as the local build, and
  `aapt2 dump badging` reads `versionCode='2' versionName='1.0.1'`)
- `versionCode 2`, `versionName 1.0.1`, package `com.romirmile.hermeslinux`, `arm64-v8a`, debug-signed.
  The APK embeds the guest disk uncompressed as `assets/vm/base.qcow2` (150,864,896 bytes, sha256
  `2ced18a28c8a80a45a46839022512b75fcfd460fc437681bfef9823dc23ce451` — byte-identical to the image the
  boot test ran against).
- Local copies: `HermesLinux-1.0.1-debug.apk` and
  `RELEASE_NOTES-1.0.1.md`.
- **Hermes Linux 1.0.0** — https://github.com/romeomile/Hermes-Android-Linux/releases/tag/v1.0.0
- Asset: `HermesLinux-1.0.0-debug.apk`, 163,761,884 bytes,
  sha256 `037d55e713c8e1cd0a6ab3e655c0af5b44de7bb41aa497bd1b05b85dc37c5ad8`
  (verified by downloading the published asset back and comparing hashes)
- `versionCode 1`, `versionName 1.0.0`, package `com.romirmile.hermeslinux`, label "Hermes Linux",
  `arm64-v8a`, debug-signed.
- Local copy handed over: `HermesLinux-1.0.0-debug.apk`

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
test ran against. Build log: `bootstrap-image-6.log`.

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


## Image v7 + app fixes — user-chosen guest disk, and why an updated app had no dashboard

**User-chosen disk size (default 20 GB, user-selectable like RAM).** The writable overlay is now
sized by the app (`EngineStore.diskGb`, options 10/20/32/64, `qemu-img create ... <N>G`) and the
guest grows its ext4 root into it at boot. Two layers had to move together: raising only the
overlay leaves the filesystem fixed, so the extra space is unusable.

- `image/build_guest_image.sh`: guest package list gains `e2fsprogs e2fsprogs-extra` (resize2fs is
  in e2fsprogs-extra; verified in Alpine's v3.19 main/aarch64 index), and `DISK_SIZE` default is
  now 6G so the base filesystem alone already offers more than 5 GB.
- `image/guest/init_bootstrap.sh`: `grow_rootfs()` runs before the services — `resize2fs /dev/vda`
  (online grow), then prints `[ready] root filesystem <MB>, <MB free>` and warns below 5 GB.
  Guarded and never fatal.
- App: `EngineStore.diskGb` + a "Disk" dropdown in the engine screen; `VmManager.growUserImage()`
  resizes in place via `qemu-img resize` when the setting grew, and deliberately never shrinks
  (that would mean rebuilding the overlay and discarding the guest).
- Evidence, host QEMU boot of image v7 (work: disk_growth_test.py,
  log `disk-growth-test.log`): 10 GB overlay -> `[ready] root filesystem 9989 MB, 9575 MB free`;
  `qemu-img resize` to 20 GB -> `virtual size: 20 GiB` with only **24 MiB** actually used (sparse);
  rebooting that same overlay -> `df -m /` = `20062 MB total, 19644 MB free`; a canary file written
  before the grow still hashes identically afterwards; the dashboard still answers HTTP 200 with the
  session-token marker. `RESULT: ALL CHECKS PASSED`.

**"The dashboard doesn't start" after updating from 1.0.0 — root cause and fix.** The app re-extracts
the guest disk only when `filesDir/assets_extracted.$ASSET_VERSION` is missing, and `ASSET_VERSION`
stayed `"1"` while the image changed from the 0.19.0/api_server design to the 0.21.3/dashboard+relay
design. An updated app therefore kept booting the **old** guest disk, which has no relay on 9129,
while the new UI expects the dashboard there.

- Reproduced on the host with the 1.0.0 APK's guest disk
  (`old_guest_repro.py`, log `oldguest-repro.log`): the guest reports
  `[ready] control-api`, `[ready] agent`, `Hermes Agent v0.19.0 (2026.7.20)` and `/bootstrap` still
  holds `start_agent.sh`, while `GET /?locale=en` and `/api/status` through the relay both fail with
  `ConnectionResetError(104, 'Connection reset by peer')`. The engine screen looks alive; there is no
  interface. Exactly the reported symptom.
- Fix: `ASSET_VERSION` `"1"` -> `"2"`, so an installed app takes the image it ships with. The old
  guest disk is no longer deleted — it is set aside as `vm/user.qcow2.previous` (only the newest is
  kept). It is not bootable by itself, because its backing file is the base image that gets replaced;
  recovering it means pairing it with the base image of its own release (unzip `assets/vm/base.qcow2`
  from that release's APK and rename the file back to `user.qcow2`).
- Also fixed while in there: the device-side budgets were tuned for a build host (control API 180s,
  agent 300s, dashboard 300s). A phone runs the guest several times slower — on this host the guest
  reports bootstrap complete after 216s — so the app could report failure while the guest was still
  coming up. Now 600s / 600s / 900s.

**Artefacts of this pass** (not committed and not published):
guest image `app/src/main/assets/vm/base.qcow2.gz` 143 MB, sha256
`650c9140ab451cc7f2312a7541d7ec7a4c878057932e5ac4b53b7627d35ef97b`, guest
`Hermes Agent v0.21.3 (2026.9.14)`, base filesystem 6 GiB with resize2fs present; APK
`app/build/outputs/apk/debug/app-debug.apk` 202,885,388 bytes, sha256
`f24ed8f7c90ab220f67502772612d6587f3827a08be1dda19830601ef9618ce1`, `versionCode 2`
`versionName 1.0.1` (unchanged, per the standing rule), carrying the v7 image (its
`assets/vm/base.qcow2` unpacked sha256 matches the asset) and the fixes above verified in the dex
(`assets_extracted.2`) and resources (the Disk strings).


## The dashboard's chat inside the guest — three faults, and what "trying to install" really was

Romeo's report ("once trying to install when the dashboard loads") was the dashboard's Chat tab
spawning `hermes --tui` in a PTY (`web/src/pages/ChatPage.tsx` -> `WS /api/pty` ->
`hermes_cli/web_server.pty_ws` -> `ui-tui`). Three separate faults sat in that one path:

1. **No Node and no prebuilt TUI in the image.** `main_tui_launch._make_tui_argv` returns the
   bundled `hermes_cli/tui_dist/entry.js` when it exists; without it the launcher prints
   `Installing TUI dependencies…` and runs `npm install --workspace ui-tui` inside the guest. That
   install attempt is what the UI showed. Fixed by adding `nodejs` to the guest packages and baking
   the bundle built on the host (`ui-tui`: `npm install && npm run build:ink && npm run build`).
2. **The bundle is ESM and lost its module marker.** Baked alone, the nearest `package.json` was
   gone, so Node loaded `entry.js` as CommonJS and died: `SyntaxError: Cannot use import statement
   outside a module`. Fixed by also writing `hermes_cli/tui_dist/package.json` = `{"type": "module"}`
   (upstream relies on `ui-tui/package.json`, which is not in the image). The build now runs a smoke
   check in the guest (`node entry.js`, fail on module errors) so a bundle that cannot load never
   reaches the image. Note: a check that only asserts "not a refusal and not an install attempt"
   passes on this error — assert the failure markers explicitly.
3. **The gateway budget was sized for a native machine.** The TUI starts its own Python gateway
   (`spawn(python, ['-m', 'tui_gateway.entry'])`, `ui-tui/src/gatewayClient.ts`) and waits
   `HERMES_TUI_STARTUP_TIMEOUT_MS` (default 15s) for it. Under emulation that is not enough, and the
   tab sat on `gateway startup timeout`. `image/guest/start_dashboard.sh` now exports
   `HERMES_TUI_STARTUP_TIMEOUT_MS` / `HERMES_TUI_RPC_TIMEOUT_MS` = 600000 for the spawned TUI.
   Related: with default config the guest also tried a **lazy `pip install` of faster-whisper** at
   startup and burned its full 120s timeout (the host agent's `errors.log`: "pip not available and
   ensurepip failed ... timed out after 120 seconds"), holding the chat on "summoning hermes".
   `init_bootstrap.sh` now writes `security.allow_lazy_installs: false` into the guest's config
   (merge-only, never fatal), so nothing installs at runtime — everything ships from the build.

**The remaining "Setup Required" panel is not an install.** `ui-tui/src/content/setup.ts` renders it
when no model provider is configured: "Hermes needs a model provider before the TUI can start a
session" with `/model` and `/setup`. That configuration belongs to the VM and the app already owns
the path: the engine screen's agent-model action posts to the guest's control API
(`POST /agent/config`), which writes `.env` (mode 600) and `hermes config set model.provider
model.default`. Verified end to end on a booted image with placeholder values:

- `POST /agent/config` -> `{"ok": true, "env": ["DEEPSEEK_API_KEY"], "settings": ["model.provider", "model.default"], "restartRequired": true}`
- guest `config.yaml` then holds `model: {provider: deepseek, default: deepseek-chat}` plus
  `security: {allow_lazy_installs: false}`; `.env` holds the key at mode 600.
- after the app's `/agent/stop` + `/agent/start`, the Chat tab reports
  `─ starting agent… │ deepseek chat │ 1s ─/` and `❯ Try "fix the linter errors"` — the Setup
  Required panel is gone and the VM's own Hermes drives the session.

Harnesses: `{tui_pty_test.py, tui_render_probe.py,
tui_ready_probe.py, tui_configured_probe.py}` (logs alongside them). Tools used here:
`tui-configured-probe.log` is the end-to-end receipt for the app->VM configuration path.

**Image v11**: 163 MB gz (node + the TUI bundle are the growth from 143 MB), sha256
`3217c97586c755000c76f24142b0b8bfbb861954b5714530313c3780aff0a856`. APK rebuilt locally with it:
224,010,520 bytes, `versionCode 3` / `versionName 1.0.2` (unchanged), not committed and not published.

## On-device speech: Chatterbox, in the app's own process (not in the guest)

**Why the guest cannot host it.** The guest is Alpine/aarch64 (musl): `pip download torch
--platform musllinux_1_2_aarch64` has **no wheel at all** (the glibc aarch64 wheel is 454 MB), and
`numba` is missing the same way — so the stack chatterbox-tts pins (`torch==2.6.0`, `torchaudio`,
`transformers==5.2.0`, `librosa`, `gradio`) cannot install. Beyond that the checkpoints are 2.2 GB
minimum (`t3_cfg` 1.06 GB + `s3gen` 1.06 GB) against a 6 GB guest disk, and the VM is fully emulated
(`VmManager` launches `-machine virt -cpu cortex-a53` with no KVM — Android gives apps no
`/dev/kvm`), so TTS there would be minutes per sentence. The engine therefore runs in the app's
own process, on the phone's CPU.

**Engine.** `libchatterbox.so` — the C++/ggml port of Chatterbox (MIT,
github.com/gianni-cor/chatterbox.cpp @ `ddca05fb69c2910b0d7b5eae420d360ed98c067b`, ggml pinned to
`58c38058` by that port's `scripts/setup-ggml.sh`), cross-compiled for arm64-v8a with NDK 28.
`tts-cpp` + `ggml` are linked **statically into one 21 MB shared object** whose only `NEEDED` entries
are `libc/libm/libdl` (OpenMP off — no runtime in the NDK). JNI bridge: `chatterbox/jni/`.
Rebuild: `chatterbox/build_libchatterbox_android.sh`; weights: `chatterbox/build_model_pack.sh`.

**Weights** — converted on this host from the MIT `ResembleAI/chatterbox-turbo` checkpoint (Turbo =
English, built-in reference voice), shipped in the APK's assets and stored uncompressed:

| file | bytes |
|---|---|
| `cbx-t3-turbo-q8.gguf` (T3, GPT-2-medium backbone) | 487,861,440 |
| `cbx-s3gen-turbo-q8.gguf` (S3Gen + HiFT vocoder) | 829,657,344 |

Variants converted for comparison: T3 `q5_0` 372 MB / `q8_0` 488 MB; S3Gen `q4_0` 790 MB / `q8_0`
830 MB / `f16` 1065 MB. S3Gen barely shrinks at any setting: its HiFT conv kernels (K in 3/7/11/16)
cannot take a 32-block quant and stay f32 by the port's own deny-list — not a conversion bug.

**Measured here** (6 vCPU Xeon E5-2680 v2 VM, `--threads 6`, one 21-BPE sentence → 5.9-6.0 s of
audio): T3 9.9-20.1 s + S3Gen 15.0-21.3 s → **RTF 2.6-6.2 wall-clock**, and the spread is the shared
host rather than the models (run-to-run variance ±50 %). Inside S3Gen: encoder ~1.7 s, 2-step CFM
~6.8 s, HiFT ~6.4 s; T3 load ~1.9-3.7 s. A phone with eight modern cores should be faster; only the
device can say by how much.

**App wiring.**
- `data/ChatterboxVoice.kt` — JNI surface `ChatterboxNative`, deliberately a **top-level** object: a
  nested one compiles to `ChatterboxVoice$Native` (`_00024Native` in the JNI symbol) and the bridge
  would not resolve. Also holds the engine (lazy load, reused across replies, `cancel()`,
  `release()`) and the one-time asset→storage pack copy with per-cent progress.
- `SpeechEngine.CHATTERBOX`, and a voice path that chunks a reply at 220 chars, synthesizes a 24 kHz
  WAV into the cache dir and plays it with the screen's existing `MediaPlayer`; any failure (pack
  missing, engine error) falls back to the phone voice and says so once.
- Settings: the engine entry, pack status, "Install model pack" with a progress bar.
- `jniLibs/arm64-v8a/libchatterbox.so` is committed; the 1.3 GB pack is gitignored (release-asset
  material, like the guest image).

**APK**: `versionCode 7` / `versionName 1.0.6`, **1,551,071,740 bytes** with the pack inside
(`noCompress += "gguf"`, so the copy out of assets is a straight byte copy), sha256
`04b4ee14e02e0fac7320d83f12d1eccb1486f6ce31a02a2e55eae97cc6ac3371`, staged at
`HermesLinux-1.0.6-debug.apk` — not committed, not published.

Audited inside the built APK: both GGUF assets (exact names/sizes `ChatterboxVoice` expects),
`libchatterbox.so` 1,760,024 B after `llvm-strip --strip-unneeded` (the JNI entry points live in
`.dynsym`, so all four survive) with `NEEDED` = libm/libdl/libc only, `com/romirmile/hermes/data/ChatterboxNative`
in the dex (top-level, no `$Native`), `versionCode 7`, and **zero** host paths/credentials in the
library — the first build carried 190 build-directory strings from `__FILE__`, which is why
`chatterbox/CMakeLists.txt` now passes `-ffile-prefix-map` for both the checkout and the build tree
and the build script fails if any build path survives.

Delivery note: at 1.5 GB the APK cannot travel over Telegram (50 MB document cap), and Romeo's phone
was off the tailnet when this was built, so the route is a release asset in `romeomile/Hermes-Android-Linux`
(as every previous version was) — the tag/release for 1.0.6 was NOT created without his word.

**Still to prove, on the device**: the pack install, first-reply latency, real per-sentence RTF, and
the voice quality itself. Follow-ups worth their own round: Adreno OpenCL offload
(`-DGGML_OPENCL=ON` plus the port's OpenCL patch — Mali is unsupported), the multilingual variant
(23 languages, **no Russian**), and cloning a voice from a reference wav
(`EngineOptions.reference_audio`).

## 1.0.7: what the chat drop was, and the on-device voice self-test

Romeo's report: chat loses the connection "after a few seconds of thinking", the VM and agent look
fine, and later the same chat works; the on-device voice produced no sound even with the pack
installed.

**Measured on a booted copy of the shipped image** (same QEMU forwards, the app's own request —
SSE `POST /v1/chat/completions`, model pointed at a fake slow provider so no credentials are
involved): `HTTP 200` → role frame immediately → `: keepalive` every ~30 s → **first token at
135 s** → 30 deltas → `stop` frame → clean close at 196 s. Nothing drops on a healthy guest, and a
cold turn under emulation really does spend about two minutes before its first token. The app's
read timeout was 75 s while its comment claimed 10 s keepalives, so a cold turn could be abandoned
mid-preparation and reported as "the network changed" — both are now fixed (`readTimeout` 120 s,
honest wording); the retry with the same idempotency key is unchanged, so a retried turn is not run
twice.

**A real guest-side failure mode found while reproducing** (not proven to be Romeo's trigger): the
gateway's startup guard refuses a missing/placeholder/<16-char `API_SERVER_KEY`, logs
"Refusing to start" and the **gateway exits** — after which 8642 never serves. Because QEMU accepts
the TCP connection on the phone side and only then hands it to the guest, the app sees a reset
("Connection lost") rather than "can't reach the gateway", then "Stopped." after the retries. The
app's own token is a 36-char UUID, which passes that guard, so this needs the device's log to
confirm or dismiss.

**On-device voice.** `ChatterboxVoice` gained a self-test (Settings → Voice engine → Chatterbox →
"Test voice"): it synthesizes one fixed phrase, reports `Engine produced %.1f s of audio in %.1f s`
or the exact exception, and plays the clip — the only way to separate an engine failure from a
reply that never arrived. The T3 context is capped at 2048 (`ChatterboxVoice.T3_CONTEXT`) because
the GGUF's 8196 costs ~1.5 GB of KV cache on a phone that is also running the guest.

Diagnostic build: `versionCode 8` / `versionName 1.0.7`, **218 MB, no model pack inside** (it reuses
the pack already installed on the device, so an update keeps app data) — published as the
pre-release `v1.0.7-test`, sha256 `1f10adccc09f4dcb29bbbab610021dd2f026739f7298e28ad45f4f3673e86fe5`.
The full 1.5 GB build (pack included) stays 1.0.6 until the device test passes.

## The first model pack was silently broken (fixed in 1.0.8)

Romeo's device test read `Engine produced 3.4 s of audio in 41 s` — and played nothing. The engine
was working; the **vocoder GGUF** was not. Measured on the host with the same binary and text:

| pack | rms | peak | audible |
|---|---|---|---|
| T3 q8_0 + S3Gen **q8_0** (what 1.0.6/1.0.7 shipped) | 0.0000 | 0.000 | no — 2 non-zero samples out of 63,360 |
| T3 q8_0 + S3Gen **q4_0** | 0.0000 | 0.000 | no |
| T3 q8_0 + S3Gen **f16** | 0.0365 | 0.384 | yes |
| T3 q5_0 + S3Gen f16 | 0.0448 | 0.391 | yes |

So the converter's block quantization of S3Gen destroys the output while every other signal stays
healthy: T3 emits its 63 speech tokens, S3Gen "infers" 2.6 s of audio, the wav has the right
length and sample rate. Nothing upstream can tell by inspection. T3's own quantization is fine.

Fix: `S3GEN_FILE = cbx-s3gen-turbo-f16.gguf` (1.07 GB instead of 830 MB), `PACK_BYTES_APPROX`
1.55 GB, and `build_model_pack.sh` now defaults `S3GEN_QUANT=f16` with the reason written down.

Guards added so this class of bug cannot pass silently again:
- `ChatterboxVoice.peakAmplitude(wav)` / `isAudible(wav)` — parse the returned WAV and measure it.
- The voice test reports `Engine produced X s of audio, but it is silent (peak 0.000)` instead of
  success, and the voice screen falls back to the phone voice instead of playing nothing.
- `installModelPack` deletes any file in the pack directory this build does not ship, so the broken
  830 MB vocoder cannot linger on a device that updates.

Lesson for the next audio feature: **a returned buffer is not sound — measure amplitude before
claiming a voice works.** The clip sent to Romeo before this was found was silent; nobody checked.

Release: `1.0.8` (`versionCode 10`), full pack inside (~1.8 GB), latest. The broken `1.0.7` release
was deleted once 1.0.8 was verified.

## 1.0.9 — the shipped image no longer names the build host, and persistence is a tested contract

The repository went public on 2026-10-07, which is when the artifact audit that found this started:
**every published image from 1.0.1 on carried the build host's tree inside it** — 5,081 distinct
paths (`…/rootfs/usr/lib/python3.11/site-packages/…`), all of them `co_filename` strings in the
Python bytecode of the guest's own site-packages. Cause: `image/build_guest_image.sh` byte-compiled
the guest's Python **on the host**, so each `.pyc` recorded the host path it was compiled from. Fix:
compile inside the chroot (the recorded path is then the guest's own `/usr/lib/python3.11/…`), and
**fail the build** if any build-host path survives the tree — the same guard style the native library
build already used. Verified after the rebuild: 5,081 → **0** occurrences in the packed image, with
the build's own guard passing.

Also verified while auditing: no credential from the build machine appears in the tree, history, the
APK or the guest disk (all values from the machine's own env files searched as literals, plus
key-shaped patterns). The only machine values in the guest disk are upstream defaults the local
`.env` happens to hold (`api.deepseek.com`, the OpenViking endpoint, the Modal terminal image) plus
`Europe/Moscow` from Alpine's tzdata.

**Persistence is now a documented, tested contract** instead of an implied one. `VmManager` reuses
the overlay and only ever grows it, so a normal stop/start and an Android force-stop keep everything;
an update that ships a new base image starts a **fresh** overlay and sets the old one aside as
`vm/user.qcow2.previous` (newest only) — the guest the user sees comes back empty, and the previous
disk is not bootable without the base image of its own release. The README's "your data on the
overlay is kept" was wrong and is gone.

New: `image/test_guest_persistence.sh` (host-side, no phone) proves the contract — canaries written
in the guest, then five phases: fresh overlay, normal stop/start (canaries present), SIGKILL
force-stop (present), image update (absent, previous disk kept), restore (present again). It mirrors
`VmManager.buildQemuCommand()`/`createUserImage()` verbatim and guards against the trap that made a
first attempt invalid: a stale guest from an earlier experiment held the port, answered `/health` and
silently ate the canaries. The port must be free, and every boot must prove it is the guest just
started (uptime < 10 min). Result on the rebuilt image: **ALL CHECKS PASSED**.

- `versionCode 11`, `versionName 1.0.9`; the shipped APK's guest image is the verified one.
- The image marker is the packed `.gz` hash, so installing this build re-extracts and **starts a
  fresh guest** (the previous disk is kept as `vm/user.qcow2.previous`) — expected, not a bug.
- Also in this build: the control API's `/agent/status` no longer blocks its event loop while
  `hermes --version` runs (it cost 52 s and froze every other request); the version is resolved once
  in a worker thread and cached.

## 1.0.10 — the device-side ports stopped colliding with the other on-device app

Reported on the phone right after installing 1.0.9: the engine screen read **VM stopped**, and the
error was **"the guest control API rejected the request"** while the agent row still said *Starting*.
The shape of that error is the diagnosis: in the guest, `/health` needs no auth and every other route
does, so a control API that passes the health check and refuses `/agent/status` is **not the guest
this app started** — and the app's own QEMU is gone (`isRunning()` reads the process).

Cause: this app forwarded the guest's own numbers as the *device-side* ports (7080 control, 8642
gateway). The older on-device app's guest binds 7080/8642 too, two QEMU forwards cannot share a port
on the phone, so the second guest fails to bind its forwards and exits — and every request from this
app then lands on the *other* installation's guest, whose token it does not accept. The relay front
end hit exactly this and fixed it in v0.1.8 by separating the two sets of numbers; this app now does
the same:

- `EngineStore.GUEST_CONTROL_PORT` / `GUEST_AGENT_PORT` = 7080 / 8642 — the guest's own contract,
  what the guest's services and any in-guest command use, never changed by the app;
- `EngineStore.CONTROL_PORT` / `AGENT_PORT` = **17080** / **18642** — the device-side forwards, and
  the only numbers the app itself addresses (`VmApiClient`, `localEndpoint()`);
- `VmManager.buildQemuCommand()` maps device → guest (`hostfwd=tcp::17080-:7080,18642-:8642`).

Two more things the same screen exposed, both fixed in the app layer:

- **A dead VM is reported at once.** While waiting for the control API the controller now checks the
  QEMU process and fails with "the VM process exited while the guest was starting — last VM line: …"
  instead of polling out the full 600 s and calling it a timeout (the last serial line is kept for
  the message).
- **No more "Stopped" next to "Starting…".** After a failure the state is rebuilt from the process
  that is actually alive, so a component that stopped says so.

The guest image is unchanged, so the extraction marker is unchanged: this update does **not** reset
the guest, unlike 1.0.9. `versionCode 12`, `versionName 1.0.10`.

## 1.0.11 — readiness is the port, not the guest's opinion

Reported after 1.0.10: the chat opened, the first message was answered with **"Connection lost —
reconnecting…" immediately**, and the engine screen looked healthy. With the port split in place the
turn now reaches the forwarded agent port — and that is exactly where the fault showed up.

Reproduced on the host with the shipped image and the app's own forwards: the control API answered
`/agent/status` with `{"running": true, "pid": 1494, "version": "Hermes Agent v0.21.3"}`, while
`wget 127.0.0.1:8642` **inside the guest** was refused and every connection to the forwarded port was
reset instantly. The status was a claim, not evidence — this repo's `agent_running()` was
`os.kill(pid, 0)` on the pidfile first, and only probed the port if the pid was gone:

```python
def agent_running() -> bool:
    pid = agent_pid()
    if pid:
        os.kill(pid, 0); return True      # "the pid exists" == "the agent is running"
```

Guest pids are reused within minutes, so a dead agent's pidfile keeps reporting a live agent. That
also made `/agent/start` a silent no-op (`start_agent.sh` exits early on a live pid), so the app sat
on a claim that could never become true while the port stayed dark — the relay front end documented
the same three failure modes (`FORK-NOTES.md`, v0.1.9) and solved them the same way.

The app now owns readiness, in the order the relay uses:

1. wait on the control API (it answers as soon as the guest itself is up, and needs no auth);
2. **read the key the guest really holds** (`grep '^API_SERVER_KEY=' /root/.hermes/.env`, the literal
   value, since a printed one is not evidence) and rewrite `.env` + `config.yaml` through
   `/agent/config` only when it is missing, short (the gateway exits on a key under 16 characters) or
   not the device's token;
3. **clear `/var/run/hermes-agent.pid` only when the agent's port is closed**, then start the agent —
   never trusting its "started" answer;
4. **wait for the forwarded agent port itself** (`GET http://127.0.0.1:18642/v1/models`, any HTTP
   status counts as "listening") for up to twenty minutes, reporting the elapsed time every minute;
5. on failure, quote the guest's own agent log in the error instead of a bare timeout.

Guest-side sources carry the same correction (`agent_running()` probes the port first, and
`/agent/start` clears a stale pidfile), so a future image is honest on its own; the shipped image is
unchanged, which keeps this update from resetting anyone's guest. `versionCode 13`, `versionName
1.0.11`.

### The streaming read timeout was the other half of the same symptom

`HermesClient.streamChat()` set a **120 s read timeout** on what is one long-lived streaming response.
Under emulation a cold turn legitimately takes longer than that before its first byte, so the client
abandoned a live request and reported it as a dropped connection — the same user-visible error as the
dark port above, from a completely different cause. A finite read timeout is simply the wrong
semantics here: `readTimeout = 0` leaves the stream open indefinitely, `connectTimeout = 20_000` still
bounds connection establishment, and `stop()` (`active?.disconnect()`) remains the only way a turn
ends early. `failureKind()` keeps `SocketTimeoutException` → `TIMEOUT` and a reset/abort →
`CONNECTION_LOST`, so the two are still reported differently.

The reasoning carried over from the 1.0.7 measurement, where keepalives every ~30 s made 120 s look
generous: that measurement was of a *healthy* guest. A guest whose gateway is still starting emits
nothing at all, which is exactly when the client must wait rather than give up.
