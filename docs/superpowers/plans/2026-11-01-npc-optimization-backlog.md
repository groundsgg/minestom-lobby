---
title: NPC Optimierungen aus anderen Plugins
description: Priorisierter Umsetzungsstand und weitere übertragbare Ansätze für Grounds NPC Rendering.
---

# NPC Optimierungen aus anderen Plugins

Stand: 1. November 2026. Ziel sind weniger Serverarbeit, weniger tatsächlich übertragene Daten und weniger Clientarbeit bei erhaltener Geometrie, Animation und Interaktion. Die Liste ist der Arbeitsbacklog für die geprüften Ansätze aus ModelEngine, MythicMobs, Oraxen, ItemsAdder und BetterModel.

**Status:** „Umgesetzt“ bezeichnet den geprüften Performance-Kandidaten; der Live-Stand wird im Rollout-Nachweis geführt. „Teilweise“ bedeutet, dass der Grundmechanismus existiert, die beschriebene Erweiterung noch fehlt. „Offen“ ist keine Zusage eines bereits gemessenen Gewinns.

**Priorität:** P1 zuerst untersuchen/umsetzen, P2 danach, P3 nur bei nachgewiesenem Bedarf. **Aufwand:** S isolierte Änderung, M Änderung über mehrere Komponenten, L Architektur- oder Formatänderung. Exakte Prozentgewinne werden erst nach kontrollierten Vergleichsmessungen eingetragen.

**Quellen:** Die Links belegen die ausdrücklich genannten Plugin- oder Formatfunktionen. Akzeptanzkriterien und Übertragungen auf Grounds sind unsere Engineering-Entscheidungen. Proprietäre Interna werden nicht vorausgesetzt.

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
| Gemeinsame Root-/Local-Affine vorbereiten | Umgesetzt in der Folgerunde; alle Teile verwenden dieselbe unveränderliche Vorbereitung bis zum nächsten Scene-Transform. Primitive Quaternion-/Skalen-Zwischendaten vermeiden Boxing. | Lokales JFR-Profil: Matrix-/Display-Mathematik dominiert die verbleibende Rendererarbeit. |
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
| N07 | P1–P2 / M / Offen | Wiederverwendbare Clip-Samples oder fertige Display-Transformationen beim Import vorberechnen. | Fehler gegen Originalkurven messen; Speicher/Importzeit begrenzen; Kopf-Layer, variable Geschwindigkeit und Übergänge weiter dynamisch behandeln. | Aufbereitete Zwischenframes in [AnimationGenerator][bm-generator]. |
| N08 | P2 / M / Offen | Fehlerbegrenztes adaptives Sampling je Clip statt pauschal noch weniger Updates. | Schritt-Keyframes, Loop-Grenzen, Rotationswindungen und schnelle Gliedmaßen erhalten; Positions-/Winkelfehler ausdrücklich begrenzen. | [AnimationGenerator][bm-generator] behandelt Zwischenframes und Schritte; Fehlerkompression ist unser Vorschlag. |
| N09 | P1 bei Profilbefund / S / Offen | Laufende Keyframe-Cursor statt wiederholter Suche. | Seek, Reverse, Loop, Clipwechsel und Geschwindigkeitsänderung müssen Cursor zurücksetzen oder binär suchen. | [AnimationIterator][bm-iterator]. |
| N10 | P1 / M / Teilweise | Nur geänderte Kanäle und davon abhängige Teilbäume auswerten. | Paketdeltas und konstante Bind-Teile sind erledigt; Elternänderungen müssen alle betroffenen Kinder markieren. | Leere Progress-Daten und frühe Abbrüche in [AnimationProgress][bm-progress] und [RenderPipeline][bm-pipeline]. |
| N11 | P2 / M / Offen | Direkter TRS-Pfad für bewiesene starre/uniform skalierte Transformationen. | Allgemeine Zerlegung als Fallback behalten: verschachtelte nichtuniforme Skalierung plus Rotation kann Scherung erzeugen. Rekonstruktionsfehler testen. | [Display-Transformationsformat][mc-display]; schneller Pfad ist unsere mathematische Ableitung. |
| N12 | P1 nach Profil / M / Teilweise | Primitive Clip-Arrays und klar zugeordnete wiederverwendbare Scratch-Matrizen. | Bind-Caches und Indizes existieren. Keine veränderlichen Pakete wiederverwenden, solange sie noch in einer Versandwarteschlange liegen. | Primitive Keyframes und Zielobjekte in [AnimationKeyframe][bm-keyframes]. |

