# Pantera 2.3.0 — Specifications

This directory holds the implementation specs for the **2.3.0 "bulletproof" major release**. They are authored to be executed by AI coding agents (Sonnet) under Opus-authored specs, one workstream/spec at a time, each on its own branch with the standard build + itcase gate as definition of done.

**Source of truth for *why*:** [`analysis-gap-analysis.md`](analysis-gap-analysis.md) and [`analysis-api-completeness.md`](analysis-api-completeness.md) (synthesized from twelve code audits). These specs are the *what/how*.

## How to consume a spec (for a coding agent)

1. Read the spec top to bottom. Read every cited `file:line` before writing code.
2. Follow the project rules in `/CLAUDE.md` — Slice/Storage patterns, `EcsLogger` logging, PMD ruleset, thread model (never block the event loop), audit taxonomy, "metric without a panel is invisible."
3. TDD where the spec gives acceptance criteria: write the failing test first.
4. Definition of done per spec: `mvn clean install -T8` fully green (unit + PMD + license), the spec's acceptance tests pass, docs updated in the same change (CHANGELOG + reference tables), and any new metric has a Grafana panel.
5. Do **not** exceed the spec's scope. If the spec is wrong or blocked, stop and report — don't improvise product decisions.

## Spec template

Every `WS*.md` uses this skeleton:

```
# WS<n> — <title>
Status · Depends-on · Blocks · Decision-gated
1. Problem & goal
2. Current state (evidence, file:line)
3. Target design
4. Implementation plan (ordered, file-level)
5. Acceptance criteria (testable)
6. Test requirements (unit / itcase / load)
7. Out of scope
8. Risks & rollback
9. Docs & observability to update
```

## Workstream map & status

| # | Workstream | Kind | Blocking? | Status |
|---|---|---|---|---|
| **00** | [Security/integrity wire-or-delete decisions](00-security-integrity-decisions.md) | Decision | Gates WS4a | ✅ SIGNED OFF (2026-07-24) |
| **WS1** | [Storage for scale — blob-store disk-primary + presign](WS1-storage-for-scale.md) | Tentpole (build) | Gates "1000 req/s on S3" | 📝 DRAFT |
| **WS2** | [HA correctness](WS2-ha-correctness.md) | Tentpole (build) | Gates "run N nodes" | 📝 DRAFT |
| **WS3** | [Streaming & memory](WS3-streaming-and-memory.md) | Build | Gates OOM-safety at 1000 req/s | 📝 DRAFT |
| **WS4** | API completeness & hosted-write correctness — per-format sub-specs below | Correctness | WS4a gated by 00 | 📝 DRAFT (per-format) |
| ↳ | [WS4-npm](WS4-npm.md) · [WS4-maven](WS4-maven.md) · [WS4-pypi](WS4-pypi.md) · [WS4-composer](WS4-composer.md) · [WS4-go](WS4-go.md) · [WS4-docker](WS4-docker.md) | | | |
| **WS5** | [Cooldown-cache coherence](WS5-cooldown-cache-coherence.md) | Correctness | — | 📝 DRAFT |
| **WS6** | [Upstream efficiency & resolution availability](WS6-upstream-efficiency.md) | Efficiency | — | 📝 DRAFT |
| **WS7** | [Observability](WS7-observability.md) | Cross-cutting (DoD) | Gates release | 📝 DRAFT |
| **WS8** | [npm client conformance — corepack/yarn/npm](WS8-npm-client-conformance.md) | Correctness | Gates "drop-in npm registry" | 📝 DRAFT |

WS4 is split into per-format sub-specs because most items are independent "a routed handler lies; make it truthful" tasks ideal for isolated coding agents. Each WS4-*.md covers that format's API-completeness + hosted-write correctness + its WS4a security item from `00`.

## Recommended build order

1. **00 decisions** (unblocks WS4a security items) + **WS1** + **WS2** in parallel — the long poles.
2. **WS3** overlaps WS1's write path (both touch `ProxyCacheWriter`); sequence right after WS1's storage-writer lands.
3. **WS4 / WS5 / WS6** are per-format and parallelizable once the storage/streaming primitives exist.
4. **WS7** tracks every metric introduced by the above; it is a definition-of-done gate, not a trailing task.
5. **WS8** depends on WS4-npm having landed; it is also the source of the **2.2.5 backport** (see WS8 §10).

## Release gate (whole 2.3.0)

- `mvn clean install -T8` green across the reactor.
- Full itcase suite green, expanded to cover the previously-thin clients (uv, poetry, gradle dynamic versions, multi-arch docker, composer).
- A **load test at ≥1000 req/s reads AND writes against an S3 backend** proving WS1 — this is the headline claim of the release and must be demonstrated, not asserted.
- No inert security/integrity feature ships (every item in `00` is either wired-and-tested or deleted).
