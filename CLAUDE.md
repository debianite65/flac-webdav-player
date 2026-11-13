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
- Stand: Code wurde ohne Compiler/Gerät geschrieben (Arbeitsbereich ohne Zugriff auf Android SDK).
  Der erste GitHub-Actions-Lauf zeigt eventuelle Kompilierfehler.
- Bewusst nicht in v1: Suche, Playlists, Favoriten, Equalizer, Sleep-Timer, Android Auto, Chromecast,
  Offline-Download ganzer Alben, rekursives Abspielen von Unterordnern.
