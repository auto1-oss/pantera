# WS4-maven — Maven/Gradle API completeness & hosted-write correctness

- **Status:** 📝 DRAFT
- **Depends on:** `00-security-integrity-decisions.md` (S4 = **WIRE**, locked 2026-07-24) for the PGP items only. The rest are unblocked.
- **Blocks:** the "hosted Maven is bulletproof" and "advertised `.asc` verification is real" release claims.
- **Decision-gated:** WS4-maven.1–.3 (PGP) are gated by 00/S4 (now signed off WIRE). All other items are not gated.
- **Size:** L. Split into WS4-maven.1 … .12; each is an isolated agent task. The tentpole is **WS4-maven.4** (server-side `maven-metadata.xml` regeneration on deploy) — the only L item and the riskiest.

Gradle and Maven share every slice touched here (`RepositorySlices.java:766-775` local, `:776` proxy, `:990` group). "Maven" below means "Maven and Gradle."

## 1. Problem & goal

The Maven **proxy** path is mature. The **hosted (local)** path is the weak leg, and several proxy/group responses advertise capabilities they don't deliver:

- `maven-metadata.xml` on hosted deploy is **client-trusted with no server-side merge and no lock** — concurrent or stale `mvn deploy`/`gradle publish` silently drops previously-published versions from `<versions>`.
- Uploaded checksums are **stored but never verified** — a corrupt upload is accepted; the `checksum_mismatch` audit outcome never fires.
- `.asc` PGP verification is **fully-written dead code** (verifier + keyring + V131 table + `verifyPgp` flag, zero callers) — a swapped/unsigned artifact passes while the tree implies it's checked.
- The `ETag` is advertised on every local artifact but `If-None-Match` is never read → every re-resolve re-downloads a warm artifact.
- Proxy artifact bodies are served as bare `ok().body()` — no `ETag`/`Content-Type`/`Content-Disposition`/`Accept-Ranges`.
- HEAD is inconsistent (no `Content-Length` on local artifact HEAD; no `Last-Modified` anywhere on artifacts despite the proxy HEAD javadoc promising it).
- Group metadata `.sha256`/`.sha512` are computed over a member's own bytes, not the bytes the group actually serves → checksum mismatch risk.
- `Range`/`206`/`Accept-Ranges` is unwired for all Maven modes (the machinery exists, one routing hop away).

**Goal:** make the hosted path correct under concurrent deploys, make every advertised validator/header truthful, wire the locked-in PGP verification, and remove the orphaned dead code so the tree stops implying protections it doesn't provide. Release immutability and Range support are the two genuinely-new capabilities.

## 2. Current state (evidence)

