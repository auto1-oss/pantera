# WS8 npm Client Conformance — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `GET /<pkg>/<version>` work on npm proxy and group repositories, and root every emitted tarball URL at the repository base the client actually addressed — so corepack, yarn, npm, and pnpm all work against Pantera.

**Architecture:** Two independent mechanisms. (A) A `X-Pantera-Client-Base` header stamped once in `SliceByPath` — the single request-pipeline point that knows the client-facing path, the resolved repo key, and that repo's config — and consumed by the npm slices that write URLs into response bodies. (B) A `VersionManifestResolver` that generalises the existing `/latest` shortcut into a version-**or**-tag resolver, resolving through the cooldown-filtered packument and rewriting `dist.tarball`.

**Tech Stack:** Java 21, Maven multi-module, Vert.x 4.5 slices, RxJava 2 (adapter internals), Jackson (`ObjectMapper`/`JsonNode`), JUnit 5 + Hamcrest, PMD via `build-tools`.

**Spec:** `docs/specs/2.3.0/WS8-npm-client-conformance.md` — read it first. Every `file:line` citation there has been verified against this tree.

**Worktree:** `/Users/ayd/DevOps/code/auto1/pantera-wt-npm-corepack`, branch `agent/npm-corepack` (off `feat/2.3.0`).

## Global Constraints

Copied verbatim from `/CLAUDE.md`; every task's requirements implicitly include these.

- **No public static methods** except `main(String...)`. Private static helpers are fine. `public static final` *fields* are fine. This is why `ClientBaseUrl` is an instance class, not a static utility.
- **Only one constructor initializes fields**; secondary constructors delegate via `this(...)`.
- **Cyclomatic complexity** ≤15/method, ≤80/class; cognitive ≤17.
- **No unused imports/parameters** — PMD catches both and fails the build.
- GPL-3.0 header from `LICENSE.header` on every Java file. Add with `mvn com.mycila:license-maven-plugin:format` (the bare `license:format` prefix resolves to a different plugin with no `format` goal).
- All logging through `EcsLogger`; `log.source` mandatory; `event.category` must be a valid ECS value (`web`, `network`, `database`, `configuration`, …); `event.action` snake_case.
- **Never assert absolute wall-clock latency** in tests.
- JUnit 5 + Hamcrest **matcher objects, not static factories**: `new IsEqual<>(y)`, never `Matchers.equalTo(y)`. Single assertion → no reason string; multiple assertions → every one gets a reason string.
- No `Files.createFile` in tests — use `@TempDir`.
- Unit tests (`*Test.java`) must not need Docker/network/DB — use `InMemoryStorage`.
- **Build gate:** `mvn clean install -T8` fully green (unit tests + PMD + license) before any PR.
- Conventional Commits with the existing scope vocabulary (`fix(npm):`, `feat(npm):`, `test(npm):`, `docs(changelog):`) — match, don't invent.
- **Never add `Co-Authored-By` trailers to commits.**

The license header to prepend to every new Java file:

```java
/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
```

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `pantera-core/.../http/headers/ClientBaseUrl.java` *(new)* | Header constant; derive origin + repo base from request headers. Pure, no I/O. | 1 |
| `pantera-core/.../http/headers/ClientBaseUrlTest.java` *(new)* | Unit coverage for the derivation rules. | 1 |
| `pantera-main/.../settings/repo/RepoConfig.java` | Add `urlOpt()` — a non-throwing `url:` accessor. | 2 |
| `pantera-main/.../http/SliceByPath.java` | Stamp the header once, at the addressed repository. | 2 |
| `pantera-main/.../http/SliceByPathClientBaseTest.java` *(new)* | Stamp-if-absent, `url:` override, both route styles. | 2 |
| `npm-adapter/.../proxy/http/DownloadPackageSlice.java` | Consume the header for tarball prefixes; delegate version refs to the resolver. | 3, 5 |
| `npm-adapter/.../http/SingleVersionSlice.java` | Same header precedence on the hosted path. | 3 |
| `npm-adapter/.../proxy/http/VersionManifestResolver.java` *(new)* | Parse `<pkg>/<ref>`, fetch + cooldown-filter the packument, emit one manifest with a rewritten tarball + ETag. | 4 |
| `npm-adapter/.../proxy/http/VersionManifestResolverTest.java` *(new)* | Parsing, resolution precedence, 404s, ETag/304. | 4 |
| `npm-adapter/.../proxy/http/NpmProxySlice.java` | Allow `HEAD` on the packument/version route. | 5 |
| `pantera-main/docker-compose/pantera/repo/npm_proxy.yaml` | Fix the stale `url:`. | 6 |
| `CHANGELOG.md`, `docs/user-guide/`, `docs/configuration-reference.md` | Same-PR documentation. | 6 |

`VersionManifestResolver` is a **new class rather than more methods on `DownloadPackageSlice`** because that file is already 1164 lines and PMD caps class cyclomatic complexity at 80.

---

## Task 1: `ClientBaseUrl` helper

**Files:**
- Create: `pantera-core/src/main/java/com/auto1/pantera/http/headers/ClientBaseUrl.java`
- Test: `pantera-core/src/test/java/com/auto1/pantera/http/headers/ClientBaseUrlTest.java`

**Interfaces:**
- Consumes: `com.auto1.pantera.http.Headers` (`values(String) → List<String>`).
- Produces — later tasks depend on exactly these:
  - `public static final String ClientBaseUrl.HEADER` = `"X-Pantera-Client-Base"`
  - `public static final String ClientBaseUrl.ORIGINAL_PATH` = `"X-Original-Path"`
  - `new ClientBaseUrl(Headers)` — sole constructor
  - `String origin()` — e.g. `"https://host"`
  - `Optional<String> stamped()` — the already-stamped header value, if any
  - `Optional<String> derive(String originalClientPath, String remainder)` — absolute base, or empty when `remainder` is not a suffix of `originalClientPath`

- [ ] **Step 1: Write the failing test**

Create `ClientBaseUrlTest.java` (with the license header):

