# Native collider pose initialization

The Stage SOLID probe can have all six server-side bodies/carriers correctly mounted,
positioned and tracked while the client still crosses their expected collision boundary.
An A/B capture recorded the same geometry on both lobbies; only the working client stopped
at the exact native contact plane. Extracting the actual earlier shipped image confirmed
2,641 native/Minestom/collision classes were unchanged before the first reported regression.

An executable replay using actual vanilla26.3 Shulker objects establishes an initialization
gap: spawn grid-snaps their position, and mounting alone does not immediately reposition
them. A normal passenger tick repairs the pose. Explicit position synchronization after
mounting repairs it immediately in both ticking and non-ticking handler branches because
Shulker uses the NO_OP interpolation handler.

For each new viewer, queue one instance-next-tick callback per body. Reassert its dedicated
carrier mount, then send the exact server pose. Require current tracking revision, live
body/carrier, matching vehicle/instances and both viewer memberships under the probe's
cleanup monitor. Removal invalidates the viewer revision. This costs twelve extra packets
per normal viewer entry and adds no periodic or animation update traffic.

Regression coverage includes position-after-mount for initial/retracked viewers, exactly
one snapshot after rapid reentry, and no snapshot after leaving or closing. The missing
position regression failed before the fix. The source change excludes temporary diagnostic
listeners and traces; private Stage verification retained them separately for client acceptance.

The private build must preserve the corrected NPC addon and separately reviewed Scene
corner guard. This does not extend native collision to BOUNCE; that request is tracked as N31.
