# NPC viewer snapshot design

Continue the approved NPC optimization backlog with the measured viewer-snapshot candidate. Preserve rendering, per-display membership, rate hysteresis, entry initialization, reconnect identities, model assets and authoritative collision.

## Approaches

Use the pinned Minestom EntityView snapshot iterator directly (chosen): iterator() resolves the tracked player IDs into a private ArrayList under the EntityView mutex, then returns that snapshot's iterator. Capture it before updateViewableRule. Iterating it afterward preserves old membership without Kotlin toList() size traversals/copies.

Manual one-pass copying would preserve membership too but duplicate an already stable snapshot. Sharing one snapshot across displays is rejected because individual displays can have different spawned viewers. No new global cache or public API is needed.

## Evidence and constraints

The accepted dba22bf8 Stage JAR's EntityView.SetImpl bytecode proves size() resolves every player under its mutex and iterator() already snapshots resolved players. The implementation depends on the pinned Minestom version; tests must keep old per-display membership even when rule updates admit a new viewer. CPU/allocation improvement must be measured rather than inferred from fewer scans. Keep held matrix/SVD changes out.

Native inline execution continues the established workflow. Add a real renderer entity-resolution budget and per-display entry/rate-transition regression before changing production. Run the full suite, exact-artifact checks and connected load qualification. Publish/deploy only if qualified; keep all adverse windows.
