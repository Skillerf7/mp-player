package com.mp.player.ai

/**
 * Gespraechszustand: haelt fest, in welcher Lage der Nutzer gerade ist (Gefuehl, Thema, Ziel), damit Kurzaussagen
 * wie "mach", "ja" oder "aber nicht traurig" richtig eingeordnet werden - auch ohne das Wort "Musik".
 * Lange Gespraeche werden verdichtet: aelteste Nachrichten fliegen raus, Gefuehl/Thema/Ziel bleiben.
 */
class ConversationState(private val clock: () -> Long = { System.currentTimeMillis() }) {
    var emotion: Mood? = null; private set
    var strong: Boolean = false; private set
    /** Staerke des Gefuehls 0..1 ("ein bisschen traurig" ~0.35, "komplett am Boden" ~0.9). */
    var intensity: Float = 0f; private set
    var topic: String? = null; private set
    var goal: Mood? = null; private set
    /** Der Nutzer hat sich gerade ausgekotzt und noch keine Musik bekommen. */
    var venting: Boolean = false; private set
    var ventCount: Int = 0; private set
    /** Aktueller Gesprächsmodus (CHAT/MUSIC/SUPPORT/ASSIST). */
    var mode: ConversationMode = ConversationMode.CHAT; private set
    /** Nutzer hat in dieser Session schon Inhalt geteilt (Thema/Emotion). */
    var hasSharedContent: Boolean = false; private set
    var updatedAt: Long = 0L; private set
    val wishEq = ArrayList<EqCommand>()
    private val recent = ArrayDeque<String>()
    private val topicsSeen = LinkedHashSet<String>()
    var summary: String = ""; private set

    // Themen-Stapel: das zuletzt besprochene Thema liegt vorn, aeltere bleiben zum Zurueckholen erhalten
    private val topicStack = ArrayDeque<Pair<Topic, Long>>()
    fun pushTopic(t: Topic) {
        topicStack.removeAll { it.first == t }
        topicStack.addFirst(t to clock())
        while (topicStack.size > MAX_TOPICS) topicStack.removeLast()
    }
    fun currentTopic(): Topic? = topicStack.firstOrNull()?.first
    fun topics(): List<Topic> = topicStack.map { it.first }
    /** Wie lange das Thema zuletzt aktiv war; null = nie besprochen. Aelteres ist ggf. veraltet (Kontext verfaellt). */
    fun topicAgeMs(t: Topic): Long? = topicStack.firstOrNull { it.first == t }?.let { clock() - it.second }

    fun setMode(m: ConversationMode) { mode = m; updatedAt = clock() }
    fun markShared() { hasSharedContent = true; updatedAt = clock() }
    fun isFresh(): Boolean = updatedAt != 0L && clock() - updatedAt < FRESH_MS

    fun note(text: String) {
        recent.addLast(text.take(200))
        if (recent.size > MAX_RECENT) compress()
    }

    private fun compress() {
        while (recent.size > KEEP_RECENT) recent.removeFirst()
        summary = buildSummary()
    }

    fun onEmotion(feel: Mood, topic: String?, strong: Boolean, intensity: Float = if (strong) 0.9f else 0.6f) {
        mode = ConversationMode.SUPPORT
        if (topic != null || strong) hasSharedContent = true
        if (!isFresh()) { ventCount = 0; this.intensity = 0f }
        this.intensity = maxOf(this.intensity, intensity.coerceIn(0f, 1f))
        this.emotion = feel
        this.strong = this.strong || strong
        if (topic != null) { this.topic = topic; topicsSeen.add(topic) }
        venting = true
        ventCount++
        updatedAt = clock()
    }

    fun onGoal(m: Mood?) { if (m != null) { goal = m; updatedAt = clock() } }

    fun onMusicStarted() { mode = ConversationMode.MUSIC; venting = false; ventCount = 0; wishEq.clear(); updatedAt = clock() }

    /** Welche Musik passt zur Lage? Ziel gewinnt vor Gefuehl. */
    fun musicMood(): Mood? = goal ?: when (emotion) {
        Mood.SAD -> Mood.SAD
        Mood.LONELY -> Mood.LONELY
        Mood.ANGRY -> Mood.ANGRY
        null -> null
        else -> Mood.CALM
    }

    fun takeWishEq(): List<EqCommand> { val l = wishEq.toList(); wishEq.clear(); return l }

    fun buildSummary(): String = buildString {
        if (topicsSeen.isNotEmpty()) append("Thema: ").append(topicsSeen.joinToString(", ")).append(". ")
        emotion?.let { append("Stimmung: ").append(it.label).append(if (strong) " (stark)" else "").append(". ") }
        goal?.let { append("Ziel: ").append(it.label).append(". ") }
    }.trim()

    fun recentCount() = recent.size

    private companion object {
        const val FRESH_MS = 45 * 60_000L
        const val MAX_RECENT = 30
        const val KEEP_RECENT = 12
        const val MAX_TOPICS = 6
    }
}
