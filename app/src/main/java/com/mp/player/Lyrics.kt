package com.mp.player

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Liest EINGEBETTETE Songtexte direkt aus den Tags der Audiodatei - nichts wird gesucht oder geraten, nichts geht ins Netz.
 * Unterstuetzt:
 *  - MP3 (ID3v2.2/2.3/2.4): USLT-Frame, zusaetzlich TXXX "LYRICS" / "UNSYNCEDLYRICS"
 *  - FLAC: Vorbis-Kommentar LYRICS / UNSYNCEDLYRICS
 *  - Ogg Vorbis / Opus: Vorbis-Kommentar LYRICS / UNSYNCEDLYRICS
 *  - M4A / MP4: Atom (c)lyr
 * Zeitstempel synchronisierter Texte ([01:23.45]) werden entfernt, es bleibt reiner Text.
 * Fehlt der Text oder ist die Datei nicht lesbar, ist das Ergebnis einfach null (kein Absturz).
 */
object LyricsParser {
    private const val MAX_TEXT = 12_000
    private const val MIN_TEXT = 20

    /** Zugriff auf Dateibytes (wahlfrei). */
    interface Source {
        val size: Long
        fun read(pos: Long, len: Int): ByteArray
    }

    private class BytesSource(private val data: ByteArray) : Source {
        override val size: Long = data.size.toLong()
        override fun read(pos: Long, len: Int): ByteArray {
            if (pos < 0 || pos >= size || len <= 0) return ByteArray(0)
            val end = minOf(size, pos + len).toInt()
            return data.copyOfRange(pos.toInt(), end)
        }
    }

    private class ChannelSource(private val ch: FileChannel) : Source {
        override val size: Long = ch.size()
        override fun read(pos: Long, len: Int): ByteArray {
            if (pos < 0 || pos >= size || len <= 0) return ByteArray(0)
            val n = minOf(len.toLong(), size - pos).toInt()
            val bb = ByteBuffer.allocate(n)
            var off = 0L
            while (bb.hasRemaining()) {
                val r = ch.read(bb, pos + off)
                if (r < 0) break
                off += r
            }
            return bb.array().copyOf(bb.position())
        }
    }

    internal fun sourceOf(ch: FileChannel): Source = ChannelSource(ch)

    /** Fuer Tests und den Fallback ohne wahlfreien Zugriff. */
    fun parseBytes(data: ByteArray): String? = parse(BytesSource(data))

    fun parse(src: Source): String? = try {
        val head = src.read(0, 12)
        val raw = when {
            head.size >= 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> parseId3(src)
            head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "fLaC" -> parseFlac(src)
            head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "OggS" -> parseOgg(src)
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> parseMp4(src)
            else -> null
        }
        clean(raw)
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ Bereinigung

    private val LRC_TS = Regex("""[\[<]\d{1,3}:\d{2}(?:[.:]\d{1,3})?[\]>]""")
    private val LRC_TAG = Regex("""(?m)^\[[a-zA-Z]{2,8}:[^\]]*\]\s*$""")

    internal fun clean(raw: String?): String? {
        if (raw == null) return null
        var t = raw.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "")
        t = LRC_TAG.replace(t, "")
        t = LRC_TS.replace(t, "")
        t = t.trim()
        if (t.length < MIN_TEXT) return null
        return if (t.length > MAX_TEXT) t.substring(0, MAX_TEXT) else t
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun be24(b: ByteArray, i: Int): Int = (u8(b, i) shl 16) or (u8(b, i + 1) shl 8) or u8(b, i + 2)
    private fun be32(b: ByteArray, i: Int): Int = (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
    private fun le32(b: ByteArray, i: Int): Int = (u8(b, i + 3) shl 24) or (u8(b, i + 2) shl 16) or (u8(b, i + 1) shl 8) or u8(b, i)
    private fun syncsafe(b: ByteArray, i: Int): Int =
        ((u8(b, i) and 0x7F) shl 21) or ((u8(b, i + 1) and 0x7F) shl 14) or ((u8(b, i + 2) and 0x7F) shl 7) or (u8(b, i + 3) and 0x7F)

    /** ID3-"Unsynchronisation": FF 00 -> FF. */
    private fun unsync(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (u8(b, i) == 0xFF && i + 1 < b.size && u8(b, i + 1) == 0x00) i++
            i++
        }
        return out.toByteArray()
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty() || hay.size < needle.size) return -1
        var i = from
        while (i <= hay.size - needle.size) {
            var ok = true
            var j = 0
            while (j < needle.size) {
                if (hay[i + j] != needle[j]) { ok = false; break }
                j++
            }
            if (ok) return i
            i++
        }
        return -1
    }

    // ------------------------------------------------------------------ ID3v2

