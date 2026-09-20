package com.romirmile.hermes.vm

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * Owns the QEMU process that runs the Linux guest.
 *
 * QEMU ships as `jniLibs/arm64-v8a/libqemu.so`, so Android installs it into the app's
 * `nativeLibraryDir` with the `exec_type` SELinux label and the app can execute it without root.
 * The guest disk is an app-private qcow2 pair: a read-only base image extracted from assets and a
 * writable overlay that persists everything the user does inside the guest.
 */
class VmManager(private val context: Context) {

    @Volatile private var vmProcess: Process? = null

    private val store = EngineStore(context)

    /** Lines from QEMU's serial console, for the in-app log. */
    var onLog: ((String) -> Unit)? = null

    /** Byte-level progress while the guest disk is unpacked on first launch. */
    var onProgress: ((String) -> Unit)? = null

    val token: String get() = store.token

    val apiClient: VmApiClient by lazy { VmApiClient(store.token) }

    private val filesDir: File get() = context.filesDir
    private val vmDir: File get() = File(filesDir, "vm")
    private val nativeLibDir: File get() = File(context.applicationInfo.nativeLibraryDir)

    @Synchronized
    fun start() {
        if (isRunning()) {
            Log.d(TAG, "VM already running")
            return
        }

        val fresh = !assetsReady()
        if (fresh) {
            report("Extracting the guest disk (first launch only)")
            extractAssets()
        }

        val baseImage = File(vmDir, "base.qcow2")
        val userImage = File(vmDir, "user.qcow2")
        // A persistent overlay keeps everything installed inside the guest across restarts. It is
        // recreated only when the base image itself changed, because it is then incompatible.
        if (fresh || !userImage.exists()) {
            report("Creating the writable guest overlay")
            userImage.delete()
            createUserImage(userImage.absolutePath, baseImage.absolutePath)
        } else {
            report("Reusing the existing guest overlay")
        }

        val command = buildQemuCommand(
            qemuBinary = resolveQemuBinary().absolutePath,
            baseImage = baseImage.absolutePath,
            userImage = userImage.absolutePath,
            vcpu = store.cpuCount,
            ramMb = store.ramMb
        )

        Log.d(TAG, "QEMU: ${command.joinToString(" ").replace(token, "<redacted>")}")

        vmProcess = ProcessBuilder(command).apply {
            environment()["LD_LIBRARY_PATH"] = nativeLibDir.absolutePath
            redirectErrorStream(true)
        }.start()

        // Drain the serial console; an unread pipe would eventually block the guest.
        Thread {
            try {
                vmProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                    Log.d("QEMU", line)
                    onLog?.invoke(line)
                }
            } catch (e: Exception) {
                Log.w(TAG, "QEMU console closed: ${e.message}")
            }
        }.apply { isDaemon = true; start() }

