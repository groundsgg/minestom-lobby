# Stage NPC body and prop collision

User choice: the smith and sales-table NPC, including anvil/table, must be solid.

Use the existing explicit Stage native-collision flag to enable SOLID for demo-fixed (forge_master) and demo-hands (dismantler) in the runtime request. Preserve the immutable map, every other station and the NONE demo. Extend nativeSolidCubes to tile separate body and static-prop bounds from the currently qualified models, retaining viewer/mount/position-confirmation and original Scene interaction anchor forwarding. Never fill their combined enclosing box. Keep the source collision optimization PR27 unmerged pending native acceptance.

- [x] RED: actual forge/table coverage and free space above props, Stage-only mode selection, multiple-probe lifecycle.
- [x] GREEN: component geometry, scoped Stage policy, three native probes with cleanup, full host and exact-JAR checks.
- [ ] Compare current/new static geometry and connected clients, independently review, Stage-only image trial, native acceptance.

Geometry provenance: unchanged addon d7b3f7ae05ece97b33fb0d1412dcc56c66c9bfe50d6537be830073efeb5044fc. Bounds derived from authored item composite matrices and all mesh corners with the same Y180 facing as the renderer. All work local to instance and Stage; no cross-proxy identity/routing change.