```java
package com.auto1.pantera.http.headers;

import com.auto1.pantera.http.Headers;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.util.Optional;

final class ClientBaseUrlTest {

    @Test
    void derivesBaseFromHostAndPath() {
        MatcherAssert.assertThat(
            new ClientBaseUrl(Headers.from("Host", "reg.example.com"))
                .derive("/test_prefix/api/npm/npm_group/pnpm", "/pnpm"),
            new IsEqual<>(Optional.of("http://reg.example.com/test_prefix/api/npm/npm_group"))
        );
    }

    @Test
    void honoursForwardedSchemeHostAndPrefix() {
        final Headers headers = new Headers()
            .add("Host", "internal:8080")
            .add("X-Forwarded-Proto", "https")
            .add("X-Forwarded-Host", "reg.example.com")
            .add("X-Forwarded-Prefix", "/artifactory");
        MatcherAssert.assertThat(
            new ClientBaseUrl(headers).derive("/api/npm/npm_group/pnpm", "/pnpm"),
            new IsEqual<>(Optional.of("https://reg.example.com/artifactory/api/npm/npm_group"))
        );
    }

    @Test
    void takesFirstValueOfCommaListedForwardedProto() {
        final Headers headers = new Headers()
            .add("Host", "reg.example.com")
            .add("X-Forwarded-Proto", "https, http");
        MatcherAssert.assertThat(
            new ClientBaseUrl(headers).origin(),
            new IsEqual<>("https://reg.example.com")
        );
    }

    @Test
    void returnsEmptyWhenRemainderIsNotASuffix() {
        MatcherAssert.assertThat(
            new ClientBaseUrl(Headers.from("Host", "h"))
                .derive("/test_prefix/npm_group/pnpm", "/something-else"),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void wholePathIsTheBaseWhenRemainderIsRootOrEmpty() {
        final ClientBaseUrl base = new ClientBaseUrl(Headers.from("Host", "h"));
        MatcherAssert.assertThat(
            "root remainder keeps the whole path",
            base.derive("/test_prefix/npm_group", "/"),
            new IsEqual<>(Optional.of("http://h/test_prefix/npm_group"))
        );
        MatcherAssert.assertThat(
            "empty remainder keeps the whole path",
            base.derive("/test_prefix/npm_group", ""),
            new IsEqual<>(Optional.of("http://h/test_prefix/npm_group"))
        );
    }

    @Test
    void readsAlreadyStampedHeader() {
        MatcherAssert.assertThat(
            new ClientBaseUrl(Headers.from(ClientBaseUrl.HEADER, "https://h/npm_group")).stamped(),
            new IsEqual<>(Optional.of("https://h/npm_group"))
        );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/ayd/DevOps/code/auto1/pantera-wt-npm-corepack && mvn test -pl pantera-core -Dtest=ClientBaseUrlTest`
Expected: FAIL — compilation error, `ClientBaseUrl` does not exist.

- [ ] **Step 3: Write the implementation**

Create `ClientBaseUrl.java` (license header first):

```java
package com.auto1.pantera.http.headers;

import com.auto1.pantera.http.Headers;

import java.util.List;
import java.util.Optional;

/**
 * Client-facing base URL of the repository a request actually addressed.
 *
 * <p>Stamped once by {@code SliceByPath} as {@link #HEADER} and read by every
 * slice that writes an absolute URL into a response body (npm tarball links).
 * Stamping happens above the group resolver, so a group member sees the
 * <em>group's</em> base rather than its own — which is the whole point: a
 * client that configured the group as its registry must receive URLs under
 * the group, or strict clients (corepack) reject them.</p>
 */
public final class ClientBaseUrl {

    /**
     * Internal header carrying the addressed repository's client-facing base URL.
     */
    public static final String HEADER = "X-Pantera-Client-Base";

    /**
     * Header stamped by {@code ApiRoutingSlice} with the pre-rewrite client
     * path; preferred over the live request path because it still carries the
     * {@code /api/<type>} segment the client configured as its registry.
     */
    public static final String ORIGINAL_PATH = "X-Original-Path";

    /**
     * Request headers.
     */
    private final Headers headers;

    /**
     * Ctor.
     * @param headers Request headers
     */
    public ClientBaseUrl(final Headers headers) {
        this.headers = headers;
    }

    /**
     * Scheme and authority the client used, honouring reverse-proxy
     * forwarding headers.
     * @return e.g. {@code https://reg.example.com}
     */
    public String origin() {
        return String.format(
            "%s://%s",
            this.first("X-Forwarded-Proto").orElse("http"),
            this.first("X-Forwarded-Host").or(() -> this.first("Host")).orElse("localhost")
        );
    }

    /**
     * The already-stamped base, if an outer slice set one.
     * @return Stamped base URL
     */
    public Optional<String> stamped() {
        return this.first(ClientBaseUrl.HEADER);
    }

    /**
     * Build the absolute repository base by removing the slice-relative
     * remainder from the client-facing path.
     *
     * <p>Returns empty when {@code remainder} is not a suffix of
     * {@code originalClientPath} — deliberately preferring "no value" over a
     * wrong URL, so consumers fall through to their existing fallback chain.</p>
     *
     * @param originalClientPath Path as the client sent it
     * @param remainder Path relative to the repository, e.g. {@code /pnpm}
     * @return Absolute base URL, or empty if it cannot be derived safely
     */
    public Optional<String> derive(final String originalClientPath, final String remainder) {
        final Optional<String> result;
        if (originalClientPath == null || originalClientPath.isEmpty()) {
            result = Optional.empty();
        } else if (remainder == null || remainder.isEmpty() || "/".equals(remainder)) {
            result = Optional.of(this.absolute(originalClientPath));
        } else if (originalClientPath.endsWith(remainder)) {
            result = Optional.of(
                this.absolute(
                    originalClientPath.substring(
                        0, originalClientPath.length() - remainder.length()
                    )
                )
            );
        } else {
            result = Optional.empty();
        }
        return result;
    }

    /**
     * Prepend origin and any forwarded prefix to a repository path.
     * @param repoPath Repository base path
     * @return Absolute URL without a trailing slash
     */
    private String absolute(final String repoPath) {
        return this.origin() + this.forwardedPrefix() + ClientBaseUrl.withoutTrailingSlash(repoPath);
    }

    /**
     * Reverse-proxy path prefix stripped before forwarding, if declared.
     * @return Prefix without a trailing slash, or empty string
     */
    private String forwardedPrefix() {
        return this.first("X-Forwarded-Prefix")
            .map(ClientBaseUrl::withoutTrailingSlash)
            .orElse("");
    }

    /**
     * First non-blank value of a header, taking the leftmost entry of a
     * comma-separated forwarded chain.
     * @param name Header name
     * @return Header value
     */
    private Optional<String> first(final String name) {
        final List<String> values = this.headers.values(name);
        Optional<String> result = Optional.empty();
        if (!values.isEmpty()) {
            final String raw = values.get(0);
            if (raw != null && !raw.isBlank()) {
                final int comma = raw.indexOf(',');
                final String single;
                if (comma >= 0) {
                    single = raw.substring(0, comma);
                } else {
                    single = raw;
                }
                result = Optional.of(single.trim());
            }
        }
        return result;
    }

    /**
     * Drop a single trailing slash so concatenation never doubles it.
     * @param value Path or prefix
     * @return Value without a trailing slash
     */
    private static String withoutTrailingSlash(final String value) {
        final String result;
        if (value.length() > 1 && value.endsWith("/")) {
            result = value.substring(0, value.length() - 1);
        } else if ("/".equals(value)) {
            result = "";
        } else {
            result = value;
        }
        return result;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl pantera-core -Dtest=ClientBaseUrlTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Verify PMD and license are clean for the module**

Run: `mvn com.mycila:license-maven-plugin:format && mvn install -pl pantera-core -DskipTests=true`
Expected: BUILD SUCCESS, no PMD violations.

- [ ] **Step 6: Commit**

```bash
git add pantera-core/src/main/java/com/auto1/pantera/http/headers/ClientBaseUrl.java \
        pantera-core/src/test/java/com/auto1/pantera/http/headers/ClientBaseUrlTest.java
