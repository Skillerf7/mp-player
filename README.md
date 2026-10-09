# Secret Player

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
- **Tests**: `app/src/test` (Equalizer-Mathe, ReplayGain). Laufen in GitHub Actions vor dem APK-Build; ein roter Test macht den Lauf rot und es wird keine APK gebaut.

## Bewusst NICHT umgesetzt (mit Grund)

- **Crossfade**: ExoPlayer kennt es nicht. Echtes Crossfade braucht einen zweiten Player, der synchron den Titelanfang vorlaedt, eigene Lautstaerke-Rampen
  und doppelte Audio-Focus-/Session-Logik - das aendert den Kern des stabilen PlayerService und ist ohne Geraetetests ein hohes Regressionsrisiko.
  Ein "Fake"-Fade am Titelende waere kein Crossfade. Gapless (luckenlos) liefert ExoPlayer bei MP3/AAC/FLAC bereits selbst.
- **Metadaten bearbeiten (Tags schreiben)**: Android/Media3 bieten keine Schreib-API; saubere Tag-Schreibung braucht eine Zusatzbibliothek (z. B. JAudioTagger) und wurde nicht ungeprueft eingebaut.
- **Compressor**: nicht eingebaut; der Limiter schuetzt vor Clipping.


## KI-Assistent (offline)

Menü → **KI-Assistent**. Läuft komplett auf dem Gerät: keine Cloud, kein Konto, **keine INTERNET-Berechtigung**
(ein Unit-Test prüft das bei jedem Build). Flugmodus an → funktioniert trotzdem.

Aufbau (`app/src/main/java/com/mp/player/ai/`):

| Datei | Aufgabe |
|---|---|
| `PlayerTools` / `PlayerToolsImpl` | Einzige Schnittstelle zwischen KI und Player/Datenbank (Queue, Suche, Verlauf, Audio-Zustand ...). Kein Tool zum Löschen von Dateien. |
| `IntentEngine` (`BrainEngine`) | Versteht deutschen Text inkl. Slang und liefert eine Absicht. Später durch ein On-Device-Modell austauschbar. |
| `Recommender` | Wählt Titel aus der bestehenden Bibliothek: Energie aus BPM + Lautheit (Audioanalyse), Stichwörter, Hörverlauf. |
| `TrackSimilarity` | Erstellt aus dem laufenden Titel ein lokales Profil und findet ähnliche Tracks. BPM/Audioanalyse werden stark gewichtet; Genre, Lautheit, Songtext-Stimmung, Dauer und Metadaten ergänzen den Vergleich. |
| `Explainer` | Erklärt den aktuellen Klang aus EQ, Limiter, ReplayGain, Hall und Titel-Messwerten. |
| `Persona` | Tonfall (locker, kurz; bei ernsten Themen ohne Witze). |
| `Assistant` | Verbindet alles; dauerhafte Aktionen (Playlist anlegen, Bass zurücksetzen, Scan starten) nur nach "Ja". Jeder Fehler wird abgefangen. |

Hörverlauf: neue Tabelle `play_history` in `library.db` (DB-Version 2, Migration ohne Datenverlust).
Die Stimmungserkennung ist eine Schätzung; sie wird deutlich besser, wenn die Audioanalyse (Menü → Audioanalyse) gelaufen ist.
Der Assistent kann die gespeicherten Messwerte des laufenden Titels direkt auslesen (z. B. „Lies die BPM aus“). „Mehr solcher Tracks“ erstellt daraus ein lokales Ähnlichkeitsprofil und ergänzt passende Titel aus der Library, ohne das laufende Lied zu unterbrechen.


## Secret Player - lokales Musik-Gehirn (kein LLM, kein Netzwerk)

Zusaetzlich zu Intent, Memory, Recommender und EQ-Semantik gibt es:

- **Rueckgaengig** (`Assistant.eqUndo`): "mach das rueckgaengig", "wie vorher", "nee doch nicht" stellen den kompletten EQ-Stand wieder her (`PlayerTools.setEqState`). Nur fuer die Sitzung.
- **Session-Snapshots** (`Snapshots.kt`, Datei `ai_snapshots.txt`): jede gestartete Session merkt sich Stimmung, Genre, Dauer und EQ. "Mach wieder wie gestern" / "wie letztens" stellt sie her. Gibt es mehrere unterschiedliche Sessions von gestern, fragt der Assistent kurz nach; gibt es keine, sagt er das ehrlich. Kraeftige EQ-Staende (> 6 dB) werden erst nach "Ja" gesetzt. "Wie war das gestern?" erzaehlt nur.
- **Feedback zum laufenden Titel**: "das ist geil" / "nicht meins" wird leise gemerkt (niedrige Sicherheit, verblasst), ein einzelner Skip ist kein festes Urteil. Abgelehnte Titel kommen in dieser Sitzung nicht wieder.
- **Gelernte Muster** (`MemKind.PATTERN`, z. B. `calm>hardtekk`): wiederholt gewaehltes Genre nach einem schlechten Tag waechst langsam zur Verknuepfung. Sie wird nur als Angebot genutzt ("Soll ich das nehmen?"), erscheint bei "Was weisst du ueber mich?" und ist per "Vergiss ..." loeschbar.
- **Gefuehls-Intensitaet** 0..1 im `ConversationState` ("bisschen" ~0.35, "komplett" ~0.9).
- **"Was hast du dir gerade gemerkt?"** zeigt nur die Aenderungen der laufenden Unterhaltung.

## EQ per Sprache, Lautstaerke (Stand v9)

- **Mitten und Luft** sind eigene EQ-Ziele: "Mitte tiefer / Mitte weg", "zwischen Bass und Hoehen tiefer" (= Mitten, 200 Hz - 2,5 kHz), "oben mehr Luft" (ab 10 kHz). `tiefer` heisst bei Bass mehr Tiefgang, bei Mitten/Hoehen/Luft absenken. "oben", "unten", "Mitte", "Luft" zaehlen nur mit Richtungswort als EQ-Befehl.
- **Relative Schritte in effektiven dB** (so wie man es hoert): minimal 0,75 / bisschen 1,5 / normal 3 / viel 5 dB. Der Bass-Regler wirkt x1,5; der Planer rechnet das zurueck (`EqPlanner.bassKnobFor`), "bisschen mehr Bass" ist also immer ca. +1,5 dB vom aktuellen Stand, nie ein fester Wert.
- **Lautstaerke** (`UserIntent.Volume`, `PlayerTools.getVolume/setVolume`): "lauter", "etwas leiser", "viel lauter" (+-10 / 5 / 20 Prozentpunkte), "Lautstaerke auf 10" (= 10 %). Wirkt auf die Medien-Lautstaerke des Geraets; gemeldet wird der Wert, den das Geraet danach wirklich hat.
- **Fallback** bei Unverstandenem fragt natuerlich nach ("Musik oder quatschen?") statt "Da komm ich nicht mit".

## Referenzen, Rueckfragen, Dialog-Tests (Stand v10)

- **Titel-Referenzen** (`References.kt`): "den ersten", "der dritte ist scheisse", "mach den weg", "nimm stattdessen den davor", "die ersten drei", "warum hast du den genommen?". `ReferenceResolver` ordnet sie echten Titeln zu (Queue bzw. letzte Trefferliste, "davor" zaehlt ab dem zuletzt besprochenen Titel); ohne Bezug wird nichts geraten, der Assistent sagt es.
- **Urteil vs. Anweisung**: "Der dritte ist scheisse" fragt erst "Soll er raus?"; "Mach den weg" entfernt direkt (nur aus der Queue, nie die Datei). Ein reines Wegmachen ist nur fuer die Sitzung ein Urteil, ein ausgesprochenes Urteil wird leise (niedrige Sicherheit) gemerkt. "Nee, das nicht" als Antwort auf eine offene Frage ist ein Nein, keine Titel-Referenz.
- **Ersatz** (`SimilarFinder`): "such was aehnliches" nur aus echten Daten (Genre, Energie, Stimmung, Interpret); die Antwort nennt die tatsaechlich zutreffenden Gruende. Passt nichts, sagt er das.
- **Warum?** (`ChoiceExplainer`): nur Stimmungswunsch, Analysewerte, Songtext-Stimmung, Favorit/Hoerverlauf, die wirklich vorhanden sind; sonst "keine Daten".
- **Rueckfragen im Kontext** (`Pending.Ask`, `AnswerParser`): "Musik oder quatschen?", "Eher ruhig oder mit bisschen Druck?", "Eher reinfuehlen oder hochkommen?". Die naechste Antwort wird im Licht der Frage gelesen ("Druck." = Energie + etwas Bass). Beides gleichzeitig oder gar nichts = unsicher: einmal kurz nachfragen; mittlere Sicherheit wird transparent genannt ("Ich war mir nicht ganz sicher, hab's als ... verstanden"). "Such du" / "egal" entscheidet der Kontext.
- **Gefuehle starten keine Musik mehr**: jede Befinden-Aeusserung mit Gefuehlswort ("Mir geht's scheisse", "bin komplett durch", "bin einsam") wird zuerst besprochen. Einzelwoerter wie "sad" bleiben direkte Musikwuensche.
- **Tests**: `DialogTest` (Referenz-, Antwort- und Mehrschritt-Dialoge mit `FakeTools`, laeuft als JVM-Test ohne Android; `Assistant(uiContext = EmptyCoroutineContext)`).

