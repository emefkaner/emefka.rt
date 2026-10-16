# emefka.rt — Kontur- und Feldgenerator

Ein Onepager, der aus einem Bild geometrische Konturgrafik erzeugt: Kantenfeld
auslesen, Knoten setzen, vernetzen, Linien und Raster darueberlegen, als PNG
sichern.

Alles steckt in **einer einzigen Datei**: [`index.html`](index.html). Keine
Abhaengigkeiten, kein Build, kein Server. Datei im Browser oeffnen, fertig.
Auch von einem USB-Stick oder ohne Internetverbindung.

**Bilddaten verlassen den Browser nicht.** Es gibt keinen Upload, keine
Analytik, keine externen Schriften oder Skripte.

---

## Was die Seite rechnet

| Schritt | Verfahren |
|---|---|
| **Kantenfeld** | Graustufen, Kastenglaettung, Sobel-Operator in X und Y. Daraus Betrag und Richtung des Gradienten. |
| **Kammlinien** | Nicht-Maxima quer zur Kante werden verworfen, sodass nur die Scheitel der Kanten uebrig bleiben. Bezugswert ist das 99,5. Perzentil, nicht das Maximum — eine einzelne harte Kante verdraengt damit nicht alle uebrigen. |
| **Kruemmung** | Richtungsaenderung entlang der Tangente. Steuert, ob Kontrast oder Kruemmung die Knotenwahl bestimmt. |
| **Knotenwahl** | Raeumliche Auswahl mit Mindestabstand. Weil die Kandidaten auf Linien liegen und nicht in der Flaeche, folgt der Abstand der Kurvenlaenge und wird auf die Zielmenge nachgeregelt. |
| **Vernetzung** | Nachbarschaft, Delaunay (Bowyer-Watson, eigene Implementierung), Konturzug, Strahlen, Achsen oder eine Mischung. |
| **Hoehenlinien** | Marching Squares auf dem geglaetteten Helligkeitsfeld. |
| **Stromlinien** | Integration quer zum Gradienten, also den Kanten folgend. |
| **Raster** | Rekursive Rechteckteilung entlang der Kantendichte: dichte Bereiche werden feiner geteilt, ruhige bleiben gross. |
| **Dither** | Atkinson, Floyd-Steinberg, Bayer 4x4 oder stochastisch, mit Kantenschutz und Zweifarbigkeit. |

Jedes Ergebnis haengt nur am Bild und am Seed. Gleicher Seed, gleiches Bild,
gleiche Grafik — auch im Export.

## Bedienung

* **Bild** hineinziehen, einfuegen (Strg+V) oder das Quellenfeld anklicken.
  Ohne eigenes Bild rechnet die Seite auf einer eingebauten, synthetisch
  erzeugten Quelle.
* **10 Voreinstellungen** in Abschnitt 10, dazu `ALLES ZUFAELLIG`.
* **Tasten:** `R` neuer Seed · `S` PNG sichern · `C` halten zeigt die Quelle ·
  `Z` zurueck · `Y` vor.
* **Export:** PNG mit 2160px kurzer Kante. Korngroessen und Strichstaerken sind
  auf 1080px bezogen und werden mitskaliert, das Ergebnis entspricht also der
  Vorschau.

## Grenzen

Bewusst nicht enthalten: Video, Tonreaktion und Personenerkennung
(Silhouette, Skelett, Gesichtsnetz). Der Kern ist die Standbildverarbeitung.

Gerechnet wird immer auf hoechstens 1000px langer Kante, unabhaengig von der
Groesse der Vorlage — dadurch bleibt die Bedienung auch bei grossen Dateien
fluessig. Geladene Bilder werden auf 3000px lange Kante begrenzt.

## Browser

Getestet in Chromium. Benoetigt Canvas 2D, `toBlob` und
`createImageData` — alles seit Jahren ueberall vorhanden.

---

# Ausmisten — Fotos per Wischen sortieren

Eine zweite, eigenstaendige Web-App im Ordner [`ausmisten/`](ausmisten/). Sie
bildet den Funktionsumfang einer Foto-Aufraeum-App nach und laesst sich als
App installieren („Zum Home-Bildschirm“). Sie funktioniert auch offline.

## Funktionen

| Bereich | Was es kann |
|---|---|
| **Wischen** | Links = Archiv, rechts = behalten, Doppeltippen oder Stern = Favorit. Rueckgaengig-Knopf. Tasten `←` `→` `↑` `Z`. Videos laufen stumm, Antippen schaltet den Ton. |
| **Sammlungen** | Alle unsortierten, Zufaellig, An diesem Tag, Screenshots, Videos, Groesste Dateien, dazu jeder Monat einzeln mit Fortschrittsbalken. |
| **Aehnliche und Duplikate** | Bild-Fingerabdruck (128-Bit-dHash). Gleiche Bilder werden immer gruppiert, aehnliche nur innerhalb von 30 Minuten (Serienbilder). Nebeneinander vergleichen, die groesste Datei ist vorausgewaehlt. |
| **Archiv** | Nichts wird sofort geloescht. Zuruecklegen oder behalten per Auswahl, dann mit einem Tipp leeren, mit Anzeige des freigegebenen Speichers. |
| **Favoriten** | Uebersicht, abwaehlen, als ZIP herunterladen. |
| **Fortschritt** | Tagesziel (20/50/100/200), Serie und laengste Serie, Kalender der letzten 17 Wochen, Summen, fertige Monate. |
| **Datum** | Aufnahmedatum aus EXIF, sonst aus dem Dateinamen, sonst das Aenderungsdatum. |

## Loeschen: was ein Browser darf

* **Chrome oder Edge am Computer:** „Fotoordner oeffnen“ gibt der App Schreibrechte
  auf den Ordner. „Archiv leeren“ loescht die Dateien dann wirklich. Der Ordner
  wird gemerkt und kann beim naechsten Mal fortgesetzt werden.
* **iPhone, Android, Firefox, Safari:** Ein Browser darf Dateien in der Galerie
  nicht loeschen. Die App merkt sich alle Entscheidungen. Das Archiv laesst sich
  als Dateiliste herunterladen, und die behaltenen Bilder oder Favoriten als ZIP.

Fortschritt und Entscheidungen liegen nur in diesem Browser (IndexedDB). Die
Fotos selbst werden nirgendwohin hochgeladen.

## Bewusst weggelassen

Tageslimit fuer Wischvorgaenge, Werbung, Abo und Einladungsprogramm. Das sind
Geschaeftsmodell-Funktionen der Vorlage, keine Funktionen zum Sortieren.

## Starten

Die App braucht einen Webserver, damit Installation und Offline-Betrieb gehen.
Zum Beispiel GitHub Pages oder lokal:

```
cd ausmisten && python3 -m http.server 8000
```

Direkt als Datei geoeffnet funktioniert sie auch, nur ohne Installation.