git commit -m "feat(http): add ClientBaseUrl for deriving the addressed repo's client-facing base"
```

---

## Task 2: Stamp the header in `SliceByPath`

**Files:**
- Modify: `pantera-main/src/main/java/com/auto1/pantera/settings/repo/RepoConfig.java` (add `urlOpt()`)
- Modify: `pantera-main/src/main/java/com/auto1/pantera/http/SliceByPath.java:51-81`
- Test: `pantera-main/src/test/java/com/auto1/pantera/http/SliceByPathClientBaseTest.java`

**Interfaces:**
- Consumes: `ClientBaseUrl.HEADER`, `ClientBaseUrl.ORIGINAL_PATH`, `new ClientBaseUrl(Headers).stamped()`, `.derive(String, String)` from Task 1.
- Produces: every downstream slice receives `X-Pantera-Client-Base` when derivable. `RepoConfig.urlOpt() → Optional<String>`.

**Context:** `SliceByPath.response` already computes `originalPath` (`:52`), `strippedPath` (`:53`), and resolves `key` (`:72`). `RepoConfig.url()` (`:164`) throws `IllegalArgumentException` when `url:` is absent — a group has no `url:`, so a non-throwing accessor is required.

- [ ] **Step 1: Add the non-throwing config accessor**

In `RepoConfig.java`, next to `url()`:

```java
    /**
     * Repository URL, if explicitly configured.
     *
     * <p>Unlike {@link #url()} this never throws: group repositories have no
     * {@code url:} key, and callers deriving a client-facing base must be able
     * to ask without handling an exception.</p>
     *
     * @return Configured URL, or empty
     */
    public Optional<String> urlOpt() {
        return this.stringOpt("url");
    }
```

- [ ] **Step 2: Write the failing test**

Create `SliceByPathClientBaseTest.java`. `SliceByPath` is package-private, so the test must live in `com.auto1.pantera.http`. It asserts on the headers the downstream slice observes, via a recording fake:

```java
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.headers.ClientBaseUrl;
import com.auto1.pantera.http.rq.RequestLine;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.util.Optional;

final class SliceByPathClientBaseTest {

    @Test
    void stampsApiRouteBaseFromOriginalPath() {
        MatcherAssert.assertThat(
            this.observedBase(
                "/test_prefix/npm_group/pnpm",
                Optional.of("/test_prefix/api/npm/npm_group/pnpm")
            ),
            new IsEqual<>(Optional.of("http://reg.example.com/test_prefix/api/npm/npm_group"))
        );
    }

    @Test
    void stampsPlainRouteBaseWhenNoOriginalPath() {
        MatcherAssert.assertThat(
            this.observedBase("/test_prefix/npm_group/pnpm", Optional.empty()),
            new IsEqual<>(Optional.of("http://reg.example.com/test_prefix/npm_group"))
        );
    }

    @Test
    void doesNotOverwriteAnAlreadyStampedBase() {
        // Group-wins: the group's base is stamped before any member slice runs.
        MatcherAssert.assertThat(
            this.observedBaseWithPreStamp("https://h/api/npm/npm_group"),
            new IsEqual<>(Optional.of("https://h/api/npm/npm_group"))
        );
    }
}
```

**Wiring note for the implementer:** `SliceByPath`'s constructor takes `(RepositorySlices, PrefixesConfig)`. Construct `RepositorySlices` with a `Repositories` stub exposing one `npm_group` repo (no `url:`) and one `npm_proxy` repo (`url:` set), and a recording `Slice` that captures the `Headers` it receives. Follow the construction pattern already used in `pantera-main/src/test/java/com/auto1/pantera/http/` — read a neighbouring test in that package first and mirror it. Implement `observedBase`/`observedBaseWithPreStamp` as private helpers that build the request, invoke the slice, and return `new ClientBaseUrl(recorded).stamped()`.

Add a fourth test asserting the `url:` override: address `npm_proxy` directly and expect its configured `url:` verbatim, not the derived value.

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -pl pantera-main -Dtest=SliceByPathClientBaseTest`
Expected: FAIL — no header is stamped, `stamped()` returns empty.

- [ ] **Step 4: Implement the stamp**

In `SliceByPath.response`, replace the final return (`:79-80`) with:

```java
        return this.slices.slice(key.get(), effectiveLine.uri().getPort())
            .response(
                effectiveLine,
                this.stamped(headers, key.get(), originalPath, strippedPath),
                body
            );
```

and add these private methods:

