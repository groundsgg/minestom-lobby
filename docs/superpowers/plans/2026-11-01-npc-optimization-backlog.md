---
title: NPC Optimierungen aus anderen Plugins
description: Priorisierter Umsetzungsstand und weitere übertragbare Ansätze für Grounds NPC Rendering.
---

# NPC Optimierungen aus anderen Plugins

Stand: 6. November 2026. Ziel sind weniger Serverarbeit, weniger tatsächlich übertragene Daten und weniger Clientarbeit bei erhaltener Geometrie, Animation und Interaktion. Die Liste ist der Arbeitsbacklog für die geprüften Ansätze aus ModelEngine, MythicMobs, Oraxen, ItemsAdder und BetterModel.

**Status:** „Umgesetzt“ bezeichnet den geprüften Performance-Kandidaten; der Live-Stand wird im Rollout-Nachweis geführt. „Teilweise“ bedeutet, dass der Grundmechanismus existiert, die beschriebene Erweiterung noch fehlt. „Offen“ ist keine Zusage eines bereits gemessenen Gewinns.

**Priorität:** P1 zuerst untersuchen/umsetzen, P2 danach, P3 nur bei nachgewiesenem Bedarf. **Aufwand:** S isolierte Änderung, M Änderung über mehrere Komponenten, L Architektur- oder Formatänderung. Exakte Prozentgewinne werden erst nach kontrollierten Vergleichsmessungen eingetragen.

**Quellen:** Die Links belegen die ausdrücklich genannten Plugin- oder Formatfunktionen. Akzeptanzkriterien und Übertragungen auf Grounds sind unsere Engineering-Entscheidungen. Proprietäre Interna werden nicht vorausgesetzt.

## Maßgeblicher Stand (6. November 2026)

Die folgenden älteren Tabellen und Nachträge bleiben als Messhistorie erhalten. Maßgeblich ist dieser jüngste Abschnitt: Die konservativen Composites (348 Plattform-/451 Scene-Displays), Hover-/Viewer- und Kollisionskorrekturen sind integriert und auf beiden Stage-Lobbys abgenommen. Host#250 ersetzt Klassenüberlagerungen durch einen vollständig geprüften privaten Source-Build; Maps#100 erhält den passenden unveränderlichen Katalog als begrenzten zusätzlichen Kandidaten. Map 12 mit vorberechneter Beleuchtung läuft über deploy#580/#581 auf beiden Lobbys, auf allen drei Proxys registriert und vom Nutzer bestätigt. Öffentliche PackSet-Kanäle und Production-Pins wurden nicht umgestellt.

**N24-Folgeversuch, nicht übernommen:** Host#251 bereitet den visuellen Standardzustand einmal je Metadatenpublikation vor.24 → 0 persönliche Lookup-Aufrufe im 12-Betrachter-/2-Teile-Test,214 reguläre Tests und kompletter privater Build sind korrekt. Die vier vollständigen21/61-Verbindungs-Läufe (20/60 Animationsclients + 1 fester Queue-Beobachter; 451 Displays) zeigen jedoch CPU+3,33 % stehend / +6,06 % bewegt bei 20 Animationsclients. Der 60-Bewegungsvorteil wiederholt sich nicht: CPU -14,08 % im ersten, +3,31 % im umgekehrten Paar. Kandidat geschlossen; Stage bleibt unverändert. [Alle günstigen/ungünstigen Fenster und Grenzen](2026-11-06-npc-common-visual-dispatch-results.md).

Weitere Arbeit wird anhand eines Profils des abgenommenen vollständigen Source-Builds priorisiert. Offene Ansätze wie WorldBounds-Caches (N32), räumliche Viewer-Kandidaten (N20) oder Sichtkegel-/Verdeckungsarbeit bleiben eigenständige Kandidaten; die korrigierte konstante Messanordnung ist dafür wiederverwendbar. Es wird kein weiterer Gewinn ohne getrennte Messung als umgesetzt verbucht.

## Bereits umgesetzt oder vorhanden

| Optimierung | Grounds Stand | Herkunft oder Begründung |
| --- | --- | --- |
| Gemeinsam bewegte Würfel bündeln | Umgesetzt, konservativ: 765 → 471 Displays; unterschiedliche Rotation/Pivots bleiben bei Bedarf getrennt. | Bone-basierte Modellaufteilung in [ModelEngine][me-model] und [BetterModel][bm-blueprint]. |
| Unveränderte Transformationsfelder weglassen | Umgesetzt; insbesondere keine identischen Quaternion-Arrays erneut setzen. | Direkter Befund im Grounds-Paketpfad; vollständige Deltas statt Scheingewinn durch Bundles. |
| Animation und Kopf-/Körperbewegung zusammenführen | Umgesetzt; ein begrenzter Renderpfad. | Grounds-Befund; animierte Bone-Hierarchie als gemeinsame Recheneinheit. |
| Client-Interpolation zum Update-Takt passend setzen | Umgesetzt: 200 ms bis 16 Blöcke, 250 ms bis 32 Blöcke, danach 500 ms; 4/5/10 Clientticks nach nächstem relevanten Betrachter. | [Display-Interpolation][mc-display]. |
| 3D-Sichtweite pro Station und Betrachter | Umgesetzt: Eintritt bis 64 Blöcke, bestehende Betrachter bis 72 Blöcke; unabhängig davon höchstens 32 Blöcke Höhenabstand und gleiche Instance. Trennt die 33 Blöcke höhere Testplattform vom Lobbyboden. | Entfernung und vertikale Relevanz in [ModelEngine][me-config]. |
| Keine Pose ohne Betrachter berechnen | Umgesetzt; Animationszeit läuft weiter, Wiedereintritt erhält den aktuellen Zustand. | Sichtbarkeit als Voraussetzung für Renderarbeit. |
| Kleine Kopfänderungen sammeln | Umgesetzt: 2° relativ zur zuletzt angewendeten Richtung; Kopf und Kinder ändern sich unabhängig vom Körper. | Kopf-Bones in [ModelEngine][me-bones]. |
| Stationäre Anker bei Körperdrehung nicht teleportieren | Umgesetzt. | Direkter Grounds-Befund. |
| Dekorative Physik und periodische Positionspakete vermeiden | Umgesetzt; explizite Ankerbewegungen synchronisieren weiterhin. | Trennung zwischen Darstellung und autoritativen Hitboxen, wie bei [Oraxen][oraxen] und [ModelEngine][me-base]. |
| Bind-Matrizen und Bone-Indizes vorhalten | Umgesetzt; Originalkurven bleiben erhalten. | Eigene Grounds-Optimierung, nicht als universelles Plugin-Internum behauptet. |
| Eine gemeinsame Pose pro NPC berechnen | Bereits vorhanden; die Pose wird nicht für jeden Betrachter erneut ausgewertet. | Vergleichspunkt zu globalen/persönlichen Renderpfaden in [BetterModel][bm-pipeline]. |
| Standard-Paketobjekte zwischen Betrachtern teilen | Umgesetzt; persönliche Größe/Highlights werden gesondert berücksichtigt. | Rechenarbeit und temporäre Objekte im Versandpfad reduzieren. |
| Konvergierte Matrixzerlegung beenden | Umgesetzt; allgemeiner Pfad für Scherung, Null- und negative Skalierung bleibt bestehen. | Direkter Grounds-Befund; `return@repeat` war kein Schleifenabbruch. |
| Vollständiger Zustand beim Sichtbarkeitseintritt | Umgesetzt einschließlich persönlicher Skalierung/Highlights und Stop-/Wiedereintrittsregressionen. | Explizite Renderinitialisierung in [ModelEngine][me-options]. |

