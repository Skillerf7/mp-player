package com.mp.player.ai

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * Lokales Gedaechtnis des Assistenten (komplett offline, kein Netzwerk, kein LLM).
 * Reine Kotlin-Logik ohne Android-Klassen -> testbar. Die Speicherung steckt hinter [MemoryStore];
 * faellt sie aus, arbeitet der Assistent ohne Gedaechtnis weiter (Wiedergabe bleibt unberuehrt).
 */

/** PATTERN = gelerntes Muster aus Verhalten (Verknuepfung "Gefuehl>Genre", z. B. "calm>hardtekk"), nie ausdruecklich gesagt. */
enum class MemKind { LIKE, DISLIKE, FACT, PATTERN }
enum class MemScope { EPHEMERAL, SESSION, LONG }

data class MemoryItem(
    val id: Long,
    val kind: MemKind,
    /** Normalisierter Schluessel ("hardtekk", "bass") - darueber werden Eintraege zusammengefuehrt. */
    val key: String,
    /** So hat es der Nutzer gesagt - fuer die Anzeige. */
    val label: String,
    val scope: MemScope,
    val confidence: Float,
    val importance: Float,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long,
    val expiresAt: Long?,
    /** Ausdruecklich gesagt ("merk dir ...", "ich liebe ...") -> altert sehr langsam. */
    val explicit: Boolean
)

interface MemoryStore {
    fun all(): List<MemoryItem>
    /** Speichert (id == 0 -> neu) und liefert den gespeicherten Eintrag. */
    fun put(item: MemoryItem): MemoryItem
    fun delete(id: Long)
    fun clear()
}

class InMemoryStore : MemoryStore {
    private val items = LinkedHashMap<Long, MemoryItem>()
    private var next = 1L
    override fun all(): List<MemoryItem> = items.values.toList()
    override fun put(item: MemoryItem): MemoryItem {
        val saved = if (item.id == 0L) item.copy(id = next++) else item
        items[saved.id] = saved
        return saved
    }
    override fun delete(id: Long) { items.remove(id) }
    override fun clear() { items.clear() }
}

internal data class MemCandidate(
    val kind: MemKind,
    val label: String,
    val key: String,
    val scope: MemScope,
    val confidence: Float,
    val importance: Float,
    val explicit: Boolean,
    val ttlMs: Long?
)

/** Erkennt aus freiem Text, was merkenswert ist ("ich mag Hardtekk", "heute keinen Hardtekk", "merk dir ..."). */
internal object MemoryExtractor {
    private const val HOUR = 3_600_000L

    private val QUANT = setOf(
        "viel", "viele", "sehr", "total", "richtig", "so", "auch", "eigentlich", "den", "die", "das", "ein", "eine", "einen",
        "musik", "songs", "song", "lieder", "sachen", "zeug", "sowas", "immer", "fast", "echt", "voll", "gerne", "gern",
        "wirklich", "meistens", "bock", "auf", "keinen", "keine", "kein", "mal", "bisschen", "bissl"
    )
    private val JUNK = setOf("mehr", "es", "nicht", "nix", "nichts", "heute", "jetzt", "dich", "mich", "dir", "was", "wenn", "dass", "ihn", "sie", "ja", "nein")

    private val EXPLICIT_RE = Regex("""^(?:bitte\s+)?merk(?:e)?(?:'?s)?\s+dir\s*(?:mal\s*)?,?\s*(?:dass\s+|das\s+)?(.*)$""")
    private val TODAY_RE = Regex("""^(?:heute|gerade|jetzt|diese\s+session|fuer\s+heute)\b[\s,]*(?:aber\s+|bitte\s+|doch\s+)?(.*)$""")