```java
    /**
     * Stamp the addressed repository's client-facing base URL, unless an
     * outer slice already did. Stamping here — above the group resolver —
     * is what makes a group member emit URLs under the <em>group</em>.
     *
     * @param headers Inbound headers
     * @param key Resolved repository key
     * @param originalPath Request path before prefix stripping
     * @param strippedPath Request path after prefix stripping
     * @return Headers, with the base stamped when derivable
     */
    private Headers stamped(
        final Headers headers, final Key key,
        final String originalPath, final String strippedPath
    ) {
        final ClientBaseUrl base = new ClientBaseUrl(headers);
        final Headers result;
        if (base.stamped().isPresent()) {
            result = headers;
        } else {
            final Optional<String> value = this.configured(key)
                .or(() -> base.derive(
                    SliceByPath.clientPath(headers, originalPath),
                    SliceByPath.remainder(key, strippedPath)
                ));
            if (value.isPresent()) {
                result = headers.copy().add(new Header(ClientBaseUrl.HEADER, value.get()));
            } else {
                result = headers;
            }
        }
        return result;
    }

    /**
     * Explicitly configured {@code url:} of the addressed repository. Only the
     * addressed repository is consulted — never a group member's own URL,
     * which is the bug this whole mechanism exists to fix.
     *
     * @param key Repository key
     * @return Configured URL, or empty
     */
    private Optional<String> configured(final Key key) {
        Optional<String> result = Optional.empty();
        final Repositories repos = this.slices.repositories();
        if (repos != null) {
            result = repos.config(key.string()).flatMap(RepoConfig::urlOpt);
        }
        return result;
    }

    /**
     * Path as the client sent it: the pre-rewrite path when
     * {@code ApiRoutingSlice} recorded one, else the current path.
     *
     * @param headers Inbound headers
     * @param originalPath Current request path
     * @return Client-facing path
     */
    private static String clientPath(final Headers headers, final String originalPath) {
        final List<String> recorded = headers.values(ClientBaseUrl.ORIGINAL_PATH);
        final String result;
        if (recorded.isEmpty() || recorded.get(0) == null || recorded.get(0).isBlank()) {
            result = originalPath;
        } else {
            result = recorded.get(0);
        }
        return result;
    }

    /**
     * Path relative to the repository, e.g. {@code /pnpm} for
     * {@code /npm_group/pnpm}.
     *
     * @param key Repository key
     * @param strippedPath Path after global-prefix stripping
     * @return Repository-relative remainder
     */
    private static String remainder(final Key key, final String strippedPath) {
        final String prefixed = "/" + key.string();
        final String result;
        if (strippedPath.length() > prefixed.length() && strippedPath.startsWith(prefixed)) {
            result = strippedPath.substring(prefixed.length());
        } else {
            result = "";
        }
        return result;
    }
```

Add imports: `com.auto1.pantera.http.headers.ClientBaseUrl`, `com.auto1.pantera.http.headers.Header`, `com.auto1.pantera.settings.repo.RepoConfig`, `com.auto1.pantera.settings.repo.Repositories`, `java.util.List`.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -pl pantera-main -Dtest=SliceByPathClientBaseTest`
Expected: PASS, 4 tests.

- [ ] **Step 6: Verify no regression across the module**

Run: `mvn install -pl pantera-main -DskipTests=false -Dtest='SliceByPath*,ApiRouting*'`
Expected: PASS — existing routing tests unaffected (stamping is purely additive).

- [ ] **Step 7: Commit**

```bash
git add pantera-main/src/main/java/com/auto1/pantera/http/SliceByPath.java \
        pantera-main/src/main/java/com/auto1/pantera/settings/repo/RepoConfig.java \
        pantera-main/src/test/java/com/auto1/pantera/http/SliceByPathClientBaseTest.java
git commit -m "feat(http): stamp the addressed repo's client-facing base in SliceByPath"
```

---

## Task 3: Consume the header in the npm adapter

**Files:**
- Modify: `npm-adapter/src/main/java/com/auto1/pantera/npm/proxy/http/DownloadPackageSlice.java:988-999` (`getTarballPrefix`), `:1117-1132` (`clientFormat`), `:1150-1161` (`assetPrefix`)
- Modify: `npm-adapter/src/main/java/com/auto1/pantera/npm/http/SingleVersionSlice.java`
- Test: `npm-adapter/src/test/java/com/auto1/pantera/npm/proxy/http/DownloadPackageSliceClientBaseTest.java`

**Interfaces:**
- Consumes: `ClientBaseUrl.HEADER`, `new ClientBaseUrl(headers).stamped()`, `.origin()` from Task 1.
- Produces: tarball prefix precedence **stamped header → `cfg.url()` → forwarded-aware `Host` fallback**, used by Task 4's resolver.

**Context:** `getTarballPrefix` currently returns `cfg.url()` unconditionally when present, and `assetPrefix` hardcodes `http://`.

- [ ] **Step 1: Write the failing test**

```java
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.ClientBaseUrl;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

final class DownloadPackageSliceClientBaseTest {

    @Test
    void stampedHeaderBeatsConfiguredUrl() {
        // A group stamps its own base; the member's cfg.url() must not win.
        MatcherAssert.assertThat(
            this.prefixFor(
                Headers.from(ClientBaseUrl.HEADER, "https://h/api/npm/npm_group"),
                "http://localhost:8081/npm_proxy"
            ),
            new IsEqual<>("https://h/api/npm/npm_group")
        );
    }

    @Test
    void configuredUrlUsedWhenNoHeaderStamped() {
        MatcherAssert.assertThat(
            this.prefixFor(Headers.from("Host", "h"), "http://localhost:8081/npm_proxy"),
            new IsEqual<>("http://localhost:8081/npm_proxy")
        );
    }

    @Test
    void hostFallbackHonoursForwardedProto() {
        final Headers headers = new Headers()
            .add("Host", "reg.example.com")
            .add("X-Forwarded-Proto", "https");
        MatcherAssert.assertThat(
            this.prefixFor(headers, null),
            new IsEqual<>("https://reg.example.com")
        );
    }
}
```

**Wiring note:** `getTarballPrefix` is private. Rather than widening it, have `prefixFor` construct a `DownloadPackageSlice` (`new DownloadPackageSlice(npm, new PackagePath(""), Optional.ofNullable(url).map(...))`) and drive a real packument request through it with a stubbed `NpmProxy`, asserting on the `dist.tarball` in the response body. If an existing test in this package already stubs `NpmProxy`, mirror it — read the package's tests before writing this one.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl npm-adapter -Dtest=DownloadPackageSliceClientBaseTest`
Expected: FAIL — the configured URL wins over the stamped header.

- [ ] **Step 3: Implement the new precedence**

Replace `getTarballPrefix`:

```java
    /**
     * Client-facing prefix for tarball URLs, in precedence order: the base
     * stamped by {@code SliceByPath} for the repository the client actually
     * addressed (so a group member emits group URLs), then this repository's
     * configured {@code url:}, then the request's own origin.
     *
     * @param headers Request headers
     * @return Absolute URL prefix
     */
    private String getTarballPrefix(final Headers headers) {
        final String result;
        final Optional<String> stamped = new ClientBaseUrl(headers).stamped();
        if (stamped.isPresent()) {
            result = stamped.get();
        } else if (this.baseUrl.isPresent()) {
            result = this.baseUrl.get().toString();
        } else {
            result = this.assetPrefix(headers);
        }
        return result;
    }
```

Replace `clientFormat` to use the same helper (it currently duplicates the precedence logic and throws when `Host` is missing):

```java
    /**
     * Transform internal package format for external clients.
     * @param data Internal package data
     * @param headers Request headers
     * @return External client package
     */
    private String clientFormat(final String data, final Headers headers) {
        return new ClientContent(data, this.getTarballPrefix(headers)).value().toString();
    }
```

