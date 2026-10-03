# MusicPlayer

Lokaler Android Musikplayer mit:
- Media3 / ExoPlayer
- Eigener DSP (EQ, Bass/Treble, Reverb, Limiter)
- SAF Ordner-Scan
- Jetpack Compose UI
- Mini-Player + Bottom Navigation

## Struktur

```
app/src/main/java/com/mp/player/
```

## Build

Android Studio oder `./gradlew assembleDebug`

## Wiedergabe-Architektur (Stand: Stabilitäts-Update)

- `PlayerService` besitzt als Einziger den ExoPlayer (Queue, Repeat, Shuffle, Headset-Tasten, Fehlerbehandlung,
  Speichern/Wiederherstellen). Die UI verbindet sich nur per `MediaController` (`PlayerBridge`).
- `PlaybackState` ist die zentrale Anzeige-Quelle (Mini-Player, Player, Queue lesen nur davon).
- `PlaybackStore`: crash-sichere Speicherung (`AtomicFile`, getrennte Dateien für Queue und Zustand,
  serieller Schreib-Thread). Wiederherstellung beim Service-Start, defekte/gelöschte Dateien werden entfernt.
  Es wird bewusst **nicht automatisch abgespielt**.
- Headset: 1x = Play/Pause, 2x = Weiter, 3x = Zurück; doppeltes Previous = vorheriger Titel (`onMediaButtonEvent`
  + `MediaButtonReceiver` im Manifest, damit auch bei beendetem Prozess reagiert wird).
- Previous: > 3 s = Titelanfang, sonst vorheriger Titel (UI, Benachrichtigung, Headset identisch).
- Shuffle: echte Permutation, aktueller Titel zuerst, bei "Alle wiederholen" neue Zufallsrunde nach jedem Durchlauf.
- Queue-Ansicht (`QueueScreen`): Anspringen, Entfernen (löscht NIE die Datei), Drag & Drop, Leeren.
- Sleep-Timer (`SleepTimer`): 15/30/45/60 Min. oder Ende des Titels, läuft im Service-Prozess.
- Cover: `Covers` + `CoverProvider` (eingebettete Cover, auch für Benachrichtigung/Sperrbildschirm).
- Löschen (`TrackOps`): Datei + Bibliothek + Favoriten + Playlists + laufende Queue.

## Wiedergabelisten

- Verwaltung in `PlaylistStore` (eine Quelle fuer Uebersicht, Detail und "Zur Wiedergabeliste hinzufuegen"),
  gespeichert crash-sicher in `playlists.json` (AtomicFile, bei jeder Aenderung sofort).
- Playlist loeschen immer mit Rueckfrage; entfernt nur die Playlist. Titel entfernen entfernt nur die
  Zuordnung. Musikdateien werden dabei nie geloescht.
- Lang-Druck auf einen Titel = Mehrfachauswahl (Alle auswaehlen, Zur Wiedergabeliste, Zur Warteschlange,
  in einer Playlist zusaetzlich "Aus Playlist entfernen").

## Duplikaterkennung (`Duplicates.kt`, `DuplicatesScreen.kt`)

- Erreichbar unter Einstellungen -> Bibliothek -> "Duplikate suchen". **Es wird nie automatisch geloescht.**
- Stufe 1 "Identisch": gleiche Dateigroesse + gleicher SHA-256 ueber den ganzen Inhalt. Gehasht wird nur, wo die
  Groesse schon passt; Groesse/Hash werden in `hashcache.json` gecacht (gueltig, solange "zuletzt geaendert" gleich bleibt).
- Stufe 2 "Aehnlich": gleicher Titel + Interpret (normalisiert) und fast gleiche Dauer, aber andere Datei
  (z. B. MP3 vs. FLAC). Nur ein Hinweis - der Nutzer entscheidet.
- Dieselbe Datei ueber ueberlappende Ordner (gleiche Document-ID) zaehlt nur einmal und wird nie als Duplikat
  ihrer selbst angezeigt (sonst koennte "Duplikat loeschen" die einzige Kopie loeschen). Das gilt auch im Bibliotheksscan.
- Pro Datei: Pfad, Groesse, Dauer, Bitrate, Sample Rate, Kanaele, Format (Bit-Tiefe nur bei PCM) - nur erkannte Werte.
- Loeschen pro Datei mit Sicherheitsabfrage ueber `TrackOps.deleteEverywhere` (Datei + Bibliothek + Favoriten + Playlists + Queue).

## 32-Band-Equalizer (`Eq.kt`, `EqScreen.kt`)

- Einstellungen -> "Equalizer (32 Baender)". Kurve = echter Gesamt-Frequenzgang der DSP-Kette (`Eq.specs` wird von
  DSP und UI gemeinsam genutzt), darunter Live-Spektrum waehrend der Wiedergabe (nur dann wird abgefragt).