Kontrollmessung mit 15 Modellen und einem erzwungenen Betrachter: Idle 5.866,8 → 1.855 Metadatenpakete/s; mit drei bewegten Köpfen/Körpern 8.448 → 1.960. Serialisierte Metadaten-Nutzdaten sinken um 79–84 %, temporäre Allokationen inklusive Messinstrumentierung um 86–91 %. Dies ist keine Live-TPS- oder Client-Frametime-Messung.

## Geometrie und Animation

| ID | Priorität / Aufwand / Status | Ansatz und Nutzen | Abnahme und Grenzen | Quelle |
| --- | --- | --- | --- | --- |
| N01 | P1 / M / Offen | Ein zusammengesetztes Item-Modell je Bone, auch bei unterschiedlich rotierten Würfeln. Weniger Entities und Transformationspakete. | Jeden Texturwürfel, Pivot, Inflate, UV und jede Vorzeichen-Skalierung erhalten; Originalclips und persönliche Farben prüfen. Composite-Untermodelle können weiterhin mehrere Draw Calls benötigen. 179 Bones sind eine Größenordnung, kein garantierter Entity-Zielwert. | BetterModel gruppiert Rotationscluster in [BlueprintElement][bm-blueprint] und kombiniert sie in [ModelManagerImpl][bm-modelmanager]. |
| N02 | P2 / M / Alternative zu N01 | Moderne Elementrotationen oder affine Untermodelle als einfacherer Compilerpfad. | Client-/Pack-Versionen und Bedrock-Übersetzung prüfen. Gleicher Nutzen wie N01, nicht zusätzlich addieren. | [Mojang 1.21.11][mc-12111] erweitert Elementrotationen; [26.1][mc-261] erweitert Item-Modelltransformationen. |
| N03 | P2 / M / Offen | Dauerhaft starre Kind-Bones in den Eltern-Bone einbacken. | Über alle Clips/Verhaltensweisen beweisen; Köpfe, Attachments, API-Ziele, getrennte Sichtbarkeit/Färbung ausschließen. | Bone-basierte Aufteilung in [ModelEngine][me-model]; Zusammenlegung ist unser Importvorschlag. |
| N04 | P3 / L / Offen | Vereinfachte entfernte Modellvarianten. Vor allem weniger Clientarbeit. | Silhouette und saubere Umschaltung mit Hysterese; weniger Würfel innerhalb derselben Entities spart allein keine Metadatenpakete. | [ChangePart][me-part], [RemapModel][me-remap]. |
| N05 | P1 / S / Bereits vorhanden | Gemeinsame Körperpose pro NPC über Betrachter teilen; nur notwendige persönliche Anteile ergänzen. | Heute wird der nächste Spieler global verfolgt. Persönliches Kopf-Tracking ist eine separate Produktentscheidung, keine Voraussetzung dieser Optimierung. | Getrennte globale/persönliche Pfade in [RenderPipeline][bm-pipeline] und [RenderedBone][bm-bone]. |
| N06 | P2 / M / Offen | Fertige Posen gleicher Modelle zwischen NPC-Instanzen teilen. Spart Arbeit bei wiederholten Queue-Modellen. | Schlüssel aus Asset-/Clip-Version, Samplezeit, Blending und Parametern; begrenzter Speicher und Reload-Invalidierung. | Gemeinsame importierte Daten in [ModelEngine-Terminologie][me-terms] und [BlueprintAnimation][bm-animation]; vollständiger Posecache ist unser Vorschlag. |
| N07 | P1–P2 / M / Teilansatz geprüft, nicht aktiviert | Wiederverwendbare Clip-Samples oder fertige Display-Transformationen beim Import vorberechnen. | Fehler gegen Originalkurven messen; Speicher/Importzeit begrenzen; Kopf-Layer, variable Geschwindigkeit und Übergänge weiter dynamisch behandeln. | Aufbereitete Zwischenframes in [AnimationGenerator][bm-generator]. |
| N08 | P2 / M / Offen | Fehlerbegrenztes adaptives Sampling je Clip statt pauschal noch weniger Updates. | Schritt-Keyframes, Loop-Grenzen, Rotationswindungen und schnelle Gliedmaßen erhalten; Positions-/Winkelfehler ausdrücklich begrenzen. | [AnimationGenerator][bm-generator] behandelt Zwischenframes und Schritte; Fehlerkompression ist unser Vorschlag. |
| N09 | Profilabhängig / S / Offen | Laufende Keyframe-Cursor statt wiederholter Suche. | Seek, Reverse, Loop, Clipwechsel und Geschwindigkeitsänderung müssen Cursor zurücksetzen oder binär suchen. | [AnimationIterator][bm-iterator]. |
| N10 | P1 / M / Teilweise | Nur geänderte Kanäle und davon abhängige Teilbäume auswerten. | Paketdeltas und konstante Bind-Teile sind erledigt; Elternänderungen müssen alle betroffenen Kinder markieren. | Leere Progress-Daten und frühe Abbrüche in [AnimationProgress][bm-progress] und [RenderPipeline][bm-pipeline]. |
| N11 | P2 / M / Offen | Direkter TRS-Pfad für bewiesene starre/uniform skalierte Transformationen. | Allgemeine Zerlegung als Fallback behalten: verschachtelte nichtuniforme Skalierung plus Rotation kann Scherung erzeugen. Rekonstruktionsfehler testen. | [Display-Transformationsformat][mc-display]; schneller Pfad ist unsere mathematische Ableitung. |
| N12 | P1 nach Profil / M / Teilweise | Primitive Clip-Arrays und klar zugeordnete wiederverwendbare Scratch-Matrizen. | Bind-Caches und Indizes existieren. Keine veränderlichen Pakete wiederverwenden, solange sie noch in einer Versandwarteschlange liegen. | Primitive Keyframes und Zielobjekte in [AnimationKeyframe][bm-keyframes]. |