## Sichtbarkeit und Betrachter

| ID | Priorität / Aufwand / Status | Ansatz und Nutzen | Abnahme und Grenzen | Quelle |
| --- | --- | --- | --- | --- |
| N13 | P1 / M / Umgesetzt in Folgerunde | Wirklich getrennte Versandraten pro Betrachter. | Eine geteilte Pose, eigene akkumulierte Deltas je 200/250/500-ms-Kohorte. Eigene Interpolationsdauer bei Einstieg/Bandwechsel, Hysterese und vollständige Stop-/Seek-/Personalzustände. Der nächste Betrachter bestimmt weiterhin die geteilte Pose-Abtastrate, aber nicht mehr den Versand an alle anderen. | Betrachterbezogene Entfernungsraten in [ModelEngine][me-config]. |
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
| N21 | P1 / S–M / Offen | Animationsdeadlines und Spawn-Arbeit gleichmäßig über Ticks verteilen. | Deterministische Phasen; Animationszeit bleibt an Echtzeit gebunden. Interaktionen nicht verzögern; Einblendungen stückweise oder sauber atomar. | Periodische Tracker in [BetterModel][bm-tracker]; unser Scheduler-Vorschlag. |
| N22 | P2 / M / Offen | Gewichtetes Renderbudget aus Displayzahl, Bytes, Nähe und Bedeutung. | Maximale Wartezeit garantieren; nahe Interaktionsziele bevorzugen. Keine starre NPC-Obergrenze ohne Bezug zur Modellkomplexität. | Per-Chunk-Limits in [ItemsAdder][ia-settings]; adaptives Budget ist unser Vorschlag. |
| N23 | P2–P3 / L / Offen | Rein paketbasierte dekorative Displays bei serverseitigen Gameplay-Hitboxen. | IDs, Spawn/Despawn, Weltwechsel, Respawn, Cleanup und Zuordnung korrekt verwalten. Client-Entities und notwendige Metadaten bleiben bestehen; deaktivierte Physik reduziert den Zusatzgewinn bereits. | Nicht-Entity-Basen in [ModelEngine][me-base], Paket-Displays in [MythicMobs][mm-packet]. |
| N24 | P1–P2 / M / Teilweise | Gemeinsame animierte Paketnutzdaten je Viewer-Kohorte einmal erzeugen. | Standard-Paketobjekte und unveränderliche Einzel-Deltas werden je Kohorte geteilt; Zusammenführungen übertragen ihren Map-Besitz sicher an die Versandwarteschlange. Persönliche Zustände erhalten eigene Felder. Rohbytes/Kompressionszustände bleiben eine separate, protokollabhängige Optimierung. | Globale/persönliche Pakete in [BetterModel Tracker][bm-tracker], Dispatch-Optimierungen in [MythicMobs][mm-changelog]. |
| N25 | P2 / S–M / Offen | Begrenzte atomare Bundles für zusammengehörige Updates. | Innere Paketanzahl, echte Bytes und Client-Frametimes messen. Zu große Bundles erzwingen viel Arbeit in einem Clienttick. | Bundle-Grenzen in [ModelEngine][me-config]. |
| N26 | P1 Voraussetzung / M / Teilweise | Explizite Initialisierung und Resynchronisation bei echten Lebenszykluswechseln. | Vollständiger Sichtbarkeitseintritt ist vorhanden. Für LOD/Reconnect/Reload Delta-Basen explizit zurücksetzen; keine pauschalen Vollupdates als Dauerlösung. | RenderInit in [Model-Optionen][me-options], Resync in [Befehlen][me-commands], Initial-load-Pfade in [RenderPipeline][bm-pipeline]. |
| N27 | P1 zuerst / M / Teilweise | Modellbezogene Kostenmessung für Pose, Zerlegung, Versand und Sichtbarkeit. | Lokaler Benchmark/JFR und Live-Vergleich vorhanden. Folgerunde exportiert begrenzte Modell-/Phasen-Histogramme und eigene Metadaten-Paket-/Feldzähler über die bestehende Registry. Tatsächliche Byte-Messung bleibt im Offline-Serializer bzw. ganzen Pod; kein doppeltes Serialisieren für Metriken. Cull-Gründe, Update-Alter und modellbezogene Live-Allokation bleiben offen. | Updater-/Tracker-Timings von `/meg health` in [ModelEngine][me-commands]. |

## Clientarbeit und Parallelität

