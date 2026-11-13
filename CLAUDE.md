# FlacPlayer – Projektnotizen

Entscheidungen aus der Planung (13.11.2026):

- Native Android-App: Kotlin, Jetpack Compose, Media3, OkHttp. minSdk 26, App-Name „FlacPlayer“,
  Paket `app.flacplayer.webdav`, dunkles Theme (Grün `#00E61A`), Oberfläche deutsch.
- Ein WebDAV-Server, HTTPS + Basic Auth, Passwort verschlüsselt (EncryptedSharedPreferences).
- Nur `.flac` anzeigen. Warteschlange = FLAC-Dateien des angetippten Ordners (ohne Unterordner),
  sortiert nach Disc/Tracknummer, sonst nach Dateiname.
- Cover: eingebettet (FLAC-PICTURE-Block, per HTTP-Range nur der Header gelesen) → `cover.jpg`/`folder.jpg`
  → Platzhalter.
- Vorladen: gleitendes Fenster der nächsten 15 Titel (`Preloader.kt`), auch über mobile Daten,
  Schalter „nur WLAN“ vorhanden. Cache-Obergrenze einstellbar, wirkt nach App-Neustart.
- Kein Gradle-Wrapper im Repo: GitHub Actions nutzt `gradle/actions/setup-gradle` mit Gradle 8.9.
  Android Studio erzeugt den Wrapper beim Öffnen selbst.
- Stand: Code wird ohne lokalen Compiler geschrieben (Arbeitsbereich ohne Android SDK); kompiliert wird in
  GitHub Actions. Version 1 wurde vom Nutzer auf dem Gerät als funktionierend bestätigt.
- Warteschlange (nachgerüstet): Menü "Als Nächstes"/"Ans Ende" für Titel und Ordner (ohne Unterordner),
  Queue-Screen mit Springen, Entfernen, Verschieben per Ziehen, Leeren.
- Warteschlange bleibt dauerhaft erhalten (`QueueStore.kt`, JSON in filesDir, Restore pausiert beim Dienststart).
- Android Auto: `PlaybackService` ist ein `MediaLibraryService` mit Browse-Baum (`AutoBrowse.kt`):
  root → "Weiterspielen" + "Musik" (WebDAV). Browse-Ids mit Präfix `d:`/`t:`, Queue-Items haben die reine URL als
  mediaId. Manifest: `automotive_app_desc.xml` (uses media). Im Fahrzeug ungetestet.
- Bewusst nicht umgesetzt: Suche, Playlists, Favoriten, Equalizer, Sleep-Timer, Chromecast,
  Offline-Download ganzer Alben, Unterordner in Warteschlange/Abspielen.