## Sichtbarkeit und Betrachter

| ID | Priorität / Aufwand / Status | Ansatz und Nutzen | Abnahme und Grenzen | Quelle |
| --- | --- | --- | --- | --- |
| N13 | P1 / M / Kandidat für Stage-Test | Wirklich getrennte Animationsraten pro Betrachter. | Heute bestimmt der nächste Betrachter den gemeinsamen Takt. Entfernte Betrachter sollen eigene Deltas und Interpolationsdauer erhalten; ein naher Spieler darf nicht alle beschleunigen. | Betrachterbezogene Entfernungsraten in [ModelEngine][me-config]. |
| N14 | P1–P2 / M / Offen | Hinter dem Betrachter nur notwendige Bewegung, keine Gelenkanimation übertragen. | Nahbereich ausnehmen; beim Umdrehen sofort aktuelle Pose wiederherstellen. Third-Person/Spectator berücksichtigen. | `MOVEMENT_ONLY` in [CullConfig][me-cull] und [CullType][me-terms]. |
| N15 | P2 / M / Offen | Großzügiger horizontaler/vertikaler Sichtkegel vor teurerer Arbeit. | Keine exakte Frustum-Erkennung behaupten: FOV, Seitenverhältnis und Kameraposition sind nicht vollständig bekannt. Animierte Modellgröße als Randzugabe. | Winkel-/Entfernungsprüfung in [EntityUtil.canSee][bm-visibility]. |
| N16 | P2 / M / Offen | Verdeckung durch undurchsichtige Blöcke prüfen. | Entfernung/Kegel zuerst; Glas, Zäune, Türen, bewegte Blöcke und ungeladene Chunks konservativ behandeln. Raycasts dürfen nicht mehr kosten als sie sparen. | Blocked-model culling in [ModelEngine][me-config]. |
| N17 | P1–P2 / M / Teilweise | Sichtbarkeitsprüfungen cachen, verteilen und mit Ein-/Austrittshysterese versehen. | Ein Refresh-Intervall existiert. Teurere Prüfungen verteilen; bei relevanter Bewegung/Blockänderung schnell invalidieren, Randflackern vermeiden. | Culling-Intervalle in [ModelEngine][me-config]; Verteilung und Hysterese sind unsere Erweiterung. |
| N18 | P1 Audit / S–M / Offen | Korrekte Client-Culling-Bounds für Displays generieren. | Alle Animationsextrema, Offsets und persönliche Skalierung einschließen. Kleine Bounds lassen Gliedmaßen verschwinden; Client-Culling spart allein keine Serverpakete. | Display-Breite/Höhe in [Mojang][mc-display], Anwendung in [Oraxen][oraxen]. |
| N19 | P2 / M / Teilweise | Labels/Namensschilder unabhängig vom Körper sichtbar halten und aktualisieren. | Grounds-Labels sind schon getrennte Entities. Relevanzgrenzen prüfen, ohne Stationsnamen und Zugänglichkeit zu verschlechtern. | Namenssichtbarkeit in [BetterModel EntityUtil][bm-visibility]. |
| N20 | P1 nach Profil / M / Teilweise | Räumliche Kandidatenlisten für Viewer und NPCs. | Scene-Sensorik besitzt bereits räumliche Abfragen; Renderer scannt noch lokale Instance-Spieler. Index über Wechsel/Disconnect korrekt halten. Netzwerkaktionen und Identitäten bleiben proxyübergreifend. | AABB-Abfragen im [MythicMobs-Changelog][mm-changelog]. |

## Versand und Lastbegrenzung

