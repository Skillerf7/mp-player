package com.mp.player

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Cover aus den eingebetteten Tags (MediaMetadataRetriever.embeddedPicture), mit Datei-Cache.
 * Wird von der UI (rememberCover) und - ueber den CoverProvider - von Media3 fuer die
 * Benachrichtigung/den Sperrbildschirm genutzt. Kein Cover vorhanden -> null, nie eine Exception.
 */
object Covers {
    const val AUTHORITY = "com.mp.player.covers"

    private val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun artworkUri(trackUri: String): Uri =
        Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("cover")
            .appendQueryParameter("u", trackUri).build()

    private fun dir(ctx: Context): File = File(ctx.cacheDir, "covers").apply { mkdirs() }

    private fun key(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Liefert die (ggf. neu extrahierte) Coverdatei oder null, wenn der Titel kein Cover hat. */
    @Synchronized
    fun ensureFile(ctx: Context, trackUri: String): File? {
        val base = key(trackUri)
        val f = File(dir(ctx), "$base.jpg")
        val none = File(dir(ctx), "$base.none")
        if (f.exists() && f.length() > 0) return f
        if (none.exists()) return null
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, Uri.parse(trackUri))
            val bytes = r.embeddedPicture
            if (bytes != null && bytes.isNotEmpty()) {
                f.writeBytes(bytes)
                return f
            }
            none.createNewFile()
        } catch (ignored: Exception) {
            // Datei nicht lesbar -> kein Cover (nicht dauerhaft als "keins" merken)
        } finally {
            try { r.release() } catch (ignored: Exception) {}
        }
        return null
    }

    fun bitmap(ctx: Context, trackUri: String, maxPx: Int): Bitmap? {
        val ck = "$trackUri@$maxPx"
        mem.get(ck)?.let { return it }
        val f = ensureFile(ctx, trackUri) ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
            val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            if (bmp != null) mem.put(ck, bmp)
            bmp
        } catch (ignored: Throwable) { null }
    }
}

@Composable
fun rememberCover(trackUri: String?, maxPx: Int = 512): ImageBitmap? {
    val ctx = LocalContext.current.applicationContext
    val state = produceState<ImageBitmap?>(initialValue = null, trackUri, maxPx) {
        value = null
        value = if (trackUri == null) null
        else withContext(Dispatchers.IO) { Covers.bitmap(ctx, trackUri, maxPx)?.asImageBitmap() }
    }
    return state.value
}

/** Stellt Media3 (Benachrichtigung, Sperrbildschirm, Bluetooth) die Cover als content://-URI bereit. */
class CoverProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val u = uri.getQueryParameter("u") ?: throw FileNotFoundException("no track")
        val ctx = context ?: throw FileNotFoundException("no context")
        val f = Covers.ensureFile(ctx, u) ?: throw FileNotFoundException("no cover")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String = "image/*"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
