# Bass / Höhen / PARAM 2 – Poweramp-Stack in Secret Player

Signalweg (wie im Poweramp-Screen-Recording: `DVC · EQ · PARAM 2 · TON · LMT`):

    PreAmp (+ Auto-Headroom) → 32-Band-EQ → TON (Bass/Höhen) → PARAM 2 → Limiter

## Aus dem Video abgelesen (Poweramp)
- PARAM 2: Band 1 Low-Shelf **100 Hz, +7,0 dB, Q 1,03** · Band 2 High-Shelf **8 kHz, +6,2 dB, Q 0,83**
- Tone: Bass und Höhen auf 100 %
- PreAmp: von −24 dB auf **−12,3 dB** gestellt, Limiter an
- Preset **"Poweramp Druck"** setzt PARAM 2 auf diese Werte; die Tone-Regler (Bass 3, Höhen 2) sind geschätzt, weil Poweramp die Tone-dB nicht anzeigt.

## TON (unabhängig von den 32 Bändern, ±15 dB-Regler)
- Bass: Low-Shelf 130 Hz (positive Regler ×1,5, effektiv max. 20 dB) + Sub-Shelf 50 Hz (45 %) + Punch-Peak 75 Hz (20 %), Sub/Punch nur bei Anhebung
- Höhen: High-Shelf **8 kHz, Q 0,83** (statt vorher 5 kHz: weniger schrill) + Air-Shelf 14 kHz (20 %, nur bei Anhebung)
- Shelf-Q wird jetzt wirklich benutzt (`Bq.set`, RBJ: alpha = sin(w)/(2Q))

## PARAM 2
Zwei frei einstellbare Shelves (Gain ±12 dB, Freq, Q) mit Poweramp-Standard 100 Hz / Q 1,03 und 8 kHz / Q 0,83. Wird mit gespeichert, alte Stände laden mit 0 dB.

## Auto-Headroom (gegen Über-/Untersteuern)
`Eq.autoComp` misst die tatsächliche Gesamt-Anhebung im Bereich 40 Hz – 12 kHz und senkt den Preamp um 95 % davon (max. −16 dB). Ein manuell gesetzter negativer Preamp wird angerechnet (keine doppelte Absenkung). Anzeige im EQ-Screen. Beispiel: "Poweramp Druck" → etwa −13,0 dB (Poweramp-Video: −12,3 dB).

Gemessen mit `tools/secret_dsp.py` (Python-Port der Kette) an bass-lastiger Musik (Peak 0,95) und Rosa Rauschen (−14 dBFS RMS): Limiter-Aktivität 0 %, Lautheit gegenüber Flat ±0,3 dB. Mit 85 % lag die Limiter-Aktivität bei bass-lastiger Musik bei 3–13 %.

## Geräte-Test
Keine physische Geräteprüfung in dieser Umgebung durchgeführt.
