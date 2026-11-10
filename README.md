# Sprachtext – Android-App

Teile eine WhatsApp-Sprachnachricht mit dieser App, dann bekommst du den Text. Die Erkennung läuft lokal mit Whisper und braucht keinen Server oder Webspace. Internet ist nur einmal nötig, um das Modell zu laden.

## APK bauen – Weg A: GitHub (ohne etwas zu installieren)

1. Auf github.com ein neues Repository anlegen (es darf privat sein).
2. Den **Inhalt** dieses Ordners hochladen, inklusive des Ordners `.github`. Am einfachsten geht das mit Git:
   ```
   git init && git add . && git commit -m "Sprachtext"
   git remote add origin https://github.com/DEINNAME/sprachtext.git
   git push -u origin main
   ```
3. Im Repo auf **Actions** gehen und warten, bis „APK bauen“ grün ist (ca. 3–5 Minuten).
4. Unten auf der Seite **Sprachtext-APK** herunterladen. Du bekommst eine ZIP-Datei, darin liegt `app-debug.apk`.

## APK bauen – Weg B: Android Studio

1. Den Ordner in Android Studio öffnen (File → Open) und den Gradle-Sync abwarten.
2. Build → Build App Bundle(s) / APK(s) → **Build APK(s)**.
3. Die APK liegt dann unter `app/build/outputs/apk/debug/app-debug.apk`.

## Installieren

1. Die APK aufs Handy kopieren und antippen.
2. Wenn Android fragt: „Installation aus dieser Quelle zulassen“ erlauben.
3. Die App einmal öffnen. Beim ersten Transkribieren wird das Modell geladen (Base ca. 80 MB, am besten im WLAN).

## Nutzung

WhatsApp → Sprachnachricht gedrückt halten → Teilen → **Sprachtext**.

## Aufbau

- `app/src/main/java/.../MainActivity.java`: WebView, nimmt geteilte Dateien an, Brücke für Kopieren und Teilen
- `app/src/main/assets/web/`: Oberfläche und Whisper-Logik (transformers.js)
- Modelle: `onnx-community/whisper-tiny|base|small` von Hugging Face, im App-Cache gespeichert
