# Hi-Res (Poweramp-orientiert)

## Poweramp
- Backends: Hi-Res Output, AudioTrack, OpenSL ES, AAudio
- Sample-Rate / Bit-Tiefe wählbar, DVC, Caps, Fallback

## Secret Player (Media3)
- **Standard** → DSP 16-Bit
- **Hi-Res** → DSP **Float32** (Headroom für EQ, analog PA Float32)
- **Auto** → Float wenn Gerät Caps meldet
- Sample-Rate-Präferenz: Auto / 44.1 / 48 / 96 / 192 kHz (an Caps geklemmt)
- Bit: 16 / 24 / Float32
- UI graut nicht unterstützte Optionen aus
- Fehler → 16-Bit-Session-Fallback, Präferenz bleibt

## Grenze
Kein proprietärer OpenSL-Hi-Res-Treiber wie Poweramp – Rate am DAC steuert Android/Media3 mit.
