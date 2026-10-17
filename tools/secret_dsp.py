"""Python-Port der Secret-Player-DSP-Kette (Eq.specs / Eq.autoComp / Bq / Limiter) zum Messen ohne Geraet.
Muss mit app/src/main/java/com/mp/player/Eq.kt und Dsp.kt uebereinstimmen (Konstanten dort aendern -> hier mitziehen)."""
import math
import numpy as np

# --- Konstanten aus Eq.kt ---
BAND_COUNT, F_MIN, F_MAX = 32, 20.0, 20000.0
DEFAULT_Q = 4.3
BASS_SHELF_HZ, SUB_SHELF_HZ, SUB_SHARE = 130.0, 50.0, 0.45
PUNCH_HZ, PUNCH_Q, PUNCH_SHARE = 75.0, 1.0, 0.20
TREBLE_SHELF_HZ, TREBLE_SHELF_Q = 8000.0, 0.83
AIR_SHELF_HZ, AIR_SHARE, TONE_SHELF_Q = 14000.0, 0.20, 0.707
BASS_KNOB_FACTOR, BASS_MAX_EFFECTIVE_DB, TONE_MAX_DB = 1.5, 20.0, 15.0
AUTO_COMP_SHARE, AUTO_COMP_MIN_DB = 0.95, -16.0

def center_freq(i): return F_MIN * (F_MAX / F_MIN) ** (i / (BAND_COUNT - 1.0))

def dsp(**kw):
    d = dict(pre=0.0, bands=[0.0] * BAND_COUNT, bass=0.0, treble=0.0, bassBoost=False, trebleBoost=False, limiter=True,
             pLowDb=0.0, pLowHz=100.0, pLowQ=1.03, pHighDb=0.0, pHighHz=8000.0, pHighQ=0.83)
    d.update(kw); return d

def bass_gain(c):
    v = max(-TONE_MAX_DB, min(TONE_MAX_DB, c['bass']))
    k = v * BASS_KNOB_FACTOR if v >= 0 else v
    return min(k + (3.0 if c['bassBoost'] else 0.0), BASS_MAX_EFFECTIVE_DB)

def treble_gain(c):
    v = max(-TONE_MAX_DB, min(TONE_MAX_DB, c['treble']))
    return v + (3.0 if c['trebleBoost'] else 0.0)

def specs(c):
    bg, tg = bass_gain(c), treble_gain(c)
    s = [(0, center_freq(i), g, DEFAULT_Q) for i, g in enumerate(c['bands'])]
    s += [(1, BASS_SHELF_HZ, bg, TONE_SHELF_Q), (1, SUB_SHELF_HZ, max(0, bg) * SUB_SHARE, TONE_SHELF_Q),
          (0, PUNCH_HZ, max(0, bg) * PUNCH_SHARE, PUNCH_Q), (2, TREBLE_SHELF_HZ, tg, TREBLE_SHELF_Q),
          (2, AIR_SHELF_HZ, max(0, tg) * AIR_SHARE, TONE_SHELF_Q)]
    s += [(1, min(max(c['pLowHz'], 20), 1000), c['pLowDb'], min(max(c['pLowQ'], .2), 4)),
          (2, min(max(c['pHighHz'], 1000), 20000), c['pHighDb'], min(max(c['pHighQ'], .2), 4))]
    return s

def coeffs(kind, f, g, q, fs):
    a = 10 ** (g / 40); w = 2 * math.pi * min(f, fs * 0.45) / fs; c = math.cos(w); s = math.sin(w)
    if kind == 0:
        al = s / (2 * q); n = [1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a]
    else:
        t = math.sqrt(a) * s / max(q, 0.2)
        if kind == 1:
            n = [a * ((a + 1) - (a - 1) * c + t), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - t),
                 (a + 1) + (a - 1) * c + t, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - t]
        else:
            n = [a * ((a + 1) + (a - 1) * c + t), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - t),
                 (a + 1) - (a - 1) * c + t, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - t]
    return np.array([n[0], n[1], n[2]]) / n[3], np.array([1.0, n[4] / n[3], n[5] / n[3]])

def mag_db(kind, f0, g, q, fs, freqs):
    b, a = coeffs(kind, f0, g, q, fs)
    w = 2 * np.pi * np.asarray(freqs) / fs
    z = np.exp(-1j * w)
    num = b[0] + b[1] * z + b[2] * z ** 2; den = 1 + a[1] * z + a[2] * z ** 2
    return 10 * np.log10(np.abs(num) ** 2 / np.abs(den) ** 2 + 1e-30)

def response(c, freqs, fs=48000.0):
    r = np.zeros(len(freqs))
    for k, f, g, q in specs(c):
        if abs(g) >= 0.05: r += mag_db(k, f, g, q, fs, freqs)
    return r

def peak_boost_db(c, fs=48000.0):
    f = 40.0 * (12000.0 / 40.0) ** (np.arange(48) / 47.0)
    return max(0.0, float(response(c, f, fs).max()))

def auto_comp(c):
    p = peak_boost_db(c)
    if p < 0.5: return 0.0
    manual = max(0.0, -c['pre'])
    return -min(max(0.0, AUTO_COMP_SHARE * p - manual), -AUTO_COMP_MIN_DB)

def process(x, c, fs=48000.0):
    """x: (n, ch) float. Rueckgabe: (y_nach_limiter, y_vor_limiter, limiter_gain)."""
    from scipy.signal import lfilter
    pre = 10 ** ((c['pre'] + auto_comp(c)) / 20)
    y = x * pre
    for k, f, g, q in specs(c):
        if abs(g) < 0.05: continue
        b, a = coeffs(k, f, g, q, fs)
        y = lfilter(b, a, y, axis=0)
    pre_lim = y.copy()
    gain = np.ones(len(y))
    if c['limiter']:
        rel = 1 - math.exp(-1.0 / (0.1 * fs)); g = 1.0
        pk = np.abs(y).max(axis=1)
        for i in range(len(y)):  # identisch zur Kotlin-Logik: sofortige Absenkung, 100 ms Release
            t = 0.98 / pk[i] if pk[i] > 0.98 else 1.0
            g = t if t < g else g + (1 - g) * rel
            gain[i] = g
        y = y * gain[:, None]
    return y, pre_lim, gain

def third_octave(x, fs=48000.0, f_lo=25.0, f_hi=16000.0):
    """Langzeit-Spektrum in 1/3-Oktav-Baendern (dB, relativ) aus Welch-PSD (mono-Summe)."""
    from scipy.signal import welch
    m = x.mean(axis=1) if x.ndim > 1 else x
    f, p = welch(m, fs=fs, nperseg=16384, noverlap=8192)
    centers = 1000.0 * 2 ** (np.arange(-16, 14) / 3.0)
    centers = centers[(centers >= f_lo) & (centers <= f_hi)]
    out = []
    for fc in centers:
        lo, hi = fc / 2 ** (1 / 6), fc * 2 ** (1 / 6)
        sel = (f >= lo) & (f < hi)
        out.append(10 * np.log10(p[sel].sum() + 1e-30))
    return centers, np.array(out)