    private val DISLIKE = listOf(
        Regex("""\bich\s+(?:mag|hoer(?:e)?)\s+(?:heute\s+|gerade\s+|eigentlich\s+)?(?:kein\w*|nicht)\s+(.+)$"""),
        Regex("""\bich\s+(?:hasse|verabscheue)\s+(.+)$"""),
        Regex("""^nie\s+wieder\s+(.+)$"""),
        Regex("""^(.+?)\s+(?:ist|sind)\s+(?:nicht\s+mein\s+ding|nix\s+fuer\s+mich|nicht\s+meins|scheisse|kacke|mist)$""")
    )
    private val DISLIKE_TODAY = Regex("""^(?:kein\w*|ohne)\s+(.+)$""")
    private val DISLIKE_TODAY_ICH = Regex("""\bich\s+(?:will|moechte|brauch(?:e)?)\s+(?:heute\s+|gerade\s+)?(?:kein\w*|nicht)\s+(.+)$""")
    private val LABEL_FILLER = setOf("eigentlich", "echt", "total", "voll", "so", "auch", "wirklich", "richtig", "sehr", "fast", "immer", "meistens", "gerne", "gern")
    private val LIKE = listOf(
        Regex("""\bich\s+(?:mag|liebe|feier|vergoettere)\s+(.+)$"""),
        Regex("""\bich\s+steh(?:e)?\s+auf\s+(.+)$"""),
        Regex("""\bich\s+hoer(?:e)?\s+(?:eigentlich\s+)?(?:fast\s+)?(?:meistens|immer|oft|gerne|gern)\s+(.+)$"""),
        Regex("""\bhaette\s+ich\s+(?:heute\s+)?gerne\s+(.+)$"""),
        Regex("""\b(?:bock|lust)\s+auf\s+(.+)$""")
    )
    private val STRONG_WORDS = listOf("eigentlich", "immer", "meistens", "liebe", "fast", "vergoettere")

    private fun clean(p: String): Pair<String, String>? {
        val s = p.substringBefore(',').trim().trim('.', '!', '?', ' ').replace(Regex("""\s+(?:mehr|zu\s+hoeren|hoeren|an|aus)$"""), "").trim()
        val words = s.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 5) return null
        val keyWords = words.filter { it !in QUANT }
        if (keyWords.isEmpty() || keyWords.all { it in JUNK }) return null
        val key = keyWords.joinToString(" ")
        if (key.length < 3) return null
        val label = words.dropWhile { it in LABEL_FILLER }.joinToString(" ").ifBlank { s }
        return label to key
    }

    private fun displayLabel(normalizedLabel: String, original: String): String {
        val target = normalizedLabel.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (target.isEmpty()) return normalizedLabel
        val originalWords = original.split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.isNotBlank() }
        for (start in originalWords.indices) {
            if (start + target.size > originalWords.size) break
            val candidate = originalWords.subList(start, start + target.size)
                .map { TextUtil.norm(it) }
            if (candidate == target) return originalWords.subList(start, start + target.size).joinToString(" ")
        }
        return normalizedLabel
    }

    fun extract(raw: String): List<MemCandidate> {
        var body = TextUtil.norm(raw).trim().trimEnd('.', '!', '?').trim()
        var explicit = false
        EXPLICIT_RE.find(body)?.let { explicit = true; body = it.groupValues[1].trim() }
        if (explicit) {
            // Nebensatz-Wortstellung: "dass ich Klavier mag" -> "ich mag Klavier"
            body = body.replace(Regex("""^ich\s+(.+?)\s+nicht\s+mag$"""), "ich mag kein $1")
                .replace(Regex("""^ich\s+(.+?)\s+(mag|liebe)$"""), "ich $2 $1")
        }
        var today = false
        TODAY_RE.find(body)?.let { today = true; body = it.groupValues[1].trim() }
        if (body.isBlank()) return emptyList()

        val scope = if (today) MemScope.EPHEMERAL else MemScope.LONG
        val ttl = if (today) 14 * HOUR else null

        // Ablehnung zuerst ("ich mag keine X" darf nicht als "ich mag ..." durchrutschen)
        val dislikeHit = DISLIKE.firstNotNullOfOrNull { re -> re.find(body)?.groupValues?.get(1) }
            ?: if (today) (DISLIKE_TODAY.find(body) ?: DISLIKE_TODAY_ICH.find(body))?.groupValues?.get(1) else null
        if (dislikeHit != null) {
            val (label0, key) = clean(dislikeHit) ?: return emptyList()
            val label = displayLabel(label0, raw)
            // Stimmungen ("keine traurigen Songs") regelt der Sitzungs-Ausschluss des Assistenten, nicht das Gedaechtnis
            if (IntentEngine.moodFor(key) != null) return emptyList()
            val strong = explicit || today || body.contains("hasse") || body.contains("nie wieder")
            return listOf(MemCandidate(MemKind.DISLIKE, label, key, scope, if (strong) 0.9f else 0.75f, 0.7f, explicit || strong, ttl))
        }
        val likeHit = LIKE.firstNotNullOfOrNull { re -> re.find(body)?.groupValues?.get(1) }
        if (likeHit != null) {
            val (label0, key) = clean(likeHit) ?: return emptyList()
            val label = displayLabel(label0, raw)
            val strong = explicit || STRONG_WORDS.any { body.contains(it) }
            val conf = if (today) 0.5f else if (strong) 0.9f else 0.6f
            return listOf(MemCandidate(MemKind.LIKE, label, key, scope, conf, if (strong) 0.7f else 0.4f, explicit || strong, ttl))
        }
        if (explicit) {
            val (label0, key) = clean(body) ?: return emptyList()
            val label = displayLabel(label0, raw)
            return listOf(MemCandidate(MemKind.FACT, label, key, MemScope.LONG, 0.95f, 0.6f, true, null))
        }
        return emptyList()
    }
}