| ID | Priorität / Aufwand / Status | Ansatz und Nutzen | Abnahme und Grenzen | Quelle |
| --- | --- | --- | --- | --- |
| N21 | P1 / S–M / Phasenkandidat geprüft, nicht aktiviert | Animationsdeadlines und Spawn-Arbeit gleichmäßig über Ticks verteilen. | Host#238 verteilt synthetische Updates, zeigt aber bei 60 bewegten Clients CPU +14,6 %, mittlere Tickzeit +23,0 % und Metadatenpakete +12,0 %. Deshalb Entwurf; initiale Entity-Erzeugung wird getrennt als N35 geprüft (aktueller Stand im letzten Abschnitt). [Nachweis](2026-11-02-npc-animation-phases-results.md). | Periodische Tracker in [BetterModel][bm-tracker]; unser Scheduler-Vorschlag. |
| N22 | P2 / M / Offen | Gewichtetes Renderbudget aus Displayzahl, Bytes, Nähe und Bedeutung. | Maximale Wartezeit garantieren; nahe Interaktionsziele bevorzugen. Keine starre NPC-Obergrenze ohne Bezug zur Modellkomplexität. | Gewichtetes Budget ist unser Vorschlag. Die aktuelle ItemsAdder-Seite belegt Paketverzögerung, kein hier erneut bestätigtes Per-Chunk-Limit. |
| N23 | P2–P3 / L / Offen | Rein paketbasierte dekorative Displays bei serverseitigen Gameplay-Hitboxen. | IDs, Spawn/Despawn, Weltwechsel, Respawn, Cleanup und Zuordnung korrekt verwalten. Client-Entities und notwendige Metadaten bleiben bestehen; deaktivierte Physik reduziert den Zusatzgewinn bereits. | Nicht-Entity-Basen in [ModelEngine][me-base], Paket-Displays in [MythicMobs][mm-packet]. |
| N24 | P1–P2 / M / Gemeinsamer Standardpfad umgesetzt | Identische Metadaten über die native gruppierte Versand-API gemeinsam serialisieren. | Erst ab zwei gemeinsamen Empfängern; persönliche Skalierung/Flags getrennt, Übersetzung und Cache-Regeln bleiben nativ. 175 Host-Tests, 723.456 bitidentische Modellzustände; bei 60 Clients Allokation −24,2 %, CPU −4,3 %. Keine einheitliche Tickzeit-/RX-Verbesserung. Kohorten/LOD aus #233 bleiben getrennt offen. [Nachweis](2026-11-01-npc-shared-metadata-results.md). | Globale/persönliche Pakete in [BetterModel Tracker][bm-tracker], Dispatch-Optimierungen in [MythicMobs][mm-changelog]; native Minestom-API. |
| N25 | P2 / S–M / Offen | Begrenzte atomare Bundles für zusammengehörige Updates. | Innere Paketanzahl, echte Bytes und Client-Frametimes messen. Zu große Bundles erzwingen viel Arbeit in einem Clienttick. | Bundle-Grenzen in [ModelEngine][me-config]. |
| N26 | P1 Voraussetzung / M / Teilweise | Explizite Initialisierung und Resynchronisation bei echten Lebenszykluswechseln. | Vollständiger Sichtbarkeitseintritt ist vorhanden. Für LOD/Reconnect/Reload Delta-Basen explizit zurücksetzen; keine pauschalen Vollupdates als Dauerlösung. | RenderInit in [Model-Optionen][me-options], Resync in [Befehlen][me-commands], Initial-load-Pfade in [RenderPipeline][bm-pipeline]. |
| N27 | P1 zuerst / M / Teilweise | Modellbezogene Kostenmessung für Pose, Zerlegung, Versand und Sichtbarkeit. | Lokaler Benchmark vorhanden; zusätzlich begrenzte Laufzeit-Histogramme, p95/p99, echte Bytes, innere Pakete, Allokationen, Cull-Gründe und Alter des letzten Updates. Kein Tick-für-Tick-Logging. | Updater-/Tracker-Timings von `/meg health` in [ModelEngine][me-commands]. |

## Profilbefunde nach dem SOLID-Fix

| ID | Priorität / Aufwand / Status | Ansatz und Nutzen | Abnahme und Grenzen | Quelle |
| --- | --- | --- | --- | --- |
| N32 | P1 nach Profil / M / Hover-Inverse umgesetzt | Inverse Bounds-Transformationen je Elementrevision vorberechnen. | Elementeigener unveränderlicher Cache mit Root-/Local-/Bounds-Lebenszyklus und Dreh-/Scherungsregressionen.272Scene/170Host-Tests grün. Getrenntes20Client-Paar: Allokation -20,1%, Tickzeit -1,1%, CPU +2,5%; kein CPU-Gewinn behauptet. WorldBounds-/Kollisionscache bleibt bewusst offen. | Eigene verbundene Profile: SceneBoundsRaycaster/AffineTransform verursachen weiterhin viele double[]-Allokationen. |
| N33 | P1 / M / Auf Stage bestätigt | Bereits gespeicherte Map-Beleuchtung verwenden; für unbeleuchtete Maps relevante Chunks vor Eintritt vorbereiten. | Keine neue Bake-Pipeline: service-maps#74 existiert seit19.Oktober. Lobby ignorierte den vorhandenen lit-Pin. Auswahl, Prüfsummen, Originalidentität und Fallback korrigiert;170Tests,6kalte verbundene Läufe, Stage-Nutzertest ohne merklichen Lag. | [Nachweis](2026-11-01-npc-cold-terrain-results.md). |

N20 ist in Scene#24 übernommen und einzeln gemessen: Interaktions-Owner bereits im Tracker-Callback auswählen, bevor die471dekorativen Displays gesammelt/sortiert werden. Ein deterministischer LocalId-Sort bleibt erhalten.268Scene-Tests und170Host-Tests bestehen. Im kontrollierten20Client-Paar sinken CPU18,2%, mittlereTickzeit38,6% undAllokation7,2%; Auf beiden Stage-Lobbys über deploy#546 zusammen mit dem separat geprüften N32-Ergebnis aktiv. Die getrennten171–187ms erster Entity-Erzeugung auf kalten prelitMaps werden inzwischen mit N35 untersucht; spätere Nachträge führen den aktuellen Stand. Der cohortbasierte Kandidat aus Host#233 bleibt wegen gemessenem CPU-Nachteil unabhängig davon Entwurf; die Traffic-Gewinne allein rechtfertigen keine Aktivierung.

## Clientarbeit und Parallelität

| ID | Priorität / Aufwand / Status | Ansatz und Nutzen | Abnahme und Grenzen | Quelle |
| --- | --- | --- | --- | --- |
| N28 | P2 / M / Offen | Dauerhaft innere Flächen entfernen und unnötige Transluzenz vermeiden. | Über alle relativen Bewegungen beweisen; zwischen bewegten Bones sichtbare Flächen erhalten. Beabsichtigte transparente Kunst nicht verändern. | Generatorverbesserungen in [BetterModel-Releases][bm-releases], Render-Pässe in [Mojang 26.1][mc-261]. |
| N29 | P3 / M / Assetabhängig | Reine Oberflächeneffekte durch Texturanimation statt zusätzlicher Gelenk-Entities. | Nur visuell gleichwertige Effekte; keine Silhouettenbewegung ersetzen. Clientarbeit und Texturspeicher ebenfalls messen. | MC-META-Generierung in [ModelEngine][me-config], Sprite-Animation in [Mojang 1.21.11][mc-12111]. |
| N30 | P3 nach Profil / L / Offen | Aufwendige reine Mathematik auf begrenzten Worker-Pools mit unveränderlichen Snapshots. | Entity-Zugriffe bleiben im richtigen Ownership-Kontext; Queue begrenzen, veraltete Generationen verwerfen, Stop/Reload sauber behandeln. Gesamt-CPU kann gleich bleiben oder steigen. | Engine-/Culling-Pools in [ModelEngine][me-config], Task-Trennung in [BetterModel][bm-tracker]. |

