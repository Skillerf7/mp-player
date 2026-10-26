# Secret Player – Offline AI + Deep Music Analysis

## Bestandsaufnahme (Kurz)

- DSP/EQ/Limiter/Playback/Queue/Hi-Res/Bluetooth: erhalten
- Analyse: LUFS, Peak, RMS, BPM+Konfidenz, Spektrum, Rhythmus, Energiekurve
- Embedding: 16-D DSP-Vektor (kein OpenL3), Genre-ONNX optional (Apache-2.0 Asset, oft ohne Weight-Datei)
- Assistent: IntentEngine + PlayerBrain + Memory + PersonalLearning – **kein** generatives LLM in der APK

## Qwen3-0.6B Instruct

| Punkt | Befund |
|-------|--------|
| Größe Q4 GGUF | ca. 0,4–0,5 GB Download |
| RAM inkl. KV | typisch 0,8–1,5 GB |
| S20 FE | möglich nur optional, Risiko Wärme/Akku neben Media3 |
| In APK | **Nein** – optionaler Import |
| Runtime | llama.cpp/JNI oder vergleichbar – **nicht** integriert als Fake |
| Musikauswahl | **immer** lokal über Library/Recommender/DB, nie „LLM sucht Song“ |

Fallback ohne Modell: PlayerBrain + TrainedIntentModel + Heuristik (bestehend).

## Deep Analysis – Änderungen in diesem Schritt

1. **Subbass-Energie** (< 80 Hz, FFT-Messung) in SpectralMeter → AnalysisResult → DB v10
2. **FEATURE_VERSION = 4** (Cache-Invalidierung bei Schema-Änderung)
3. **AnalysisInsight** – MEASURED / ESTIMATED / UNKNOWN
4. **AudioEmbedding v2** – Bass-Slot mischt Subbass
5. **TrackSimilarity** – Subbass in spektraler Nähe
6. **HybridScore** – Bass-Dimension nutzt Subbass+Bass
7. **ModelManager** – ehrliche Qwen/GGUF-Beschreibung

Nicht erfunden: Instrumentenerkennung, „präzise Stimmung aus Audio“, Cloud-URLs.

## APK bauen

```bash
# Mit Android SDK / CI:
gradle :app:assembleDebug
# Artifact: app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions: Workflow `Build APK` (Gradle 8.7, AGP 8.5.2, Java 17).
