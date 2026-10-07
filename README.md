# Hermes Android Linux

**Hermes Agent running in a real Linux VM on your phone** — no root, no Termux, no PC, no cloud.
QEMU boots an Alpine guest that already has the agent installed, the agent's API server comes up on
the device's loopback, and the app talks to it like any other gateway. Open the app and you are in a
conversation.

[![Release](https://img.shields.io/github/v/release/romeomile/Hermes-Android-Linux?label=release)](https://github.com/romeomile/Hermes-Android-Linux/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208%2B%20arm64-green.svg)](#requirements)

<!-- Screenshots go here: chat with a reply, engine screen, voice, guest shell. -->

## What you get

- **Chat with the agent** — streaming replies, tool-activity cards, images, voice, several sessions
  and settings, against the agent's own `/v1` API.
- **The VM is part of the app** — start/stop, CPU and memory allocation, autostart, a live log, and a
  shell *inside* the guest, so you can watch and fix things instead of guessing.
- **A real Linux userspace** — `apk`, `pip`, `git`, `node`, compilers, `ripgrep`, `nano` are all in
  there. The agent's terminal tool is an actual shell with a package manager, not a simulation.
- **It persists** — packages you install, files you edit and services you start survive restarts.
  Only replacing the base image resets the system part.
- **Speech on the device** — an optional on-device text-to-speech engine; no audio leaves the phone.

## Requirements

- **Android 8.0+ (API 26+)**, **arm64** — only `arm64-v8a` QEMU binaries are shipped, so 32-bit
  devices and x86 emulators cannot run the guest at all.
- **~2 GB of free RAM** while the VM runs, plus storage for the guest disk: a ~170 MB base image and
  a writable overlay you choose the size of (20 GB by default).
- The engine runs in a foreground service, so it keeps running with a visible notification when you
  leave the app.

## Install

1. Download the APK from [Releases](https://github.com/romeomile/Hermes-Android-Linux/releases) —
   arm64, and large: **~1.8 GB with the speech model pack**, ~220 MB without it.
2. Allow installing apps from your file manager or browser, then install it. There is no Play Store
   build: the app is far too large and too unusual for it.
3. Open it. The engine starts, extracts the guest disk, and the chat comes up when the agent is
   listening. The engine screen shows each step and keeps the log if something does not.

Then configure a model provider in the app. **No provider keys are bundled and no provider is
preset** — you bring your own endpoint and key, exactly as with any other chat client.

## How it works

```
   chat UI (this app)                     Linux VM (QEMU, guest kernel)
        │                                        │
        │  HTTP  http://127.0.0.1:18642          │
        ├──────────────► device loopback ◄── SLIRP hostfwd
        │                                        │
        │  control API  http://127.0.0.1:17080   │
        └──────────────► (engine state, shell, agent lifecycle)
                                                 └── Hermes Agent, policy-free userspace:
                                                     apk, pip, git, compilers, terminal
```

| Path (on the device) | Direction | Guest port | Purpose |
|---|---|---|---|
| `127.0.0.1:17080` | app → guest | 7080 | control API: health, shell, agent start/stop, agent config |
| `127.0.0.1:18642` | app → guest | 8642 | the agent's OpenAI-compatible API server (`/v1/...`) |
| kernel cmdline | app → guest | — | the device-generated token, read by the guest at boot |

The guest's own services listen on **7080** and **8642 inside the guest**; the app forwards them onto
**17080** and **18642** on the device, and those are the numbers anything on the phone must use. The
device-side numbers are deliberately not the guest's: the other on-device Hermes app's guest binds
7080/8642 as well, two QEMU forwards cannot share a port, and the loser fails to bind its forwards and
exits while the app goes on talking to the *other* installation's guest — which rejects its token.

The token is created on the device at first launch, kept in app-private storage, and used both for
the control API and as the agent's `API_SERVER_KEY`. It is never baked into the image. No credential,
address or path specific to any machine exists in this repository — and the guest image build fails
rather than ship one.

## Why a VM and not a chroot

A stock Android kernel gives a normal app no way to host a container: user namespaces are compiled
out and cgroup access is restricted. A VM brings its own kernel, so the guest is an ordinary Linux
system — packages install from upstream and services behave normally, with no root anywhere in the
picture.

## What to expect from the speed

There is no KVM on Android, so the guest runs under QEMU's **TCG emulation**. In practice:

- Work that waits on the network — model calls, downloads, `pip install` — feels normal.
- CPU-heavy work is slow. A cold agent turn can take minutes before the first token, and synthesising
  a few seconds of speech takes tens of seconds on a mid-range phone.
- The engine screen lets you give the VM more cores and RAM, which is the main lever you have.

If you want instant, this is not the tool. If you want a full Linux + agent environment living on
the phone with no root, this is what that costs.

## What survives what

The guest's writable disk (`user.qcow2`) is a file in the app's private storage, kept apart from the
base image. That splits persistence into a few clear cases:

| Action | What the guest sees |
|---|---|
| Stopping the engine in the app, then starting it | Everything is there: packages, files, agent config |
| Android force-stopping the app, or the process being killed | Everything is there — the overlay is a file, and the guest's ext4 journal replays after an unclean stop |
| Changing CPU, RAM or disk size | Everything is there — the disk is only ever grown, never shrunk |
| Updating the app with a **new base image** | An **empty guest**: a fresh overlay is created, so provider keys, installed packages and files written in the guest have to be set up again |
| Uninstalling the app, or clearing its data | Everything is lost |

Why the fourth row: the app identifies the extracted disk by the base image's SHA-256
(`assets_extracted.<hash>` in its storage). When a release ships a new image that marker does not
exist yet, so the app extracts the new base and starts a fresh writable disk. It does **not** delete
the old one — it renames it to `vm/user.qcow2.previous`. Only the newest is kept, so a second image
update deletes the earlier one.

### Recovering the previous guest disk

`user.qcow2.previous` is not bootable on its own: its backing file is the base image that was just
replaced, so it must be paired with the base image **of its own release**.

```sh
# the app is debug-signed, so its private storage is reachable from adb
adb shell run-as com.romirmile.hermeslinux ls files/vm

# base.qcow2 of the release that disk came from: unzip assets/vm/base.qcow2 from that APK
adb push base.qcow2 /data/local/tmp/base-old.qcow2
adb shell run-as com.romirmile.hermeslinux cp /data/local/tmp/base-old.qcow2 files/vm/base.qcow2
adb shell run-as com.romirmile.hermeslinux cp files/vm/user.qcow2.previous files/vm/user.qcow2
```

The `assets_extracted.*` marker has to match that old image again for the app to accept the pair, so
this is reliable when the release that created the disk is the one installed; with a newer app
installed, expect it to re-extract its own base and start fresh. There is no in-app restore —
mounting the agent's home from app storage so the agent's own state survives an image change is the
fix this repository wants.

## Known limits

- **arm64 only.** `armeabi-v7a` and x86_64/emulator builds do not exist yet — the biggest open gap.
- **Large APK.** A bootable guest disk, QEMU and a Linux userspace travel inside it.
- **No hardware acceleration.** No KVM, and no GPU offload for the speech engine.
- **The first reply after a cold boot is slow** — the disk is extracted and the agent starts; after
  that it behaves normally.
- **An image update starts an empty guest** — see *What survives what* above; the previous disk is
  kept aside as `vm/user.qcow2.previous` and is not mounted again automatically.

## Build it yourself

### 1. Guest image

Needs a Linux host with `e2fsprogs`, `qemu-utils`, `node`/`npm` and `qemu-user-static` with
`binfmt_misc` registered for `qemu-aarch64`, so the guest's own `apk`/`pip` run while the root
filesystem is populated. An x86_64 host works — no ARM hardware and no cross-Docker required.

```sh
sudo apt-get install -y qemu-user-static binfmt-support e2fsprogs qemu-utils nodejs npm
sudo update-binfmts --enable qemu-aarch64
./image/build_guest_image.sh
# -> app/src/main/assets/vm/base.qcow2.gz
```

The script downloads the Alpine aarch64 minirootfs, installs the guest packages and Hermes through a
chroot, writes the guest services, then packs a sparse ext4 image and converts it to a compressed
qcow2. It prints the image sha256, which is also the app's extraction marker: a rebuilt image is
picked up by existing installs automatically, with nothing to hand-bump.

### 2. APK

```sh
export JAVA_HOME=<jdk-17>
export ANDROID_HOME=<android-sdk>
./scripts/build_apk.sh
# -> app/build/outputs/apk/debug/app-debug.apk
```

The APK carries the guest disk, QEMU and the speech model pack, which is why builds are handed out
as release assets rather than through a store.

## Contributing

The project is one Android app plus a guest image, and most contributions do not need a phone. See
[CONTRIBUTING.md](CONTRIBUTING.md) for the build and test workflow and for where help is most useful
— other architectures, a smaller image, speed, and real-device reports.

Two host-side tests need no phone: `image/test_guest_image.sh` boots the image and walks the startup
sequence, and `image/test_guest_persistence.sh` proves the persistence table above — canary files
surviving a stop/start and a force-stop, an image update starting an empty guest, and the restore
path bringing the previous disk back.

## FAQ

**Do I need root?** No. Nothing in the app requires it — that is the point of using a VM.

**Why is the APK so big?** It contains a bootable Linux disk with the agent preinstalled, the QEMU
binaries and an optional speech model. Download once, then it works offline.

**Does it run on an x86_64 emulator?** No. Only `arm64-v8a` QEMU binaries ship.

**Does anything leave my phone?** Only what you configure: model calls to the provider you choose.
The guest reaches the network through QEMU's user-mode networking, and speech is generated locally.

**Can I point another chat client at it?** Yes — the engine screen shows the gateway address and key
the app itself uses.

**Can I use my own provider?** Yes, that is the only way it works.

**Why does the engine restart on upgrade?** A new base image is a new disk identity, so the app
re-extracts it and nobody keeps a stale guest. That also means the guest comes back empty: the
previous disk is kept as `vm/user.qcow2.previous`, not mounted (see *What survives what*).

## Credits and license

MIT — see [LICENSE](LICENSE). The Android VM layer is derived from **Pockr**
([AI2TH/Pockr](https://github.com/AI2TH/Pockr), MIT): the Flutter UI was replaced with this app's own
Kotlin/Jetpack Compose UI, the Docker stack was removed from the guest, Hermes Agent is baked into
the image, and the guest control API was rewritten around the agent lifecycle.

Bundled and executed components — QEMU (GPLv2, invoked as a separate process), the Alpine guest, the
Linux kernel, Hermes Agent itself, the speech engine and its model weights — are listed with their
licenses in [NOTICE.md](NOTICE.md).

**If you fork this:** you may, under the MIT terms. Keep the copyright notice and the attribution,
and ship it under **your own** name and icon — the project's name and logo identify this build, and a
modified fork must not look like it.