| Area | State | Evidence (file:line) |
|---|---|---|
| Hosted `maven-metadata.xml` on deploy | **Only normalizes client XML**; never recomputes `<versions>` from storage; no lock | `UploadSlice.java:180-224` (intercept), `:287-376` (`fixMetadataBytes`) |
| Concurrency-safe regen (reference impl) | Exists, **only in the import path** — read-modify-write under `storage.exclusively(metadataKey)` | `MetadataRegenerator.java:218-275`, `collectMavenVersions:285-328`, `writeMavenMetadata:339-381`; `MavenMetadata.versions():50-67` |
| Hosted checksum verify on store | **None** — client `.sha1` saved via normal flow, never compared; server also **re-reads the primary 4×** to generate its own | `UploadSlice.java:240` (save), `:441-460` (`generateChecksums`, `storage.value(key)` per alg) |
| Release-redeploy immutability | **None** — unconditional `storage.save`; release GAVs silently overwrite | `UploadSlice.java:240` |
| `.asc` PGP verification | **Dead code** — zero non-test callers of `PgpVerifier`/`KeyringStore`/`verifyPgp`/`pgp_keyring` | `PgpVerifier.java:75-103` (`Result` enum), `KeyringStore.java`, `JdbcKeyringStore.java`, `InMemoryKeyringStore.java:55` (`addAsciiArmored`), `V131__pgp_keyring.sql` |
| Local artifact `If-None-Match`→304 | **ETag advertised, never read** | `ArtifactHeaders.java:70-73` (ETag=sha1); `LocalMavenSlice.java:86-109` (GET never reads `If-None-Match`) |
| Proxy artifact response headers | **Bare `ok().body()`** — no ETag/Content-Type/Content-Disposition/Accept-Ranges | `CachedProxySlice.java:1078` (fresh fetch), `:887-889` (cache.load), `:1399-1405` (`serveFromCache`) |
| Proxy metadata headers (the model to copy) | **Correct** — Pantera ETag + Last-Modified + `If-None-Match`→304 | `CachedProxySlice.java:729-746` (`buildMetadataResponse`) |
| HEAD `Content-Length`/`Last-Modified` | local artifact HEAD: **no Content-Length**; proxy HEAD: **Content-Length only** (javadoc promises Last-Modified) | `LocalMavenSlice.java:110-125`; `HeadProxySlice.java:59-63` (promise), `:85-89` (emits only length) |
| Group metadata `.sha256`/`.sha512` | **Bypass the merge** — only `.sha1`/`.md5` recomputed over served bytes; sha256/512 fall through to a member's own sidecar | `MavenGroupSlice.java:237` (sha1/md5 handled), `:242` (else → `delegate`), `:249-302` (recompute over merged) |
| Range/206/Accept-Ranges | **Unwired for Maven** — `RangeSlice` lives only inside `StorageArtifactSlice.response()`; Maven serves via the static `optimizedValue()` helper, never `.response()` | `RangeSlice.java`; `StorageArtifactSlice.java:104-136` (wires Range in `selectArtifactSlice`), `:208-214` (static `optimizedValue`); `LocalMavenSlice.java:97,141` (uses static helper) |
| Orphaned `RepoHead` | Never instantiated (maven-adapter copy); only its own ITCase references it. go-adapter has a separate `RepoHead` | `RepoHead.java:26-51`; only ref is `maven-adapter/.../RepoHeadITCase.java` |
| Dead `EXT` branches | `maven-plugin`/`ejb` are packaging types, never file suffixes → their regex branches never match | `MavenSlice.java:53-54` (`EXT`), `:61-65` (`ARTIFACT` regex) |

**Module direction (constrains the tentpole):** `maven-adapter` depends on `pantera-storage-core` (has `Storage.exclusively`, `Storage.java:200`), `pantera-core`, and BouncyCastle — but **not** `pantera-main`. `pantera-main` depends on `maven-adapter` (the importer already imports `com.auto1.pantera.maven.metadata.*`). So the shared regenerator must be **extracted into `maven-adapter`**; the pantera-main importer then delegates to it. `UploadSlice` cannot call `MetadataRegenerator` directly.

## 3. Sub-items

Each is independently shippable with the standard gate. Format: current → target → file-level plan → acceptance → size. Prefer real `mvn`/`gradle` client-driven itcases (`test_images/`, `-Pitcase`) over unit-only proofs where noted.

### (a) S4 — `.asc` PGP verification (WIRE, per 00/S4)

#### WS4-maven.1 — `verifyPgp` config + `JdbcKeyringStore` install + keyring plumbing — **M**
- **Current:** `PgpVerifier`/`KeyringStore`/`JdbcKeyringStore` compile but have zero non-test callers; `verifyPgp` is referenced nowhere; the `pgp_keyring` table (V131) is never read.
- **Target:** a per-repo `verifyPgp: true` YAML flag parsed into `RepoConfig`; a single `JdbcKeyringStore` installed at boot from the shared `DataSource`; the keyring + flag threaded to the two slices that need them (proxy fetch, hosted store). DB-less boots fall back to `InMemoryKeyringStore` (empty) so `verifyPgp` degrades to "everything untrusted → reject" only when explicitly enabled.
- **Plan:**
  1. `settings/repo/RepoConfig.java` — parse `verifyPgp` (default `false`); expose `boolean verifyPgp()`.
  2. `VertxMain.java` — construct one `JdbcKeyringStore(dataSource)` and expose it via a static `install(...)` + `activeSupplier()` fallback, mirroring `UpstreamBreakerSettingsLoader.install` (`VertxMain.java:350-359`). No DB ⇒ `InMemoryKeyringStore`.
  3. Thread `verifyPgp` + a `PgpVerifier` (built from the installed keyring) through `MavenProxy` (`adapters/maven/MavenProxy.java`) → `MavenProxySlice.buildRoute` (`:203-268`) → `CachedProxySlice` ctor; and through `MavenSlice.createSliceRoute` (`:138-190`) → `UploadSlice` ctor. Keep the existing ctors as delegating overloads (PMD: one field-initializing ctor).
