S4 # 00 — Security / Integrity: Wire-or-Delete Decisions

**Status: ✅ SIGNED OFF (2026-07-24)** — all rows locked per the recommended column (S4 = WIRE per user override). WS4a specs are authored against these decisions. This was the one genuine product fork in 2.3.0. Each item below is an *advertised* security or integrity feature that today provides **zero protection** (dead code, an inert sidecar, or a 200-OK stub). For each, the choice is **WIRE** (implement for real) or **DELETE** (remove the code + drop the claim, optionally re-add later). The choice changes release size materially, so nothing in WS4a starts until this is signed off.

**Guiding principle:** it is never acceptable to *ship* an inert security feature. "Do nothing" is not an option for any row — only wire or delete.

Mark each row's **Decision** column and return this doc.

| # | Feature | Today | WIRE size | DELETE size | Recommendation |
|---|---|---|---|---|---|
| S1 | **npm `--provenance` / attestations / `audit signatures`** | No code; bundles silently dropped; endpoints 404 | **L** (greenfield subsystem) | **S** | **WIRE** — it's the supply-chain story the project leads with; a registry that drops provenance undercuts the cooldown narrative |
| S2 | **Docker OCI 1.1 referrers** (cosign/notation/SBOM) | Empty 200 stub; `subject` never indexed | **M** (index on push + serve) / **L** (full conformance) | **S** (return 404/`OCI-Filters` honestly) | **WIRE (M half)** — modern container supply-chain depends on it; deleting means "no image signing discovery," a hard sell for enterprise |
| S3 | **Docker proxy blob cache integrity** | `TrustedBlobSource`, never re-hashed; corrupt upstream cacheable | **M** (verifying tee; `CheckedBlobSource` mostly exists) | n/a — cannot "delete" correctness | **WIRE** — not optional; caching unverified bytes under a trusted digest is a correctness hole |
| S4 | **Maven `.asc` PGP verification** | Full verifier + keyring + V131 table + `verifyPgp` flag; zero callers | **L** (wire verify + admin keyring UI + request path) | **S** (delete pkg + migration + flag) | **✅ WIRE (user decision, 2026-07-24)** — implement per-repo `verifyPgp` parsing, install `JdbcKeyringStore` in `VertxMain`, verify `.asc` against the primary on proxy fetch and hosted store (403 + `checksum_mismatch`-class audit on TAMPERED/UNTRUSTED), and add an admin keyring-upload endpoint + UI card over `pgp_keyring`. Gets its own spec (`WS4-maven.md` / a dedicated `WS4-pgp.md`). |
| S5 | **Go `.zip` integrity (`.ziphash`)** | Inert sidecar (URL/format don't exist) | part of S6 | **S** (delete wiring + fix javadoc) | **DELETE now, WIRE via S6** — the honest zip integrity is dirhash-vs-sumdb, so real integrity is the S6 work; delete the false claim immediately regardless |
| S6 | **Go checksum-db (sumdb) proxy** + genuine zip integrity | Not proxied; docs push a no-op flag; clients disable verification | **M** (sumdb proxy+cache) + **L** (dirhash verify) | **S** (document `GOSUMDB=off` honestly) | **WIRE (M sumdb proxy)** — lets air-gapped clients keep verification on; defer the L dirhash-verify unless required |
| S7 | **PyPI / Composer upload digest verification** | twine `sha256_digest` ignored; `dist.shasum` unverified; gpg dropped | **S** each | n/a — correctness | **WIRE** — trivial and closes a "corrupt upload accepted" hole in hosted mode |
| S8 | **PyPI yank per-repo authorization** | Any valid token can yank any repo (no `security.policy()` on the handler) | **S** | n/a — security bug | **WIRE (must-fix)** — this is a straight authz vulnerability, not a feature choice |

## Also in scope: dead-code hygiene (delete regardless — no decision needed)

These are orphaned classes with no security implication; they're removed as part of the touching workstream so the tree stops implying they do something:

- Maven `RepoHead` (never instantiated), dead `ejb/rar/maven-plugin` `EXT` regex branches.
- npm `NpmStarRepository` + `MetadataEnhancer.enhanceWithStars` (star subsystem, never wired) — unless you want `npm star` (then it becomes a WS4c feature).
- Go `goproxy/Goproxy.java` (hosted metadata helper, unreferenced), orphaned `CacheTimeControl` (revive under WS6 Go-caching instead).
- Docker `CheckedBlobSource` — do NOT delete; wire it under S3.

## Net effect (with S4 = WIRE per user decision)

- **WIRE:** S1 (npm attestation), S2-half (docker referrers), S3 (docker blob verify), **S4 (maven PGP)**, S6-sumdb (go), S7 (upload digests), S8 (pypi authz).
- **DELETE:** S5 (go `.ziphash` inert-claim only — real Go integrity is delivered by S6) + the hygiene list.

**LOCKED 2026-07-24:** S1, S2 (M-half), S3, S4, S6 (sumdb), S7, S8 → **WIRE**; S5 (`.ziphash` inert claim) → **DELETE** (real Go integrity comes via S6); hygiene deletes as listed. WS4a security specs are authored against this.
