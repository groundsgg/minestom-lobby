# Primitive display pose implementation plan

> **For agentic workers:** use superpowers:executing-plans in the existing isolated worktree.

**Goal:** Reduce temporary boxed numbers during display pose conversion without changing client metadata.

**Architecture:** Preserve the existing decomposition and quaternion algorithms;
replace only boxed intermediate collections with primitive results. Qualify against
the accepted Stage artifact, without the rejected prepared-clip candidate.

**Tech Stack:** Kotlin, JDK, Gradle, Minestom, existing owned protocol clients.

**Spec:** `docs/superpowers/specs/2026-11-04-npc-primitive-pose-design.md`

## Global Constraints

- All branch predicates, arithmetic order, float conversion points and array ownership remain unchanged.
- Accepted 64/72-block range, animation cadence, geometry and SOLID remain unchanged.
- No #62 source substitution, Production, package publication or shared service restart.

## Review Focus

- Quaternion trace and diagonal-tie boundaries retain identical branches and bits.
- Negative and zero scales keep the general decomposition path.
- Returned arrays remain independent across calls and metadata objects.
- The measured jar preserves all unrelated active Stage entries and duplicate archive entries.
- Allocation measurements do not substitute for full connected-server qualification.

### Task 1: Primitive arithmetic and correctness qualification

**Files:** modify `src/main/kotlin/gg/grounds/minestom/lobby/scene/DisplayTransform.kt`;
add `src/test/kotlin/gg/grounds/minestom/lobby/scene/DisplayQuaternionTest.kt`.

**Interfaces:** unchanged `DisplayTransform.fromModel` and `apply`.

- [ ] Write branch/boundary/ownership characterizations and an external warmed conversion allocation probe (budget 64 bytes per returned quaternion).
- [ ] Run the probe against the accepted jar; retain actual failure or revise the hypothesis before implementation.
- [ ] Replace boxed quaternion intermediates and reciprocal scale list only.
- [ ] Run formatter and all licensed Host tests; compare 723456 serialized states.
- [ ] Freeze source jar, overlay only reviewed Host classes onto accepted Stage jar; verify every archive entry and run artifact tests.
- [ ] Run an owned 20/60-client comparison with movement and readmission; retain all windows and decide qualification.
- [ ] Obtain independent read-only whole-branch review; address correctness findings and record results/backlog/PR.
