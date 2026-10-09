package com.mp.player.ai

/** Gesprächsmodus – steuert, ob Musikaktionen oder reines Zuhören Priorität haben. */
enum class ConversationMode {
    /** Normale Unterhaltung ohne Musikaktion. */
    CHAT,
    /** Musikbezogene Planung (Draft, Genre, Mood). */
    MUSIC,
    /** Konkrete Player-Aktionen (Pause, Next, EQ…). */
    ASSIST,
    /** Emotionaler Support – Musik nur auf ausdrücklichen Wunsch. */
    SUPPORT
}
