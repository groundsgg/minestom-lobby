# NPC visibility range follow-up

User request: the Stage NPC render distance feels too short; continue the performance work.

Design: increase admission from 32 to 64 blocks in 3D, while retaining the independent
32-block vertical limit that excludes the Stage test platform from the lobby floor.
Existing viewers remain until 72 blocks, preventing boundary spawn/despawn churn.
The same admission rule applies during initial attachment and later movement.
Spawn metadata sets the relative client Display view range to 1.25, leaving headroom
for the retention band. Client render-distance/entity-scaling settings still apply
as documented by [Mojang](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-19-4).
Near animation stays at 200 ms through 16 blocks and 250 ms through 32 blocks;
NPCs whose nearest relevant viewer is farther away use 500 ms / 10 clientticks.
This continues the shared-pose policy; true independent per-viewer rates remain N13.
All instance isolation, current-pose re-entry, personal visuals, native SOLID and
Map9/addon catalogue binding remain intact. Labels and gameplay ranges are unchanged.

Alternatives: simply raising the old spherical constant would also animate the elevated
test platform for lobby-floor players. Raising all update rates would restore unnecessary
traffic. Independent vertical admission plus a slower added range addresses both concerns.

- [x] Reproduce initial 50-block invisibility, boundary churn and distant update cost.
- [x] Implement one shared admission rule, hysteresis and matching animation interpolation.
- [x] Verify range movement, vertical exclusion, instance isolation, re-entry and stopped animation.
- [x] Run full Host tests/formatting and the real Stage scene/addon gate; independent review.
- [ ] Open current-main follow-up PR and hand the exact candidate to the joint release owner.

Evidence: 159 Host tests, zero failures/skips, formatting and shadowJar pass. Six added
regressions cover the new range, transitions and client spawn metadata. Three first failed
against the old policy; the spawn-metadata regression failed separately before its fix.
The production-JAR gate accepts the real Map9 and 983 addon asset digests plus both ZIP
hashes, and rejects a wrong catalogue and a pack.mcmeta-only mutation. Independent review
found no actionable issues. The existing Stage image still has the earlier 32-block policy
until the release owner activates the combined follow-up.
