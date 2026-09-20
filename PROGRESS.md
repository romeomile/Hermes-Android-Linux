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
- [ ] Guest image built (`app/src/main/assets/vm/base.qcow2.gz`) and boot-tested on the host
- [ ] APK built and delivered to the user for on-device testing
- [ ] On-device numbers: VM boot time, agent readiness time, first chat turn, battery behaviour

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
