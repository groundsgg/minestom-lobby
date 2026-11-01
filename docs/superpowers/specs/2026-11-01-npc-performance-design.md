# NPC render performance

User approved the preceding measured diagnosis and requested implementation plus comparison with ModelEngine, MythicMobs, Oraxen and ItemsAdder. Preserve original animation curves, upright body/head tracking, personal highlight/scale, scene interactions, collision and cross-proxy routing. Change visual update work only.

## Approaches

1. Runtime fixes with the current pack: suppress redundant fields/teleports, coalesce head/body updates, distance-based cadence/visibility, reuse static pose calculations. Chosen first because the deployed pack remains compatible.
2. Group compatible cubes into per-bone models, like ModelEngine. Evaluate and implement only with geometry equivalence tests and an atomic matching pack/runtime artifact; never silently change a model behind the old pack digest.
3. Packet bundling alone: rejected as the principal fix because fewer F3 rx does not demonstrate fewer bytes or less client work.

## Runtime decisions

Use 200ms near (within 16 blocks), 250ms farther (up to 32 blocks), and stop evaluating poses without eligible viewers. Visibility is three-dimensional, per viewer and instance-local; existing scene runtime remains responsible for network actions and identities. New viewers receive the current pose and personal state. Keep time advancing while invisible. Head targets accumulate and render on the animation cadence, with a 2 degree threshold for head-only work. Pure body yaw never teleports the stationary display anchor. Preserve explicit transforms, animation starts/stops, viewer lifecycle and failure cleanup. Cache immutable bind transforms and pre-index bones. Suppress each serialized transform field separately. Terminate converged matrix diagonalization. Disable decorative display physics.

## Validation

Regression tests cover packet fields/counts, stationary anchors under body yaw, coalesced tracking, viewer enter/leave and cross-instance isolation, clip continuity after invisibility, personal visual states, affine/nonuniform/zero/negative scales, and original curves. Re-run the exact 15-model diagnostic at one viewer and compare packet payload bytes and allocation as well as counts. No production deployment is implied by preparing the implementation.

## Primary-source comparison

- ModelEngine documents per-bone geometry grouping, virtual bones without visuals, per-viewer culling and distance-based animation frequency. We adopt conservative rigid grouping and distance/cadence controls; exact arbitrary-rotation constraints keep some bones split. [Model construction](https://wiki.mythiccraft.io/modelengine/Modeling/Creating-a-Model), [network configuration](https://wiki.mythiccraft.io/modelengine/Configuration#network-optimization).
- MythicMobs delegates articulated models to ModelEngine. Its separate skill/timer dispatch is not a substitute skeletal renderer. [ModelEngine integration](https://wiki.mythiccraft.io/mythicmobs/Model-Engine).
- Oraxen places a furniture model in a base ItemDisplay, with separate interaction hitboxes; its articulated furniture path uses ModelEngine ActiveModel. This supports separating static geometry, animation and gameplay collision. [Furniture source](https://github.com/oraxen/oraxen/blob/master/src/main/java/io/th0rgal/oraxen/mechanics/provided/gameplay/furniture/FurnitureMechanic.java).
- ItemsAdder documents custom-entity/bone limits and optional packet delay. Its official comparison uses historical IA3.6.2/MEG3.1.9/4.0.2, not a current ranking. It demonstrates why fewer F3 rx with bundles need not mean less traffic. [Advanced settings](https://wiki.itemsadder.com/adding-content/entities/advanced-settings/), [historical comparison](https://wiki.itemsadder.com/faq/modelengine-vs-itemsadder/).

No claim is made about proprietary internals or universal competitor pose caches. Our bind-matrix caching is an implementation decision supported by direct allocation/geometry tests.
