# ONNX Genre Classifier (GTZAN)

## Model
- **File:** `assets/ml/model_quantized.onnx` (~23 MB)
- **Source:** [onnx-community/Musical-genres-Classification-Hubert-V1-ONNX](https://huggingface.co/onnx-community/Musical-genres-Classification-Hubert-V1-ONNX)
- **Original:** [SeyedAli/Musical-genres-Classification-Hubert-V1](https://huggingface.co/SeyedAli/Musical-genres-Classification-Hubert-V1) (Apache-2.0)
- **SHA256:** f26299dd32a5b77fe343e525f8369dae2b18600e1cdf80bb33a4a9efc661af98
- **Runtime:** `com.microsoft.onnxruntime:onnxruntime-android:1.19.2`

## Labels (only these)
blues, classical, country, disco, hiphop, jazz, metal, pop, reggae, rock

**Not** claimed: Deutschrap, Trap, Drill, Emotional Rap, Hardtekk as separate ML classes.

## Input
16 kHz mono float, mean-variance normalized, max 30 s segment. Titel ab ca. 40 s werden aus der Mitte
analysiert (nicht vom Intro), kuerzere ab Anfang. Das 30-s-Limit gilt in Quell-Samples, nicht in 16-kHz-Samples.

## Verdrahtung
- `Analysis.kt`: ML-Genre nach jeder erfolgreichen Analyse **und** als Nachlauf fuer bereits analysierte Titel
  ohne Ergebnis (`ml_genre` je Modellversion; leerer Marker = "kein Ergebnis", wird nicht endlos neu versucht).
- `LibraryDb.mlGenresAll()` -> `PlayerTools.loadMlGenres()` -> `GenreProfile.build(..., mlGenres)`.
- `GenreProfile.evidence()/attributions()` kennen `GenreSource.AUDIO_ML`; `PlaylistProfile`/`TrackProfile` nutzen es mit.
- Analyse-Bildschirm zeigt `LocalOnnxBridge.statusLine()`.

## Priority
User correction > file tag > playlist > ML prediction > heuristics

ML-Evidenz = Konfidenz x 0.8, gedeckelt bei 0.72, nur ab roher Konfidenz 0.25. Die strenge Genre-Schwelle des
Recommenders (0.68) erreichen damit nur sehr sichere Treffer (roh >= 0.85); alles darunter wirkt nur im Scoring.
