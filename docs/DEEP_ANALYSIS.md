# Deep Song Analysis (lokal)

## Was gemessen wird (MEASURED)
- LUFS, Peak, RMS, Crest
- 8 Frequenzbänder Sub→Air
- Spectral bandwidth, brightness
- Onset-Dichte, Punch/Transienten
- Silence-Anteil, Dynamikvariation, Drop-Jump
- Energie Intro/Mid/Late

## Was geschätzt wird (ESTIMATED)
- BPM + Konfidenz (inkl. Half/Double)
- Rhythmus/Groove
- Tonart-Schätzung (Key?) – unsicher
- Harmonizität, Vocal-Presence (nur Präsenzband)

## Nicht behauptet
- Instrumentenerkennung, Genre als Fakt, Sänger-ID

## Speicherung
- Basis: `analysis` Tabelle
- Deep JSON: `analysis_deep`
- FEATURE_VERSION = 5 → Neuanalyse bei Update

## Integration
- Läuft im gleichen Decoder-Loop wie Basisanalyse
- UI: Analyse-Screen + Player Info/Tags