- **Acceptance:** with `verifyPgp: false` (default), behavior is byte-identical to today (a regression guard test asserting no keyring lookups occur). With `verifyPgp: true` and an empty keyring, a signed-artifact fetch/store is rejected (proves the flag is honored and the store is consulted).
- **Size:** M.

#### WS4-maven.2 — verify `.asc` on proxy fetch + hosted store — **M**
- **Current:** `.asc` bytes are proxied/stored and served but never verified (`CachedProxySlice.isChecksumSidecar:432-436` treats `.asc` as a passthrough sidecar; `UploadSlice` saves it as a normal file).
- **Target:** when `verifyPgp` is enabled, verify the detached `.asc` signature against the **primary bytes** before the primary is committed (proxy) or acknowledged (hosted). `PgpVerifier.Result`:
  - `VERIFIED` → proceed.
  - `TAMPERED` / `UNTRUSTED_KEY` → **403**, do **not** cache/persist the primary, emit `artifact_publish`(hosted)/`artifact_access`(proxy) with `event.outcome=failure`, `reason=checksum_mismatch` (the sanctioned taxonomy value closest to the V131 `pgp_verification_failed` intent), **and** a structured application-log state transition `event.action=pgp_verification_failed` (CLAUDE.md: state transitions are logged, not just counted).
  - `MISSING_SIGNATURE` / `MALFORMED` → policy: reject when `verifyPgp` requires a signature (default when enabled); the "no signature at all" case is the per-repo decision — spec it as **reject** for `verifyPgp: true` (Maven Central-tier semantics) with a documented `verifyPgp: signed-only` future knob left out of scope.
- **Plan:**
  1. Proxy: in `CachedProxySlice.fetchVerifyAndCache` (`:957-1091`), fetch the `.asc` sidecar alongside `.sha1` (reuse `fetchSidecar(line, headers, ".asc")`, `:1367-1386`) and gate the commit on `PgpVerifier.verify(primaryBytes, ascBytes)`. The primary bytes are already teed through `ProxyCacheWriter`; verify before `streamThroughAndCommit` commits, or reject-and-drain on failure (mirror the cooldown-block drain at `:1010`). Preserve the circuit-open marker on any error funnel (`UpstreamCircuitOpenException`).
  2. Hosted: in `UploadSlice`, when a primary + `.asc` are both present for a GAV and `verifyPgp` is on, verify on the second-arriving file (Maven deploys primary then `.asc`); reject + audit on failure. Use `storage.value` for the primary bytes.
- **Acceptance (itcase):** a `mvn deploy`/proxy fetch of a correctly-signed artifact with the signer's key in `pgp_keyring` succeeds; the same artifact with (a) a tampered body, (b) a signature from a key not in the keyring is rejected `403`, the primary is **absent** from storage/cache afterward, and one `checksum_mismatch` audit + one `pgp_verification_failed` app-log line are emitted. `verifyPgp: false` path unchanged.
- **Size:** M.

