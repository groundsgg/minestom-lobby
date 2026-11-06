# Immutable NPC catalog delivery implementation plan

> **For agentic workers:** Use superpowers:executing-plans inline. This continues the authorized Stage rollout; no additional design approval is required for these implementation choices.

**Goal:** Qualify the private NPC distribution through the real Maps service without changing public PackSet channels or existing Stage content.

**Architecture:** Allocate a distinct private build identity using the real Resourcepacks source commit. Export its neutral standalone catalog JAR alongside the private host. The shared Maps API accepts at most two explicitly configured immutable catalog pins in addition to Stable/Edge; its existing worker and catalog validation remain authoritative.

**Tech stack:** Python/Gradle, Kotlin/Quarkus, existing HTTPS CDN and Maps API, private Docker images.

**Spec:** Accepted Stage rollout and the observed rejection of map10: only current Stable/Edge candidates are available. Public catalog1.3.0 is neutral and must not share an identity with the generated NPC variant.

## Global constraints

- Never publish licensed model/animation/texture resources as catalog metadata.
- Do not change Stable/Edge pointers or declare private bytes to be existing public artifacts.
- Keep existing worker validation, authorization and map pin gates. The backend addition is shared metadata support, not an environment authorization boundary.
- At most two configured pins and four total candidates; preserve normal channels if a pin conflicts after channel refresh.
- All world/light bytes remain identical to the currently loaded map; scene catalog reference is the intended content change.
- Preserve active players; no forced deletion of allocated lobby pods.

## Review focus

- Private generated definitions accidentally reuse a public neutral catalog identity.
- Licensed resources slip into a published metadata JAR.
- Hostile or mutable pin URLs bypass trusted CDN origin/path restrictions.
- Duplicate identities break single-candidate worker selection.
- A conflicting pin blocks unrelated production map derivation or replaces a live channel.

### Task 1: Export a uniquely versioned private catalog

**Files:** tools/build_npc_distribution.py, tools/test_build_npc_distribution.py, README.md.
**Interfaces:** `private_catalog_version(commit, number)`; `verify_catalog_metadata(jar, version)`; required `--catalog-build-number`. Receipt exports the source-built neutral `catalog.jar`, its coordinate/version/hash/size and all private input digests.

- [ ] Add failing identity/publication-boundary tests, then implement them.
- [ ] Pass exact build version, real publicationId/provenanceCommit to the included Resourcepacks build. Export the original standalone catalog JAR after acceptance.
- [ ] Full regular checks, private build/loader checks and immutable input receipt.

### Task 2: Preserve explicitly pinned catalogs in Maps derivation

**Files:** service-maps catalog pin parser/provider, DeriveCoordinator, readiness, configuration/tests/docs.
**Interfaces:** empty-default operator pin JSON; up to two strict pins. `candidates()` returns canonicalized channel candidates plus valid pins; coordinator preserves all four. No request schema or worker-image change.

- [ ] Test empty config, bounds, strict CDN URI/coordinate/digest, duplicate/conflicting identities and refreshed channel conflicts before implementation.
- [ ] Implement bounded parser and additive canonical merge, preserving channel readiness and reporting withheld pins.
- [ ] Prove a third candidate reaches the existing worker, run the complete service suite, and obtain a fresh critical review.

### Task 3: Deliver and qualify the exact Stage bundle

**Files:** private rollout artifacts, Stage deploy values/tests and concrete image/catalog config.

- [ ] Publish neutral catalog only at a fresh immutable Stage-specific hash path; preserve all channels.
- [ ] Deploy the tested additive Maps API support while preserving worker and other settings.
- [ ] Derive the new map, use an isolated test pin to finish lighting, verify world/light/scene contracts, and run connected clients against the complete host JAR.
- [ ] Publish the verified private host image, review/merge Stage values and conditionally advance only the lobby pin. Await user native acceptance on both lobbies.
