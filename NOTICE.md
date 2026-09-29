# Notices and attribution

This project is a derivative work. It was started from the source of
**Pockr** (https://github.com/AI2TH/Pockr), Copyright (c) 2026 AI2TH,
released under the MIT License (see `LICENSE`). The Android VM layer
(QEMU launch, asset extraction, overlay disk creation, foreground service)
and the QEMU/kernel runtime assets come from that project.

Modifications in this project: the Flutter UI was replaced with a native
Kotlin/Jetpack Compose host UI, the Docker stack was removed from the guest,
Hermes Agent is baked into the guest image, and the guest control API was
rewritten around the agent lifecycle.

## Bundled / executed components

| Component | License | How it is used |
|---|---|---|
| Pockr | MIT | Source for the Android VM layer (see `LICENSE`) |
| QEMU (`qemu-system-aarch64`) | GPLv2 (TCG: BSD/Expat) | Executed as a **separate process**, not linked into the app. Source: https://gitlab.com/qemu-project/qemu |
| Alpine Linux | MIT and others | Guest root filesystem (Alpine 3.19) |
| Linux kernel (`vmlinuz-virt`, `initramfs-virt`) | GPLv2 | Guest kernel, unmodified Alpine build |
| Hermes Agent | see upstream | Installed inside the guest image (https://github.com/NousResearch/hermes-agent) |
| Chatterbox TTS (ggml port) | MIT | Speech engine **linked into the app** as `libchatterbox.so` (`tts-cpp` + `ggml`, C++/ggml, no Python/PyTorch at runtime). Port: https://github.com/gianni-cor/chatterbox.cpp (commit `ddca05fb69c2910b0d7b5eae420d360ed98c067b`), ggml: https://github.com/ggml-org/ggml |
| Chatterbox model weights | MIT | Converted to GGUF for the app from https://huggingface.co/ResembleAI/chatterbox-turbo, and shipped inside the APK's assets. Original model: https://github.com/resemble-ai/chatterbox (Copyright (c) 2025 Resemble AI) |

GPLv2 (QEMU, Linux): the binaries are redistributed unmodified and are invoked
as separate programs, so this project's own source is not derived from them.
Corresponding source is available from the upstream projects linked above.

`LICENSES.md` (carried over from Pockr) contains the original compliance notes.
