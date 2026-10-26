# EXTREME-UPGRADE – Korrekturen (Code-Review, nicht kompiliert)

Gefunden und behoben im Vergleich zu SecretPlayer-COMPILE-FIX:

1. **Genre-Presets übersteuerten** (Deep Bass, Subbass, Rap, Hardtekk): Bass-Regler, Sub-Shelf,
   PARAM-2-Shelf und 32 Bänder stapeln sich. Rechnerisch +26…31 dB Spitzenanhebung bei 40 Hz,
   nach Preamp/Auto-Comp bis +10 dB Netto (Auto-Comp ist bei -16 dB gedeckelt).
   Jetzt ~ +19…21 dB Spitze und ~ +1 dB Netto. Test: `GenrePresetHeadroomTest`.
2. **ConstraintExtractor**: Normalisierung (`TextUtil.norm`) war durch `lowercase()` ersetzt worden;
   Schlüsselwörter und Text werden jetzt beide normalisiert (düster/duester, kräftig/kraeftig, wütend/wuetend).
   Vorher traf z. B. „düster“ nur zufällig, „kraeftig“ nie.
3. **BPM-Erkennung** las „um 20 Uhr“ als Ziel-BPM 20 und wertete dadurch alle Titel ab.
   Jetzt nur 60–300 BPM und nicht vor Uhr/Minuten/Sekunden/Prozent.
4. **Rückschritte repariert**: Genres „pop“/„classical“ wieder erkannt, `toPlayMood()` wieder da,
   Leer-Text liefert wieder leere Constraints.
5. Textfehler „kein Trefferquote“ → „keine Trefferquote“; veralteter Kommentar in `Assistant.kt` entfernt.

Nicht ausgeführt: `gradle test` / `assembleDebug` (kein Gradle in dieser Umgebung).

## Phase A/B – Audit-Ergebnis und Umsetzung (Code-Review, nicht kompiliert)

Tatsächlicher Zustand der KI (Stand EXTREME-UPGRADE):
- **Kein generatives Modell.** `NativeLlmSlot.interpret()` gibt immer `null` zurück (Klasse E, Stub). Die Sprachverarbeitung besteht aus
  Regeln (`IntentEngine`), trainiertem Intent-Klassifikator (`TrainedIntentModel`, `intent_model.bin`, Klasse A/B),
  `EmbeddedIntentBrain` (Heuristik), `ConstraintExtractor`/`ReasoningCore` (Regeln), `MemoryEngine`/SQLite und Recommender/Hybrid-Score.
- Kette: `Assistant` → `IntentEngine` → (nur bei Bedarf `LlmGate` → `LlmCoordinator` → `PlayerBrain`/`TrainedIntentModel`/`EmbeddedIntentBrain`)
  → `ReasoningCore.analyze` → `applyToRequest` → `Recommender.build` (+ `AdaptiveMusic.constraintDelta`).

Gefundene Lücke und Fix:
- Verneinungen wurden als **positive** Genres gespeichert („ohne Frenchcore“ → Frenchcore gewünscht), `excludeMoods` blieb immer leer.
  Jetzt: `MusicConstraints.excludeGenres`, Verneinungs-Erkennung pro Teilaussage (3-Wort-Fenster, „aber/sondern“ beenden den Bereich),
  Ausschlüsse landen in `QueueRequest.avoidTerms` (harter Filter), Merge: neuere Aussage gewinnt in beide Richtungen.
- „zwischen 170 und 190 BPM“ → Ziel 180 mit Toleranz ±10 (`bpmTolerance`), Scoring-Zonen skalieren mit der Toleranz.
- „dunkel/finster“ wurde nicht als Stimmung DARK erkannt.
- Tests: `NegationAndRangeTest` (11 Fälle).

Nicht umgesetzt (offen, ehrlich): lokales LLM-Backend, Embedding-Retrieval, Evaluations-Dashboard, Gerätemessung auf dem S20 FE,
Memory-Versionierung/Rollback. Nichts davon wurde gebaut oder gemessen.

## Phase B (Fortsetzung): Bass-Synonyme im EQ-Parser (Code-Review, nicht kompiliert)

- **Fehler behoben:** „Der Bass ist zu schwach“ / „zu wenig Bass“ wurde als **weniger** Bass gelesen („zu“ = Absenkung). Jetzt: „zu schwach/leise/wenig/flach/zahm“ = mehr.
- **Synonyme:** „Sub“, „Subwoofer“, „Low-End“, „Fundament“, „die Tiefen“ → Bass. „Tiefen“ und „Fundament“ nur zusammen mit einem Richtungswort
  (mehr/weniger/stärker/drücken …), damit „tiefe Gespräche“ kein EQ-Befehl wird.
- `ConstraintExtractor.wantDeepBass` erkennt zusätzlich „mehr Sub“, „Low-End“, „Fundament“.
- Tests: `EqParserSynonymTest` (10 Fälle). Nicht ausgeführt.
