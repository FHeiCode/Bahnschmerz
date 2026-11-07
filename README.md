# BahnSchmerz – Echtzeitdaten für deine Bahnstrecke

![Logo](store/icon_512.png)

![Schriftzug](store/wordmark_light.png)

Android-App (Kotlin, Jetpack Compose) für eine frei wählbare Strecke **A → B**.
Sie zeigt alle direkten Züge, die in A abfahren und B anfahren – mit Live-Daten:

- Abfahrt in A und Ankunft in B, jeweils Plan- und Ist-Zeit (+Verspätung, farbig)
- Gleis und Gleiswechsel
- Zugausfälle und entfallende Halte
- Meldungen der DB (z. B. Grund der Verspätung)
- Zwischenhalte (Antippen einer Karte klappt Details auf)
- Optional **„Über“**: legt die Streckenführung fest (z. B. Frankfurt → Mannheim über Darmstadt);
  es zählen dann nur Züge, die über diesen Bahnhof fahren
- Gespeicherte Lieblingsstrecken, Gegenrichtung per Knopfdruck; Reihenfolge per Ziehen
  am Griff (≡) links neben der Strecke frei sortierbar
- Automatische Aktualisierung jede Minute, solange die App sichtbar ist; Pull-to-Refresh
- **Gerade unterwegs**: alle Züge, die sich jetzt zwischen A und B befinden – in beiden
  Richtungen und unabhängig davon, wann sie am Start abgefahren sind. Als Liste oben im
  Reiter „Abfahrten“ und auf der **Karte** (Reiter „Karte“), farbig nach Verspätung:

  | Farbe | Verspätung |
  |---|---|
  | Grün | unter 5 Min. |
  | Hellgrün | 5–15 Min. |
  | Gelb | 15–30 Min. |
  | Orange | 30–60 Min. |
  | Rot | über 60 Min. |
  | Grau | keine Echtzeitdaten |

  Zug antippen zeigt Richtung, Verspätung und den nächsten Bahnhof mit Zeit.

## Wie die unterwegs befindlichen Züge gefunden werden

Jede Abfahrtstafel reicht nur von etwa einer Stunde zurück bis drei Stunden voraus. Ein Zug,
der vor vier Stunden in A losgefahren ist, steht also nicht mehr auf der Tafel von A. Deshalb
fragt die App die Tafeln von **A, B und bis zu vier Zwischenbahnhöfen** ab (gleichmäßig
verteilt, jeweils der Bahnhof mit den meisten Halten). Einträge desselben Zuges (gleiche
Zugnummer) werden zusammengeführt. Aus dem Laufweg ergibt sich die Fahrtrichtung, aus den
Zeiten an den Bahnhöfen, wo er gerade ist.

## Wie die Zugpositionen entstehen

Die DB-Echtzeitdaten enthalten **keine GPS-Positionen**. Die App schätzt die Position aus
den Zeiten an den Bahnhöfen, in drei Stufen:

1. **DB-Fahrtverlauf** (Einstellung „Genauere Zugpositionen“, Standard: an): Für die Züge
   auf der Strecke holt die App den kompletten Fahrtverlauf aus der Schnittstelle der
   DB-Navigator-App – mit Echtzeit an *jedem* Halt. Die Zuglauf-IDs kommen aus den
   Ankunftstafeln von Start und Ziel. Diese Schnittstelle ist **nicht offiziell freigegeben**
   und kann sich ändern; die App fragt sparsam (höchstens 20 Anfragen/Minute, Zwischenspeicher,
   nach Fehlern 10 Minuten Pause).
2. **Rückfall:** Ist sie nicht erreichbar oder fehlt ein Zug dort, gilt die bisherige Schätzung
   aus den Abfahrtstafeln (Start, Ziel, bis zu vier Zwischenbahnhöfe). Die Statuszeile zeigt,
   für wie viele Züge der Fahrtverlauf genutzt wird.
3. Zwischen zwei bekannten Zeiten wird gleichmäßig interpoliert, fehlende Zeiten werden mit
   der Geschwindigkeit des Zuges hochgerechnet.

