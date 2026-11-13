# FlacPlayer

Android-App, die FLAC-Dateien von einem WebDAV-Server streamt, Coverbilder anzeigt und
immer die nächsten 15 Titel der Warteschlange vorab in den Cache lädt.

## APK bekommen (ohne Android Studio)

1. Auf GitHub im Repo den Reiter **Actions** öffnen. Der Lauf **APK bauen** startet bei jedem Push
   automatisch (oder über **Run workflow**).
2. Lauf öffnen, unten unter **Artifacts** `FlacPlayer-debug-apk` herunterladen und entpacken.
3. `app-debug.apk` aufs Handy übertragen und installieren (Android fragt nach der Erlaubnis
   „Installation aus unbekannten Quellen“).

Die APK ist mit einem festen Debug-Schlüssel signiert (`app/debug.keystore`), Updates lassen sich daher
über eine bestehende Installation einspielen.

## Erste Schritte in der App

1. Beim ersten Start **Server-Adresse**, **Benutzername** und **Passwort** eintragen, z. B.
   `https://server/remote.php/dav/files/NAME/Musik/` (Nextcloud) oder die WebDAV-URL deines NAS.
2. **Verbindung testen und speichern**. Danach erscheint der Ordner-Browser.
3. Auf einen Titel tippen: Der ganze Ordner wird zur Warteschlange, die Wiedergabe startet beim Titel.

## Funktionen

- Nur `.flac`-Dateien und Ordner werden angezeigt.
- Cover: zuerst eingebettetes Cover der FLAC-Datei, sonst `cover.jpg`/`folder.jpg`/`front.png` im Ordner,
  sonst Platzhalter. Cover werden verkleinert zwischengespeichert.
- Vorladen: die nächsten 15 Titel der Warteschlange (beachtet Shuffle/Repeat) landen vollständig im
  Audio-Cache (LRU, Obergrenze einstellbar) und laufen auch offline. Optional nur im WLAN.
- Hintergrundwiedergabe mit Benachrichtigung, Lockscreen- und Bluetooth-Steuerung.
- Shuffle, Repeat, Seekbar, „Zuletzt gespielt“.

## Technik

Kotlin, Jetpack Compose, Media3 (ExoPlayer + MediaSession), OkHttp. Das Passwort liegt verschlüsselt
(EncryptedSharedPreferences / Android Keystore). Die App enthält kein Tracking.

Selbst bauen: Projekt in Android Studio öffnen und auf „Run“ klicken, oder mit installiertem
Android SDK `gradle assembleDebug` (Gradle 8.9, JDK 17).