    private fun skipTerminated(b: ByteArray, from: Int, enc: Int): Int {
        if (enc == 1 || enc == 2) {
            var i = from
            while (i + 1 < b.size) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i + 2
                i += 2
            }
            return b.size
        }
        var i = from
        while (i < b.size) {
            if (b[i].toInt() == 0) return i + 1
            i++
        }
        return b.size
    }

    private fun decodeText(b: ByteArray, start: Int, end: Int, enc: Int): String {
        if (start >= end) return ""
        val len = end - start
        val s = when (enc) {
            1 -> {
                if (len >= 2 && u8(b, start) == 0xFF && u8(b, start + 1) == 0xFE) String(b, start + 2, len - 2, Charsets.UTF_16LE)
                else if (len >= 2 && u8(b, start) == 0xFE && u8(b, start + 1) == 0xFF) String(b, start + 2, len - 2, Charsets.UTF_16BE)
                else String(b, start, len, Charsets.UTF_16LE)
            }
            2 -> String(b, start, len, Charsets.UTF_16BE)
            3 -> String(b, start, len, Charsets.UTF_8)
            else -> String(b, start, len, Charsets.ISO_8859_1)
        }
        return s.trimEnd('\u0000')
    }

    /** USLT: Kodierung(1) Sprache(3) Beschreibung(terminiert) Text. */
    private fun parseUslt(b: ByteArray): String? {
        if (b.size < 5) return null
        val enc = u8(b, 0)
        val p = skipTerminated(b, 4, enc)
        if (p > b.size) return null
        return decodeText(b, p, b.size, enc)
    }

    /** TXXX: Kodierung(1) Beschreibung(terminiert) Wert. Nur Beschreibung LYRICS / UNSYNCEDLYRICS. */
    private fun parseTxxx(b: ByteArray): String? {
        if (b.size < 3) return null
        val enc = u8(b, 0)
        val p = skipTerminated(b, 1, enc)
        if (p > b.size) return null
        val descEnd = if (enc == 1 || enc == 2) p - 2 else p - 1
        if (descEnd < 1) return null
        val desc = decodeText(b, 1, descEnd, enc).trim().uppercase()
        if (desc != "LYRICS" && desc != "UNSYNCEDLYRICS" && desc != "UNSYNCED LYRICS") return null
        return decodeText(b, p, b.size, enc)
    }

    internal fun parseId3(src: Source): String? {
        val h = src.read(0, 10)
        if (h.size < 10) return null
        val major = u8(h, 3)
        val flags = u8(h, 5)
        if (major !in 2..4) return null
        val tagSize = syncsafe(h, 6)
        val total = minOf(tagSize.toLong(), 16L * 1024 * 1024, src.size - 10).toInt()
        if (total <= 0) return null
        var data = src.read(10, total)
        if ((flags and 0x80) != 0 && major < 4) data = unsync(data)

        var pos = 0
        if ((flags and 0x40) != 0 && major >= 3 && data.size >= 4) {
            val ext = if (major == 4) syncsafe(data, 0) else be32(data, 0) + 4
            pos = ext.coerceIn(0, data.size)
        }
        val idLen = if (major == 2) 3 else 4
        val hdrLen = if (major == 2) 6 else 10
        var best: String? = null
        while (pos + hdrLen <= data.size) {
            if (data[pos].toInt() == 0) break // Padding
            val id = String(data, pos, idLen, Charsets.ISO_8859_1)
            val size = when (major) {
                2 -> be24(data, pos + 3)
                3 -> be32(data, pos + 4)
                else -> syncsafe(data, pos + 4)
            }
            val frameFlags = if (major == 2) 0 else (u8(data, pos + 8) shl 8) or u8(data, pos + 9)
            val start = pos + hdrLen
            if (size < 0 || start + size > data.size) break
            pos = start + size

            val isUslt = id == "USLT" || id == "ULT"
            val isTxxx = id == "TXXX" || id == "TXX"
            if (!isUslt && !isTxxx) continue
            // komprimierte oder verschluesselte Frames koennen wir nicht lesen
            val unreadable = when (major) {
                4 -> (frameFlags and 0x000C) != 0
                3 -> (frameFlags and 0x00C0) != 0
                else -> false
            }
            if (unreadable) continue
            var body = data.copyOfRange(start, start + size)
            if (major == 4 && (frameFlags and 0x0001) != 0 && body.size > 4) body = body.copyOfRange(4, body.size)
            if (major == 4 && (frameFlags and 0x0002) != 0) body = unsync(body)
            val t = if (isUslt) parseUslt(body) else parseTxxx(body)
            if (t != null && (best == null || t.length > best.length)) best = t
        }
        return best
    }

    // ------------------------------------------------------------------ Vorbis-Kommentare (FLAC, Ogg)

    internal fun vorbisComments(b: ByteArray, start: Int): String? {
        var p = start
        if (p + 4 > b.size) return null
        val vendorLen = le32(b, p)
        p += 4
        if (vendorLen < 0 || p + vendorLen > b.size) return null
        p += vendorLen
        if (p + 4 > b.size) return null
        val count = le32(b, p)
        p += 4
        var best: String? = null
        var i = 0
        while (i < count && p + 4 <= b.size) {
            val len = le32(b, p)
            p += 4
            if (len < 0 || p + len > b.size) break
            val s = String(b, p, len, Charsets.UTF_8)
            p += len
            val eq = s.indexOf('=')
            if (eq > 0) {
                val key = s.substring(0, eq).uppercase()
                if (key == "LYRICS" || key == "UNSYNCEDLYRICS" || key == "UNSYNCED LYRICS") {
                    val v = s.substring(eq + 1)
                    if (best == null || v.length > best.length) best = v
                }
            }
            i++
        }
        return best
    }

    internal fun parseFlac(src: Source): String? {
        var pos = 4L
        while (pos + 4 <= src.size) {
            val h = src.read(pos, 4)
            if (h.size < 4) return null
            val isLast = (u8(h, 0) and 0x80) != 0
            val type = u8(h, 0) and 0x7F
            val len = be24(h, 1)
            pos += 4
            if (type == 4) {
                val body = src.read(pos, minOf(len, 8 * 1024 * 1024))
                return vorbisComments(body, 0)
            }
            pos += len
            if (isLast) break
        }
        return null
    }

    internal fun parseOgg(src: Source): String? {
        // Kommentar-Paket liegt direkt hinter dem Kopf-Paket; ueber Seitengrenzen hinweg zusammensetzen.
        val data = src.read(0, minOf(src.size, 2L * 1024 * 1024).toInt())
        val out = ByteArrayOutputStream()
        var pos = 0
        var pages = 0
        while (pos + 27 <= data.size && pages < 60) {
            if (String(data, pos, 4, Charsets.ISO_8859_1) != "OggS") break
            val segs = u8(data, pos + 26)
            if (pos + 27 + segs > data.size) break
            var payload = 0
            for (k in 0 until segs) payload += u8(data, pos + 27 + k)
            val start = pos + 27 + segs
            val end = minOf(start + payload, data.size)
            if (end > start) out.write(data, start, end - start)
            pos = start + payload
            pages++
        }
        val all = out.toByteArray()
        val opus = indexOf(all, "OpusTags".toByteArray(Charsets.ISO_8859_1))
        if (opus >= 0) return vorbisComments(all, opus + 8)
        val vorbis = indexOf(all, byteArrayOf(0x03, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte()))
        if (vorbis >= 0) return vorbisComments(all, vorbis + 7)
        return null
    }

    // ------------------------------------------------------------------ MP4 / M4A

    /** Sucht in [start, end) eine Box vom Typ [type]; Ergebnis = Inhaltsbereich. */
    private fun box(b: ByteArray, start: Int, end: Int, type: String): Pair<Int, Int>? {
        var p = start
        while (p + 8 <= end) {
            val size = be32(b, p)
            if (size < 8 || p + size > end) return null
            if (String(b, p + 4, 4, Charsets.ISO_8859_1) == type) return Pair(p + 8, p + size)
            p += size
        }
        return null
    }

    private fun mp4Lyrics(moov: ByteArray): String? {
        val udta = box(moov, 0, moov.size, "udta") ?: return null
        val meta = box(moov, udta.first, udta.second, "meta") ?: return null
        val ilst = box(moov, meta.first + 4, meta.second, "ilst") ?: return null // meta ist eine "Full Box": 4 Byte Version/Flags
        val lyr = box(moov, ilst.first, ilst.second, "\u00A9lyr") ?: return null
        val data = box(moov, lyr.first, lyr.second, "data") ?: return null
        val from = data.first + 8 // Typ(4) + Locale(4)
        if (from >= data.second) return null
        return String(moov, from, data.second - from, Charsets.UTF_8)
    }

    internal fun parseMp4(src: Source): String? {
        var pos = 0L
        while (pos + 8 <= src.size) {
            val h = src.read(pos, 16)
            if (h.size < 8) return null
            var size = be32(h, 0).toLong() and 0xFFFFFFFFL
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var hdr = 8
            if (size == 1L) {
                if (h.size < 16) return null
                size = (be32(h, 8).toLong() shl 32) or (be32(h, 12).toLong() and 0xFFFFFFFFL)
                hdr = 16
            } else if (size == 0L) {
                size = src.size - pos
            }
            if (size < hdr) return null
            if (type == "moov") {
                if (size > 32L * 1024 * 1024) return null
                return mp4Lyrics(src.read(pos + hdr, (size - hdr).toInt()))
            }
            pos += size
        }
        return null
    }
}

/** Android-Teil: oeffnet die Datei ueber die Content-URI und uebergibt die Bytes an den [LyricsParser]. */
object LyricsReader {
    private const val HEAD_BYTES = 4 * 1024 * 1024

    private fun readUpTo(ins: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (total < max) {
            val n = ins.read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    /** @return bereinigter Songtext oder null (kein Text eingebettet / nicht lesbar). */
    fun read(ctx: Context, uriString: String): String? {
        val uri = Uri.parse(uriString)
        try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                pfd.use {
                    FileInputStream(it.fileDescriptor).use { fis ->
                        return LyricsParser.parse(LyricsParser.sourceOf(fis.channel))
                    }
                }
            }
        } catch (e: Exception) {
            // wahlfreier Zugriff nicht moeglich -> Fallback unten
        }
        try {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                return LyricsParser.parseBytes(readUpTo(ins, HEAD_BYTES))
            }
        } catch (e: Exception) {
        }
        return null
    }
}