**Gleisverlauf:** Die Positionen liegen auf den echten Gleisen. Quelle ist zuerst
[Transitous](https://transitous.org) (freier, gemeinschaftlich betriebener Fahrplandienst):
Eine Anfrage pro Strecke liefert in 1–2 Sekunden die Linienverläufe der direkten Züge
zwischen Start und Ziel, die App schneidet daraus die Abschnitte zwischen den Bahnhöfen.
Nur wo dort etwas fehlt (z. B. bei „Über“ auf einer anderen Strecke), fragt sie die
OpenStreetMap-Gleise über die Overpass API ab – diese Server sind oft überlastet (Issue #16).
Das Ergebnis wird 30 Tage gespeichert. Die Legende der Karte zeigt, wie viel der Strecke auf
Gleisen liegt, und ggf. warum etwas fehlt.

Transitous darf nur von quelloffenen, nicht kommerziellen Projekten genutzt werden, mit
eigener Kennung (App-Name, Version, Repo-Adresse) und sichtbarem Quellenhinweis – die App
erfüllt das (Lizenz: MIT, Link auf [transitous.org/sources](https://transitous.org/sources/)
auf dem Startbildschirm).

Die Züge bewegen sich alle 10 Sekunden weiter; die Daten werden jede Minute neu geladen.

## Datenquelle

Die App nutzt die öffentliche JSON-Schnittstelle von **DBF** (`dbf.finalrewind.org`),
die die IRIS-Echtzeitdaten der Deutschen Bahn bereitstellt – ohne Registrierung und API-Schlüssel.
Die frühere Schnittstelle `v6.db.transport.rest` ist abgeschaltet.

Nutzungsregeln von DBF: höchstens 30 Anfragen/Minute und eine Anfrage pro Bahnhof und Minute.
Die App hält das ein: 60-Sekunden-Cache je Bahnhof, pro Aktualisierung höchstens 6 Anfragen
(A, B, 4 Zwischenbahnhöfe) und ein eingebautes Limit von 26 Anfragen pro Minute.
Für intensive Nutzung kann DBF selbst gehostet werden (Open Source: github.com/derf/db-fakedisplay);
dann nur die Basis-URL in `DbfApi.kt` ändern.

## Bauen

1. Ordner in **Android Studio** (Ladybug oder neuer) öffnen → *Open*.
2. Gradle-Sync abwarten (lädt Android SDK 35 und Abhängigkeiten).
3. Gerät/Emulator wählen → *Run*.

Kommandozeile: `./gradlew assembleDebug` → APK unter `app/build/outputs/apk/debug/`.

Voraussetzungen: JDK 17, Android SDK 35, minSdk 26 (Android 8.0).

## Eingabe der Bahnhöfe

Bahnhofsname, möglichst so geschrieben wie bei der DB (z. B. `Köln Hbf`,
`Frankfurt(Main)Hbf`). Die Vorschläge unter dem Eingabefeld helfen dabei. Bei mehrdeutigen
Namen zeigt die App Vorschläge des Servers an.

Das Feld **Über** ist optional. Gibt es zwischen zwei Bahnhöfen mehrere Strecken, wählt man
damit eine aus. Abfahrten, Liste „Gerade unterwegs“ und Karte zeigen dann nur Züge, die
zwischen Start und Ziel über diesen Bahnhof fahren. Seine Tafel wird immer mit abgefragt. EVA-Nummern und DS100-Kürzel werden
nicht unterstützt, weil die Strecke über die Bahnhofsnamen im Laufweg erkannt wird.

## Projektstruktur

```
app/src/main/java/de/bahnlive/app/
├── MainActivity.kt          Einstieg, Navigation zwischen den zwei Ansichten
├── data/
│   ├── DbfApi.kt            HTTP-Zugriff + tolerantes JSON-Parsen + Cache
│   ├── RouteRepository.kt   Filtert Züge A→B, Bahnhofsfolge, Zusatztafeln
│   ├── MovingTrainFinder.kt Findet alle Züge, die gerade auf der Strecke sind
│   ├── RouteStore.kt        Lieblingsstrecken (DataStore)
│   ├── Stations.kt          Große Bahnhöfe mit Koordinaten
│   ├── StationLocator.kt    Koordinatensuche (OSM Nominatim) + Cache
│   ├── TrainPosition.kt     Positionsschätzung, Streckengeometrie, Farbstufen
│   ├── VendoApi.kt          DB-Fahrtverlauf (inoffizielle DB-Navigator-Schnittstelle)
│   ├── VendoParser.kt       Liest deren Antworten
│   ├── TripEnricher.kt      Verfeinert Züge mit dem Fahrtverlauf, Rückfall auf Schätzung
│   ├── RailRouter.kt        Gleisgraph aus OpenStreetMap, Wegsuche
│   ├── TrackRepository.kt   Lädt und speichert Gleisverläufe (Overpass)
│   ├── SettingsStore.kt     Einstellungen
│   └── Models.kt
└── ui/
    ├── MainViewModel.kt     Zustand, Auto-Refresh
    ├── RouteSelectScreen.kt Streckenauswahl
    ├── BoardScreen.kt       Echtzeit-Anzeige
    └── Theme.kt
```

## Grenzen

- Nur direkte Verbindungen (keine Umstiege).
- „Gerade unterwegs“: Ein Zug, der auf einer sehr langen Strecke an keinem der sechs
  abgefragten Bahnhöfe innerhalb ihres Zeitfensters auftaucht, fehlt. Bei Strecken mit
  vielen Halten zwischen den Messpunkten ist die Position ungenauer.
- Ohne „Über“ zeichnet die Karte Züge einer Parallelstrecke auf der Hauptlinie (der
  Bahnhofsfolge mit den meisten Halten). Mit „Über“ werden sie ausgeblendet.
- Zeitfenster: etwa die nächsten 3 Stunden (Vorgabe von DBF).
- Abdeckung wie DB IRIS: Fern- und Regionalverkehr, S-Bahn; keine Busse/Trams.

## Über GitHub bauen und als APK verteilen

Der Workflow `.github/workflows/android.yml` baut die APK automatisch:

| Auslöser | Ergebnis |
|---|---|
| Push auf `main` oder Pull Request | APK unter **Actions → Lauf → Artifacts** (ZIP mit der APK) |
| Neue Nummer in `version.txt` auf `main` oder Tag `v…` | zusätzlich ein **GitHub-Release** mit der APK zum Herunterladen |
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

Am einfachsten: In `version.txt` die neue Versionsnummer eintragen (z. B. `1.2.0`) und nach
`main` übernehmen. Der Build legt dann automatisch das Release `v1.2.0` mit der APK an.
Bleibt die Nummer gleich, entsteht kein neues Release.

Alternativ geht weiterhin ein Tag:

```bash
git tag v1.2.0
git push origin v1.2.0
```

Nach wenigen Minuten liegt `Bahnschmerz-1.2.0.apk` unter **Releases**. Diesen Link kannst
du weitergeben. Auf dem Handy muss einmalig „Installation aus unbekannten Quellen“ für den
Browser erlaubt werden.


## Lizenz

MIT – siehe [LICENSE](LICENSE).
