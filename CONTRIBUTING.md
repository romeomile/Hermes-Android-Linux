# Contributing

Thanks for wanting to work on this. The project is a single Android app plus a
guest disk image built from Alpine Linux — both halves are in this repository, and
most contributions do not need a phone.

## Where help is most useful

- **Real-device reports.** Android version, SoC, RAM, what the engine screen logs,
  and where it is slow. Two data points beat one opinion.
- **Other architectures.** Only `arm64-v8a` QEMU binaries are shipped today, so
  `armeabi-v7a` and emulators (x86_64) cannot run the guest at all. Making the
  build produce those binaries is the single biggest gap.
- **Making the APK smaller.** The guest disk, QEMU and a Linux userspace travel
  inside the APK; a slimmer base image is a straight win for every user.
- **Speed.** The guest runs under QEMU TCG (no KVM on Android). Anything that
  cuts emulated CPU work — fewer guest services, a smaller Python surface, host
  offload where the GPU allows it — is welcome and measurable.
- **Docs.** Build notes that assume less, especially for hosts other than this one.

## Ground rules

- **No machine-specific values in the repository.** No IP addresses, hostnames,
  API keys, tokens, local paths, or config values from the machine you built on.
  The build scripts assert this for the guest image; the same rule applies to
  code, docs and assets. Anything host-derived has to be reverted.
- **Nothing third-party-branded in the app.** The shipped UI carries this
  project's own naming only.
- **No proprietary fonts or assets** — open equivalents only.
- **Keep user-visible strings in `app/src/main/res/values/strings.xml`**, never
  inlined in Kotlin.

## Building

### Guest image (`app/src/main/assets/vm/base.qcow2.gz`)

Needs a Linux host with `e2fsprogs`, `qemu-utils`, `node`/`npm`, and
`qemu-user-static` with `binfmt_misc` registered for `qemu-aarch64` so the
guest's own `apk`/`pip` run during the build. An x86_64 host works; no ARM
hardware is required.

```sh
sudo apt-get install -y qemu-user-static binfmt-support e2fsprogs qemu-utils nodejs npm
sudo update-binfmts --enable qemu-aarch64

./image/build_guest_image.sh
# -> app/src/main/assets/vm/base.qcow2.gz
```

The script prints the image sha256 at the end. That hash is what the app stores as
its extraction marker, so a rebuilt image is picked up by existing installs
automatically — never hand-bump a marker.

Anything compiled inside the guest (Python bytecode in particular) records the
path it was compiled from. Compile it from inside the chroot, or strip the paths,
and let the build's own host-path check be the referee: `image/build_guest_image.sh`
fails rather than shipping a tree that mentions the build machine.

### APK

```sh
export JAVA_HOME=<jdk-17>
export ANDROID_HOME=<android-sdk>
./scripts/build_apk.sh
# -> app/build/outputs/apk/debug/app-debug.apk
```

The APK is large (hundreds of MB up to ~1.8 GB with the speech model pack), so
builds are handed out as release assets rather than through a store.

## Testing

- `./image/test_guest_image.sh` boots the built image on the host (QEMU, no
  phone) and probes the boot sequence.
- `./image/test_guest_persistence.sh` proves the persistence contract on the
  host: canary files that must survive a stop/start and an Android-style
  force-stop, an image update that must start an empty guest, and the restore
  path that must bring the previous disk back. Run it after touching anything
  that creates, reuses or replaces the guest disk — `VmManager`'s overlay and
  marker logic, or an image change. Remember the overlay must be at least as
  large as the base filesystem (the app defaults to 20 GB): a smaller one makes
  the guest unable to mount its root and drop into the initramfs shell.
- On a device, the engine screen's log and the guest shell are the primary
  instruments; the control API answers on `127.0.0.1:7080`.
- **Prove the whole ordered startup sequence, not one layer.** Reports here have
  consistently been stage-order faults: the guest booting, the agent coming up,
  the API server listening, the chat path answering. A check that passes while an
  earlier stage is broken is a false positive worth fixing before shipping.

## Pull requests

- One change per PR, with the reason in the commit message (what was wrong, what
  the fix is, what you measured).
- Say what you verified and how. "Built and booted on <device>" is evidence;
  "should work" is not.
- No secrets in diffs, no build artifacts committed (`.gitignore` covers the
  guest disk, the model pack and build trees).

## Forks and naming

The code is MIT-licensed, so you may fork it, modify it and ship it. Keep the
copyright notice and the attribution in `NOTICE.md`, and use **your own** app
name and icon: the project's name and logo identify this build, and a modified
fork must not look like it.