## Playlist-Entwurf, Dramaturgie, laenger/kuerzer, Widerspruch (Stand v11)

- **Entwurf statt Sofortstart**: "Such mir ... / stell mir ... zusammen" bereitet einen Entwurf vor (Anzahl, Spielzeit, erste 5 Titel) und startet NICHT. "Spiel sie" / "Mach" / "Ja" startet ihn. Vorher aenderbar: "der dritte ist scheisse" -> "mach den weg" -> "such was aehnliches" (wirkt nur auf den Entwurf, nicht auf die laufende Queue), "laenger", "kuerzer".
- **Dramaturgie** (`PlaylistPlanner`): Energiekurve je Situation - CHILL/SLEEP faellt, Traurig/Einsam/Abschalten steigt sanft und klingt ruhig aus, PARTY/ENERGETIC baut auf zum Hoehepunkt. Nur wenn mindestens die Haelfte der Titel eine Audioanalyse hat; sonst bleibt die Reihenfolge des Recommenders und es wird keine Dramaturgie behauptet.
- **Laenger / kuerzer** ("mach sie laenger", "etwas kuerzer"): laenger haengt passende Titel an (Entwurf: erweitert ihn), kuerzer behaelt ca. 60 % der Spielzeit und fasst den laufenden Titel nie an. Gemeldet wird, was wirklich passiert ist.
- **Widerspruch** ("traurig, aber nicht zu traurig"): wird nicht als Ausschluss behandelt, sondern abgeschwaecht (traurig -> nostalgisch, aggressiv -> energiegeladen), und der Assistent sagt das offen.

## Mehrphasige Wuensche (Stand v12)

- **"Mir geht's scheisse, mach fuer zwei Stunden was Trauriges mit bisschen Bass und danach was Ruhiges"** wird als EIN Plan gelesen (`PhaseParser`): Gefuehlssatz (nur Empathie, startet nichts fuer sich), Phase 1 (traurig, 2 h, Bass klein), Phase 2 (ruhig). Getrennt wird bei "danach / dann / anschliessend / zum Schluss / spaeter". Jede Phase wird mit dem normalen Parser gelesen; ist ein Teil kein Musikwunsch ("dann Pause", "dann lauter"), bleibt es ein normaler Befehl.
- **Zeit**: nennt nur die erste Phase eine Dauer, gilt sie als Gesamtzeit und wird gleichmaessig geteilt (der Assistent sagt das: "Ich teile die 120 Minuten auf: 60 + 60 Min."). Nennt jede Phase ihre eigene Zeit, bleibt es dabei.
- **Ablauf**: Phasen werden nacheinander in einen Entwurf gebaut (jede mit eigener Dramaturgie, Wiederholungen ausgeschlossen) und mit "Such mir ..." nur vorbereitet, sonst sofort gestartet. EQ-Wuensche (z. B. Bass) gehoeren zur ersten Phase und laufen ueber den normalen EQ-Pfad (inkl. Rueckfrage bei extremen Werten). Findet eine Phase nichts, steht das ehrlich in der Antwort; scheitert schon die erste, wird nichts gestartet.

