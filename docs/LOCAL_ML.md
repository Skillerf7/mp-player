# Local ML / Embeddings

## Was wirklich läuft

| Komponente | Status |
|------------|--------|
| DSP-Messungen (BPM, LUFS, Spectral, Rhythm) | aktiv |
| Feature-Embedding 16-D aus Messwerten (`AudioEmbedding`) | aktiv, offline |
| Cosine-Ähnlichkeit in `TrackSimilarity` | aktiv |
| ONNX Runtime + Modell-Binaries | **nicht** gebündelt |
| Cloud / Download | keine |

## Warum kein ONNX-Modell in dieser Version

1. Kein lizenziertes, kleines, Android-taugliches Genre-/Embedding-Gewicht im Repo geprüft und freigegeben.
2. Leere Runtime-Dependency ohne Modell bläht die APK auf und täuscht Fähigkeiten vor.
3. `LocalOnnxBridge` prüft nur Assets; Inference bleibt `false`, bis Modell + Runtime bewusst ergänzt werden.

## Assets für spätere Integration

- `assets/ml/audio_embed.onnx`
- `assets/ml/genre.onnx`

Dann: `onnxruntime-android` Dependency, Preprocessing (Mel-Spektrogramm), Versionierung in DB.

## Feature-Embedding-Herkunft

Vektor aus: BPM, BPM-Konfidenz, Lautheit, Peak, Dynamik, Centroid, Rolloff, Flux, Flatness, Bass/Mid/High, Rhythmus, Energie Intro/Mid/Late. L2-normalisiert. `AudioEmbedding.VERSION = 1`.
