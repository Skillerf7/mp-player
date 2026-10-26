# Klang 1:1 an Poweramp angleichen (Messverfahren)

Ob Secret Player wie Poweramp klingt, lässt sich nur mit einer **Vergleichsaufnahme** prüfen.

## Aufnahme (beide Player gleich)
1. Gleicher Titel, gleiche Stelle (ca. 30 s ab einer festen Position), gleiche Systemlautstärke.
2. Poweramp mit deinen Einstellungen aufnehmen (Bildschirmaufnahme mit Ton, das hast du schon).
3. Secret Player aufnehmen mit **EQ-Schalter AUS** (Schalter oben im EQ-Screen).

## Auswerten
    python3 tools/eq_match.py poweramp.mp4 secret.mp4 --start 2 --dur 30
Das Skript richtet beide Aufnahmen zeitlich aus, vergleicht das Langzeit-Spektrum (1/3 Oktave), verankert den Pegel bei 400 Hz – 3 kHz und rechnet die 32 Band-Gains aus. Ausgabe: Tabelle mit Restfehler und eine fertige Kotlin-Zeile für `Eq.BUILT_IN`.

## Geprüft
`python3 tools/eq_match.py --selftest` schiebt Rosa Rauschen durch die bekannte Kette (Poweramp-Druck), versetzt es um 0,37 s und senkt es um 9 dB: Das Verfahren findet Versatz und Pegel und trifft die Kurve auf **0,25 dB RMS, max. 0,55 dB**.

`tools/secret_dsp.py` ist ein Python-Port von `Eq.kt`/`Dsp.kt`. Konstanten in `Eq.kt` ändern → dort mitziehen.

## Grenzen
- Das Langzeit-Spektrum gleicht die **Klangfarbe** an, nicht Dynamik, Limiter oder Resampling.
- Ohne die Secret-Player-Aufnahme ist ein 1:1-Abgleich nicht belegbar.