| ID | Priorität / Aufwand / Status | Ansatz und Nutzen | Abnahme und Grenzen | Quelle |
| --- | --- | --- | --- | --- |
| N28 | P2 / M / Offen | Dauerhaft innere Flächen entfernen und unnötige Transluzenz vermeiden. | Über alle relativen Bewegungen beweisen; zwischen bewegten Bones sichtbare Flächen erhalten. Beabsichtigte transparente Kunst nicht verändern. | Generatorverbesserungen in [BetterModel-Releases][bm-releases], Render-Pässe in [Mojang 26.1][mc-261]. |
| N29 | P3 / M / Assetabhängig | Reine Oberflächeneffekte durch Texturanimation statt zusätzlicher Gelenk-Entities. | Nur visuell gleichwertige Effekte; keine Silhouettenbewegung ersetzen. Clientarbeit und Texturspeicher ebenfalls messen. | MC-META-Generierung in [ModelEngine][me-config], Sprite-Animation in [Mojang 1.21.11][mc-12111]. |
| N30 | P3 nach Profil / L / Offen | Aufwendige reine Mathematik auf begrenzten Worker-Pools mit unveränderlichen Snapshots. | Entity-Zugriffe bleiben im richtigen Ownership-Kontext; Queue begrenzen, veraltete Generationen verwerfen, Stop/Reload sauber behandeln. Gesamt-CPU kann gleich bleiben oder steigen. | Engine-/Culling-Pools in [ModelEngine][me-config], Task-Trennung in [BetterModel][bm-tracker]. |
| N31 | P1 Korrektheit / M / Offen | Native Shulker-Kollisionskörper für SOLID und BOUNCE gemeinsam verwalten. | BOUNCE benötigt eine Kontaktzone an der nativen Oberfläche, weil der Client den Spieler vor dem alten Überschneidungs-Auslöser stoppt. Rückstoßdistanz, vertikale Bewegung, diagonale Kontakte, Ownership, Sichtbarkeit und Wiedereintritt prüfen; native Hitboxen allein beseitigen keine widersprüchlichen Server-Korrekturen. Java zuerst; Bedrock ist nach Nutzerentscheidung nachrangig. | Erweiterung des bestehenden Grounds-Shulker-PoC auf Nutzerwunsch; keine behauptete fremde Plugin-Implementierung. |

## Empfohlene Reihenfolge

1. N27: Neue Phasenmetriken nach dem nächsten Rollout auswerten; [bisheriger Messnachweis](2026-11-01-npc-performance-impact.md).
2. N21: Lastspitzen verteilen; N13 ist in der Folgerunde umgesetzt und muss auf Stage bewertet werden.
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

Aktueller Korrekturstand: Die fehlenden Modellteile sind durch resourcepacks#60/deploy#521
behoben und vom Nutzer bestätigt. Host#230 korrigiert die Skalierungsdauer aller Teile.
Host#232/deploy#526 initialisieren die gemounteten Shulker explizit; der Nutzer bestätigt
Java-SOLID auf beiden Stage-Lobbys als stabil. Deploy#527 entfernt die temporäre Diagnose.
Nachweise liegen unter `/home/lukas/grounds/artifacts/npc-model-missing-parts-2026-11-01/`,
einschließlich `java-client-acceptance.json` und `live-final-mounted-proof.json`.
Die weitere Performance-Runde ist separat in Host#233 als Entwurf vorbereitet und enthält
die bestätigten Korrekturen. N31 bleibt eine eigene offene Erweiterung.

Vor jeder privaten Host-/Editor-Aktivierung muss die fertige Variante gegen die echte veröffentlichte Scene geprüft werden. Map 9 bindet den Asset-Katalog `0.0.0-edge.1793492850.ge7a80f9731a7`; eine neuere öffentliche Maven-Version ist kein Ersatz für diese Scene-Bindung. `tools/NpcStageBundleAcceptance.java` ruft den produktiven `LobbySceneLoader` auf und vergleicht alle NPC-Addon-Dateien mit dem im Host eingebetteten Digest-Vertrag. Der erste Performance-Build mit der falschen Katalogversion wurde damit reproduzierbar abgelehnt; der korrigierte Build akzeptiert dieselbe Scene und dasselbe Addon. Dies ersetzt weder Agones-Ready-Prüfung noch Clientabnahme. Die erste Quellrunde ging in den gemeinsamen Release mit der Queue-/Scene-Änderung ein. Die Folgerunde wird separat geprüft; der Entwurf startet keinen weiteren Release.

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
