# NPC Performance: bisheriger Gewinn und nächste Runde

Stand: 1. November 2026. Host 2.15.0 einschließlich größerer Sichtweite ist auf Stage
aktiviert. Die hier ausgewerteten Live-Fenster gehören zur ersten Performance-Runde
`lobby-74f8709a1c09`. Diese Folgeänderung basiert auf veröffentlichtem Host 2.15.0 und
ist noch nicht ausgerollt. Der getrennte Ressourcen-Hotfix gegen fehlende Modellteile
([resourcepacks#60](https://github.com/groundsgg/resourcepacks/pull/60),
[deploy#521](https://github.com/groundsgg/deploy/pull/521)) enthält diese Folgeänderung nicht.

## Live-Beobachtung

Verglichen wurden 12:00–12:10 Uhr und 12:26–12:36 Uhr **Europe/Berlin** am 1. November,
jeweils 600 Sekunden mit durchgehend drei Spielern. Die Zeitfenster liegen nach dem jeweiligen
Start; die zweite Messung beginnt mindestens fünf Minuten nach dem ersten Spielerbeitritt.
Die Entity-Bestände zeigen die geladene NPC-Scene in beiden Fenstern.

| Messgröße | Vorher | Erste Runde auf Stage | Beobachtete Änderung |
| --- | ---: | ---: | ---: |
| Mittlere Tickarbeit aus rohen Summe-/Zählerdifferenzen | 7,063 ms | 2,475 ms | −65,0 % |
| Allokation, Median der 5-Minuten-Raten | 87,29 MB/s | 14,19 MB/s | −83,7 % |
| CPU, Median in CPU-Kernen | 0,201 | 0,119 | −40,6 % |
| Gesamter Pod-Ausgang über eth0 | 712,5 kB/s | 64,0 kB/s | −91,0 % |
| Entities, Median | 1115 | 676 | −39,4 % |
| TPS aus rohen Tickzähler-Differenzen | 20,000 | 19,925 | Keine Behauptung einer TPS-Steigerung |
| Größte erfasste Tickspitze im Fenster | 36,0 ms | 436,4 ms | Spitzen sind weiterhin vorhanden |

MB/kB sind dezimale Einheiten. Spielerpositionen und Bewegungen waren nicht identisch
erfasst; JVM-Alter und begleitende Serveränderungen unterscheiden sich. Pod-Egress enthält
auch andere Pakete und Monitoring. Diese Zahlen sind deshalb eine Live-Beobachtung und
keine isolierte kausale Prozentmessung des NPC-Codes. Die große Tickspitze kann ohne
zeitgleiches Serverprofil keiner einzelnen Ursache zugeordnet werden. Der laufende Host
hat kein Shell-/Diagnosewerkzeug für eine unkomplizierte Live-JFR-Aufzeichnung bereitgestellt.

Rohdaten, Abfragen und Berechnung:
`artifacts/npc-performance-next-2026-11-01/stage-query-manifest.json`,
`stage-impact-analysis.json`, `collect_stage_metrics.py`, `summarize_stage_metrics.py`.
Rohe Counter werden je Pod verglichen; ausgelaufene Pods und 5-Minuten-Extrapolation werden
nicht als aktuelle TPS interpretiert.

## Kontrollierter lokaler Vergleich der ersten Runde

Der eingefrorene Sichtweiten-Kandidat enthält 15 geteilte NPC-Handles und 471 Displays.
Zwei unabhängige JVM-Läufe mit je fünf Warmups und sieben Messwiederholungen vergleichen
dieselbe Szene mit dem Original aus 765 Displays. Alle Betrachter hängen an denselben
Handles; der Test vervielfacht keine Skelette pro Spieler.

| Ein naher Betrachter | Original Pakete/s | Erste Runde Pakete/s | Original Metadaten B/s | Erste Runde B/s |
| --- | ---: | ---: | ---: | ---: |
| Idle | 5867,9 | 1855 | 327630,5 | 69865 |
| Kontinuierliche Kopfbewegung | 6903,9 | 1855 | 385228,9 | 69865 |
| Körper- und Kopfbewegung | 8448,7 | 1960 | 470893,5 | 73835 |

Das entspricht 68–77 % weniger Metadatenpaketen und 79–84 % weniger Metadaten-Nutzdaten.
Im reinen Zählmodus sinkt die mittlere Renderer-CPU im Idle von 0,712 auf 0,111 ms pro
simuliertem Tick, die Allokation von 76,58 auf 10,37 MB pro simulierter Sekunde. Die Messung
mit echter Metadaten-Serialisierung wird separat geführt, weil der Mess-Serializer selbst
allokiert und Rechenzeit verbraucht. Framing, Kompression, Socketarbeit und Client-Rendering
sind damit weiterhin nicht gemessen.

## Auswahl der nächsten Änderungen

Mit gleichzeitig nahen/mittleren/fernen Betrachtern bei 2/24/48 Blöcken erhalten bisher alle
1855 Pakete/s. Ein alleiniger ferner Betrachter benötigt dagegen nur ungefähr 740 Pakete/s.
Der nächste Spieler bestimmt bisher also auch den Versand an die anderen.

Die lokale JFR-Aufzeichnung ohne Mess-Serialisierung enthält 371 NPC-CPU-Stichproben:
rund 51 % entfallen auf Display-Transformation/Zerlegung und 30 % auf Pose-/Matrixmathematik.
`double[]` macht 65 % der erfassten allokierten Bytes aus. Dies ist ein grobes lokales Profil,
keine Attribution der 436-ms-Stage-Spitze.

Die Folgeänderung bereitet deshalb Root-/Local-Affines einmal pro Scene-Transform vor,
vermeidet boxed Quaternion-/Skalen-Zwischendaten und sammelt Deltas je 200/250/500-ms-Kohorte.
Ein NPC behält eine geteilte Pose. Langsame Betrachter erhalten alle seit ihrem letzten
Versand geänderten Felder, vollständige Einstieg-/Bandwechsel-Snapshots und passende
Interpolation. Stop/Seek, persönliche Größe/Highlights und unveränderliche Paketdaten
sind durch Regressionen geschützt.

Ein erster Prototyp hatte acht Prozent zusätzliche Nutzdaten bei nahen Betrachtern durch
ein wiederholtes konstantes Dauer-Feld. Das wurde vor Freigabe entfernt. Auch unnötige
Map-Kopien wurden anhand des Profilvergleichs durch sichere Besitzübergabe vermieden.
Die abschließende Messung umfasst 24 erfolgreiche JVM-Läufe mit 504 Messzeilen:
zwei unabhängige JVMs und je sieben Wiederholungen nach fünf Warmups pro Szenario.
Der eingefrorene Kandidat hat SHA-256
`b94da1bcdac731d5527d2da16da4422758b08a0395042eb94aa528b5558068f7`.

| Drei gleichzeitige Betrachter im Idle | Bisher je Spieler: Pakete/s / B/s | Folgeänderung: Pakete/s / B/s |
| --- | ---: | ---: |
| Nah, 2 Blöcke | 1855 / 69875 | 1855 / 69875 |
| Mittel, 24 Blöcke | 1855 / 69875 | 1484 / 55900 |
| Fern, 48 Blöcke | 1855 / 69875 | 742 / 27950 |
| Summe | 5565 / 209625 | 4081 / 153725 |

Das sind in dieser Anordnung zusätzlich 26,67 % weniger Metadatenpakete und Nutzdaten.
Die Nahansicht behält denselben Verkehr. Mit echter Prometheus-Registry ist der Verkehr
identisch zum Lauf ohne Metriken; das konstante Interpolationsdauer-Feld wird nicht wiederholt.

Im Idle mit Mess-Serialisierung sinkt die Renderer-CPU pro simuliertem Tick nah von
0,1526 auf 0,1173 ms und gemischt von 0,2373 auf 0,2114 ms, jeweils mit eingeschalteten
Metriken. Ohne Mess-Serialisierung sinkt die Allokation nah von 10,35 auf 7,91 MB/s und
gemischt von 10,78 auf 9,62 MB/s. Einzelne reine Rechenzeitmessungen im gemischten Layout
liegen innerhalb überlappender Streubereiche oder höher; deshalb wird kein universeller
CPU-Gewinn jeder Teilmessung behauptet. Socketarbeit, Kompression, echte TPS und
Client-Frametimes bleiben außerhalb dieses lokalen Vergleichs.

Reproduktion und alle Streubereiche: `artifacts/npc-performance-next-2026-11-01/`
mit `cohort-v2-conclusion.txt`, `cohort-v2-summary.json`, `cohort-v2-verification.json`,
`NpcPerfProbeV2.java` und den einzelnen JSONL-/Prometheus-Ausgaben.

Der spätere Stage-Pod mit nur 204 Entities und zwei Spielern ist kein weiterer
Performance-Nachweis: Dort war die Testplattform nach dem Neustart noch nicht geladen.
Vor dem nächsten Livevergleich müssen vollständige sichtbare Modelle, geladene Plattform,
Spielerzahl und Entfernungen geprüft sein. Die Ressourcen-Kollision war ein visueller
Fehler und darf nicht als erwünschte Vereinfachung der Geometrie zählen.

## Begrenzte Laufzeitmetriken

Die Folgeänderung verwendet die vorhandene Runtime-MeterRegistry und deren Endpoint.
Vier Phasen (`pose`, `transform`, `visibility`, `delivery`) erhalten 13 feste Histogrammgrenzen
von 10 µs bis 500 ms. Es gibt nur Modell-/Phasen-/Paketart-Labels, keine Spieler- oder NPC-IDs.
Ohne Runtime-Registry bleibt die Instrumentierung deaktiviert.

```promql
histogram_quantile(0.95,
  sum by (le, model, phase) (
    rate(grounds_npc_work_duration_seconds_bucket{namespace="stage"}[5m])
  )
)

sum by (model, kind) (
  rate(grounds_npc_metadata_packets_total{namespace="stage"}[5m])
)
```

Die Zähler erfassen eigene Metadaten-Dispatches/Felder, einschließlich expliziter Snapshots,
und keine Vanilla-Spawn-/Positionspakete. Sie sind weder Socket-ACKs noch tatsächliche
komprimierte Wirebytes. Für Metriken wird kein Paket zusätzlich serialisiert. Die Phase
`visibility` enthält bei Lebenszykluswechseln Resynchronisation; Phasenzeiten sind deshalb
nicht ohne Weiteres als disjunkte Gesamt-CPU addierbar.
