# Weiterbau nach Phase 3

## Neu
1. Analyse drosselt zwischen Tracks bei laufender Wiedergabe (~450 ms)
2. `listen_totals` – echte Hörzeit (ms), ab 5 s Segment
3. Info-Dialog zeigt „Hörzeit gesamt“
4. Widget dekodiert Cover mit inSampleSize (weniger OOM)
5. Cover-Picker + Widget + Transparent (vorher)

## Test auf S20 FE
- Widget: Startbildschirm → Widgets → Secret Player
- Track 40 s hören → Info → Wiedergaben + Hörzeit
- Analyse starten, Musik laufen lassen → keine Aussetzer
- Cover-Menü → Online-Cover → bei mehreren Treffern auswählen