        report("VM process started")
    }

    @Synchronized
    fun stop() {
        vmProcess?.let { process ->
            process.destroy()
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        vmProcess = null
        report("VM stopped")
    }

    /** "running" only while the QEMU process is alive — a stale flag would lie to the UI. */
    fun status(): String {
        val process = vmProcess ?: return "stopped"
        return try {
            process.exitValue()
            vmProcess = null
            "stopped"
        } catch (_: IllegalThreadStateException) {
            "running"
        }
    }

    fun isRunning(): Boolean = status() == "running"

    fun vmExec(cmd: String, timeoutSeconds: Int = 60) = apiClient.vmExec(cmd, timeoutSeconds)

    private fun report(message: String) {
        Log.d(TAG, message)
        onProgress?.invoke(message)
    }

    // ---------------------------------------------------------------------------------------------
    // Guest disk
    // ---------------------------------------------------------------------------------------------

    private fun assetsReady(): Boolean =
        File(filesDir, "assets_extracted.$ASSET_VERSION").exists() &&
            resolveQemuBinary().exists() &&
            File(vmDir, "base.qcow2").exists() &&
            File(vmDir, "vmlinuz-virt").exists() &&
            File(vmDir, "initramfs-virt").exists()

    private fun extractAssets() {
        vmDir.mkdirs()

        val base = File(vmDir, "base.qcow2")
        if (base.exists()) base.delete()
        // `noCompress` keeps the asset gzipped; if a toolchain ever unpacks it, the plain name works.
        try {
            extractAsset("vm/base.qcow2", base)
        } catch (_: Exception) {
            extractAndDecompress("vm/base.qcow2.gz", base)
        }

        listOf("vmlinuz-virt", "initramfs-virt").forEach { name ->
            val target = File(vmDir, name)
            if (!target.exists()) extractAsset("vm/$name", target)
        }

        File(filesDir, "assets_extracted.$ASSET_VERSION").createNewFile()
        report("Guest disk ready")
    }

    private fun extractAsset(assetPath: String, destination: File) {
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destination).use { output -> copyWithProgress(input, output, destination) }
        }
    }

    private fun extractAndDecompress(assetPath: String, destination: File) {
        context.assets.open(assetPath).use { raw ->
            GZIPInputStream(raw).use { gzip ->
                FileOutputStream(destination).use { output -> copyWithProgress(gzip, output, destination) }
            }
        }
    }

    /** Copies in 1 MiB chunks and reports progress, so a long first launch never looks hung. */
    private fun copyWithProgress(input: java.io.InputStream, output: FileOutputStream, target: File) {
        val buffer = ByteArray(1 shl 20)
        var total = 0L
        var lastReported = -1
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            total += read
            val megabytes = (total / (1 shl 20)).toInt()
            if (megabytes / 32 != lastReported) {
                lastReported = megabytes / 32
                report("  ${target.name}: ${megabytes} MB written")
            }
        }
    }

    private fun createUserImage(userImagePath: String, baseImagePath: String) {
        val qemuImg = File(nativeLibDir, "libqemu_img.so")
        check(qemuImg.exists()) { "libqemu_img.so is missing from ${nativeLibDir.absolutePath}" }

        val process = ProcessBuilder(
            qemuImg.absolutePath, "create", "-f", "qcow2",
            "-b", baseImagePath, "-F", "qcow2",
            userImagePath, OVERLAY_SIZE
        ).apply {
            environment()["LD_LIBRARY_PATH"] = nativeLibDir.absolutePath
        }.start()

        val exit = process.waitFor()
        if (exit != 0) {
            val error = process.errorStream.bufferedReader().readText()
            error("qemu-img create failed (exit $exit): $error")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // QEMU
    // ---------------------------------------------------------------------------------------------

    private fun buildQemuCommand(
        qemuBinary: String,
        baseImage: String,
        userImage: String,
        vcpu: Int,
        ramMb: Int
    ): List<String> = buildList {
        add(qemuBinary)
        addAll(listOf("-machine", "virt"))
        addAll(listOf("-cpu", "cortex-a53"))
        addAll(listOf("-smp", vcpu.toString()))
        addAll(listOf("-m", ramMb.toString()))
        addAll(listOf("-drive", "if=none,file=$baseImage,id=base,format=qcow2,readonly=on"))
        addAll(listOf("-drive", "if=none,file=$userImage,id=user,format=qcow2"))
        addAll(listOf("-device", "virtio-blk-pci,drive=user"))
        // Two forwardings: the guest control API and the agent's API server, both on device loopback.
        addAll(
            listOf(
                "-netdev",
                "user,id=net0,hostfwd=tcp::${EngineStore.CONTROL_PORT}-:${EngineStore.CONTROL_PORT}," +
                    "hostfwd=tcp::${EngineStore.AGENT_PORT}-:${EngineStore.AGENT_PORT}"
            )
        )
        addAll(listOf("-device", "virtio-net-pci,netdev=net0,romfile="))
        addAll(listOf("-fw_cfg", "name=opt/api_token,string=$token"))
        addAll(listOf("-display", "none"))
        addAll(listOf("-serial", "stdio"))
        val kernel = File(vmDir, "vmlinuz-virt")
        val initrd = File(vmDir, "initramfs-virt")
        if (kernel.exists() && initrd.exists()) {
            addAll(listOf("-kernel", kernel.absolutePath))
            addAll(listOf("-initrd", initrd.absolutePath))
            // Alpine's initramfs only loads the modules named here, and the token travels on the
            // kernel command line so the guest needs no shared folder to receive it.
            addAll(
                listOf(
                    "-append",
                    "console=ttyAMA0 root=/dev/vda rootfstype=ext4 rootflags=rw " +
                        "modules=virtio_blk,ext4 api_token=$token quiet"
                )
            )
        }
    }

    private fun resolveQemuBinary(): File {
        val binary = File(nativeLibDir, "libqemu.so")
        check(binary.exists()) {
            "libqemu.so is missing from ${nativeLibDir.absolutePath} — the arm64 QEMU libs must ship in jniLibs."
        }
        return binary
    }

    companion object {
        private const val TAG = "VmManager"
        private const val OVERLAY_SIZE = "8G"

        /** Bump whenever the guest image in assets changes, so installed apps re-extract it. */
        private const val ASSET_VERSION = "1"
    }
}
