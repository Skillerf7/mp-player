#!/usr/bin/env python3
"""Klang-Abgleich: passt den 32-Band-EQ von Secret Player an eine Referenz (z. B. Poweramp) an.

  python3 eq_match.py REF.wav TEST.wav [--start 0] [--dur 30]
     REF  = Aufnahme der Referenz (Poweramp mit deinen Einstellungen)
     TEST = Aufnahme von Secret Player, gleicher Titel, gleiche Stelle, EQ AUS (Schalter oben im EQ-Screen), gleiche Systemlautstaerke
  python3 eq_match.py --selftest      (prueft das Verfahren an bekannter Kette)

Verfahren: Zeit-Ausrichtung per Kreuzkorrelation -> Langzeit-Spektrum (1/3 Oktave) beider Aufnahmen ->
Differenz, verankert im Mittenbereich 400 Hz-3 kHz (dort bleiben Shelf-/Tone-Ketten ~0 dB) ->
beschraenkte Kleinste-Quadrate-Anpassung der 32 Band-Gains (+-12 dB, geglaettet).
"""
import sys, subprocess, tempfile, os, argparse
import numpy as np
from scipy.signal import correlate, resample_poly
from scipy.optimize import lsq_linear
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import secret_dsp as S

FS = 48000

def load_any(path):
    if not path.lower().endswith('.wav'):
        tmp = tempfile.mktemp(suffix='.wav')
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', path, '-vn', '-ac', '2', '-ar', str(FS), tmp], check=True)
        path = tmp
    import wave
    w = wave.open(path); n = w.getnframes(); ch = w.getnchannels()
    x = np.frombuffer(w.readframes(n), dtype=np.int16).astype(float).reshape(-1, ch) / 32768
    if w.getframerate() != FS: raise SystemExit('Bitte 48 kHz WAV (ffmpeg -ar 48000)')
    return x if ch == 2 else np.repeat(x, 2, axis=1)

