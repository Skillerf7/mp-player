# Secret Player AI Brain — Phase 1 Bestandsanalyse

Stand: Analyse des vorhandenen Codes in `app/src/main/java/com/mp/player/ai/`.

## Was bereits funktioniert

| Komponente | Status | Art |
|---|---|---|
| `Assistant.kt` (~1488 Z.) | Produktiv | Orchestrierung: Intent → Tools → Persona, Pending-Bestätigungen, Fehlerisolation von Playback |
| `IntentEngine` / Brain | Produktiv | Regel-/Heuristik-Parser für deutsche Befehle |
| `TrainedIntentModel` | Echte Inferenz | Multi-Label LogReg (~72 KB) in `assets/ml/intent_model.bin`, Hashing-Features, offline |
| `EmbeddedIntentBrain` | Heuristik | Fallback-Interpreter für StructuredMusicIntent |
| `NativeLlmSlot` | **Stub** | `interpret()` gibt immer `null` zurück — kein natives Backend |
| `LlmCoordinator` | Produktiv | Kette: Trained → Embedded → Native; Gate über `LlmGate` |
| `LlmGate` | Produktiv | LLM nur bei komplexen/unklaren Phrasen |
| `Memory` + `MemoryDb` | Produktiv | SQLite, LIKE/DISLIKE/FACT/PATTERN, Scope EPHEMERAL/SESSION/LONG, Confidence, TTL, Extractor |
| `ConversationState` | Produktiv | Emotion, Topic-Stack, Mode, Kompression, Summary |
| `PersonalLearning` | Produktiv | Play/Skip/Accept/Reject → Affinitäten, Score-Bonus −0.3…+0.3, **kein** Retrain von intent_model.bin |
| `HybridScore` / `Recommender` | Produktiv | Hybrides Ranking |
| `GenreOnnxClassifier` | ONNX Runtime | Genre-Modell offline |
| `Persona` / `ResponsePlanner` | Produktiv | Antwortstil |
| Privacy | Grundsolide | Kein Netzwerk im AI-Paket dokumentiert |

## Lücken (Priorität)

1. **Kein generatives Sprachmodell** — NativeLlmSlot ist leer; „KI“ = Intent-Klassifikator + Heuristik.
2. **Situative vs. langfristige Prefs** nur teilweise (Memory-Scopes existieren, werden nicht überall konsequent genutzt).
3. **Chat → Musiksignale** (Bass/Tempo/Stimmung aus Freitext) nur schwach verdrahtet.
4. **Unsicherheit** wird selten an den Nutzer kommuniziert.
5. **Empfehlungsbegründung** und Lernstatistik in UI begrenzt.
6. **Echtes Fine-Tuning** des Sprachmodells: bewusst nicht vorhanden und auf S20 FE nicht sinnvoll pro Message.

## Entscheidung Modell (S20 FE, „intern im Player“)

- 1B–3B GGUF (Q4) = ca. 0,7–2 GB → **nicht** in APK, RAM-kritisch neben Media3.
- Gewählt: **Player-internes Modell** = `TrainedIntentModel` (Assets) + erweiterte `PlayerBrain` (Kotlin, im App-Code) + optionales späteres GGUF über Datei-Import.
- Lizenz Intent-Modell: projekteigenes Training (siehe `ml/scripts`).
- ONNX Genre: Apache-2.0 (NOTICE in assets).

## Architektur-Prinzip (beibehalten)

Playback > KI. Fehler in AI dürfen Player nicht crashen. Lernen ≠ Model-Retrain. Memory und Ranking-Updates sind „laufendes Lernen“, kein „Selbsttraining des LLM“.