## Empfohlene Reihenfolge

1. N27: Live-Messung nach dem Rollout als belastbare Grundlage.
2. N13: Viewer-Multiplikation auf dem akzeptierten gemeinsamen Serialisierungspfad begrenzen. N21-Phasen aus #238 bleiben mangels einheitlichem Nutzen Entwurf; initiale Spawn-Arbeit separat untersuchen.
3. N01: Composite-Compiler-Spike mit vollständigem Geometrie-/Texturvergleich; danach erst passenden Host und Pack gemeinsam aktivieren.
4. N07, N09, N10 und N12 gemäß Pose-/Allokationsprofil.
5. N14/N17/N18; danach N16 nur bei positivem Kostenvergleich.
6. N23 und N30 erst, wenn nach diesen Schritten ein messbarer Engpass bleibt.

## Nicht als Gewinn verbuchen

- Weniger F3-rx allein: Bundles können die Anzeige ändern, ohne Nutzdaten zu entfernen. Der [ItemsAdder-Vergleich][ia-comparison] ist historisch und kein aktuelles Ranking.
- „Async“ allein: verlagert Arbeit, beseitigt sie nicht; Paketverzögerung kann ruckeln.
- Unsichtbarkeit oder Nullskalierung allein: spart ohne Rechen-/Versand-Gate keine Serverarbeit.
- „Sight trace“ ohne Quellprüfung: die geprüfte BetterModel-Methode macht Winkel-/Entfernungstests, keine Block-Raycasts.
- Pauschal nochmals niedrigere Rate: nur nach Relevanz oder nachgewiesener Interpolationsfehlerschranke.
- Shader-Skelett als Sofortlösung: eigene Kompatibilitätsprüfung erforderlich; [Mojang][mc-1216] behandelt Core-Shader-Overrides als nicht unterstützte Schnittstelle.

## Rollout und Messnachweis

Die aktuellen lokalen Nachweise und das gemeinsam zu aktivierende Host-/NPC-Pack-Paar stehen unter `/home/lukas/grounds/artifacts/npc-performance-2026-11-01/`. Live-Aktivierung und serverseitige Akzeptanz werden in `stage-live-proof.json` vermerkt. Ein tatsächlicher Clienttest für Animationsgefühl, Highlight/Größe und SOLID-Kollision bleibt als eigene Prüfung kenntlich.

Vor jeder privaten Host-/Editor-Aktivierung muss die fertige Variante gegen die echte veröffentlichte Scene geprüft werden. Map 9 bindet den Asset-Katalog `0.0.0-edge.1793492850.ge7a80f9731a7`; eine neuere öffentliche Maven-Version ist kein Ersatz für diese Scene-Bindung. `tools/NpcStageBundleAcceptance.java` ruft den produktiven `LobbySceneLoader` auf und vergleicht alle NPC-Addon-Dateien mit dem im Host eingebetteten Digest-Vertrag. Der erste Performance-Build mit der falschen Katalogversion wurde damit reproduzierbar abgelehnt; der korrigierte Build akzeptiert dieselbe Scene und dasselbe Addon. Dies ersetzt weder Agones-Ready-Prüfung noch Clientabnahme. Quellen gehen in den gemeinsamen Release mit der Queue-/Scene-Änderung; zusätzliche getrennte Versionsreleases werden nicht angestoßen.

Aufruf mit JDK 25, dem tatsächlich zu deployenden Fat-JAR, dem entpackten Map-9-Verzeichnis und dem tatsächlich auszuliefernden NPC-Addon. SHA-256 und Minecraft-SHA-1 müssen aus dem geprüften Deployment-/Artefaktmanifest stammen; die vollständige ZIP-Prüfung schließt `pack.mcmeta` ein:

```sh
java --class-path /absolute/path/minestom-lobby-all.jar \
  tools/NpcStageBundleAcceptance.java \
  /absolute/path/map-v9 /absolute/path/npc-addon.zip \
  "$PINNED_ADDON_SHA256" "$PINNED_ADDON_SHA1"
```

