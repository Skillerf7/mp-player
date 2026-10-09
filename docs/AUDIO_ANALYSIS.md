# Secret Player – Audio Analysis

## Status (Phase 1 – implementiert)

**Offline**, kein Cloud, kein Essentia in der APK.

### Was läuft (LIGHT)

Während des bestehenden MediaCodec-Decode-Passes (max. 5 Min):

| Feature | Quelle | Fake? |
|---------|--------|-------|
| LUFS | ITU-R BS.1770 (K-Weighting + Gating) | Nein |
| Peak / RMS dBFS | Sample-Peak / RMS | Nein |
| BPM + Confidence | Onset-Hüllkurve + Autokorrelation (BpmCore v2) | Schätzung |
| Spectral centroid / rolloff | FFT 512 @ ~11 kHz | Nein (DSP) |
| Spectral flux / flatness | FFT | Nein (DSP) |
| Bass / Mid / High energy | Band-Anteile 0–250 / 250–4k / >4k Hz | Nein (DSP) |
| Dynamic range | peak − RMS | Nein |

Cache: SQLite `analysis` + `feature_version` (aktuell **2**).

### AnalysisLevel

- **LIGHT** – aktuell implementiert (Scan / Hintergrund)
- **NORMAL** – geplant (Beat-Positionen, Key/HPCP, Danceability) – noch nicht
- **DEEP** – geplant (optional ML) – **nicht** in APK

---

## Essentia + MAEST – Bewertung (noch nicht integriert)

| Punkt | Bewertung |
|-------|-----------|
| **Lizenz Essentia** | **AGPL-3.0** – Linken würde AGPL-Pflichten auf die App ziehen. Play-Store / proprietär riskant ohne MTG-Proprietary-Lizenz. |
| **Android** | NDK / prebuilt libs möglich, APK-Größe steigt stark. |
| **MAEST / Discogs-400/519** | Modelle in essentia-models; oft **zehn–hunderte MB**. |
| **S20 FE** | Deep parallel zu Playback = Thermik/Akku-Risiko. |
| **Entscheidung Phase 1** | **Kein** Essentia/MAEST in der APK. DSP zuerst. DEEP erst nach Lizenz + optionalem Download + ONNX Runtime Mobile. |

### Empfohlene DEEP-Strategie (später)

1. Modelle **nicht** in APK → optionaler Download.
2. ONNX Runtime Mobile (Apache-2.0) statt Essentia-AGPL für Inferenz, **wenn** Modell-Lizenz ok.
3. 5–30 s Segmente, nicht ganzer Mix.
4. Cache nach `modelVersion` + Datei-Hash.
5. Nicht im Playback-Hotpath.

---

## Genre / Mood

BPM und Spectral Features sind **Evidence**, kein Genre.

Priorität: User-Override → Metadata → Playlist → (später) ML → Heuristik.

Mood getrennt von Genre.

## Bekannte Grenzen

- Kein Key/Scale/Chords in Phase 1
- Kein Audio-Embedding
- Kein MAEST Multi-Label Genre
- Spektral = Mittel über max. 5 Min (lange Mixes = partial)
- BPM Half/Double-Time bleibt schwierig

## Repair notes (2026-10-09)

- Spectral Hz uses **effectiveRate** after downsampling (fixes 48 kHz mis-binning).
- Failed analyses (`null`) are **not** written as success → retry next run.
- `featureVersion` invalidates old rows for re-analysis.
- BPM prior prefers 120–180; half/double pair prefers higher tempo when scores close; confidence reduced when ambiguous.
- Recommender: empty strict pool no longer collapses via `size/4 == 0`.
- Hi-Res: track change re-applies Store preference; sink failure is session-only fallback (preference kept).