#### WS4-maven.3 — admin keyring-upload endpoint + UI card — **M**
- **Current:** no way to populate `pgp_keyring`; V131 comment references a "T-S03 follow-up REST endpoint" that was never built.
- **Target:** admin REST CRUD over `pgp_keyring`, and a Settings UI card.
- **Plan:**
  1. `api/v1/AdminAuthHandler.java` — `GET /api/v1/admin/pgp-keys` (list: key_id_hex, fingerprint, uploaded_by/at, description), `POST` (accept an ASCII-armored public key block; parse via `InMemoryKeyringStore.addAsciiArmored` to derive `key_id_hex` + SHA-1 `fingerprint`; `INSERT … ON CONFLICT (key_id_hex) DO NOTHING`), `DELETE /{key_id_hex}`. Reuse the shared auth handler instance; do blocking JDBC on `HandlerExecutor.get()`, never the event loop; audit each mutation (`event.category=configuration`, action snake_case). After write/delete, call `JdbcKeyringStore.invalidate(keyId)`.
  2. `pantera-ui` `SettingsView.vue` — a "Maven PGP Keyring" card (upload textarea, list with fingerprints, delete), labeled so it isn't confused with the two circuit-breaker cards. UI checks green (`type-check && lint && test && build`).
- **Acceptance:** upload a public key via the endpoint → it appears in the list with the correct fingerprint → a signed artifact whose signer matches now verifies (ties WS4-maven.2 end-to-end); delete → next verification of that signer returns `UNTRUSTED_KEY`. Endpoint requires admin auth (401/403 without).
- **Size:** M.

### (b) Tentpole — hosted-write correctness