Replace `assetPrefix` so the scheme is no longer hardcoded:

```java
    /**
     * Generates asset base reference from the request's own origin,
     * honouring reverse-proxy forwarding headers.
     * @param headers Request headers
     * @return Asset base reference
     */
    private String assetPrefix(final Headers headers) {
        final String origin = new ClientBaseUrl(headers).origin();
        final String result;
        if (StringUtils.isEmpty(this.path.prefix())) {
            result = origin;
        } else {
            result = String.format("%s/%s", origin, this.path.prefix());
        }
        return result;
    }
```

Update `clientFormat`'s call sites to pass `Headers` instead of `Iterable<Header>`. Remove the now-unused `StreamSupport` / `Header` imports if nothing else uses them — **PMD fails the build on unused imports**.

- [ ] **Step 4: Apply the same precedence to the hosted path**

In `SingleVersionSlice`, the tarball rewrite currently uses `this.base` unconditionally. Thread the headers through to `serve(...)` and prefer the stamped base:

```java
        final String prefix = new ClientBaseUrl(headers).stamped()
            .orElseGet(() -> this.base.toString());
```

then use `prefix` in the existing `Tarballs.rewriteTarball(...)` call.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -pl npm-adapter -Dtest='DownloadPackageSliceClientBaseTest,SingleVersionSlice*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add npm-adapter/src/main/java/com/auto1/pantera/npm/proxy/http/DownloadPackageSlice.java \
        npm-adapter/src/main/java/com/auto1/pantera/npm/http/SingleVersionSlice.java \
        npm-adapter/src/test/java/com/auto1/pantera/npm/proxy/http/DownloadPackageSliceClientBaseTest.java
git commit -m "fix(npm): root tarball URLs at the repository the client addressed"
```

---

## Task 4: `VersionManifestResolver`

**Files:**
- Create: `npm-adapter/src/main/java/com/auto1/pantera/npm/proxy/http/VersionManifestResolver.java`
- Test: `npm-adapter/src/test/java/com/auto1/pantera/npm/proxy/http/VersionManifestResolverTest.java`

**Interfaces:**
- Consumes: `NpmProxy` (`getPackageMetadataOnly`, `getPackageContentStream`), `CooldownMetadataService.filterMetadata(...)`, `NpmMetadataParser`/`Filter`/`Rewriter`, `Tarballs.rewriteTarball(String, String)`, `MetadataETag`, `AuditContext`, `AuditLogger.resolution(...)`.
- Produces — Task 5 calls exactly these:
  - `new VersionManifestResolver(NpmProxy, CooldownMetadataService, String repoType, String repoName)`
  - `static Optional<PackageRef> parse(String rawPath)` — **package-private static**, because it is a pure function of its argument and uses no instance state. PMD bans only *public* static methods, so this is legal, and it keeps the tests from constructing a resolver with null collaborators just to reach it. Task 5 calls it as `VersionManifestResolver.parse(...)`.
  - `CompletableFuture<Response> resolve(String pkg, String ref, String tarballPrefix, Optional<String> clientETag, AuditContext ctx, String owner)`
  - nested `PackageRef` with `String pkg()` and `String ref()`

**Reference implementation:** `DownloadPackageSlice.serveLatestManifest` (`:649-725`) and `buildLatestManifestResponse` (`:770-820`) already do the fetch + filter + extract shape. Read both before writing — the Rx interop (`Concatenation.withSize(...).single().map(...).toMaybe()`, `.toSingle(new byte[0]).to(SingleInterop.get()).toCompletableFuture()`) must be copied exactly; it is easy to get subtly wrong.

- [ ] **Step 1: Write the failing parsing tests**

```java
package com.auto1.pantera.npm.proxy.http;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

final class VersionManifestResolverTest {

    @Test
    void parsesUnscopedPackageAndVersion() {
        final VersionManifestResolver.PackageRef ref =
            VersionManifestResolver.parse("pnpm/11.5.1").orElseThrow();
        MatcherAssert.assertThat("package", ref.pkg(), new IsEqual<>("pnpm"));
        MatcherAssert.assertThat("reference", ref.ref(), new IsEqual<>("11.5.1"));
    }

    @Test
    void parsesScopedPackageAndVersion() {
        final VersionManifestResolver.PackageRef ref =
            VersionManifestResolver.parse("@types/node/22.0.0").orElseThrow();
        MatcherAssert.assertThat("package", ref.pkg(), new IsEqual<>("@types/node"));
        MatcherAssert.assertThat("reference", ref.ref(), new IsEqual<>("22.0.0"));
    }

