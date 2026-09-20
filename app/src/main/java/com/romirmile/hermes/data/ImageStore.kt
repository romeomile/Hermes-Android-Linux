package com.romirmile.hermes.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.romirmile.hermes.PendingImage
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Inline image attachments. Hermes accepts images as `data:` URLs inside a content-part
 * array, so a picked photo is downscaled, stored in the app's private storage (so the thumbnail
 * can be re-rendered later) and re-encoded as a data URL for each turn.
 */
object ImageStore {

    private const val MAX_DIM = 1568
    private const val QUALITY = 85
    private const val MAX_BYTES = 4 * 1024 * 1024

    fun import(context: Context, uri: Uri): PendingImage? = runCatching {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > MAX_DIM || bounds.outHeight / sample > MAX_DIM) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null

        val dir = File(context.filesDir, "images").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        bitmap.recycle()

        val bytes = file.readBytes()
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) {
            file.delete()
            return null
        }
        PendingImage(path = file.absolutePath, dataUrl = dataUrl(bytes))
    }.getOrNull()

    /** Re-encode a stored attachment so it can be replayed in later turns. */
    fun dataUrlFor(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        return runCatching { dataUrl(file.readBytes()) }.getOrNull()
    }

    private fun dataUrl(bytes: ByteArray): String =
        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
}
