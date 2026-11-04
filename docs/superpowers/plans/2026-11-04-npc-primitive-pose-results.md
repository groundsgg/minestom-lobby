# Primitive Display-Pose-Umrechnung: Qualifikation (4. November 2026)

Kleine Allokationsoptimierung, für einen begrenzten Stage-Test qualifiziert.
Kein allgemeiner CPU-, Tickzeit-, Netzwerk- oder TPS-Gewinn belegt.
[Host #245](https://github.com/groundsgg/minestom-lobby/pull/245) entfernt nur
verpackte Zahlen/Zwischenlisten bei Quaternionen und inversen Skalierungswerten.
Algorithmus, Rundung, Array-Eigentum, Animation, Geometrie, Sichtweite und SOLID
bleiben gleich. Resourcepacks #62 ist ausdrücklich ausgeschlossen.

## Korrektheit und Komponentenmessung

- 204 lizenzierte Host-Tests und 204 Tests gegen das fertige Vergleichs-JAR;
  jeweils keine Fehler, Ausnahmen oder übersprungenen Tests.
- 723.456 serialisierte Zustände/15 Modelle bitidentisch, SHA256
  `27daa3691026f1a7a958113dcda036b9365f56708ded853bbc60e1429295083e`.
- Aufgewärmte eigene JVM: 160 → 32 Byte pro Quaternion, fünf Fenster gleicher
  Richtung; Budget 64 Byte. Zwei Quaternionen pro Display-Anwendung.
  Reflexiver Komponententest, keine absolute Allokationsrate des Gesamtservers.
- Bytecode der zwei Klassen: 17 Double.valueOf-Aufrufstellen → 0,
  vier Double-Array-Erzeugungsstellen → 0, eine ArrayList-Erzeugungsstelle → 0.
- Alle 40.415 physischen Stage-Archiveinträge einschließlich Duplikaten in
  derselben Reihenfolge erhalten. Ausschließlich DisplayTransform und Companion
  ersetzt; unabhängige Prüfung bestätigt dieselben Payloads wie im Quellbuild.
- Unabhängiges Review: kein Produktionsfehler. Kleine zurückgestellte
  Testverbesserung: Grenzfall-Fixtures mit transponierter zyklischer Matrix und
  negativen X/Y-Offdiagonalen würden versehentliche >→>=-Änderungen zusätzlich
  durch unterschiedliche Quaternion-Vorzeichen erkennen. Aktuelle Prädikate
  bleiben unverändert; vorhandene Grenzfälle und Golden bestehen.

## Verbundener Serververgleich

Frische eigene Offline-JVMs, leerer Anwendungsprozess-Umgebungssatz außer PATH,
512 MiB Heap, ein sichtbarer Prozessor, Backend auf CPU23. CPU23 und SMT-Geschwister22
von eigenen Controllern, Zählproxy und Clients ausgeschlossen; keine exklusive
Reservierung gegenüber sonstiger Hostarbeit und keine Stage-Quota-Emulation.
20/60 echte Java-Protokollclients in drei Workern, unveränderte native Scene,
gespeicherte Map9-Beleuchtung, 557 Besucherchunks und 619 Displays. 30/40 Sekunden
Warmup, 35 Sekunden Ruhe, 30 Sekunden Bewegung, zwei Wiedereintritte. Keine
Profileraufnahme in den Vergleichsfenstern, keine GUI-/FPS-/Bedrock-Abnahme.
Beide Prozesse regulär beendet, 60 Verbindungen akzeptiert, keine Disconnects
oder Netzwerkfehler, Map-Quelle unverändert, alle eigenen Container entfernt.

| Abschnitt | mittlere Tickzeit alt → neu (ms) | CPU-Änderung | Allokationsänderung | TCP-Nutzdatenänderung |
| --- | ---: | ---: | ---: | ---: |
| 20 ruhig | 1.087 → 1.087 | -2.26 % | -1.73 % | +2.16 % |
| 60 ruhig | 1.327 → 1.323 | -2.41 % | -0.77 % | +2.62 % |
| 60 bewegt | 1.943 → 2.025 | +0.90 % | -0.91 % | +1.62 % |
| Wiedereintritt 1 | 1.506 → 1.537 | +0.02 % | -1.13 % | +5.33 % |
| Wiedereintritt 2 | 1.524 → 1.526 | -0.04 % | -0.60 % | +4.04 % |

| Abschnitt | p99 alt → neu (ms) | gesamter Abschnitt: Maximum alt → neu (ms) |
| --- | ---: | ---: |
| 20 ruhig | 5.804 → 4.275 | 7.935 → 6.247 |
| 60 ruhig | 3.742 → 2.888 | 6.221 → 7.589 |
| 60 bewegt | 7.942 → 8.794 | 42.301 → 45.892 |
| Wiedereintritt 1 | 3.878 → 3.848 | 12.734 → 11.907 |
| Wiedereintritt 2 | 5.121 → 4.792 | 16.884 → 11.406 |

## Entscheidung und Grenzen

Die kleine Gesamtallokationsersparnis zeigt sich in allen fünf Fenstern, trotz
mehr tatsächlich versendeter Metadaten. In Ruhe ist CPU etwa 2,3–2,4 % geringer,
bei Bewegung 0,9 % höher; dort steigen mittlere Tickzeit 4,2 % und p99 10,7 %.
Die bewegte Spitze steigt 42,301 → 45,892 ms. Statisch60 steigt das Maximum
6,221 → 7,589 ms trotz geringerem Mittel/p99. Reale Metadatendispatches steigen
2,2–5,8 %, TCP-Nutzdaten 1,6–5,3 %. Keine geänderte Soll-Rate und kein
Netzwerkgewinn behauptet; genaue Ursache der Fensterunterschiede nicht belegt.
Diese Gegenwerte gehören zur Qualifikation und werden nicht verworfen.

Ein Paar mit Baseline zuerst ist kein Nachweis der Wiederholbarkeit oder
statistischen Signifikanz. Kleine CPU-/Timingunterschiede werden deshalb nicht
als gesicherte Beschleunigung verkauft. Gegenüber dem deutlich regressiven
Clip-Kandidaten zeigt sich hier kein großer, über Ruhe/Bewegung wiederholter
CPU-/Ticknachteil. Die bitgleiche, kleine Allokationsänderung wird begrenzt auf
Stage geprüft, ohne Production, Release oder Veröffentlichung der Bibliotheken.

Mittel/p99 verwenden dieselben nativen Tick-Ordnungsintervalle; CPU, Allokation,
Metadaten und TPS ihre jeweiligen nativen Lesezeitpunkte. Das Maximum umfasst
jeweils den ganzen markierten Abschnitt. Wiedereintritte behalten alle619Entities
und sind keine kalte Rekonstruktion. Erster Launcher-Start scheiterte vor jeder
JVM-/Containererzeugung an einer fehlenden lokalen counting_proxy.py-Kopie;
unveränderten Helfer ergänzt, Import-/JAR-Hash-Vorprüfung bestanden, sauberes Paar
vollständig neu ausgeführt. Keine Messfenster nachträglich ausgeschlossen.

Artefakte: `artifacts/npc-primitive-pose-2026-11-04`; Rohdaten:
`artifacts/npc-load-profile-2026-11-01/pose-steady-{baseline,candidate}-a`.
Historische größere Stage-Spitzen bleiben ungeklärt. Nächster größerer Hebel:
separat abgesicherte Modellkonsolidierung (N01); diese Änderung spart keine Entities.

## Integration

Quelle f4b6a099ee619e0d4a97eaa510e8643b039bb7f0; exakter Stage-Kandidat
`df2d9f671ae25811dd34745b751156c62f771946b2c8384d3c670eec24985952`.
PR #245: alle drei Prüfungen auf diesem Quellstand grün. Rollout-Nachweis wird
separat festgehalten; die frühere native Nutzerabnahme gilt nur für #243/#560.