- Bedienung: Tippen = Band waehlen, Ziehen = Gain malen, Doppeltipp = 0 dB; Frequenz / Gain / Q des gewaehlten Bandes per Regler.
- 32 Baender log. von 20 Hz bis 20 kHz, +-12 dB, Q-Standard 4,3 (~1/3 Oktave). Baender mit ~0 dB werden in der DSP
  uebersprungen (flacher EQ kostet keine CPU). Aeltere gespeicherte Staende mit 5 Baendern werden beim Oeffnen
  klangaehnlich auf 32 Baender umgerechnet.
- Bass-/Hoehen-Regler (kalibriert an Poweramp-Aufnahmen): Bass-Shelf 200 Hz (Regler x1,5, Boost +10 dB) plus Sub-Punch
  bei 55 Hz, Hoehen-Shelf 7,9 kHz (Boost +8 dB), automatische Pegelabsenkung gegen Limiter-Pumpen (`Eq.autoComp`).
- Presets: Flat, Rock, Pop, Classical, Dance, Electronic, Hip-Hop, Vocal, Bass Boost (+ aeltere Presets);
  eigene Presets speichern / umbenennen / loeschen; "Custom" = aktuelle Einstellung passt zu keinem Preset.
- Speichern der Einstellung entprellt (400 ms) und beim Verlassen des Screens.

## Weitere Funktionen (Stand dieser Version)

- **Bibliothek in SQLite** (`LibraryDb.kt`, Android-eigenes SQLite, keine Zusatz-Abhaengigkeit): Sofort beim Start da. Die alte JSON-Bibliothek
  wird beim ersten Start einmalig uebernommen. Neue Felder: Genre, Track-/CD-Nummer, Dateiname (alte Eintraege werden beim naechsten Scan einmal neu eingelesen).
- **Kategorien**: Favoriten (Herz im Player), Alben, Genres zusaetzlich zu Titel/Interpret/Album-Interpret/Jahr/Ordner/Playlists/Zuletzt.
- **Suche**: Titel, Interpret, Album, Genre, Dateiname; Suchtext einmal pro Bibliotheksstand aufgebaut, Eingabe 150 ms entprellt, mehrere Woerter (alle muessen vorkommen).
- **Mehrfachauswahl -> "Loeschen"** mit Sicherheitsabfrage; Dateien werden im Hintergrund geloescht, danach Bibliothek, DB, Favoriten, Playlists und Queue bereinigt.
- **ReplayGain / Normalisierung** (`ReplayGain.kt`, Einstellungen -> Wiedergabe & Lautstaerke): Tags aus der Datei (ID3/Vorbis/MP4) oder - ohne Tag - gemessene Lautheit, Ziel -18 LUFS, Gain in [-20, +6] dB, bei Analysewerten durch den Spitzenpegel begrenzt.
- **Audioanalyse** (`Analysis.kt`): LUFS nach BS.1770 (K-Gewichtung + Gating), Peak, RMS, BPM-Schaetzung (Autokorrelation, 70-200 BPM). Hintergrundjob mit niedriger Prioritaet, nur auf Knopfdruck, stoppt bei Akku < 15 %, lange Titel nur 5 Minuten.
  Algorithmen wurden gegen synthetische Signale geprueft (-20-dBFS-Sinus = -20,0 LUFS; Klickspuren 128/150/174 BPM exakt erkannt).
- **Sleep-Timer**: 5/10/15/30/45/60 Min, Ende des Titels, Ausblenden (0-30 s einstellbar).
- **Tests**: `app/src/test` (Equalizer-Mathe, ReplayGain). Laufen in GitHub Actions als eigener Schritt; ein roter Test blockiert die APK nicht.

## Bewusst NICHT umgesetzt (mit Grund)

- **Crossfade**: ExoPlayer kennt es nicht. Echtes Crossfade braucht einen zweiten Player, der synchron den Titelanfang vorlaedt, eigene Lautstaerke-Rampen
  und doppelte Audio-Focus-/Session-Logik - das aendert den Kern des stabilen PlayerService und ist ohne Geraetetests ein hohes Regressionsrisiko.
  Ein "Fake"-Fade am Titelende waere kein Crossfade. Gapless (luckenlos) liefert ExoPlayer bei MP3/AAC/FLAC bereits selbst.
- **Metadaten bearbeiten (Tags schreiben)**: Android/Media3 bieten keine Schreib-API; saubere Tag-Schreibung braucht eine Zusatzbibliothek (z. B. JAudioTagger) und wurde nicht ungeprueft eingebaut.
- **Compressor**: nicht eingebaut; der Limiter schuetzt vor Clipping.