#### WS4-maven.4 — server-side `maven-metadata.xml` regeneration on deploy (concurrency-safe) — **L · TENTPOLE**
- **Current:** on deploy, `UploadSlice` only normalizes the client-uploaded XML (`fixMetadataBytes:287-376`) — it never recomputes `<versions>` from what's actually in storage. Two clients deploying `1.0` and `1.1` concurrently each PUT a metadata that lists only their own version; last-write-wins ⇒ the other version vanishes from `<versions>` even though its jar is present. No lock.
- **Target:** on every **primary-artifact** deploy (not metadata, not checksum, not `.asc`/`.sig`), regenerate `maven-metadata.xml` server-side under a **per-GA lock** by re-deriving the version set from storage — so concurrent/stale deploys converge and never drop a published version. The client-uploaded `maven-metadata.xml` is no longer trusted as the source of `<versions>`; the server view is authoritative. Reuse the importer's proven algorithm — but, per the module constraint (§2), **extract it into `maven-adapter` first**.
- **Plan:**
  1. **Extract** `collectMavenVersions` + the write step (`MetadataRegenerator.java:263-381`) into a new `maven-adapter` class `com.auto1.pantera.maven.metadata.MavenMetadataRegenerator` that runs under `storage.exclusively(metadataKey, …)` (`Storage.java:200`) and builds the XML via the existing `MavenMetadata.versions()` (`MavenMetadata.java:50-67`, which already computes `<latest>`/`<release>`/`<versions>`). This kills the today's `<release>`-client-sent-vs-`<latest>`-recomputed disagreement (gap #7).
  2. Repoint `pantera-main` `MetadataRegenerator.regenerateMaven` (`:218-275`) to **delegate** to the extracted class — one algorithm, two callers, no divergence.
  3. Wire it into `UploadSlice.response`: after a successful **primary** save (the `shouldGenerateChecksums` branch, `:240-257`), call the regenerator for that GA. Drop the `fixMetadataBytes` client-XML path as the source of truth — either ignore client metadata PUTs (regenerator owns the file) or accept-then-immediately-regenerate. Keep `<lastUpdated>` normalization (`MavenTimestamp.now()`).
  4. Regenerate the metadata checksums (`.sha1/.md5/.sha256/.sha512`) over the **regenerated** bytes inside the same lock, so metadata and its sidecars are always consistent (feeds WS4-maven.10's group parity).
  5. SNAPSHOT: release-level `maven-metadata.xml` (the GA listing) is regenerated as above. Snapshot-level `<snapshot><timestamp>` maintenance stays client-driven (out of scope — call it out in §5) to bound the tentpole.
- **Acceptance (itcase, the headline test):** two `mvn deploy` (or `gradle publish`) runs of **different versions** of the same GA executed **concurrently** both end up in `<versions>` of the served `maven-metadata.xml`, and `<latest>`/`<release>` point at the highest (release) version — repeated under a small burst (e.g. 5 versions × parallel) with zero lost versions. A stale deploy (client metadata listing only an old version) does **not** shrink `<versions>`. `mvn` re-resolves and Gradle dynamic-version (`1.+`) resolution against the regenerated metadata succeed.
- **Size:** L (largest and riskiest in this spec — land behind the concurrency itcase before anything else in WS4-maven flips hosted behavior).

#### WS4-maven.5 — checksum verification on hosted store — **M**
- **Current:** client checksums are saved but never compared (`UploadSlice.java:240`); `generateChecksums` re-reads the primary 4× (`:441-460`); `checksum_mismatch` audit outcome never emitted for Maven.
- **Target:** when a client uploads a checksum sidecar (`.sha1`/`.sha256`/`.md5`/`.sha512`) for a primary that exists, compare the uploaded digest against the server-computed digest of the stored primary; on mismatch **reject 400** (or 409) and emit `artifact_publish` `event.outcome=failure`, `reason=checksum_mismatch`; the corrupt primary is not left advertised. Fold in single-pass digest generation (compute all four in one read via `ContentDigest`/`DigestingContent`) so the primary is read once, not four times.
- **Plan:**
  1. `UploadSlice.response` checksum branch: on a sidecar PUT whose primary key exists, read the primary once, compute the matching digest, compare to the uploaded value; mismatch → reject + audit + log `event.action=checksum_verification_failed`.
  2. Replace the per-alg `storage.value(key)` loop in `generateChecksums` (`:441-460`) with a single-read multi-digest computation.
- **Acceptance (itcase):** `mvn deploy` of a jar whose companion `.sha1` is corrupted is rejected with `checksum_mismatch` audit and the jar is not served as a valid artifact; a correct deploy succeeds and stores all four sidecars; the primary is read exactly once during generation (invocation-count unit test on a recording storage).
- **Size:** M.

#### WS4-maven.6 — release-redeploy immutability — **M**
- **Current:** unconditional `storage.save` (`:240`) — a released (non-SNAPSHOT) GAV can be silently overwritten.
- **Target:** a config-gated policy (`releaseImmutable: true`, default per admin decision) that rejects redeploy of an existing **release** primary with **409 Conflict** + an `artifact_publish` failure audit; SNAPSHOT redeploys are always allowed. No feature-flag runtime toggle beyond the per-repo config (CLAUDE.md: settled changes ship full; this is a genuine per-repo policy, not a rollback lever).
- **Plan:**
  1. `settings/repo/RepoConfig.java` — parse `releaseImmutable` (default documented in configuration-reference).
  2. `UploadSlice.response` primary branch — for a non-SNAPSHOT path, `storage.exists(key)` before save; if present and immutable, 409 + audit; else proceed. SNAPSHOT detection reuses the `.*SNAPSHOT.*` routing already in `MavenSlice.createSliceRoute:161-174`.
- **Acceptance (itcase):** redeploying an identical release GAV returns 409 and does not overwrite the stored bytes; a SNAPSHOT redeploy succeeds; with `releaseImmutable: false` the old overwrite behavior returns (regression guard).
- **Size:** M.

### (c) Validator / header truthfulness

#### WS4-maven.7 — honor `If-None-Match`→304 on local + proxy-cache-hit artifacts — **S**
- **Current:** local artifacts advertise `ETag: <sha1>` (`ArtifactHeaders.java:70-73`) but `LocalMavenSlice` never reads `If-None-Match`; proxy artifact hits never set/read validators. Proxy metadata already does this correctly (`CachedProxySlice.buildMetadataResponse:729-746`) — the model to copy.
- **Target:** on a GET whose inbound `If-None-Match` matches the artifact's sha1 ETag, return **304 Not Modified** with the `ETag` (and `Last-Modified`), no body — for local artifacts and proxy cache-hit artifacts.
- **Plan:**
  1. `LocalMavenSlice.artifactResponse` GET (`:86-109`) — compute/lookup the sha1 (already fetched via `RepositoryChecksums`), compare to `If-None-Match`; on match return 304 before streaming the body.
  2. `CachedProxySlice.serveFromCache` (`:1394-1406`) and the `cache.load` hit branch (`:882-890`) — attach the ETag (from the stored `.sha1` sidecar) and honor `If-None-Match`.
- **Acceptance (itcase):** an `mvn` re-resolve of an unchanged artifact issues a conditional GET and receives **304** (no body) instead of a full re-download, in both local and proxy modes (assert via a client that sends `If-None-Match`, or a wire-level assertion in the itcase harness).
- **Size:** S.

#### WS4-maven.8 — consistent proxy artifact response headers — **S**
- **Current:** proxy artifact bodies are bare `ok().body()` (`CachedProxySlice.java:1078,:887-889,:1399-1405`) — no `ETag`/`Content-Type`/`Content-Disposition`/`Accept-Ranges`, unlike local (`ArtifactHeaders.from`).
- **Target:** proxy artifact responses carry the same header set local does — `Content-Type`, `Content-Disposition`, `X-Checksum-*`, `ETag` (sha1), `Accept-Ranges: bytes`.
- **Plan:** build proxy artifact responses through `ArtifactHeaders.from(key, checksums)` (using the stored/verified sidecars) in the three proxy serve points; add `Accept-Ranges: bytes`. Keep `ArtifactHeaders` where it is (make it package-visible to the proxy serve path or move to a shared helper — it's already in `maven.http`).
- **Acceptance:** a proxy artifact GET returns identical validator/content headers to the local mode for the same artifact (parametrized test across modes); `curl -I`/client sees `ETag`, `Content-Type`, `Accept-Ranges`.
- **Size:** S.

#### WS4-maven.9 — HEAD `Content-Length` + `Last-Modified` across modes — **S**
- **Current:** local artifact HEAD emits `ArtifactHeaders` but **no `Content-Length`** (`LocalMavenSlice.java:110-125`); proxy HEAD cache-hit emits **only `Content-Length`** though its javadoc promises `Last-Modified` (`HeadProxySlice.java:59-63,85-89`).
- **Target:** every HEAD (local artifact, local metadata, proxy hit, group) returns `Content-Length` and `Last-Modified` from storage metadata, plus the artifact validator headers, matching the corresponding GET.
- **Plan:**
  1. `LocalMavenSlice.artifactResponse` HEAD (`:110-125`) — add `Content-Length` from `storage.metadata(...OP_SIZE)` and `Last-Modified` from the metadata mtime.
  2. `HeadProxySlice.response` (`:84-100`) — add `Last-Modified` from `Meta` (fulfil the javadoc), keep `Content-Length`.
- **Acceptance:** a HEAD and a GET for the same artifact agree on `Content-Length`, `ETag`, `Content-Type`, and expose `Last-Modified`, in local/proxy/group (parametrized).
- **Size:** S.

#### WS4-maven.10 — group metadata `.sha256`/`.sha512` over served bytes — **S**
- **Current:** `MavenGroupSlice` recomputes `.sha1`/`.md5` over the merged/served bytes (`:237,:249-302`) but `.sha256`/`.sha512` fall through to `delegate.response()` (`:242`) → a member's own sidecar, computed over that member's (possibly differently-serialized) metadata ≠ what the group serves → mismatch.
- **Target:** compute `.sha256`/`.sha512` over the exact bytes the group serves for `maven-metadata.xml`, in the same `handleChecksumRequest` path as sha1/md5.
- **Plan:** extend the `handleChecksumRequest` route guard (`:237`) to include `.sha256`/`.sha512`, and its digest switch (`:271-277`) to `SHA-256`/`SHA-512`, sourcing bytes from `mergeMetadata` exactly like sha1/md5.
- **Acceptance:** for a group `maven-metadata.xml`, all four sidecars validate against the served metadata bytes (`mvn` checksum policy `fail` passes); a member whose own metadata serializes differently no longer produces a mismatch.
- **Size:** S.

### (d) Missing — Range support

#### WS4-maven.11 — wire `RangeSlice`/206/`Accept-Ranges` into Maven serving — **M**
- **Current:** `RangeSlice` (`RangeSlice.java`) is instantiated only inside `StorageArtifactSlice.selectArtifactSlice` (`:111-135`), reachable via `StorageArtifactSlice.response()`. Maven serves via the **static** `StorageArtifactSlice.optimizedValue(...)` helper (`LocalMavenSlice.java:97,141`) which bypasses `.response()` → Range never runs. `Accept-Ranges` is never advertised on Maven paths.
- **Target:** Maven artifact GET honors `Range: bytes=…` with **206 Partial Content** + `Content-Range` + `Accept-Ranges: bytes`, and **416** on unsatisfiable ranges — for local and proxy artifact bytes (never metadata).
- **Plan:**
  1. Route Maven artifact GETs through a `RangeSlice`-wrapped serving path (either serve artifacts via `new StorageArtifactSlice(storage).response(...)` instead of the static `optimizedValue` in `LocalMavenSlice.artifactResponse`, or wrap the serve with `RangeSlice` directly). `RangeSlice` needs `Content-Length` present on the 200 to compute ranges — ensure the wrapped response sets it (ties WS4-maven.9/.8).
  2. Restrict to artifact byte paths; leave `maven-metadata.xml` and checksum sidecars non-ranged.
- **Acceptance (itcase):** a ranged GET (`Range: bytes=0-1023`) of a large jar returns 206 with correct `Content-Range`/`Content-Length` and the right byte slice; a resumed/parallel download (download-manager-style two-range fetch) reassembles to the full artifact bit-identically; an out-of-bounds range returns 416; a normal GET still returns 200 + `Accept-Ranges: bytes`.
- **Size:** M.

### (e) Hygiene deletes

#### WS4-maven.12 — delete orphaned `RepoHead` + dead `EXT` branches — **S**
- **Current:** `RepoHead.java:26-51` (maven-adapter) is never instantiated (only `RepoHeadITCase` references it; go-adapter has its own separate `RepoHead`). `MavenSlice.EXT` (`:53-54`) includes `maven-plugin`/`ejb` which are packaging types that never appear as a file suffix, plus `rar`.
- **Target:** remove the maven-adapter `RepoHead` + its ITCase; remove the unreachable `EXT` tokens so the regex stops implying support that never fires.
- **Plan:**
  1. Delete `maven-adapter/.../http/RepoHead.java` and `RepoHeadITCase.java`.
  2. Remove `maven-plugin` and `ejb` from `MavenSlice.EXT` (`:53-54`) — unreachable branches. **`rar`:** the 00-decision lists it for deletion, but `rar`-packaged projects do emit a `.rar` file; **verify with an itcase before removing** — keep `rar` if a `.rar` deploy/resolve regresses. Do not touch `ear` (kept) or the cooldown `PRIMARY_EXTENSIONS` list.
- **Acceptance:** `mvn clean install -T8` green with the files removed; a `.jar`-packaged `maven-plugin`/`ejb` artifact still resolves (its file is `.jar`, unaffected); the `rar` guard itcase decides `rar`'s fate.
- **Size:** S.

## 4. Recommended build order

Cheapest/lowest-risk first; the tentpole and the security wiring last so they land on a clean, well-tested base.

1. **WS4-maven.12** (hygiene) — trivial, shrinks the surface.
2. **WS4-maven.10, .9, .8, .7** (validators/headers) — small, self-contained, high correctness-per-line; .8/.9 also unblock .11 (Range needs `Content-Length`).
3. **WS4-maven.11** (Range) — depends on `Content-Length` from step 2.
4. **WS4-maven.5, .6** (checksum verify, immutability) — hosted-write hardening, independent of the tentpole.
5. **WS4-maven.1 → .2 → .3** (PGP) — strict order: config/install, then verify, then admin/UI.
6. **WS4-maven.4** (metadata regen tentpole) — **last**, behind its concurrency itcase, because it changes the authoritative hosted metadata path.

WS4-maven.4 and .5 both touch `UploadSlice`'s primary-save branch; sequence .5 before .4 (both land in the same method) or coordinate the two agents on that file.

## 5. Out of scope

- SNAPSHOT `<snapshot><timestamp>`/`<snapshotVersions>` server-side maintenance (stays client-driven) — WS4-maven.4 covers only the GA-level `<versions>` listing.
- Group **union-merge** across members (a package in ≥2 members still exposes the sequential-first winner) — a product decision deferred to 2.3.x per the gap analysis.
- Maven-specific REST/search endpoints beyond the generic `ArtifactHandler`/`SearchHandler`.
- Streaming cache-commit / `readAllBytes` removal — that is WS1.3/WS3, not here.
- A `verifyPgp: signed-only` (allow-unsigned) mode — WS4-maven.2 specs reject-when-enabled; the softer mode is a later knob.

## 6. Risks & rollback

- **WS4-maven.4 is the highest-risk item in the spec.** It replaces the authoritative hosted-metadata path. Mitigations: (a) land it behind the concurrent-deploy itcase before merge; (b) the per-GA `storage.exclusively` lock is the same primitive the importer has run in production; (c) `releaseImmutable`/regen are per-repo config — a repo can be pinned to the old behavior during rollout; (d) rollback is `git revert` (no runtime toggle, per CLAUDE.md).
- **PGP (WS4-maven.2) fails closed:** an empty/unreachable keyring with `verifyPgp: true` rejects — document loudly that enabling the flag without uploading keys blocks all signed artifacts. Keep the DB path authoritative; a keyring-store DB blip degrades to `UNTRUSTED_KEY` (reject), never fail-open.
- **WS4-maven.12 `rar`:** don't delete on faith — the itcase guard decides. `maven-plugin`/`ejb` are safe (never file suffixes).
- **Header changes (WS4-maven.7–.9):** conditional-GET/304 regressions are caught by client-driven itcases; keep the 200-path body-consumption contract intact (bodies always consumed, even on 304/416 paths — CLAUDE.md reactive-body rule).

## 7. Docs & observability

- **CHANGELOG** (`CHANGELOG.md`, house sections only): `### 🔒 Security` — `.asc` PGP verification now enforced (WS4-maven.1–.3), hosted checksum verification (WS4-maven.5); `### 🔧 Bug fixes` — hosted `maven-metadata.xml` no longer drops versions under concurrent deploy (WS4-maven.4), `If-None-Match`→304 honored (WS4-maven.7), proxy artifact headers + HEAD parity (WS4-maven.8/.9), group sha256/512 checksum correctness (WS4-maven.10); `### 🌟 New features` — release immutability (WS4-maven.6), Range/resumable downloads (WS4-maven.11). One attributed bullet each; no dev-log narrative.
- **Reference tables (same PR):** `docs/configuration-reference.md` + `docs/admin-guide/environment-variables.md` — `verifyPgp`, `releaseImmutable` per-repo settings and any `PANTERA_` env overrides; `docs/rest-api-reference.md` — the `/api/v1/admin/pgp-keys` GET/POST/DELETE endpoints.
- **Guides:** `docs/admin-guide/` — PGP keyring setup + `verifyPgp` operational note (fail-closed behavior), release-immutability policy, Range behavior; `docs/user-guide/` — Maven/Gradle client note on conditional GET, resumable downloads, and immutable-release 409s.
- **Observability (WS7):** metrics + Grafana panels (a metric without a panel is invisible) for — PGP verify outcomes by result (`verified`/`tampered`/`untrusted`), hosted checksum-mismatch rejections, metadata-regen duration + failure count (extend the existing `recordMetadataOperation`/`recordMetadataGenerationDuration` used at `MetadataRegenerator.java:1035-1042` and `MavenGroupSlice.java:752-758`), conditional-GET 304 ratio, range-request rate/416s. Bounded tags only (repo cap via `RepoNameMeterFilter`); log every new state transition (pgp reject, checksum reject, immutability reject) via `EcsLogger`. New alerts ship with runbooks under `docs/runbooks/`.
