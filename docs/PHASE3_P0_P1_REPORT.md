# Phase 3 – P0/P1 Fixes (Code-Stand)

## Behobene Ursachen

### P0 Pegel
- `Store.transparentPlayback` + `autoCompEnabled`
- `DspProcessor.autoCompOn` – AutoComp abschaltbar / Transparent
- UI: Einstellungen → Wiedergabe & Lautstärke
- Limiter bleibt EQ-Schalter; ReplayGain bleibt `rgMode`

### P0 Stabilität
- `BgJobs` Executor für Cover (abbruchbar, URI-gebunden)
- Kein roher `Thread` mehr für Cover
- Hi-Res-Prefs nicht mehr bei **jedem** Trackwechsel angewendet
- Play-Count nicht mehr bei Start

### P1 Hi-Res Transparenz
- `HiRes.audioInfoLine` sagt klar: Ausgabe 16-Bit PCM, kein nachweisbarer Hi-Res-DAC-Pfad

### P1 Hörstatistik
- Zählung nach ~30 s (kurze Tracks ~50 % Dauer, min 8 s)
- Info-Dialog: Plays, Skips, zuletzt gehört, Favorit

### P1 Favoriten
- Zusätzlich URI-Set `fav_uri` + Migration parallel zu Hash-IDs
- `Store.isFavorite` / `setFavorite`

### P2 Cover (teilweise)
- Manuelle Suche `force=true` löscht `.none`
- Auto-Cooldown 1 h statt permanent

## Nicht auf Gerät getestet
Build/Unit-Tests in dieser Umgebung ohne Android SDK nicht ausgeführt.

## Verbleibend
- Widget fehlt
- Cover-Mehrfachauswahl-UI noch nicht
- Float-Sink weiterhin absichtlich aus (Stabilität)
- Gesamte Hörzeit-Aggregation nicht implementiert

## Fortsetzung (Cover-Picker + Widget)

### Cover-Mehrfachauswahl
- `OnlineCover.searchCandidates` / `searchForPicker` mit Score (Artist/Album/Title)
- Bei unsicherem Match: Dialog „Cover auswählen“
- Bei klarem Treffer (score ≥ 0.7, nur 1 starker): Auto-Download
- Speicherung über `Covers.cacheExternal`

### Home-Screen-Widget
- `PlayerWidget` + `res/layout/player_widget.xml` + `res/xml/player_widget_info.xml`
- Prev / Play-Pause / Next → `PlayerService` Actions
- Update bei Trackwechsel und Play-State
- Cover aus lokalem Cache

