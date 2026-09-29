package com.romirmile.hermes.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Native entry points. Declared top-level on purpose: JNI resolves them by class name, and a
 * nested object would compile to `ChatterboxVoice$Native` (`_00024Native` in the symbol), which
 * the C++ bridge does not export.
 */
private object ChatterboxNative {
    init { System.loadLibrary(ChatterboxVoice.LIB_NAME) }

    external fun nativeCreate(
        t3Gguf: String,
        s3genGguf: String,
        referenceWav: String,
        threads: Int,
        gpuLayers: Int,
        seed: Int
    ): Long

    external fun nativeSynthesize(handle: Long, text: String, seed: Int): ByteArray?
    external fun nativeCancel(handle: Long)
    external fun nativeFree(handle: Long)
}

/**
 * Speech from a model that runs on this device — no agent, no endpoint, no key.
 *
 * The engine is the C++/ggml port of Chatterbox: `libchatterbox.so` is the app's own native
 * library (T3 text→speech-token model + S3Gen/HiFT vocoder), so nothing is downloaded at
 * synthesis time and no Python or PyTorch exists anywhere in the app. The two GGUF weight files
 * ship in the APK's assets and are copied into app storage once, on demand.
 *
 * Costs and limits, as measured on the build host: the pack is ~1.3 GB on disk, and synthesis is
 * slower than real time on a CPU-only device (see PROGRESS.md for the numbers). Turbo is the
 * English variant; the engine's multilingual variant does not cover Russian.
 */
object ChatterboxVoice {

    const val LIB_NAME = "chatterbox"

    /** Where the pack lives inside the APK and inside app storage. */
    const val ASSET_DIR = "chatterbox"
    const val T3_FILE = "cbx-t3-turbo-q8.gguf"
    const val S3GEN_FILE = "cbx-s3gen-turbo-q8.gguf"
    val ASSET_FILES = listOf(T3_FILE, S3GEN_FILE)

    /** Pack size on disk, for the settings screen. */
    const val PACK_BYTES_APPROX = 1_320_000_000L

    const val DEFAULT_SEED = 42

    /** A speech failure that should fall back to the phone engine. */
    class VoiceException(message: String) : Exception(message)

    private val engineLock = Mutex()
    private var handle = 0L
    private var loadedKey = ""

    fun modelsDir(context: Context): File = File(context.filesDir, ASSET_DIR)

    fun modelFiles(context: Context): List<File> = ASSET_FILES.map { File(modelsDir(context), it) }

    /** True when both weight files are on disk and look complete. */
    fun modelsInstalled(context: Context): Boolean =
        modelFiles(context).all { it.isFile && it.length() > 0L }

    /** True when the native library loaded and the weights are present. */
    fun isReady(context: Context): Boolean = libraryLoaded() && modelsInstalled(context)

    private var libraryError: String? = null

    private fun libraryLoaded(): Boolean {
        if (libraryError != null) return false
        return try {
            ChatterboxNative // triggers the companion `init` block
            true
        } catch (t: Throwable) {
            libraryError = t.message
            false
        }
    }

    fun libraryFailure(): String? = libraryError?.also { libraryLoaded() }

    /**
     * Copies the shipped GGUF files from the APK into app storage. Assets are streamed, so the
     * copy needs no extra memory, and [onProgress] reports whole per-cent values for the UI.
     */
    suspend fun installModelPack(context: Context, onProgress: (Int) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            val dir = modelsDir(context)
            if (!dir.exists() && !dir.mkdirs()) {
                throw VoiceException("could not create ${dir.absolutePath}")
            }
            val total = ASSET_FILES.sumOf { sizeOfAsset(context, it) }
            var done = 0L
            var reported = -1
            for (name in ASSET_FILES) {
                val target = File(dir, name)
                if (target.isFile && target.length() > 0L) {
                    done += target.length()
                    continue
                }
                val partial = File(dir, "$name.part")
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 20)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            done += read
                            val percent = if (total > 0) ((done * 100) / total).toInt() else 0
                            if (percent != reported) {
                                reported = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
                if (!partial.renameTo(target)) {
                    partial.delete()
                    throw VoiceException("could not install $name")
                }
            }
            onProgress(100)
        }

    private fun sizeOfAsset(context: Context, name: String): Long = runCatching {
        context.assets.openFd("$ASSET_DIR/$name").use { it.length }
    }.getOrElse { 0L }

    /**
     * Synthesizes [text] into a 24 kHz mono 16-bit WAV. Serialised: the engine's KV cache and
     * sampler state belong to the instance, so concurrent calls would corrupt each other.
     */
    suspend fun synthesize(context: Context, text: String, seed: Int = DEFAULT_SEED): ByteArray {
        val input = text.trim()
        if (input.isEmpty()) throw VoiceException("nothing to speak")
        return withContext(Dispatchers.Default) {
            engineLock.withLock {
                val engine = ensureEngine(context)
                ChatterboxNative.nativeSynthesize(engine, input, seed)
                    ?: throw VoiceException("chatterbox produced no audio")
            }
        }
    }

    private fun ensureEngine(context: Context): Long {
        val files = modelFiles(context)
        val missing = files.filter { !it.isFile || it.length() == 0L }
        if (missing.isNotEmpty()) {
            throw VoiceException("model pack is not installed: ${missing.joinToString { it.name }}")
        }
        val key = files.joinToString("|") { "${it.absolutePath}:${it.length()}" }
        if (handle != 0L && key == loadedKey) return handle
        if (handle != 0L) {
            runCatching { ChatterboxNative.nativeFree(handle) }
            handle = 0L
            loadedKey = ""
        }
        val threads = max(1, minOf(Runtime.getRuntime().availableProcessors(), 6))
        handle = ChatterboxNative.nativeCreate(
            t3Gguf = files[0].absolutePath,
            s3genGguf = files[1].absolutePath,
            referenceWav = "",
            threads = threads,
            gpuLayers = 0,
            seed = DEFAULT_SEED
        )
        if (handle == 0L) throw VoiceException("the chatterbox engine did not start")
        loadedKey = key
        return handle
    }

    /** Best-effort stop of an in-flight synthesis. */
    fun cancel() {
        val current = handle
        if (current != 0L) runCatching { ChatterboxNative.nativeCancel(current) }
    }

    /** Frees the loaded models; the next synthesis loads them again. */
    fun release() {
        val current = handle
        handle = 0L
        loadedKey = ""
        if (current != 0L) runCatching { ChatterboxNative.nativeFree(current) }
    }
}