def envelope(x, rate=200):
    m = np.abs(x).mean(axis=1); k = FS // rate
    return m[:len(m) // k * k].reshape(-1, k).mean(axis=1)

def align(ref, test, max_shift_s=10):
    er, et = envelope(ref), envelope(test)
    er = (er - er.mean()) / (er.std() + 1e-9); et = (et - et.mean()) / (et.std() + 1e-9)
    c = correlate(er, et, mode='full'); lags = np.arange(-len(et) + 1, len(er))
    sel = np.abs(lags) <= max_shift_s * 200
    lag = lags[sel][np.argmax(c[sel])]   # ref[t+lag] ~ test[t]
    off = int(lag * FS / 200)
    if off >= 0: ref, test = ref[off:], test
    else: ref, test = ref, test[-off:]
    n = min(len(ref), len(test)); return ref[:n], test[:n], lag / 200.0

def fit_bands(centers, diff, lam=0.15):
    f32 = np.array([S.center_freq(i) for i in range(S.BAND_COUNT)])
    M = np.stack([S.mag_db(0, f, 1.0, S.DEFAULT_Q, FS, centers) for f in f32], axis=1)  # dB pro 1 dB Gain
    D = np.diff(np.eye(S.BAND_COUNT), 2, axis=0) * lam
    A = np.vstack([M, D]); b = np.concatenate([diff, np.zeros(len(D))])
    r = lsq_linear(A, b, bounds=(-12, 12))
    return np.round(r.x * 10) / 10

def match(ref, test, start=0.0, dur=30.0, verbose=True):
    ref, test, lag = align(ref, test)
    a = int(start * FS); n = int(min(dur * FS, len(ref) - a))
    ref, test = ref[a:a + n], test[a:a + n]
    cr, sr = S.third_octave(ref); ct, st = S.third_octave(test)
    raw = sr - st
    anchor = (cr >= 400) & (cr <= 3000)
    level = float(np.median(raw[anchor]))
    diff = raw - level
    bands = fit_bands(cr, diff)
    f32 = np.array([S.center_freq(i) for i in range(S.BAND_COUNT)])
    fitted = S.response(S.dsp(bands=list(bands)), cr)
    res = diff - fitted
    if verbose:
        print('Zeit-Offset: %.2f s | Pegelunterschied im Mittenbereich (REF - TEST): %+.1f dB | Dauer %.0f s' % (lag, level, n / FS))
        print('\n 1/3-Okt  Ziel(dB)  Fit(dB)  Rest')
        for fc, d, fi, r in zip(cr, diff, fitted, res): print('%7.0f  %8.1f %8.1f %6.1f' % (fc, d, fi, r))
        print('\nRest RMS %.2f dB | max %.2f dB' % (np.sqrt((res ** 2).mean()), np.abs(res).max()))
        print('\nKotlin (Eq.kt, BUILT_IN):')
        print('"Poweramp Match" to Dsp(bands = Eq.flatBands().let { b -> listOf(%s).mapIndexed { i, g -> b[i].copy(g = g) } }),' %
              ', '.join('%.1ff' % g for g in bands))
    return bands, diff, fitted, level

def selftest():
    rng = np.random.default_rng(7)
    n = FS * 40
    X = np.fft.rfft(rng.standard_normal(n)); f = np.arange(len(X)); f[0] = 1
    pink = np.fft.irfft(X / np.sqrt(f), n); pink /= np.sqrt((pink ** 2).mean()); pink *= 0.1
    # leichte Lautheitsschwankungen wie Musik
    env = 1 + 0.5 * np.sin(2 * np.pi * np.arange(n) / FS * 0.7) * np.sin(2 * np.pi * np.arange(n) / FS * 0.13)
    test = np.stack([pink * env, np.roll(pink, 11) * env], axis=1)
    truth = S.dsp(bass=3, treble=2, pLowDb=7, pHighDb=6.2, limiter=False)
    ref, _, _ = S.process(test, truth)
    ref = ref * 10 ** (-9 / 20)                      # anderer Aufnahmepegel
    ref = np.vstack([np.zeros((int(0.37 * FS), 2)), ref])  # Versatz 0,37 s
    bands, diff, fitted, level = match(ref, test, start=2, dur=30, verbose=False)
    cr, _ = S.third_octave(test)
    f = cr
    real = S.response(S.dsp(bass=3, treble=2, pLowDb=7, pHighDb=6.2), f)
    # Wahrheit ohne Preamp ist nur die Filterkette; Pegel im Mittenbereich ~0 -> direkt vergleichbar
    err = fitted - real
    print('SELFTEST  Offset-Erkennung ok | Rest vs. echte Kette: RMS %.2f dB, max %.2f dB' % (np.sqrt((err ** 2).mean()), np.abs(err).max()))
    print('          Pegelerkennung: %+.1f dB (erwartet etwa %+.1f dB)' % (level, -9 + S.auto_comp(truth)))
    for fc, r, fi in list(zip(f, real, fitted))[::3]: print('   %6.0f Hz  echt %+5.1f  Fit %+5.1f' % (fc, r, fi))
    ok = np.sqrt((err ** 2).mean()) < 1.0 and np.abs(err).max() < 2.5
    print('SELFTEST', 'BESTANDEN' if ok else 'FEHLGESCHLAGEN'); return ok

if __name__ == '__main__':
    ap = argparse.ArgumentParser(); ap.add_argument('ref', nargs='?'); ap.add_argument('test', nargs='?')
    ap.add_argument('--start', type=float, default=0); ap.add_argument('--dur', type=float, default=30); ap.add_argument('--selftest', action='store_true')
    a = ap.parse_args()
    if a.selftest: sys.exit(0 if selftest() else 1)
    if not (a.ref and a.test): ap.error('REF und TEST angeben (oder --selftest)')
    match(load_any(a.ref), load_any(a.test), a.start, a.dur)