    @Test
    void treatsScopedPackageWithoutVersionAsAPackument() {
        // THE ambiguity: /@types/node is a package name, not (pkg=@types, ref=node).
        MatcherAssert.assertThat(
            VersionManifestResolver.parse("@types/node").isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void treatsBarePackageAsAPackument() {
        MatcherAssert.assertThat(
            VersionManifestResolver.parse("pnpm").isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void rejectsDashAndEmptyReferences() {
        MatcherAssert.assertThat(
            "dash", VersionManifestResolver.parse("pnpm/-").isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "empty", VersionManifestResolver.parse("pnpm/").isPresent(), new IsEqual<>(false)
        );
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -pl npm-adapter -Dtest=VersionManifestResolverTest`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement parsing and the nested `PackageRef`**

Create the class with the license header, the four constructor fields, and:

```java
    /**
     * Split {@code <pkg>/<ref>} into package and version-or-tag reference.
     *
     * <p>npm package names cannot contain {@code /} unless scoped, so the
     * split is exact: two segments with a leading {@code @} are a scoped
     * <em>package name</em> ({@code @types/node}), while two segments without
     * one are package + reference ({@code pnpm/11.5.1}).</p>
     *
     * @param rawPath Package path, with or without a leading slash
     * @return Parsed pair, or empty when the path is a plain packument request
     */
    static Optional<PackageRef> parse(final String rawPath) {
        Optional<PackageRef> result = Optional.empty();
        if (rawPath != null && !rawPath.isEmpty()) {
            final String trimmed;
            if (rawPath.startsWith("/")) {
                trimmed = rawPath.substring(1);
            } else {
                trimmed = rawPath;
            }
            final String[] segments = trimmed.split("/");
            String pkg = null;
            String ref = null;
            if (segments.length == 2 && !segments[0].startsWith("@")) {
                pkg = segments[0];
                ref = segments[1];
            } else if (segments.length == 3 && segments[0].startsWith("@")) {
                pkg = segments[0] + "/" + segments[1];
                ref = segments[2];
            }
            if (pkg != null && !ref.isEmpty() && !"-".equals(ref)) {
                result = Optional.of(
                    new PackageRef(
                        URLDecoder.decode(pkg, StandardCharsets.UTF_8),
                        URLDecoder.decode(ref, StandardCharsets.UTF_8)
                    )
                );
            }
        }
        return result;
    }
```

`PackageRef` is a nested `static final class` with two final fields, one constructor, and `pkg()`/`ref()` accessors — copy the shape from `SingleVersionSlice.PackageRef` verbatim.

- [ ] **Step 4: Run parsing tests to verify they pass**

Run: `mvn test -pl npm-adapter -Dtest=VersionManifestResolverTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit the parser**

```bash
git add npm-adapter/src/main/java/com/auto1/pantera/npm/proxy/http/VersionManifestResolver.java \
        npm-adapter/src/test/java/com/auto1/pantera/npm/proxy/http/VersionManifestResolverTest.java
git commit -m "feat(npm): add scope-aware <pkg>/<ref> parsing for proxy single-version"
```

- [ ] **Step 6: Write the failing emit tests**

Add to `VersionManifestResolverTest` — these drive `emit` through a resolver with cooldown disabled (`cooldownMetadata = null`), using a fixed packument fixture:

```java
    private static final byte[] PACKUMENT = ("""
        {"name":"pnpm","dist-tags":{"latest":"11.5.1"},"versions":{
          "11.5.1":{"name":"pnpm","version":"11.5.1",
            "dist":{"tarball":"https://registry.npmjs.org/pnpm/-/pnpm-11.5.1.tgz",
                    "shasum":"abc"}},
          "11.4.0":{"name":"pnpm","version":"11.4.0",
            "dist":{"tarball":"https://registry.npmjs.org/pnpm/-/pnpm-11.4.0.tgz",
                    "shasum":"def"}}}}
        """).getBytes(java.nio.charset.StandardCharsets.UTF_8);
```

Assert, each as its own `@Test`:
1. `emit(PACKUMENT, "pnpm", "11.5.1", "https://h/api/npm/npm_group", empty)` → `200`, body's `dist.tarball` equals `https://h/api/npm/npm_group/pnpm/-/pnpm-11.5.1.tgz`.
2. Same call with `ref = "latest"` → resolves via `dist-tags` to the `11.5.1` manifest. **This is the `/latest` upstream-URL leak fix (spec §2.D)** — assert the tarball is rooted at Pantera, not `registry.npmjs.org`.
3. `ref = "9.9.9"` → `404`, and the body is **not** a `{name, modified}` stub. This is the regression guard for the reported corepack bug.
4. Passing the ETag from assertion 1 back as `clientETag` → `304` with a matching `ETag` header and no body.
5. Two calls with **different** `tarballPrefix` values produce **different** ETags (the base is part of the served bytes).

- [ ] **Step 7: Run to verify they fail**

Run: `mvn test -pl npm-adapter -Dtest=VersionManifestResolverTest`
Expected: FAIL — `emit`/`resolve` not implemented.

- [ ] **Step 8: Implement `resolve` and `emit`**

```java
    /**
     * Resolve one version-or-tag reference against the (cooldown-filtered)
     * packument and emit its manifest.
     *
     * <p>Resolution goes through the packument rather than proxying
     * {@code /<pkg>/<version>} upstream verbatim, because a passthrough would
     * bypass cooldown filtering entirely — a blocked version must 404 here.</p>
     *
     * @param pkg Package name
     * @param ref Version string or dist-tag name
     * @param tarballPrefix Client-facing base for the tarball URL
     * @param clientETag Client's If-None-Match value, if any
     * @param auditCtx Audit context captured before the async hop
     * @param owner Request owner
     * @return Response
     */
    CompletableFuture<Response> resolve(
        final String pkg, final String ref, final String tarballPrefix,
        final Optional<String> clientETag, final AuditContext auditCtx, final String owner
    ) {
        return this.packumentBytes(pkg).thenCompose(raw -> {
            final CompletableFuture<Response> result;
            if (raw.length == 0) {
                result = CompletableFuture.completedFuture(VersionManifestResolver.notFound(pkg, ref));
            } else if (this.cooldownMetadata == null || this.repoType == null) {
                AuditLogger.resolution(auditCtx, this.repoType, this.repoName, pkg, owner, List.of());
                result = CompletableFuture.completedFuture(
                    this.emit(raw, pkg, ref, tarballPrefix, clientETag)
                );
            } else {
                result = this.cooldownMetadata.filterMetadata(
                    this.repoType, this.repoName, pkg, raw,
                    new NpmMetadataParser(), new NpmMetadataFilter(), new NpmMetadataRewriter(),
                    auditCtx, owner
                ).handle((filtered, ex) -> {
                    final Response response;
                    if (ex == null) {
                        response = this.emit(filtered, pkg, ref, tarballPrefix, clientETag);
                    } else if (VersionManifestResolver.allBlocked(ex)) {
                        response = VersionManifestResolver.notFound(pkg, ref);
                    } else {
                        response = this.emit(raw, pkg, ref, tarballPrefix, clientETag);
                    }
                    return response;
                });
            }
            return result;
        });
    }
```

`packumentBytes(pkg)` is the Rx chain copied verbatim from `serveLatestManifest` (`:660-673`), returning `CompletableFuture<byte[]>` that yields an empty array when the package is absent.

`allBlocked(Throwable)` walks the cause chain for `AllVersionsBlockedException` (mirroring `:697-715`) and logs the existing `all_versions_blocked` `EcsLogger` record on a hit.

`emit(...)` parses with Jackson, looks up `versions[ref]`, falls back to `versions[distTags[ref]]`, deep-copies the manifest, rewrites `dist.tarball` via `Tarballs.rewriteTarball`, computes `new MetadataETag(body).calculate()`, returns `304` on a matching `If-None-Match`, else `200` with `Content-Type: application/json; charset=utf-8`, `ETag`, and `Cache-Control: public, max-age=300`. On `IOException` it logs `event.action=version_resolution`, `event.outcome=failure` and returns the 404.

`notFound(pkg, ref)` returns `ResponseBuilder.notFound().jsonBody(String.format("{\"error\":\"version not found: %s\",\"package\":\"%s\"}", ref, pkg)).build()` — matching `SingleVersionSlice`'s body shape exactly.

**Complexity watch:** if `emit` exceeds cyclomatic 15, extract the tag-fallback lookup into a private `Optional<JsonNode> manifestFor(JsonNode root, String ref)`.

- [ ] **Step 9: Run to verify they pass**

Run: `mvn test -pl npm-adapter -Dtest=VersionManifestResolverTest`
Expected: PASS, 10 tests.

- [ ] **Step 10: Commit**

```bash
git add npm-adapter/src/main/java/com/auto1/pantera/npm/proxy/http/VersionManifestResolver.java \
        npm-adapter/src/test/java/com/auto1/pantera/npm/proxy/http/VersionManifestResolverTest.java
git commit -m "feat(npm): resolve version-or-tag manifests from the filtered packument"
```

---

## Task 5: Wire the resolver into the proxy route

**Files:**
- Modify: `npm-adapter/src/main/java/com/auto1/pantera/npm/proxy/http/DownloadPackageSlice.java:140` (`LATEST_SUFFIX`), `:174-179` (dispatch), `:649-820` (delete `serveLatestManifest`, `resolveLatestFromRaw`, `buildLatestManifestResponse`)
- Modify: `npm-adapter/src/main/java/com/auto1/pantera/npm/proxy/http/NpmProxySlice.java:107-115`
- Test: `npm-adapter/src/test/java/com/auto1/pantera/npm/proxy/http/DownloadPackageSliceSingleVersionTest.java`

**Interfaces:**
- Consumes: everything Task 4 produced, plus `getTarballPrefix(Headers)` from Task 3.
- Produces: `GET`/`HEAD /<pkg>/<version>` answered by proxy and group repositories.

**Context:** `PackagePath.pattern()` (`^/(((?!/-/).)+)$`) already matches `/pnpm/11.5.1`, so **no new route is needed for `GET`** — the dispatch happens inside `DownloadPackageSlice`. Only `HEAD` needs a routing change.

- [ ] **Step 1: Write the failing slice-level test**

`DownloadPackageSliceSingleVersionTest` drives a `DownloadPackageSlice` with a stubbed `NpmProxy` returning the Task 4 packument fixture:

1. `GET /pnpm/11.5.1` → `200`, body has `version` = `11.5.1` and a `dist.tarball` rooted at the stamped base. **Explicitly assert the body is not `{"name":"pnpm","modified":...}`** — the exact reported stub.
2. `GET /pnpm` → still the full packument (`versions` present) — proves the packument path is untouched.
3. `GET /@types/node` → still the full packument — proves the scoped-package ambiguity is handled at slice level, not just in the parser.
4. `GET /pnpm/9.9.9` → `404`.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -pl npm-adapter -Dtest=DownloadPackageSliceSingleVersionTest`
Expected: FAIL — assertion 1 returns the `{name, modified}` stub.

- [ ] **Step 3: Replace the `/latest` special case with the general dispatch**

Delete the `LATEST_SUFFIX` constant (`:136-140`) and replace the dispatch block (`:174-179`) with:

```java
            // Single-version / dist-tag reference: GET /<pkg>/<version> and
            // /<pkg>/<tag> (including /latest). Resolved from the filtered
            // packument so cooldown still applies, with the tarball URL
            // rewritten to the base the client addressed.
            final Optional<VersionManifestResolver.PackageRef> versionRef =
                VersionManifestResolver.parse(rawPackageName);
            if (versionRef.isPresent()) {
                return this.resolver.resolve(
                    versionRef.get().pkg(), versionRef.get().ref(),
                    this.getTarballPrefix(headers), clientETag, auditCtx, owner
                );
            }
```

Initialise `this.resolver` in the primary constructor:

```java
        this.resolver = new VersionManifestResolver(npm, cooldownMetadata, repoType, repoName);
```

Delete `serveLatestManifest`, `resolveLatestFromRaw`, and `buildLatestManifestResponse` — the resolver supersedes all three, and `buildLatestManifestResponse` is the source of the upstream-tarball leak (spec §2.D). Remove any imports left unused by the deletion; **PMD fails on unused imports.**

- [ ] **Step 4: Allow `HEAD` on the packument/version route**

In `NpmProxySlice`, change the packument `RtRulePath` rule (`:107-115`) from `MethodRule.GET` to:

```java
                    new RtRule.Any(MethodRule.GET, MethodRule.HEAD),
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -pl npm-adapter -Dtest='DownloadPackageSliceSingleVersionTest,DownloadPackageSlice*,NpmProxySlice*'`
Expected: PASS. Any pre-existing `/latest` test must still pass — if one asserts an upstream `registry.npmjs.org` tarball URL, that assertion encoded the §2.D defect: update it to expect the Pantera-rooted URL and note why in the commit body.

- [ ] **Step 6: Full reactor build**

Run: `mvn clean install -T8 -Dexec.skip=true`
Expected: BUILD SUCCESS — all modules, PMD, and license clean.

- [ ] **Step 7: Commit**

```bash
git add npm-adapter/src/main/java/com/auto1/pantera/npm/proxy/http/DownloadPackageSlice.java \
        npm-adapter/src/main/java/com/auto1/pantera/npm/proxy/http/NpmProxySlice.java \
        npm-adapter/src/test/java/com/auto1/pantera/npm/proxy/http/DownloadPackageSliceSingleVersionTest.java
git commit -m "fix(npm): serve single-version metadata on proxy and group repositories"
```

---

## Task 6: Sample configs, docs, CHANGELOG

**Files:**
- Modify: `pantera-main/docker-compose/pantera/repo/npm_proxy.yaml`
- Modify: `CHANGELOG.md`, `docs/user-guide/` npm page, `docs/configuration-reference.md`

- [ ] **Step 1: Fix the stale sample `url:`**

`npm_proxy.yaml` declares `url: http://localhost:8081/npm_proxy` while the documented dev route is `http://localhost:8081/test_prefix/api/npm_proxy/…`. Because a configured `url:` now **overrides** the derived base, a stale value actively overrides a correct one. Delete the `url:` line so the dev stack derives correctly. Check the sibling `npm.yaml` for the same drift.

- [ ] **Step 2: Update `configuration-reference.md`**

Document: `url:` is optional; when set it is the client-facing base for the repository the client addresses; when unset the base is derived from the request, honouring `X-Forwarded-Proto`/`-Host`/`-Prefix`. State plainly that a group's members never contribute their own `url:` to emitted URLs.

- [ ] **Step 3: Update the npm user-guide page**

Add corepack setup (`COREPACK_NPM_REGISTRY`), and note that single-version metadata is served by local, proxy, and group repositories alike.

- [ ] **Step 4: Add CHANGELOG entries**

Under `## Version 2.3.0` → `### 🔧 Bug fixes`, house style (one concise attributed bullet per user-visible change):

```markdown
- **corepack works against npm proxy and group repositories** — `GET /<pkg>/<version>` returns a real version manifest instead of a `{name, modified}` stub that `200`s. ([@aydasraf](https://github.com/aydasraf))
- **npm tarball URLs are rooted at the repository the client addressed** — a group no longer hands out its winning member's URLs, and the derived fallback honours `X-Forwarded-Proto`/`-Host`/`-Prefix` instead of assuming `http://` + `Host`. ([@aydasraf](https://github.com/aydasraf))
- **`GET /<pkg>/latest` on a proxy no longer returns upstream tarball URLs** — the manifest is rewritten to point back at Pantera, so the download is cached and audited rather than going straight to the upstream registry. ([@aydasraf](https://github.com/aydasraf))
```

- [ ] **Step 5: Commit**

```bash
git add CHANGELOG.md docs/ pantera-main/docker-compose/pantera/repo/
git commit -m "docs(npm): document client-base derivation and corepack support"
```

---

## Task 7: Client conformance sweep (WS8-npm.6)

**This task is open-ended by design** — it is what makes "fully supports corepack, yarn and npm" an evidenced claim rather than an asserted one. Findings become new tasks.

- [ ] **Step 1: Build and start the local stack**

```bash
mvn clean install -T8 -DskipTests=true -Dexec.skip=true
docker build -t pantera:2.3.0 --build-arg JAR_FILE=pantera-main-2.3.0.jar \
  -f pantera-main/Dockerfile pantera-main
cd pantera-main/docker-compose && docker compose down && docker compose up -d
```

- [ ] **Step 2: Reproduce the original bug against the backend directly**

Bypass nginx (it caches artifact responses). Credentials come from the committed sample projects under `pantera-main/docker-compose/pantera/artifacts/*/test.sh` — **never** from `.env`.

```bash
curl -sS -u "$USER:$PASS" \
  http://localhost:8088/test_prefix/api/npm/npm_group/pnpm/11.5.1 | jq '{name, version, tarball: .dist.tarball}'
```

Expected: real `version` and a `dist.tarball` starting with `http://localhost:8088/test_prefix/api/npm/npm_group`. Before this change it returned `{"name":"pnpm","modified":"…"}`.

- [ ] **Step 3: Run each client against local, proxy, and group**

corepack, npm, pnpm, yarn classic, yarn berry — install a real dependency through each. For every client, record which endpoints it hit (`docker compose logs pantera | grep url.path`) and whether it succeeded.

- [ ] **Step 4: Fill in the conformance matrix**

Replace every `?` in the spec's §6 matrix with a result. For each failure, open a numbered sub-item in the spec describing the endpoint, the client's expectation, and the observed behaviour.

- [ ] **Step 5: Commit the evidence**

```bash
git add docs/specs/2.3.0/WS8-npm-client-conformance.md
git commit -m "docs(specs): record WS8 client conformance results"
```

- [ ] **Step 6: Report before implementing new findings**

Do **not** silently expand scope. Report the filled matrix and the proposed sub-items, and get a decision on which to fix in this branch versus defer.

---

## Task 8: 2.2.5 backport

Only start once Tasks 1–7 are green and merged into `feat/2.3.0`.

- [ ] **Step 1: Create the branch**

```bash
cd /Users/ayd/DevOps/code/auto1/pantera
git worktree add -b release/2.2.5 /Users/ayd/DevOps/code/auto1/pantera-wt-2.2.5 master
```

- [ ] **Step 2: Cherry-pick in order**

```bash
cd /Users/ayd/DevOps/code/auto1/pantera-wt-2.2.5
git cherry-pick 5ef2c832f 971612178 7ea3c283b adc1fb990 ecc43500e 757435f89 7cb5a64b2 542f94aec
```

**Excluded deliberately:** `8e9a0a41d` (presigned npm download — a 2.3.0 feature) and `ceb6a37ae` (cluster de-clustering, not npm-specific).

**Expected conflict:** `adc1fb990` adds a `downloadPolicy` parameter to `NpmSlice` that comes from the excluded presign commit. Resolve by dropping that parameter and its constructor overload.

- [ ] **Step 3: Cherry-pick this branch's commits**

Replay the Task 1–6 commits from `agent/npm-corepack` in order.

- [ ] **Step 4: Bump the version**

```bash
./bump-version.sh 2.2.5
```

Never edit versions by hand.

- [ ] **Step 5: Write the 2.2.5 CHANGELOG section**

The backport carries package signing, provenance attestations, and `npm token`/`profile` — **new capabilities**, so 2.2.5 needs a `### 🌟 New features` section alongside `### 🔧 Bug fixes`. Historical entries above are immutable. The existing uncommitted 2.2.5 section on `fix/proxy-502-cache-toctou` (the cooldown-ETag bullet) should be folded in.

- [ ] **Step 6: Full gate**

```bash
mvn clean install -T8
```

Expected: BUILD SUCCESS. Then re-run the Task 7 smoke check against a 2.2.5 image.

- [ ] **Step 7: Commit and open the PR**

```bash
git commit -am "release: bump version to 2.2.5"
gh pr create --base master --title "release(2.2.5): npm client conformance + npm fixes backport"
```

---

## Self-Review

**Spec coverage:** WS8-npm.1 → Task 1. WS8-npm.2 → Task 2. WS8-npm.3 → Task 3. WS8-npm.4 → Tasks 4–5. WS8-npm.5 → Task 6. WS8-npm.6 → Task 7. §10 release shape → Task 8. Acceptance criteria 1–4 → Task 4 Step 6 + Task 5 Step 1; 5 → Task 4 Step 3 + Task 5 Step 1 case 3; 6 → Task 4 Step 6 cases 4–5; 7 → Task 1 + Task 3; 8 → Task 2 + Task 3; 9 → Task 4 Step 6 case 2; 10 → Task 7; 11 → Task 5 Step 6.

**Type consistency:** `ClientBaseUrl.HEADER`/`ORIGINAL_PATH`/`origin()`/`stamped()`/`derive(String, String)` are defined in Task 1 and used with identical signatures in Tasks 2 and 3. `VersionManifestResolver.parse`/`resolve`/`PackageRef.pkg()`/`.ref()` are defined in Task 4 and called with matching signatures in Task 5. `RepoConfig.urlOpt()` is added and used in Task 2.

**Known gaps, deliberate:** Task 2 Step 2 and Task 3 Step 1 describe test wiring in prose rather than complete code, because both depend on construction patterns in neighbouring test classes that must be read first — inventing a fake `Repositories`/`NpmProxy` here would likely diverge from the existing helpers. Both steps name the file to read before writing. Task 7 is intentionally open-ended and ends with a reporting gate rather than an implementation step.