[me-model]: https://wiki.mythiccraft.io/modelengine/Modeling/Creating-a-Model
[me-bones]: https://wiki.mythiccraft.io/modelengine/Modeling/Bone-Behaviors
[me-config]: https://wiki.mythiccraft.io/modelengine/Configuration
[me-cull]: https://wiki.mythiccraft.io/modelengine/Skills/Mechanics/CullConfig
[me-terms]: https://wiki.mythiccraft.io/modelengine/Technical/Terminology
[me-part]: https://wiki.mythiccraft.io/modelengine/Skills/Mechanics/ChangePart
[me-remap]: https://wiki.mythiccraft.io/modelengine/Skills/Mechanics/RemapModel
[me-base]: https://wiki.mythiccraft.io/modelengine/API/Advanced/Custom-Base-Entity
[me-options]: https://wiki.mythiccraft.io/modelengine/Skills/Mechanics/Model
[me-commands]: https://wiki.mythiccraft.io/modelengine/Commands-and-Permissions
[bm-blueprint]: https://github.com/toxicity188/BetterModel/blob/v3/api/src/main/java/kr/toxicity/model/api/data/blueprint/BlueprintElement.java
[bm-modelmanager]: https://github.com/toxicity188/BetterModel/blob/v3/core/src/main/kotlin/kr/toxicity/model/manager/ModelManagerImpl.kt
[bm-pipeline]: https://github.com/toxicity188/BetterModel/blob/v3/api/src/main/java/kr/toxicity/model/api/data/renderer/RenderPipeline.java
[bm-bone]: https://github.com/toxicity188/BetterModel/blob/v3/api/src/main/java/kr/toxicity/model/api/bone/RenderedBone.java
[bm-animation]: https://github.com/toxicity188/BetterModel/blob/v3/api/src/main/java/kr/toxicity/model/api/data/blueprint/BlueprintAnimation.java
[bm-generator]: https://github.com/toxicity188/BetterModel/blob/v3/api/src/main/java/kr/toxicity/model/api/data/blueprint/AnimationGenerator.java
[bm-iterator]: https://github.com/toxicity188/BetterModel/blob/v3/api/src/main/java/kr/toxicity/model/api/animation/AnimationIterator.java
[bm-progress]: https://github.com/toxicity188/BetterModel/blob/v3/api/src/main/java/kr/toxicity/model/api/animation/AnimationProgress.java
[bm-keyframes]: https://github.com/toxicity188/BetterModel/blob/v3/api/src/main/java/kr/toxicity/model/api/animation/AnimationKeyframe.java
[bm-visibility]: https://github.com/toxicity188/BetterModel/blob/v3/api/src/main/java/kr/toxicity/model/api/util/EntityUtil.java
[bm-tracker]: https://github.com/toxicity188/BetterModel/blob/v3/api/src/main/java/kr/toxicity/model/api/tracker/Tracker.java
[bm-releases]: https://github.com/toxicity188/BetterModel/releases
[oraxen]: https://github.com/oraxen/oraxen/blob/master/src/main/java/io/th0rgal/oraxen/mechanics/provided/gameplay/furniture/FurnitureMechanic.java
[mm-changelog]: https://wiki.mythiccraft.io/mythicmobs/changelogs/Changelogs
[mm-packet]: https://wiki.mythiccraft.io/mythicmobs/changelogs/5.9.x_changelogs
[ia-settings]: https://wiki.itemsadder.com/adding-content/entities/advanced-settings/
[ia-comparison]: https://wiki.itemsadder.com/faq/modelengine-vs-itemsadder/
[mc-display]: https://www.minecraft.net/en-us/article/minecraft-java-edition-1-19-4
[mc-1214]: https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-4
[mc-12111]: https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-11
[mc-261]: https://www.minecraft.net/en-us/article/minecraft-java-edition-26-1
[mc-1216]: https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-6

Aktuelle Sensor-Messungen: [N20/N32-Nachweis](2026-11-01-npc-sensor-performance-results.md). Die Prozentwerte gehören zu zwei unabhängigen kontrollierten Paaren und werden nicht addiert. N13/N24/N27 aus Host#233 bleiben wegen des größeren CPU-Nachteils getrennt im Entwurf. Weitergehende Geometrieänderungen und native BOUNCE-Anpassungen werden nicht ohne eigene visuelle/Gameplay-Abnahme in diesen Performance-Rollout gemischt.

N24-Standardpfad ist über deploy#554 auf beiden Stage-Lobbys aktiv (Image e447d40b5747, Digest c6d3952478658bfbaa945757e2b50667a2778e78fd02ff6a81b46a968ac7bf30). Alle 765 Testplattform-Würfel und die bestehende SOLID-/Lighting-Konfiguration bleiben erhalten. #236 bleibt wegen nicht durchgehend belegtem CPU-Gewinn separat im Entwurf; #233 wird mit seinen bisherigen CPU-Nachteilen nicht pauschal übernommen.

