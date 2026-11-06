# Private NPC distribution implementation plan

> **For agentic workers:** Use superpowers:executing-plans. Execute inline; the accepted roadmap authorizes implementation without another design approval.

**Goal:** Build and qualify a private NPC lobby directly from sources, without class overlays.

**Architecture:** An opt-in composite substitutes only resourcepacks-catalog and resourcepacks-npc-runtime from an explicitly pinned, clean Resourcepacks checkout. The host binds the exact client addon SHA-1 into its resources; a build driver verifies the final executable JAR against the scene and addon using the existing production-loader acceptance probe before writing a distribution receipt.

**Tech Stack:** Gradle Kotlin DSL, Kotlin/JUnit, Python standard library, Java source launcher.

**Spec:** Accepted NPC delivery roadmap in this conversation. Public Maven artifacts retain their neutral empty NPC index. Licensed sources and generated data stay local/private.

## Global constraints

- No purchased inputs, generated model data, or addon ZIPs in Git or public Maven.
- Use published Scene 0.4.5; substitute only the two Resourcepacks modules.
- Explicit source commit, addon SHA-256/SHA-1 and map inputs; no downloads on player login.
- Normal builds continue without private inputs; Stage demo startup rejects absent or mismatched addon binding.
- Preserve accepted Stage deployment until a replacement passes qualification.

## Review focus

- Neutral JAR accidentally selected for Stage must fail before world creation.
- Addon changed after qualification must fail its pinned hashes and file contract.
- Missing, duplicate or unexpected addon entries must fail qualification.
- Dirty/wrong Resourcepacks checkout must fail before Gradle starts.
- A successful build alone must not produce a qualification receipt.

### Task 1: Bind addon identity and enable opt-in private source builds

**Files:** settings.gradle.kts, build.gradle.kts, src/main/kotlin/gg/grounds/minestom/lobby/NpcAddonBinding.kt, NpcDemoPack.kt, src/test/kotlin/gg/grounds/minestom/lobby/NpcAddonBindingTest.kt.

**Interfaces:** `NpcAddonBinding.load().requireMatches(sha1)` validates the packaged binding; `npcResourcepacksDirectory` substitutes two modules, `npcAddonZip` generates the binding resource.

- [ ] Add tests for valid, invalid and mismatched bindings; watch them fail.
- [ ] Implement binding and fail-closed configured Stage pack loading before world creation.
- [ ] Add explicit composite and reproducible binding generation. Run regular tests and private build.

### Task 2: Qualify complete source-built distributions

**Files:** tools/build_npc_distribution.py, tools/test_build_npc_distribution.py, tools/NpcStageBundleAcceptance.java, README.md, .github/workflows/ci.yml.

**Interfaces:** CLI receives pinned Resourcepacks commit, licensed sources, composite policy, pinned addon, map directory and new output directory. Receipt includes exact host/source/input/output hashes and verifier output.

- [ ] Test checkout/input rejection and real ZIP completeness/corruption checks before implementation.
- [ ] Implement build driver using Gradle and the existing Java production-loader probe. Require exact hashes, unique ZIP entries and nonempty compiled contracts; copy results only after acceptance.
- [ ] Run Python suite, full regular host suite and full private host suite. Build a real private distribution and verify failure cases without publishing.
- [ ] Review the whole branch with a fresh reviewer; record remaining rollout steps and create a reviewable PR.