## Themen-Stapel, episodisches Gedaechtnis, Tageszeit-Muster (Stand v13)

- **Themen-Stapel** (`ConversationState.pushTopic`): Emotion, Musik, Playlist-Entwurf, EQ, Lautstaerke, Smalltalk. Nach einem Themenwechsel holt "Und die Playlist von eben?" / "zurueck zur Playlist" den offenen Entwurf zurueck (zeigt ihn, startet nichts), "zurueck zum EQ" nennt den echten EQ-Stand, "Wo waren wir?" fasst Thema/Stimmung/Ziel zusammen. Gibt es nichts Offenes, sagt er das.
- **Episodisches Gedaechtnis** (`Episodes.kt`): jeder echte Musikstart (Stimmung/Genre) wird als strukturiertes Ereignis gemerkt - Tagesabschnitt, Stimmung oder Genre, Gefuehl und Thema der letzten 45 Minuten, Dauer - nie als Chattext. Such-/Artist-Starts ohne Stimmung/Genre erzeugen kein Ereignis. Maximal 300 Ereignisse.
- **Tageszeit-Muster**: erst ab 3 gleichen Starts im selben Tagesabschnitt (morgens/tagsueber/abends/nachts) innerhalb von 90 Tagen. "Was hoere ich nachts?" antwortet nur aus echten Mustern, sonst "zu wenig Daten (bisher n Starts)". "Was wuerdest du jetzt hoeren?" bietet ein vorhandenes Muster als Angebot an ("Du hoerst abends oft Rap (4x). Soll ich das machen?") - startet nie von allein. "Was weisst du ueber mich?" listet die Muster mit auf.
- **Datenbank**: `memory.db` Version 2 mit Migration (v1 -> v2 legt nur die Tabelle `episodes` an; `memory` bleibt unveraendert). Faellt die DB aus, arbeitet der Episoden-Store im Speicher weiter.

## Spec-Audit gegen den echten Parser (Stand v14)

- `AuditTest` ist die ausfuehrbare Matrix der Spec-Beispielsaetze (Abschnitte 77-80, 120, 122): Small Talk, Emotionen, Musikwuensche, Kontext, EQ, Suche, Playlist, Widersprueche, Edge Cases. Schlaegt ein Satz fehl, nennt die Meldung den Satz und was stattdessen erkannt wurde.
- **Einwuerfe sind nie "nicht verstanden"**: "Echt?", "Warum bro?" / "Warum?" (erklaert die letzte echte Aktion: Titelwahl, EQ-Aenderung, warum nicht gleich Musik - oder fragt ehrlich nach), "Und?", "Hm.", "Kein Ding", "Bro?". Offene Floskeln wie "Keine Ahnung" / "Ich weiss nicht was ich machen soll" / "Kannst du mich ablenken?" / "Mir ist langweilig" stellen eine kurze Rueckfrage (Musik oder quatschen; bei Langeweile: Neues entdecken = Titel, die lange nicht liefen). "Erzaehl irgendwas" liefert einen Fun-Fact aus einer kleinen festen Liste verifizierter Audio-Fakten.
- **Gute Laune** ("Bin happy", "Mir geht's super") freut sich und BIETET Musik an, startet nichts.
- **Gefuehlssaetze brauchen einen Selbstbezug** ("bin", "fuehl", "heute war", "mir ..." oder >= 4 Woerter): "Mach traurig" und "Noch trauriger" sind Musikwuensche, "Bin traurig" ein Befinden.
- **Weitere Luecken**: "Ich brauch Musik", "Such was", "Mach eine Playlist" (Entwurf), "Such Maytrixx" (Suche), "Mach weiter" (statt Suche nach "weiter"), "Mehr Bass, aber nicht uebertrieben" (kleiner Schritt statt Richtungswechsel), "nicht einschlafen" (schliesst Schlaf-Musik aus), "Mehr Druck" waehrend Musik laeuft = untenrum mehr statt neuer aggressiver Queue.