N21-Nachprüfung am 2. November: [Host#238](https://github.com/groundsgg/minestom-lobby/pull/238) bleibt trotz 182 grünen Host-Tests und unveränderter Geometrie im Entwurf. Frisches abgeschlossenes Paar mit 1/20/60 echten Java-Testclients plus Bewegung und drei Wiedereintritten; alle vollständigen und ungünstigen Fenster im [Messbericht](2026-11-02-npc-animation-phases-results.md). Nominale Intervalle bleiben gleich, reale Metadatenpakete nehmen jedoch um etwa 4–12 % zu. Kein neuer Stage-Rollout. Der Nutzer hat Skalierung und SOLID des bisherigen #237-Stage-Stands bestätigt.

N13-Nachprüfung: [Host#239](https://github.com/groundsgg/minestom-lobby/pull/239) trennt die Betrachterraten von #233-Messinstrumentierung/Mathematik, gruppiert Empfänger pro Handle und nutzt native gemeinsame Serialisierung pro Kohorte.193Host-Tests; vollständige1/20/60Client-Läufe. Gemischte60Clients: tatsächlicheTCP-Nutzdaten4,654→3,425MB/s(-26,4%), CPU-2,7%, mittlereTickzeit-3,1%. Bewegung: Bytes-21,0%, CPU+1,4%, Allokation+10,9%;56msÜbergangsausreißer bleibt offen dokumentiert. Ein-Gruppen-Direktpfad hält Wiedereintrittsmittel nahe Referenz. Kein allgemeinerCPU/Tick-Gewinn; qualifiziert für einen Stage-Test mit expliziten Kosten. Die Stage-API ist nicht erreichbar; privatesImage/Deployment-Entwurf werden vorbereitet, keine Aktivierung behauptet. [Vollständiger Nachweis](2026-11-02-npc-cohort-dispatch-results.md). #233/#236/#238 bleiben getrennt.

Nachprofilierung von N13/N24: [Host#240](https://github.com/groundsgg/minestom-lobby/pull/240) entfernt nach Bytecode/JFR-Nachweis das Boxing bei der Intervallauswahl.193Host-Tests, unveränderte Geometrie und frischer verbundener Vergleich; gesamteJVM-Allokation nahezu gleich, kein großerRAM-Gewinn behauptet. In dieser Messung sinken CPU/mittlereTickzeit bei60bewegtenClients5,0%/11,5%. Der erste Bewegungspeak wurde unter Diagnose51,4→37,5ms beobachtet, bleibt aber nicht abschließend erklärt; keine passendeGC-Pause, Scheduler wartet auf Instanzarbeit, Stichproben imViewer-/Triggerpfad. [Vollständiger Nachweis](2026-11-02-npc-rate-allocation-results.md). N24-Folgepunkt: Flags ohne vollständiges metadataPacket lesen und gleiche Übergangssnapshots gesammelt serialisieren; noch nicht implementiert oder als Gewinn verbucht. Stage-Entwurf#555 wird vorbereitet aktualisiert, tatsächliche Aktivierung erfordert wieder erreichbaren Tailscale-Zugang.

N13/N20-Folgeprofil: Unveränderte Viewer-Maps werden vor dem Neuaufbau erkannt; kein Scan-Throttling. 195 Tests, 723456 unveränderte Pose-Zustände. Der direkte Komponentenvergleich senkt Allokationen; ein isolierter Lauf mit 20/60 Clients zeigt CPU -6,7 bis -8,1% und Allokation -6,5 bis -7,1% in Ruhe/Bewegung. Der ursprüngliche vollständige Vergleich war teilweise schlechter; eine reale Wiedereintrittsspitze von 156ms und gemischte Wiedereintritts-Tails bleiben offen. Deshalb nur begrenzter Stage-Test, kein allgemeiner CPU-Gewinn. [Vollständige Messungen und Grenzen](2026-11-03-npc-viewer-scan-results.md). Die vier alten Entwürfe und Release #231 bleiben getrennt.


## Aktueller Stand und Prioritäten (3. November 2026)

Dieser Abschnitt führt den aktuellen Stand; vorhergehende Messnotizen bleiben als Historie erhalten. Die erneute Prüfung verwendet aktuelle Primärquellen; eigene Übertragungen sind als Kandidaten benannt. Bestehende Entfernungskohorten, Paketdeltas, Interpolation, Head-Tracking-Schwelle und Abschalten der Poseberechnung ohne Betrachter bleiben bereits umgesetzte Grundlagen.

| ID | Status | Konkreter nächster Schritt | Abnahme / Quelle |
| --- | --- | --- | --- |
| N34 | Für begrenzten Stage-Test qualifiziert | Unveränderliche ItemStacks je Modellteil beim Renderer-Laden vorbereiten; Flags direkt lesen. | 201 lizenzierte Tests; kein persönlicher/Entity-Zustand im gemeinsamen Cache. Eigene Erstaktivierungsprofile und vorbereitete Renderer-/Animationsdaten in [BetterModel ModelRenderer][bm-modelrenderer] / [BlueprintAnimation][bm-animation]. |
| N35 / N21 | Für begrenzten Stage-Test qualifiziert | Bestehendes Scene-Budget in der Lobby auf einen vollständigen automatischen Übergang pro Tick setzen. Alle Teile erst nach fertiger Anfangspose sichtbar machen. | Zählbudget, keine harte Millisekundengrenze; Ladezeit bis zum letzten Modell mitmessen. Zwei reale Publikationsregressionen und Mehr-NPC-/Shutdown-Tests. Eigener Ansatz, keine behauptete interne ModelEngine-Spawn-Queue. |
| N07 / N09 | Nächster CPU-Kandidat | Clip-Daten beim Import vorbereiten, Keyframe-Cursor und begrenzte wiederverwendbare Samples prüfen. | [BetterModel AnimationGenerator][bm-generator] behandelt große Rotationen und Step-Frames ausdrücklich. Originalkurven, Loops und Kopf-Layer erhalten; kein pauschales FPS-Absenken. |
| N15 / N16 / N17 | Gezielter Folgeversuch | Animationsupdates außerhalb des Sichtkegels drosseln; danach konservatives Verdeckungs-Culling mit Nahbereich-Ausnahme. | [ModelEngine-Konfiguration][me-config] dokumentiert Blickwinkel-, Verdeckungs- und Distanz-Culling. NPC-Nähe/Interaktion/Labels schützen, bei Drehung sofort resynchronisieren. Bestehende Sichtweite nicht pauschal verkürzen. |
| N20 | Nach Profil priorisieren | Renderer-Viewer über räumliche Kandidaten suchen statt jeden Instance-Spieler je Handle zu prüfen. | [Aktueller MythicMobs-Changelog][mm-current] beschreibt direkte AABB-Abfragen und weniger temporäre Bewegungsobjekte. Scene-Sensorik besitzt bereits Indizes; Wechsel, Disconnect und View-Hysterese testen. |
| N25 | Separater Netzwerk-/Client-Versuch | Kleine begrenzte Paketbundles für vollständige Erstzustände prüfen. | [ModelEngine][me-config] warnt vor zu großen Bundles, weil sie in einem Clienttick verarbeitet werden. Innere Paketanzahl, echte Bytes und Clientlatenz messen; Bundles sparen nicht automatisch RX oder Bytes. |
| N01 / N28 | Hoher möglicher Nutzen, höheres Regressionsrisiko | Geometrie statischer Teilgruppen zusammenfassen und dauerhaft innere Flächen prüfen. | Alle Würfel/UVs/Pivots/Rotationsachsen behalten. Die früher fehlenden Körperteile zeigen, warum dies eigene Asset-/Animationsabnahme braucht. Keine pauschale Zusammenlegung aller Würfel pro Bone. |

ItemsAdder dokumentiert aktuell `delay_packets` für seine Entity-Kompatibilität. Das ist kein Beleg für einen schnellen Serverpfad oder eine passende pauschale Verzögerung bei unseren Display-NPCs. Oraxens statische Furniture-Ansätze sind ebenfalls keine direkte Begründung, animierte Bones oder autoritative Hitboxen wegzulassen. Paketbasierte Dekoration (N23) bleibt ein größerer eigener Lebenszyklusumbau.

Historische Stage-Spitze441,802ms bleibt ungeklärt; die konkreten CPU-/Allokationskandidaten werden nicht als deren vollständiger Fix verkauft. Ältere Entwürfe #233/#236/#238/#241 bleiben getrennt; insbesondere wird #241 nicht pauschal importiert. Design/Abnahme: [Aktivierungsvorbereitung](../specs/2026-11-03-npc-activation-preparation-design.md).

[bm-modelrenderer]: https://github.com/toxicity188/BetterModel/blob/v3/api/src/main/java/kr/toxicity/model/api/data/renderer/ModelRenderer.java
[mm-current]: https://wiki.mythiccraft.io/mythicmobs/changelogs/Changelogs

N34/N35-Qualifikation:201lizenzierte und201Tests gegen das fertige Stage-JAR grün; unveränderte723456Golden-Zustände/983Addon-Dateien. Vier kalte verbundene ABBA-Läufe: erste Plattformspitzen45,575/46,521→28,655/14,498ms, vollständiger619Display-Aufbau335–437→743–1152ms. Separates20/60Client-Paar mit Bewegung/2Sichtbarkeitseintritten: Allokationen in allen Fenstern1,7–3,1%geringer, CPU/Tickzeiten gemischt (60statisch Tickmittel+6,72%,CPU+2,53%;Bewegung CPU−4,32%,Tickmittel+2,23%). Keine allgemeine mittlereTickzeitverbesserung behauptet. Nur begrenzterStage-Test; tatsächliche Aktivierung/Gameplay-Prüfung werden separat erfasst. [Vollständige Messungen, adverse Fenster und Grenzen](2026-11-03-npc-activation-preparation-results.md).

## N07: Clip-Vorbereitung geprüft, nicht aktiviert (3. November)

[Resourcepacks#62](https://github.com/groundsgg/resourcepacks/pull/62) bereitet konstante0/1-Key-Bone-Transformationen und Kanalzuordnungen vor.110von391Bone-/Clip-Kombinationen sind konstant; Tracks haben Median5/max23Keys.14Runtime-/201lizenzierteHost-/201Tests gegen das fertigeStageJAR bestehen,723456Zustände/983Addon-Dateien bleiben unverändert. Ein eingeführter Fehler bei extremen, ungenutzten Clipwerten wurde per zwei RED/GREEN-Regressionen behoben.

Der direkte Komponentenvergleich spart etwa16%Pose-Allokation, aber der volle20/60Client-Vergleich zeigt statisch60CPU+21,15%/Tickmittel+48,48%, bei BewegungCPU+25,56%/Tickmittel+27,04%. Ein getrennter umgekehrter60Client-Vergleich nach Warmup/Erstbewegungsprofil bestätigt die negative Richtung: statischCPU+17,23%/Tickmittel+46,25%, BewegungCPU+10,40%/Tickmittel+26,67%, Gesamtallokation nur−1,27%/−0,57%. Adverse87,45/52,78msSpitzen bleiben dokumentiert.

**Nicht qualifiziert;#62bleibt Entwurf, Stage bleibt#243/#560.** JIT/Host-Last beeinflussen Messungen, sind aber keine belegte alleinige Ursache. Vollständige dynamische Clip-Samples bleiben offen; N09Cursor werden bei diesen kurzen Tracks ohne Profilbeleg nicht ergänzt. Kein allgemeiner CPU-/Tickgewinn, keine Paket-/Geometrieänderung als umgesetzt verbuchen. Weitere Arbeit auf die dominanten Versand-/Sichtbarkeitspfade und die separat abzusichernde Modellkonsolidierung ausrichten. [Vollständige Messungen und Grenzen](2026-11-03-npc-prepared-clips-results.md).

## N12: Primitive Pose-Zwischenergebnisse (4. November)

[Host#245](https://github.com/groundsgg/minestom-lobby/pull/245) entfernt nur boxed
Quaternion-Zwischenwerte und die temporäre reziproke Skalierungsliste. 204lizenzierte
und204Tests gegen das fertigeStageJAR,723456bitgleicheZustände; unabhängigesReview
ohneProduktionsfehler. Komponente160→32Byte/Quaternion, Gesamt-JVM−0,6bis−1,7%
Allokation in einem20/60Client-Paar. RuheCPU−2,3bis−2,4%, BewegungCPU+0,9% und
Tickmittel+4,2%; tatsächlicheTCP-Nutzdaten+1,6bis+5,3%. KleineSpeicheroptimierung,
keinallgemeinerCPU/TPS/Netzwerkgewinn; begrenzterStage-Testqualifiziert, tatsächlicher
Rollout undnativeAbnahme separat. EinekleineGrenzfall-Testverstärkung bleibt als
Reviewnachtrag offen. N01Modellkonsolidierung bleibt dernächstegrößereHebel, mit
eigenerAsset-/Clientabnahme. [MessungenundGegenwerte](2026-11-04-npc-primitive-pose-results.md).

## Abgenommener Stand und nächster Kandidat (5. November 2026)

Die älteren Abschnitte bleiben als Messhistorie erhalten. Aktuell abgenommen sind die konservativen Scale-Composites aus Resourcepacks#63:348 Displays auf der Testplattform und451 in der vollständigen Test-Scene. Der aggressivere Konsolidierungsversuch blieb wegen sichtbarer Skalierungsfehler unaktiviert. Die früher verworfenen Entwürfe Host#233/#236/#238/#241 und Resourcepacks#62 sind geschlossen.

Die zusätzliche primitive Hover-Strahlprüfung aus [Scene#26](https://github.com/groundsgg/library-scene/pull/26) ist gemerged und auf beiden Stage-Lobbys bestätigt. Vier verbundene20/60Client-Läufe: Bei60Clients sinken JVM-Allokationen im Stand/in Bewegung um18,9/18,4%, nach Wiedereintritt um26,6–26,7%; an Sichtbarkeitsgrenzen nur etwa2%. CPU/Tickzeiten sind gemischt und teilweise höher. Das ist eine Speicher-/GC-Druck-Optimierung; keine allgemeine TPS-/Netzwerkverbesserung.

**Aktueller Folgekandidat N17:** [Host#246](https://github.com/groundsgg/minestom-lobby/pull/246) nutzt Minestoms bereits stabilen Snapshot-Iterator pro Display direkt. Die bisherige toList-Kopie löst für12Spieler/2Displays72Spieler-IDs auf; der Kandidat besteht ein48erBudget.206lizenzierte und206Tests gegen das exakteStage-abgeleiteteJAR ohneSkips sind grün; frisches unabhängiges Review ohne Befund. Vier vollständige20/60Client-ABBA-Läufe sind geprüft: CPU in9von10aggregiertenFenstern niedriger, bei60Bewegung−2,61%; Tickmittel dort+2,60%/p99+15,83%.60Rand: Allokation+3,62%/TCP+2,05%. KeinallgemeinerTPS-/Speicher-/Netzwerkgewinn. Nur begrenzterStage-Test qualifiziert; Aktivierung/nativeAbnahme separat. Bei Minestom-Upgrades muss die Snapshot-Eigenschaft erneut geprüft werden.
