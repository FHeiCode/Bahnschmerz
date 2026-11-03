# Bahnschmerz – Echtzeitdaten für deine Bahnstrecke

![Logo](store/icon_512.png)

Android-App (Kotlin, Jetpack Compose) für eine frei wählbare Strecke **A → B**.
Sie zeigt alle direkten Züge, die in A abfahren und B anfahren – mit Live-Daten:

- Abfahrt in A und Ankunft in B, jeweils Plan- und Ist-Zeit (+Verspätung, farbig)
- Gleis und Gleiswechsel
- Zugausfälle und entfallende Halte
- Meldungen der DB (z. B. Grund der Verspätung)
- Zwischenhalte (Antippen einer Karte klappt Details auf)
- Gespeicherte Lieblingsstrecken, Gegenrichtung per Knopfdruck
- Automatische Aktualisierung jede Minute, solange die App sichtbar ist; Pull-to-Refresh
- **Karte** (Reiter „Karte“): alle Züge, die gerade zwischen A und B unterwegs sind
  (beide Richtungen), farbig nach Verspätung:

  | Farbe | Verspätung |
  |---|---|
  | Grün | unter 5 Min. |
  | Hellgrün | 5–15 Min. |
  | Gelb | 15–30 Min. |
  | Orange | 30–60 Min. |
  | Rot | über 60 Min. |
  | Grau | keine Echtzeitdaten |

  Zug antippen zeigt Richtung, Verspätung und Ankunftszeit.

## Wie die Zugpositionen entstehen

Die DB-Echtzeitdaten enthalten **keine GPS-Positionen**. Die App schätzt die Position:
Aus der Ist-Abfahrt in A und der prognostizierten Ankunft in B ergibt sich der zeitliche
Fortschritt, der auf den Laufweg (A → Zwischenhalte → B) übertragen wird. Die Züge
bewegen sich alle 10 Sekunden weiter; die Daten werden jede Minute neu geladen.
Haltezeiten an Zwischenbahnhöfen sind dabei nicht berücksichtigt – die Position kann
deshalb einige Kilometer abweichen.

Bahnhofskoordinaten: Für ~70 große Bahnhöfe sind sie in der App enthalten. Alle anderen
werden einmalig bei OpenStreetMap (Nominatim, max. 1 Anfrage/Sekunde) gesucht und
auf dem Gerät gespeichert. Beim ersten Öffnen einer neuen Strecke erscheinen
Zwischenhalte deshalb nach und nach.

Kartenmaterial: © OpenStreetMap-Mitwirkende (über osmdroid).

## Datenquelle

Die App nutzt die öffentliche JSON-Schnittstelle von **DBF** (`dbf.finalrewind.org`),
die die IRIS-Echtzeitdaten der Deutschen Bahn bereitstellt – ohne Registrierung und API-Schlüssel.
Die frühere Schnittstelle `v6.db.transport.rest` ist abgeschaltet.

Nutzungsregeln von DBF: höchstens 30 Anfragen/Minute und eine Anfrage pro Bahnhof und Minute.
Die App hält das ein (60-Sekunden-Cache je Bahnhof, pro Aktualisierung 2 Anfragen).
Für intensive Nutzung kann DBF selbst gehostet werden (Open Source: github.com/derf/db-fakedisplay);
dann nur die Basis-URL in `DbfApi.kt` ändern.

## Bauen

1. Ordner in **Android Studio** (Ladybug oder neuer) öffnen → *Open*.
2. Gradle-Sync abwarten (lädt Android SDK 35 und Abhängigkeiten).
3. Gerät/Emulator wählen → *Run*.

Kommandozeile: `./gradlew assembleDebug` → APK unter `app/build/outputs/apk/debug/`.

Voraussetzungen: JDK 17, Android SDK 35, minSdk 26 (Android 8.0).

## Eingabe der Bahnhöfe

Bahnhofsname (z. B. `Köln Hbf`, `Frankfurt(Main)Hbf`), EVA-Nummer (`8000105`)
oder DS100-Kürzel (`FF`). Bei mehrdeutigen Namen zeigt die App Vorschläge des Servers an.

## Projektstruktur

```
app/src/main/java/de/bahnlive/app/
├── MainActivity.kt          Einstieg, Navigation zwischen den zwei Ansichten
├── data/
│   ├── DbfApi.kt            HTTP-Zugriff + tolerantes JSON-Parsen + Cache
│   ├── RouteRepository.kt   Filtert Züge A→B, verknüpft Ankunft in B
│   ├── RouteStore.kt        Lieblingsstrecken (DataStore)
│   ├── Stations.kt          Große Bahnhöfe mit Koordinaten
│   ├── StationLocator.kt    Koordinatensuche (OSM Nominatim) + Cache
│   ├── TrainPosition.kt     Positionsschätzung, Farbstufen
│   └── Models.kt
└── ui/
    ├── MainViewModel.kt     Zustand, Auto-Refresh
    ├── RouteSelectScreen.kt Streckenauswahl
    ├── BoardScreen.kt       Echtzeit-Anzeige
    └── Theme.kt
```

## Grenzen

- Nur direkte Verbindungen (keine Umstiege).
- Karte: Züge, die A vor mehr als etwa einer Stunde verlassen haben, fehlen
  (die Abfahrtstafel reicht nur eine Stunde zurück). Bei sehr langen Strecken
  ist die Karte daher unvollständig.
- Zeitfenster: etwa die nächsten 3 Stunden (Vorgabe von DBF).
- Abdeckung wie DB IRIS: Fern- und Regionalverkehr, S-Bahn; keine Busse/Trams.

## Über GitHub bauen und als APK verteilen

Der Workflow `.github/workflows/android.yml` baut die APK automatisch:

| Auslöser | Ergebnis |
|---|---|
| Push auf `main` oder Pull Request | APK unter **Actions → Lauf → Artifacts** (ZIP mit der APK) |
| Tag `v…` pushen, z. B. `v1.0.0` | zusätzlich ein **GitHub-Release** mit der APK zum Herunterladen |
| „Run workflow“ im Actions-Tab | manueller Build |

### Einmalig: Signaturschlüssel hinterlegen

Ohne Schlüssel baut der Workflow nur eine Debug-APK, die bei jedem Lauf anders signiert
ist – Updates schlagen dann fehl („App nicht installiert“). Deshalb unter
**Settings → Secrets and variables → Actions** vier Secrets anlegen:
`KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.

Eigenen Schlüssel erzeugen (falls nicht schon vorhanden):

```bash
keytool -genkeypair -keystore bahnschmerz-release.jks -storetype PKCS12 \
  -alias bahnschmerz -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 bahnschmerz-release.jks   # Ausgabe = KEYSTORE_BASE64
```

Schlüssel und Passwort gut aufbewahren und **nie** ins Repository legen.

### Neue Version veröffentlichen

```bash
git tag v1.0.1
git push origin v1.0.1
```

Nach wenigen Minuten liegt `Bahnschmerz-1.0.1.apk` unter **Releases**. Diesen Link kannst
du weitergeben. Auf dem Handy muss einmalig „Installation aus unbekannten Quellen“ für den
Browser erlaubt werden.