class MemoryHints(val avoid: List<MemoryItem>, val prefer: List<MemoryItem>)

class MemoryEngine(private val store: MemoryStore, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private companion object { const val DAY = 86_400_000f }

    private inline fun <T> safe(default: T, block: () -> T): T =
        try { block() } catch (e: Exception) { default }

    private fun opposite(k: MemKind) = when (k) { MemKind.LIKE -> MemKind.DISLIKE; MemKind.DISLIKE -> MemKind.LIKE; else -> null }

    internal fun remember(c: MemCandidate): MemoryItem? = safe(null) {
        val now = clock()
        val items = store.all()
        val opp = opposite(c.kind)
        // Dauerhaft umentschieden ("mag ich" -> "mag ich nicht"): die alte Aussage verschwindet, die neue gewinnt
        if (opp != null && c.scope == MemScope.LONG) {
            items.filter { it.kind == opp && it.key == c.key && it.scope == MemScope.LONG }.forEach { store.delete(it.id) }
        }
        val expires = c.ttlMs?.let { now + it }
        val same = items.firstOrNull { it.kind == c.kind && it.key == c.key && it.scope == c.scope }
        val item = if (same != null) {
            // Wiederholte Aussage staerkt den Eintrag, aber nie auf einmal auf 1.0
            same.copy(
                label = c.label,
                confidence = min(1f, max(same.confidence, c.confidence) + 0.1f),
                importance = max(same.importance, c.importance),
                updatedAt = now, expiresAt = expires ?: same.expiresAt,
                explicit = same.explicit || c.explicit
            )
        } else {
            MemoryItem(0L, c.kind, c.key, c.label, c.scope, c.confidence, c.importance, now, now, now, expires, c.explicit)
        }
        store.put(item)
    }

    /** Wirksame Sicherheit: aeltere, nicht ausdrueckliche Aussagen verblassen; Kurzzeit-Eintraege verfallen. */
    fun effective(i: MemoryItem, now: Long = clock()): Float {
        val exp = i.expiresAt
        if (exp != null && now >= exp) return 0f
        val days = ((now - i.updatedAt) / DAY).coerceAtLeast(0f)
        val half = when { i.scope == MemScope.EPHEMERAL -> 1f; i.explicit -> 365f; else -> 60f }
        return i.confidence * 0.5f.pow(days / half)
    }

    fun prune() = safe(Unit) {
        val now = clock()
        store.all().filter { effective(it, now) < 0.1f }.forEach { store.delete(it.id) }
    }

    fun active(kind: MemKind, minEffective: Float = 0.3f): List<MemoryItem> = safe(emptyList()) {
        val now = clock()
        store.all().filter { it.kind == kind && effective(it, now) >= minEffective }
            .sortedByDescending { effective(it, now) * it.importance }
    }

    /** Fuer die Empfehlung: was soll gemieden bzw. bevorzugt werden. */
    fun hints(): MemoryHints = MemoryHints(
        avoid = active(MemKind.DISLIKE, 0.5f),
        prefer = active(MemKind.LIKE, 0.5f)
    )

    /** Verhalten beobachtet: Der Nutzer hat in dieser Lage dieses Genre gewaehlt. Wiederholung staerkt die Verknuepfung langsam. */
    fun learnPattern(emotion: Mood, target: String): MemoryItem? {
        val t = TextUtil.norm(target).trim()
        if (t.length < 3) return null
        val key = emotion.name.lowercase() + ">" + t
        return remember(MemCandidate(MemKind.PATTERN, target, key, MemScope.LONG, 0.2f, 0.3f, false, null))
    }

    /** Staerkstes gelerntes Muster fuer diese Lage - erst ab genug Wiederholungen (minEffective). */
    fun patternFor(emotion: Mood, minEffective: Float = 0.4f): MemoryItem? {
        val prefix = emotion.name.lowercase() + ">"
        return active(MemKind.PATTERN, minEffective).firstOrNull { it.key.startsWith(prefix) }
    }

    fun forget(topic: String): Int = safe(0) {
        val q = TextUtil.norm(topic).trim()
        if (q.length < 2) return@safe 0
        val hit = store.all().filter { it.key.contains(q) || q.contains(it.key) || TextUtil.norm(it.label).contains(q) }
        hit.forEach { store.delete(it.id) }
        hit.size
    }

    fun forgetAll(): Int = safe(0) { val n = store.all().size; store.clear(); n }

    fun isEmpty(): Boolean = safe(true) { store.all().isEmpty() }

    /** Verstaendliche Zusammenfassung fuer "Was weisst du ueber mich?". */
    fun describe(): String = safe("Mein Gedächtnis ist gerade nicht erreichbar - die Musik läuft aber normal.") {
        val now = clock()
        val live = store.all().filter { effective(it, now) >= 0.15f }
        if (live.isEmpty()) return@safe "Bisher hab ich mir noch nichts über dich gemerkt. Sag z. B. \"Ich mag Hardtekk\" oder \"Merk dir, dass ich nachts ruhige Musik höre\"."
        fun fmt(i: MemoryItem) = i.label + if (effective(i, now) < 0.55f && !i.explicit) " (noch unsicher)" else ""
        val likes = live.filter { it.kind == MemKind.LIKE && it.scope == MemScope.LONG }
        val dislikes = live.filter { it.kind == MemKind.DISLIKE && it.scope == MemScope.LONG }
        val today = live.filter { it.scope != MemScope.LONG && it.kind != MemKind.FACT }
        val facts = live.filter { it.kind == MemKind.FACT }
        val patterns = live.filter { it.kind == MemKind.PATTERN }
        buildString {
            append("Das hab ich mir gemerkt (nur lokal auf deinem Gerät):")
            if (likes.isNotEmpty()) append("\n👍 Du magst: ").append(likes.joinToString(", ") { fmt(it) })
            if (dislikes.isNotEmpty()) append("\n👎 Eher nicht: ").append(dislikes.joinToString(", ") { fmt(it) })
            if (today.isNotEmpty()) append("\n⏳ Nur für heute: ").append(today.joinToString(", ") { (if (it.kind == MemKind.DISLIKE) "kein " else "") + it.label })
            if (facts.isNotEmpty()) append("\n📝 Sonstiges: ").append(facts.joinToString(", ") { it.label })
            if (patterns.isNotEmpty()) append("\n🔗 Mir fällt auf: ").append(patterns.joinToString("; ") {
                emotionPhrase(it.key.substringBefore('>')) + " hörst du oft " + it.label + (if (effective(it, now) < 0.5f) " (noch unsicher)" else "")
            })
            append("\n\nSag \"Vergiss …\", um etwas zu löschen.")
        }
    }
}
