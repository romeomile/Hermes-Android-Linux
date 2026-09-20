# Hermes Android Linux

An Android app that runs **Hermes Agent inside a real Linux VM on the phone** — no root, no Termux,
no PC — and chats with it through the app's own interface.

QEMU boots an Alpine guest that already has Hermes installed, the agent's OpenAI-compatible API
server comes up on the device's loopback, and the app talks to it like any other gateway. Nothing
is left for the user to install: opening the app brings the engine up and the chat is ready.

```
   chat UI (this app)                     Linux VM (QEMU, guest kernel)
        │                                        │
        │  HTTP  http://127.0.0.1:8642           │
        ├──────────────► device loopback ◄── SLIRP hostfwd
        │                                        │
        │  control API  http://127.0.0.1:7080    │
        └──────────────► (engine state, shell, agent lifecycle)
                                                 └── Hermes Agent, policy-free userspace:
                                                     apk, pip, git, compilers, terminal
```

## Why a VM

A stock Android kernel gives a normal app no way to host a container or chroot: user namespaces are
compiled out and cgroup access is restricted. A VM brings its own kernel, so the guest is an ordinary
Linux system — packages install from upstream, and the agent's terminal tool is a real shell.

The cost is speed: without KVM the guest runs under QEMU's TCG emulation. Work that waits on the
network (model calls, downloads) feels normal; CPU-heavy work is slow.

## What is in the box

- **Chat client** — conversations, streaming replies, tool-activity cards, images, voice, sessions
  and settings against a Hermes `api_server` (the `/v1` surface).
- **Engine** — the VM itself: start/stop, CPU and memory allocation, autostart, live log, a shell
  inside the guest, and the gateway address and key to point another chat app at.
- **Guest image** — Alpine with Hermes preinstalled, baked at build time so first launch needs no
  long package install. Everything the user does in the guest survives restarts (persistent overlay
  disk), except when the base image itself is replaced.

## Requirements (device)

- Android 8.0+ (API 26+), **arm64** — only `arm64-v8a` QEMU binaries are shipped
- ~2 GB of RAM free while the VM runs, and storage for the guest disk (~1 GB + overlay)
- The engine runs in a foreground service, so it keeps running (with a visible notification) while
  the app is in the background

## Build

### 1. Guest image

Needs a Linux host with `e2fsprogs`, `qemu-utils`, `qemu-user-static` and `binfmt_misc` registered
for `qemu-aarch64`, so the guest's own `apk`/`pip` can run while the root filesystem is populated.
No ARM hardware and no cross-Docker required — an x86_64 host works.

```sh
sudo apt-get install -y qemu-user-static binfmt-support e2fsprogs qemu-utils
sudo update-binfmts --enable qemu-aarch64
./image/build_guest_image.sh
# -> app/src/main/assets/vm/base.qcow2.gz
```

The script downloads the Alpine aarch64 minirootfs, installs the guest packages and Hermes through a
chroot, writes the guest services, then packs a sparse ext4 image and converts it to a compressed
qcow2. Bump `ASSET_VERSION` in `VmManager.kt` whenever the image changes so installed apps re-extract
it.

### 2. APK

```sh
export JAVA_HOME=<jdk-17>
export ANDROID_HOME=<android-sdk>
./scripts/build_apk.sh
# -> app/build/outputs/apk/debug/app-debug.apk
```

The APK is large — the guest disk, QEMU and a Linux userspace travel inside it — so builds are
handed out as a release asset rather than through a store.

## How the pieces talk

| Path | Direction | Purpose |
|---|---|---|
| `127.0.0.1:8642` | app → guest | the agent's OpenAI-compatible API server (`/v1/...`) |
| `127.0.0.1:7080` | app → guest | control API: health, shell, agent start/stop, agent config |
| kernel cmdline | app → guest | the device-generated token, read by the guest at boot |

The token is created on the device on first launch, kept in the app's private preferences, and used
for both the control API and the agent's `API_SERVER_KEY`. No credential, address or path specific to
any machine is stored in this repository.

Guest-side sources (`image/guest/`) are what ends up installed in the image; the guest services are
ordinary OpenRC scripts, so the same layout works if the image is rebuilt on another distro.

## Licensing

The Android VM layer is derived from **Pockr** (AI2TH, MIT) — see `LICENSE` and `NOTICE.md`, which
also cover the bundled QEMU (GPLv2, executed as a separate process), the Alpine guest and Hermes
itself.
