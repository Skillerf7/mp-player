package com.mp.player.ai

/**
 * Antwort-Planung ohne LLM: wählt Antworttyp und Varianten aus Kontext,
 * verhindert stumpfe Wiederholungen von „Klar, lass quatschen“.
 */
object ResponsePlanner {
    enum class Kind {
        ACKNOWLEDGE, EMPATHIZE, LISTEN, CLARIFY, SUGGEST_MUSIC, CONFIRM, EXECUTE, SUMMARIZE
    }

    data class Plan(
        val kind: Kind,
        /** Wenn true: keine Musik-Chips anbieten. */
        val pureChat: Boolean
    )

    fun plan(
        mode: ConversationMode,
        venting: Boolean,
        alreadyShared: Boolean,
        userAskedForChat: Boolean,
        userAlreadySaidThat: Boolean
    ): Plan {
        if (userAlreadySaidThat) return Plan(Kind.ACKNOWLEDGE, pureChat = true)
        if (mode == ConversationMode.SUPPORT || (venting && userAskedForChat)) {
            return if (alreadyShared) Plan(Kind.LISTEN, pureChat = true)
            else Plan(Kind.EMPATHIZE, pureChat = true)
        }
        if (mode == ConversationMode.CHAT) return Plan(Kind.LISTEN, pureChat = true)
        if (mode == ConversationMode.MUSIC) return Plan(Kind.SUGGEST_MUSIC, pureChat = false)
        return Plan(Kind.CLARIFY, pureChat = false)
    }

    fun isAlreadySaidPhrase(norm: String): Boolean {
        val t = norm.lowercase()
        return listOf(
            "hab ich gesagt", "hab ich doch gesagt", "hab ich dir gesagt", "schon gesagt",
            "gerade gesagt", "eben gesagt", "schon erzaehlt", "hab ich erzaehlt",
            "weisst du doch", "kennst du doch", "wie gesagt"
        ).any { t.contains(it) }
    }
}
