# NPC Performance: bisheriger Gewinn und nächste Runde

Stand: 1. November 2026. Host 2.15.0 einschließlich größerer Sichtweite ist auf Stage
aktiviert. Die hier ausgewerteten Live-Fenster gehören zur ersten Performance-Runde
`lobby-74f8709a1c09`. Diese Folgeänderung basiert auf veröffentlichtem Host 2.15.0 und
ist noch nicht ausgerollt. Der getrennte Ressourcen-Hotfix gegen fehlende Modellteile
([resourcepacks#60](https://github.com/groundsgg/resourcepacks/pull/60),
[deploy#521](https://github.com/groundsgg/deploy/pull/521)) enthält diese Folgeänderung nicht.

Die anschließenden Korrekturen für gemeinsame Skalierungsdauer (Host#230) und einen
reproduzierten SOLID-Eckfall (Scene#22) wurden getrennt durch deploy#524 aktiviert.
Die anschließend über deploy#525 eingegrenzte Initialisierungslücke gemounteter Shulker
wurde separat durch Host#232/deploy#526 korrigiert. Der Nutzer bestätigt danach stabile
Java-SOLID-Kollision auf beiden Lobbys. Die Performance-Runde bleibt als eigener Entwurf
unaktiviert. Die fehlenden Modellteile wurden vom Nutzer nach dem Ressourcen-Hotfix
ebenfalls als behoben bestätigt.

<!-- POST_SOLID_CONNECTED_START -->
## Lasttest nach dem SOLID-Fix

Der stabile Host wurde auf Stage mit echten Minecraft-26.3-Protokollverbindungen
belastet. 1, 5, 10 und 20 unabhängige Azalea-Clients standen jeweils am normalen
Lobby-Spawn und auf `/npctest`; danach folgten Kamerabewegung und erneutes Betreten
der Plattform mit 20 Clients. Tatsächliche Spielerzahlen wurden am Zielserver
geprüft. Der Spawn enthält bereits sechs Queue-NPCs und ist kein NPC-freier Kontrollfall.

| Clients auf der Testplattform | Mittlere Tick-Zeit | Tatsächliche TPS | Empfangene PLAY-Pakete/s gesamt | Verbindungsabbrüche |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 2,11 ms | 19,99 | 1557 | 0 |
| 5 | 3,06 ms | 19,99 | 7814 | 0 |
| 10 | 4,17 ms | 20,00 | 15699 | 0 |
| 20 | 6,10 ms | 19,99 | 31694 | 0 |

Mit 20 Clients und Kamerabewegungen wurden rund 6,33 ms mittlere Tick-Zeit gemessen;
nach dem Wiedereintritt rund 6,13 ms. Alle zehn stabilen Abschnitte hatten die
erwartete Spielerzahl und keine Verbindungsabbrüche. In den späteren 20-Client-
Abschnitten zeigt die rollierende Tick-Max-Gauge bis 85,16 ms. Frühere Anzeigen
reichen bis 1713,27 ms am Spawn bzw. 1033,67 ms auf der Plattform; sie können aus
vorheriger Initialisierung/Einblendung fortgetragen sein. Daraus lassen sich weder
phasengenaue Einzelmaxima noch individuelle Tick-Perzentile ableiten.
Der Node behielt mehr als 3 GB verfügbaren Speicher.

Die vollständige Stage-Reihe verwendet den bestätigten SOLID-Host
`5115d05ffea229bf91eb50f702f294de31d7fd14b7ee1b51b7394b1a63fc754e`.
Ein temporärer interner Bare-Velocity-Proxy leitete ausschließlich auf die unbenutzte
Testlobby. ClusterIP, verweigerter Ingress, Egress nur zum Zielpod und ein authentifizierter
Loopback-Portforward begrenzten den Zugang. Öffentliche Authentifizierung, Wartung,
Ressourcenpins und Proxy-Replikate blieben unverändert. Testclients, Testproxy,
Service, ConfigMap, NetworkPolicy und Portforwards wurden entfernt. Das ist eine
Backend-/NPC-Lastmessung ohne die globalen Plugins des öffentlichen Proxys.

### Verbundener Vergleich mit der Folgeänderung

Vier frische lokale JVMs liefen in der Reihenfolge Baseline, Kandidat, Kandidat,
Baseline. Jede verwendete dieselbe kopierte Map9, den tatsächlichen Lobby-Loader,
SceneRuntime, Renderer, den nativen SOLID-PoC, einen CPU-Affinitätskern und 512 MiB
Heap. Der Kandidat ist der oben eingefrorene `bf22154…`-Build von Host#233;
die fünf nativen SOLID-Klassen und eingebetteten Packverträge sind bytegleich.
Beide Varianten verwenden dieselben 15 Modelle mit 471 Displays auf der Plattform.

48 Messabschnitte und insgesamt 66.394 einzelne Tick-Zeiten wurden geprüft.
Die Clientzahlen, Entfernungsgruppen, Spielmodi, Original-Map-Digests und fehlerfreien
Prozessenden wurden validiert. Alle vier begrenzten JFR-Dateien haben DataLoss 0.
Nahe, mittlere und ferne Positionen sind `(0,173,2)`, `(24,173,0)` und `(48,173,0)`;
gemischt stehen dort 7/7/6 Clients. Das sind Layoutpositionen relativ zur Szene,
keine pauschalen Entfernungen zu jedem einzelnen NPC. Headless-Clients behalten
ihre Höhe; Positionsgruppen wurden während der Messung geprüft. Eine separate
NPC-freie Kontrolle steht unterhalb der Szene bei `(0,80,0)`.

| 20 Clients | PLAY-Pakete/s Baseline | PLAY-Pakete/s Kandidat | Änderung | Allokation Baseline → Kandidat |
| --- | ---: | ---: | ---: | ---: |
| Nah | 35664 | 35612 | -0,15 % | 34,28 → 32,34 MB/s |
| Mittel | 23863 | 24481 | 2,59 % | 26,79 → 26,08 MB/s |
| Fern | 16270 | 16376 | 0,65 % | 12,30 → 11,94 MB/s |
| Gemischt | 35440 | 25398 | -28,33 % | 28,93 → 26,88 MB/s |

Der Nutzen getrennter Versandkohorten ist bei gemischten Entfernungen klar sichtbar.
Eine vollständig ferne Gruppe wurde bereits vorher gemeinsam gedrosselt. Ein
allgemeiner CPU-Gewinn ist nicht nachgewiesen: nahe 20 Clients liegen im Mittel bei
1,55 → 1,55 ms Tick-Zeit und 0,087 → 0,089 CPU-Kernen. Im gemischten Layout streuen
die Kandidatenläufe deutlich: 1,37 und 2,63 ms gegenüber 1,64 und 1,41 ms Baseline.
Die gemittelten 1,52 → 2,00 ms und die größere P99-Streuung sind daher kein Beleg
für eine Server-CPU-Verbesserung. Die Folgeänderung bleibt auf Stage unaktiviert.

### Gezielte Nachmessung des gemischten Layouts

Ein separates Paar neuer JVMs prüft 20 Clients in derselben 7/7/6-Verteilung.
Nach 40 Sekunden naher Aufwärmzeit und 60 Sekunden im gemischten Layout folgt
ein eigener unprofilierter Messabschnitt; erst danach startet ein 60-Sekunden-Profil
mit 2-ms-Execution-/Native-Abtastung. Whitelist, Datenbegrenzung und Ereignisprüfung
bleiben identisch; der Recorder liegt in einem separaten Classpath-Overlay.
Die ursprünglichen vier Messungen und ihre Klassen wurden nicht verändert.

| Messgröße | Baseline | Kandidat | Änderung |
| --- | ---: | ---: | ---: |
| PLAY-Pakete/s | 34464 | 25197 | -26,89 % |
| Mittlere Tick-Zeit | 1,915 ms | 2,166 ms | 13,10 % |
| P99 Tick-Zeit | 5,837 ms | 7,325 ms | 25,49 % |
| Prozess-CPU | 0,0920 Kerne | 0,1008 Kerne | 9,57 % |
| Allokation | 28,57 MB/s | 26,69 MB/s | -6,56 % |

Beide Läufe hatten durchgehend die erwarteten Clients, keine Verbindungsabbrüche
und ungefähr 20 TPS. Der Zielkonflikt bleibt nach längerer Layout-Aufwärmung sichtbar:
weniger Pakete und Allokation bei höherer CPU- und mittlerer Tick-Zeit. Die
Folgeänderung wird daher nicht als pauschale Server-Performance-Verbesserung freigegeben.
Der frühere Kandidaten-Ausreißer kann damit nicht nachträglich einer einzelnen Ursache
zugeordnet werden.

Die getrennten Diagnoseprofile enthalten 1211 bzw.
1532 Java-Execution-Samples und DataLoss 0. Sie dienen
der Pfadzuordnung, nicht als unprofilierte Durchsatzmessung. Im Baseline-Profil
sind die Hover-Kandidatensammlung und UUID-Sortierung in deutlich mehr Java-Stacks
sichtbar als die eigentliche Ray-Distanzberechnung; letztere bleibt bei den
Array-Allokationen auffällig. Scheduler-Pacing und native Socket-/epoll-Wartepfade
werden getrennt behandelt.

Die gemessenen Renderer-Wall-Timer des Kandidaten betragen pro Tick rund
0,240 ms Lieferung, 0,214 ms Transformation, 0,092 ms Pose und 0,074 ms Sichtbarkeit.
Diese begrenzten Scopes sind keine CPU-Anteile und können sich bei Lebenszyklus-
Übergängen überschneiden. `focused-summary.json` enthält Zähler, Zeitfenster und
Grenzen; die beiden zusätzlichen Manifeste dokumentieren den 2-ms-Recorder.



### Erster Clientbeitritt und Kaltstart

Die lokalen Rohdaten bestätigen unabhängig von der Stage-Gauge eine echte einzelne
Tick-Spitze beim ersten Clientbeitritt in allen vier frischen JVMs:

| Lauf | Einzelner Tick im Abschnitt `join-1` |
| --- | ---: |
| Baseline A | 1751,78 ms |
| Kandidat A | 1791,04 ms |
| Kandidat B | 1645,59 ms |
| Baseline B | 1920,70 ms |

Diese Phase steht vor den warmen Messfenstern und gehört nicht in deren Mittel-
oder Perzentilwerte. Der Kaltstart ist ein eigener Engpass; gute warme 20-TPS-Werte
sind kein Nachweis ruckelfreier erster Beitritte. `cold-tick-analysis.json` ordnet
die einzelnen Tick-Zeitstempel den Controllerphasen zu. Die zeitliche Zuordnung
allein beweist noch nicht, ob Modellinitialisierung, allgemeine Minestom-Daten oder
ein anderer Pfad verantwortlich sind.


Eine zusätzliche einzelne Kaltstart-Aufzeichnung startet die begrenzte 2-ms-JFR
vor dem ersten Client. Sie trifft einen 1878,978-ms-Tick vollständig
im erlaubten Aufnahmefenster. Der genaue Tick-Abschnitt zeigt:

- 1876,26 ms Monitor-Wartezeit des Instance-Tick-Threads auf `InstanceContainer`.
  Der aufgezeichnete vorherige Besitzer ist der Socket-Writer; dessen Java-Stacks
  liegen in `DynamicChunk.createChunkPacket → LightingChunk.createLightData → relightSection`.
- 193 von 226 Java-Samples liegen im Lighting-Worker, insbesondere Sky-/Block-Light-
  Ausbreitung und Occlusion-Prüfungen. Das sind Samplezahlen, keine CPU-Prozentwerte.
- Der Scheduler wartet rund 1878,66 ms an der Tick-Abschlussbarriere.
- GC-Pausen überlappen nur 23,77 ms. Die 723 nativen Samples
  dieses Ausschnitts sind `epoll`-Warten und werden nicht als CPU-Hotspot interpretiert.

Der Zusammenhang aus Lock-Besitzer, Writer-Stacks, Light-Workern und Tick-Barriere
ordnet diese reproduzierte Spitze kalter Chunk-Lichtberechnung beim ersten Versand zu.
`LobbyWorld.open` lädt die Map ausdrücklich mit neu zu berechnenden Lichtdaten.
Vorbereitung/Caching relevanter Spawn- und Plattform-Chunks vor der Freigabe sowie
aufwendige Light-Arbeit unter dem Instance-Monitor sind deshalb eigene P1-Themen
(N33). Licht nicht pauschal deaktivieren; Lichtkorrektheit, neue Bereiche, Änderungen,
Startzeit und Speicherbudget müssen geprüft werden.

Das ist ein separater Diagnosefall, kein weiterer Baseline/Kandidaten-Vergleich.
`cold-spike-proof.json` bestätigt das vollständige Zeitfenster, den einzelnen Client
und den unveränderten Map-Vertrag. `cold-tick-interval-final.json` enthält nur typed
Methoden-/Wartezeit-Aggregate aus dem genauen Tick-Intervall; DataLoss ist 0.


### Profilbefund und nächste Prioritäten

Nach der getrennten Kaltstart-Ursache ist der Sensorpfad der nächste Ansatzpunkt
für den warmen NPC-Betrieb:

1. `MinestomSensorQueries.hoverInteractions` sammelt sämtliche Entities aus einem
   Chunk-Radius von zwei Chunks und sortiert sie nach UUID. Erst danach filtert
   `NpcSensorEngine.hoverCandidates` auf Interaktionsanker und sortiert nochmals
   nach Element-ID. Früh filtern und die notwendige deterministische Ordnung
   erhalten; Mehrfachanker und `oversizedHover` müssen funktionieren (N20).
2. `SceneBoundsRaycaster.distance` baut in diesen Abfragen wiederholt World- und
   inverse Bounds-Transformationen. Diese je Element-/Transformationsrevision
   vorberechnen und bei Root-, Eltern- oder Bounds-Änderungen invalidieren (N32).
3. Verbleibende Renderer-Zerlegung und dekoratives Entity-Ticking gezielt prüfen
   (N11/N12/N23). `PhysicsUtils`/`movementTick` ist in Samples sichtbar; die
   Zuordnung zu konkreten Entity-Typen ist damit noch nicht bewiesen.

In den ersten beiden 20-Client-Profilen zeigen die nach der ersten Sekunde gefilterten
`double[]`-Samples ungefähr 465 → 507 MB-Gewicht unter dem Scene-Raycaster und
277 → 162 MB-Gewicht unter den Display-Transformationen. Das sind Zuordnungen
von Sampling-Druck, keine exakten Allokationsraten oder CPU-Prozentzahlen. Der
unveränderte Modell-Sampler zeigt keinen klaren Unterschied. Listen-/Sortier-Stacks
der Hover-Abfrage sind zusätzlich sichtbar. Die Liste enthält nun 33 Ansätze und
priorisiert N20/N32 nach diesem Befund.

### Messgrenzen und Reproduktion

- Absolute Allokationsraten kommen aus JVM-Rohzählern. Erste JFR-Allokationsgewichte
  enthalten teilweise Vorlaufzeit: ein 6,21-GB-Gewicht nach 6 ms wäre fälschlich als
  Matrix-Hotspot interpretiert worden. Nach Anlaufkorrektur liegen die Samples bei
  rund 33,7–33,8 MB-Gewicht/s; der JVM-Zähler misst 33,95 MB/s. Die gefilterten
  Gewichte bleiben Näherungen, insbesondere bei virtuellen Threads.
- Java-Execution-Samples und native Warte-Samples sind getrennt. `epoll.wait` ist
  keine CPU-Attribution. Die vier 20-ms-Profile haben nur 59–66 Java-Samples je
  Datei; präzise CPU-Anteile einzelner Funktionen werden daraus nicht behauptet.
- Die lokale Zählung verwendet einen identischen `PlayerPacketOutEvent`-Listener
  in beiden Varianten. Seine eigenen Event-Allokationen sind Messaufwand. CPU-
  und Allokationswerte gelten für diese isolierte Scene-Konfiguration, nicht als
  absolute Werte des vollständigen Stage-Hosts mit seinen Runtime-Providern.
- Der Clientzähler zählt empfangene PLAY-Pakete; Serverzähler zählen Metadaten-
  Dispatches. Kein Netzwerk-Bytewert wird aus dem F3-Rx-Wert abgeleitet. Pod-Egress
  enthält Framing, Kompression, Monitoring und weiteren Traffic.
- Kein Java-/Bedrock-FPS-Gewinn wurde gemessen. Horizon Nightly `4aa6854…` wurde
  hashgeprüft und seine CLI geprüft; die isolierte Ressourcenaufbereitung scheiterte
  an fehlenden Linux-Natives im vorhandenen Windows-Modrinth-Bestand. Es wurde
  kein Horizon-Spielerbeitritt behauptet und auf keine Kontodaten zugegriffen.
- Die JFR-Aufzeichnung verwendet eine explizite CPU-/Allokations-/GC-Whitelist;
  keine Umgebung, JVM-Argumente, System-Properties, Heap-Inhalte, File-/Socket-
  Payloads oder Live-Attach-Zugriffe wurden für diese Profile benötigt.

Rohdaten und Reproduktion liegen in `artifacts/npc-load-profile-2026-11-01/`:
`stage-summary.json`, `connected-summary.json`, `verification.json`, die vier
Run-Manifeste, `samples.jsonl`, `tick-durations.csv`, private auditierte JFR-Dateien
und begrenzte `jfr-aggregate.json`-Ausgaben. `allocation-validity.md` und
`allocation-site-diagnosis.md` dokumentieren die Profilkorrekturen.
`npc-performance-results.png/.svg` sowie `npc-cpu-allocation-results.png/.svg`
zeigen Messwerte und Spannweiten. Diagramm-Spannweiten sind keine Konfidenzintervalle.

<!-- POST_SOLID_CONNECTED_END -->

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

Nach Integration der zwischenzeitlichen Korrekturen bestehen 176 lizenzierte Host-Tests
ohne Fehler oder Skips. Alle 54 kompilierten Host-Scene-/Rendererklassen sind bytegleich
zum oben vermessenen V2-Kandidaten; der neue private Prüfbuild enthält zusätzlich die
kollisionsfreien Ressourcen und den Scene-Eckfallfix. Nachweis:
`current-correctness-proof.json`, Build `candidate-correctness.jar` mit SHA-256
`d1ba30145d62d3b49ee2975b505f017d41981c512f05d4851103e12a1c33c6cc`.
Dieser eingefrorene Prüfbuild liegt zeitlich vor der zusätzlichen Initialisierung gemounteter
Shulker aus Host#232. Die Folgeänderung ist inzwischen auf diese Korrektur rebasiert; für
eine Aktivierung ist ein frischer geprüfter Build erforderlich. Der historische Prüfbuild
ist kein Ersatz für den neuesten Stage-Kollisionsbuild.

Die auf Host#232 rebasierte Folgeänderung besteht 179 lizenzierte Tests ohne Skips.
Ihr neuer Prüfbuild `candidate-post-mounted.jar` hat SHA-256
`bf22154ee298595007b46dc520cfba0d23b83196351f3cd44f47a8fef2c8cac6` und akzeptiert
denselben Map9-/Addon-Vertrag. Der öffentliche neutrale Build enthält weiterhin keine
privaten NPC-Modelle. Die Folgeänderung ist nicht auf Stage aktiviert.

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
