# Secret Player – Local Audio Intelligence

## Offline guarantee
- No network calls for genre, mood, or recommendations.
- No model download at runtime.
- Knowledge base: `assets/knowledge/music_kb.json` (bundled).

## What is measured (audio decode)
- BPM + confidence, half/double candidates
- LUFS-like loudness, peak, RMS
- Spectral centroid/rolloff/flux/flatness, bass/mid/high
- Rhythm regularity, energy intro/mid/late

## What is derived (heuristic, not ML)
- `AudioCharacter`: energy, valence, danceability with explicit low–medium confidence
- Valence is **not** an emotion classifier

## What is NOT implemented
- Essentia (AGPL / NDK size)
- ONNX genre models (none bundled)
- Cloud APIs
- Key detection / vocal detection ML

## Genre provenance
`GenreAttribution` + `GenreSource`: USER_CORRECTION > FILE_TAG > PLAYLIST > ARTIST_PRIOR > AUDIO_HEURISTIC

## Hybrid ranking
`HybridScore.explain` / Recommender weights: mood, genre profile, audio energy, personal signals, skip penalty, novelty.

## Analysis lifecycle
- LIGHT analysis on demand, background, cancellable
- Failed analyses: retry with backoff, not counted as success
- `featureVersion` invalidates on algorithm change
