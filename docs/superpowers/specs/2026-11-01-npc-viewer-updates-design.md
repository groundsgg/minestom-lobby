# Next NPC performance batch

The user requested further implementation and a measured assessment of the first batch.
This work starts after released Host 2.15.0; it must not delay the already coordinated rollout.

## Evidence and selected approach

The first Stage batch reduces observed ten-minute mean tick time from 7.06 to 2.47 ms,
allocation from 87.3 to 14.2 MB/s and pod egress from 713 to 64 kB/s in windows with three
players. Positions and warmup differ; this is an observation, not a controlled experiment.
A 436 ms tick remains. Raw counter deltas give 20.0 versus 19.925 TPS in those windows.
The repeatable local benchmark isolates the renderer and confirms 68% fewer metadata
packets and 79% fewer metadata payload bytes than the original model/renderer pair.

Local mixed-viewer profiling shows all viewers still receive the nearest viewer's cadence.
JFR without serialization attributes about 51% of sampled CPU to display conversion and
30% to pose math; double arrays dominate sampled allocations. Therefore this batch:

1. Shares root/local affine preparation between all parts until the Scene transform changes.
   Keep the general decomposition and exact operation order; no approximate TRS shortcut.
   Convert quaternions directly to primitive floats, avoiding boxed Double arrays.
2. Keeps one shared pose but accumulates metadata deltas separately for active 200/250/500 ms
   viewer cohorts. Flush on each cohort's deadline, including ticks without a new shared pose.
   Override interpolation duration for the receiving cohort. A far viewer receives all field
   changes since its previous flush, not only the last common delta. Publish a detached map
   by ownership transfer, reusing immutable common deltas when only one frame accumulated.
   Ordinary viewers share packet objects; queued maps must never be mutated afterwards.
3. Adds bounded model/phase timers and metadata packet/field counters to the existing runtime
   MeterRegistry. Metrics are optional if the runtime registry is absent. Do not serialize
   packets again for metrics or label users, NPC instance IDs, positions or clip timestamps.

Global lower update rate was rejected because it would also degrade nearby viewers.
Dropping individual common deltas was rejected because a field may change only once.
Async math and a new model format remain separate backlog items.

Benchmark ruling: the first cohort prototype repeated the duration field and copied every
common delta into every cohort; near payload rose8% and mixed count-only work rose28%.
Duration is now set only in full entry/transition snapshots. MetadataHolder was inspected:
individual changes use Map.of and batches publish a copied map before clearing internal
state. The single-delta fast path and transfer of detached merged maps retain that ownership
contract while avoiding unnecessary copies. Packet immutability has regressions for both
published near deltas subsequently merged for far viewers and successive far flushes.

## Delivery lifecycle

Initial attachment uses the established full Minestom spawn plus explicit personalized state.
Band changes receive a current full transform snapshot and reset interpolation. Use small
distance-band hysteresis (near exit 18 blocks, medium exit 36; entry 16/32) to avoid repeated
snapshots while walking near a threshold. Global admission64/retention72/vertical32 stays.
Stop, seek and forced Scene changes flush pending final poses; close and empty cohorts discard
pending maps. Viewer scale/highlight updates remain immediate and do not restart animation.
No-viewer suspension and real-time elapsed animation remain intact. Position updates, sounds,
labels, collision, queue actions and network-wide routing retain existing behavior.

## Verification and rollout

Regressions use real Minestom viewers and captured packets: mixed near/medium/far cadence,
changes accumulated across skipped sends, final stop/seek, re-entry, band transitions,
personal scale/glow, closed handles and packet-map immutability. Affine tests reconstruct
client-visible geometry for signed/zero scale, shear, root/local changes and prepared reuse.
Metrics tests use a real registry and ensure bounded labels and zero retained per-viewer data.
Run the full licensed and public Host suites, formatting, exact Map9/983-file addon gate,
repeated baseline/candidate probes and an independent whole-branch review.
Open one follow-up source PR; no new pack, map, regular release or parallel Stage activation.
