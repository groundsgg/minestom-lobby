# NPC transition metadata

Reduce allocation and repeated serialization during rate transitions, preserving the complete visual state that fixed missing parts and delayed scaling. Continue the authorized optimization backlog after merged #240; implementation choices below are the agent's, within the existing Kotlin/Minestom architecture.

Read flags with `metadata.get(MetadataDef.ENTITY_FLAGS)`, retaining the zero default and all eight bits before the existing per-viewer glow overlay. Do not construct the full metadata map merely to select index zero.

For existing viewers changing cadence, collect exactly the previous predicate's recipients after view-rule refresh, group by their new rate, and build one seven-field pose snapshot per display/rate. Reuse existing `sendMetadata` personalization and native grouped encoding. Single-viewer initial spawn and personal-state updates remain immediate and ordered as before. Recheck actual membership on grouped delivery. Do not batch across durations, displays or refresh invocations.

Alternatives considered: keep per-player snapshots (simplest but repeats work); cache snapshots across refreshes (requires additional invalidation and mutable-array ownership); fresh grouped snapshots (chosen, bounded to one transition). No global snapshot cache or new transport abstraction.

Preserve 200/250/500 ms rates, hysteresis, visibility, deadlines, viewer identity, personal scale/glow, immutable published snapshots, all geometry and native SOLID. Existing cross-proxy presence/routing is outside this instance-local entity delivery change. No profiler in production. Do not claim that the original motion outlier is fixed.

Validate fresh cohort transitions, different durations, personal/default recipients, queued old frames and membership lifecycle. Preserve existing full licensed/ordinary suites, fixed-time pose proof and bundle acceptance. Compare the frozen #240 jar against the new jar with the same owned real-client harness and audited recorder; report favorable and adverse results. Keep Stage deployment draft until an observable management path exists; no Production changes.
