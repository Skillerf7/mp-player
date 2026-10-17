# Secret Player AI Brain — Fortschritt

## Phase 1 — Analyse ✅
Siehe `docs/AI_BRAIN_PHASE1_ANALYSIS.md`.

## Phase 2 — Gedächtnis & Kontext ✅ (erweitert)
- Bestehendes Memory (Scopes, Confidence, TTL) beibehalten
- Neu: `PreferenceSignals.kt` / `PreferenceExtractor`
  - Extrahiert Bass/Tempo/Energy/Mood/Vocal aus Freitext
  - „Heute…“ → EPHEMERAL, sonst LONG
  - Mapping auf Memory-Kandidaten
- `Assistant.remember` + stilles `learnFromFeedback` in `route()`

## Phase 3 — Internes Modell ✅ (Player-intern, kein Cloud)
- Neu: `llm/PlayerBrain.kt`
  - Kombiniert TrainedIntentModel + Embedded + Prefs + Memory + ConversationState
  - Liegt **im Player-Code**, Gewichte in Assets (`intent_model.bin`)
- `LlmCoordinator` priorisiert PlayerBrain, status()-Hilfe
- `NativeLlmSlot` bleibt optionaler GGUF-Stub (kein Fake-LLM)

## Phase 5 — PersonalLearning ✅ (teilweise)
- `PersonalSignals.dimensionAffinity`
- `mergeDimensions()` für Chat-Signale
- scoreBonus nutzt Dimensions-Bias nur schwach

## Noch offen / bewusst Fallback
| Thema | Status |
|---|---|
| Generatives 1B–3B GGUF in APK | **Nein** (zu groß für S20 FE / APK) — Slot vorbereitet |
| Echtes Fine-Tuning des Sprachmodells | **Nein** (nicht sinnvoll pro Message) |
| Ranking-Modell online lernen | HybridScore + PersonalLearning (laufend), kein NN |
| UI: Modellstatus / Lernstatistik | nächste Iteration |
| Vollständiger Gradle-Build hier | Umgebung ohne Android SDK — Tests als JVM-Unit ausgelegt |

## Geänderte / neue Dateien
- `docs/AI_BRAIN_PHASE1_ANALYSIS.md` (neu)
- `docs/AI_BRAIN_PROGRESS.md` (neu)
- `ai/PreferenceSignals.kt` (neu)
- `ai/llm/PlayerBrain.kt` (neu)
- `ai/llm/LlmCoordinator.kt` (erweitert)
- `ai/llm/NativeLlmSlot.kt` (klarstellung)
- `ai/PersonalLearning.kt` (dimensionAffinity)
- `ai/Assistant.kt` (learnFromFeedback, Kontext-Wiring)
- `test/.../PreferenceSignalsTest.kt` (neu)
- `test/.../PlayerBrainTest.kt` (neu)

## Modell intern
**Aktives „Sprachmodell“ von Secret Player:**  
`PlayerBrain` + `TrainedIntentModel` (`assets/ml/intent_model.bin`, Multi-Label LogReg, projekteigenes Training) + Heuristik-Fallback.  
Alles offline, im Player, kein Upload.

## Phase 4 — KI-Status & Lernstatistik ✅
- `AiSettingsScreen` unter Einstellungen → „KI-Status & Lernen“
- Status: PlayerBrain, Intent-Modell, Offline, Memory, Personal Learning
- Statistik-Felder aus `AiStatsCollector` (echte Quellen, 0 ohne Session)
- Datenschutz-Schalter: Learning, Memory, Gespräch – deaktiviert speichert nichts Neues
- `AiPrivacyStore.memoryEnabled` ergänzt
- `MemoryEngine.countAll()`

## Phase 5 — Empfehlungs-Engine ✅
- `HybridScore.dimensionFit` (Bass/Energy/Tempo/Mood, Cap ±0.18)
- In `Recommender.build` eingebunden
- Fehlende Analyse/Affinität = neutral
- Tests: `HybridScoreDimensionTest`
