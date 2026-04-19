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
package com.auto1.pantera.http.context;

import com.auto1.pantera.http.context.RequestContext.ArtifactRef;
import java.time.Duration;
import org.apache.logging.log4j.ThreadContext;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exhaustive contract test for {@link RequestContext}: record accessors,
 * {@link RequestContext#minimal} defaults, {@link RequestContext#withRepo}
 * copy-on-write behaviour, {@link RequestContext#bindToMdc()} /
 * {@link RequestContext#fromMdc()} round-trip through Log4j2
 * {@link ThreadContext}, and backward compatibility of the 4-arg constructor.
 */
final class RequestContextTest {

    @BeforeEach
    void clear() {
        ThreadContext.clearMap();
    }

    @AfterEach
    void cleanup() {
        ThreadContext.clearMap();
    }

    // ================== defaults & copy-with ==================

    @Test
    @DisplayName("minimal(...) sets safe defaults: anonymous user, empty artifact, 30s deadline")
    void minimalSetsSafeDefaults() {
        final RequestContext ctx = RequestContext.minimal(
            "trace-1", "req-1", "npm_group", "/npm/@scope/pkg"
        );
        MatcherAssert.assertThat(ctx.traceId(), Matchers.is("trace-1"));
        MatcherAssert.assertThat(ctx.httpRequestId(), Matchers.is("req-1"));
        MatcherAssert.assertThat(ctx.repoName(), Matchers.is("npm_group"));
        MatcherAssert.assertThat(ctx.urlOriginal(), Matchers.is("/npm/@scope/pkg"));
        MatcherAssert.assertThat(ctx.userName(), Matchers.is("anonymous"));
        MatcherAssert.assertThat(ctx.clientIp(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.userAgent(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.repoType(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.urlPath(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.transactionId(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.spanId(), Matchers.nullValue());
        MatcherAssert.assertThat("artifact is EMPTY", ctx.artifact().isEmpty(), Matchers.is(true));
        MatcherAssert.assertThat(ctx.deadline(), Matchers.notNullValue());
        final Duration rem = ctx.deadline().remaining();
        MatcherAssert.assertThat(
            "deadline within the 30s default", rem.toSeconds() <= 30, Matchers.is(true)
        );
    }

    @Test
    @DisplayName("withRepo(...) produces a copy with only the repo/artifact fields updated")
    void withRepoProducesCopyWithNewRepoFields() {
        final RequestContext base = RequestContext.minimal(
            "t", "r", "old_repo", "/u"
        );
        final ArtifactRef art = new ArtifactRef("@scope/pkg", "1.2.3");
        final RequestContext after = base.withRepo("new_repo", "npm", art);

        MatcherAssert.assertThat(after.repoName(), Matchers.is("new_repo"));
        MatcherAssert.assertThat(after.repoType(), Matchers.is("npm"));
        MatcherAssert.assertThat(after.artifact(), Matchers.is(art));
        // Preserved:
        MatcherAssert.assertThat(after.traceId(), Matchers.is(base.traceId()));
        MatcherAssert.assertThat(after.httpRequestId(), Matchers.is(base.httpRequestId()));
        MatcherAssert.assertThat(after.urlOriginal(), Matchers.is(base.urlOriginal()));
        MatcherAssert.assertThat(after.userName(), Matchers.is(base.userName()));
        MatcherAssert.assertThat(after.deadline(), Matchers.is(base.deadline()));
        // base is untouched:
        MatcherAssert.assertThat(base.repoName(), Matchers.is("old_repo"));
        MatcherAssert.assertThat(base.repoType(), Matchers.nullValue());
    }

    @Test
    @DisplayName("withRepo(..., null artifact) coerces to ArtifactRef.EMPTY")
    void withRepoNullArtifactCoercesToEmpty() {
        final RequestContext after = RequestContext
            .minimal("t", "r", "x", "/u")
            .withRepo("y", "maven", null);
        MatcherAssert.assertThat(after.artifact(), Matchers.is(ArtifactRef.EMPTY));
    }

    // ================== bindToMdc ==================

    @Test
    @DisplayName("bindToMdc() puts every non-null ECS field into ThreadContext")
    void bindToMdcPutsAllEcsFields() throws Exception {
        final RequestContext ctx = new RequestContext(
            "t1", "tx1", "sp1", "r1",
            "alice", "10.0.0.1", "curl/8",
            "npm_group", "npm", new ArtifactRef("@scope/pkg", "1.0.0"),
            "/npm/@scope/pkg", "/npm/@scope/pkg",
            Deadline.in(Duration.ofSeconds(30))
        );
        try (AutoCloseable ignored = ctx.bindToMdc()) {
            MatcherAssert.assertThat(ThreadContext.get("trace.id"), Matchers.is("t1"));
            MatcherAssert.assertThat(ThreadContext.get("transaction.id"), Matchers.is("tx1"));
            MatcherAssert.assertThat(ThreadContext.get("span.id"), Matchers.is("sp1"));
            MatcherAssert.assertThat(ThreadContext.get("http.request.id"), Matchers.is("r1"));
            MatcherAssert.assertThat(ThreadContext.get("user.name"), Matchers.is("alice"));
            MatcherAssert.assertThat(ThreadContext.get("client.ip"), Matchers.is("10.0.0.1"));
            MatcherAssert.assertThat(ThreadContext.get("user_agent.original"), Matchers.is("curl/8"));
            MatcherAssert.assertThat(ThreadContext.get("repository.name"), Matchers.is("npm_group"));
            MatcherAssert.assertThat(ThreadContext.get("repository.type"), Matchers.is("npm"));
            MatcherAssert.assertThat(ThreadContext.get("package.name"), Matchers.is("@scope/pkg"));
            MatcherAssert.assertThat(ThreadContext.get("package.version"), Matchers.is("1.0.0"));
            MatcherAssert.assertThat(ThreadContext.get("url.original"), Matchers.is("/npm/@scope/pkg"));
            MatcherAssert.assertThat(ThreadContext.get("url.path"), Matchers.is("/npm/@scope/pkg"));
        }
    }

    @Test
    @DisplayName("bindToMdc() skips null fields (no empty-string ghost keys)")
    void bindToMdcSkipsNullFields() throws Exception {
        // Only traceId + repoName + urlOriginal populated — everything else null.
        final RequestContext ctx = RequestContext.minimal(
            "trace-only", null, "repo", "/u"
        );
        // Sanity: minimal() sets userName to "anonymous", not null.
        MatcherAssert.assertThat(ctx.userName(), Matchers.is("anonymous"));
        try (AutoCloseable ignored = ctx.bindToMdc()) {
            MatcherAssert.assertThat(ThreadContext.get("trace.id"), Matchers.is("trace-only"));
            MatcherAssert.assertThat(ThreadContext.get("user.name"), Matchers.is("anonymous"));
            MatcherAssert.assertThat(ThreadContext.get("repository.name"), Matchers.is("repo"));
            MatcherAssert.assertThat(ThreadContext.get("url.original"), Matchers.is("/u"));
            // Null-valued fields must not appear as keys at all:
            MatcherAssert.assertThat(
                "no transaction.id when null",
                ThreadContext.containsKey("transaction.id"), Matchers.is(false)
            );
            MatcherAssert.assertThat(
                "no client.ip when null",
                ThreadContext.containsKey("client.ip"), Matchers.is(false)
            );
            MatcherAssert.assertThat(
                "no user_agent.original when null",
                ThreadContext.containsKey("user_agent.original"), Matchers.is(false)
            );
            MatcherAssert.assertThat(
                "no package.name for EMPTY artifact",
                ThreadContext.containsKey("package.name"), Matchers.is(false)
            );
            MatcherAssert.assertThat(
                "no package.version for EMPTY artifact",
                ThreadContext.containsKey("package.version"), Matchers.is(false)
            );
        }
    }

    @Test
    @DisplayName("bindToMdc().close() restores the ThreadContext snapshot taken at bind time")
    void bindToMdcCloseRestoresPriorContext() throws Exception {
        ThreadContext.put("pre.existing", "keep");
        ThreadContext.put("trace.id", "prior-trace");
        final RequestContext ctx = new RequestContext(
            "new-trace", null, null, null,
            "anonymous", null, null,
            "repo", null, ArtifactRef.EMPTY,
            null, null, Deadline.in(Duration.ofSeconds(30))
        );
        final AutoCloseable bound = ctx.bindToMdc();
        MatcherAssert.assertThat(
            "binding overrode trace.id",
            ThreadContext.get("trace.id"), Matchers.is("new-trace")
        );
        bound.close();
        MatcherAssert.assertThat(
            "prior trace.id restored",
            ThreadContext.get("trace.id"), Matchers.is("prior-trace")
        );
        MatcherAssert.assertThat(
            "pre.existing preserved through bind+close",
            ThreadContext.get("pre.existing"), Matchers.is("keep")
        );
    }

    @Test
    @DisplayName("bindToMdc() is safe inside try-with-resources")
    void bindToMdcIsTryWithResourcesSafe() throws Exception {
        final RequestContext ctx = RequestContext.minimal("t", "r", "repo", "/u");
        try (AutoCloseable bound = ctx.bindToMdc()) {
            MatcherAssert.assertThat(ThreadContext.get("trace.id"), Matchers.is("t"));
            MatcherAssert.assertThat(bound, Matchers.notNullValue());
        }
        MatcherAssert.assertThat(
            "ThreadContext cleaned up after try-with-resources",
            ThreadContext.get("trace.id"), Matchers.nullValue()
        );
    }

    @Test
    @DisplayName("bindToMdc() close is idempotent — double close does not corrupt state")
    void bindToMdcIsIdempotentOnDoubleClose() throws Exception {
        ThreadContext.put("pre", "preserved");
        final RequestContext ctx = RequestContext.minimal("t", "r", "repo", "/u");
        final AutoCloseable bound = ctx.bindToMdc();
        bound.close();
        MatcherAssert.assertThat(ThreadContext.get("pre"), Matchers.is("preserved"));
        // Now put something else into ThreadContext — a second close must NOT
        // clobber it, because the snapshot was already restored on first close.
        ThreadContext.put("post-close", "still-here");
        bound.close();
        MatcherAssert.assertThat(
            "second close is a no-op, preserves state set after first close",
            ThreadContext.get("post-close"), Matchers.is("still-here")
        );
        MatcherAssert.assertThat(ThreadContext.get("pre"), Matchers.is("preserved"));
    }

    // ================== fromMdc ==================

    @Test
    @DisplayName("fromMdc() reads every ECS field from ThreadContext")
    void fromMdcReadsAllEcsFields() {
        ThreadContext.put("trace.id", "t1");
        ThreadContext.put("transaction.id", "tx1");
        ThreadContext.put("span.id", "sp1");
        ThreadContext.put("http.request.id", "r1");
        ThreadContext.put("user.name", "alice");
        ThreadContext.put("client.ip", "10.0.0.1");
        ThreadContext.put("user_agent.original", "curl/8");
        ThreadContext.put("repository.name", "npm_group");
        ThreadContext.put("repository.type", "npm");
        ThreadContext.put("package.name", "@scope/pkg");
        ThreadContext.put("package.version", "1.0.0");
        ThreadContext.put("url.original", "/npm/@scope/pkg");
        ThreadContext.put("url.path", "/npm/@scope/pkg");

        final RequestContext ctx = RequestContext.fromMdc();
        MatcherAssert.assertThat(ctx.traceId(), Matchers.is("t1"));
        MatcherAssert.assertThat(ctx.transactionId(), Matchers.is("tx1"));
        MatcherAssert.assertThat(ctx.spanId(), Matchers.is("sp1"));
        MatcherAssert.assertThat(ctx.httpRequestId(), Matchers.is("r1"));
        MatcherAssert.assertThat(ctx.userName(), Matchers.is("alice"));
        MatcherAssert.assertThat(ctx.clientIp(), Matchers.is("10.0.0.1"));
        MatcherAssert.assertThat(ctx.userAgent(), Matchers.is("curl/8"));
        MatcherAssert.assertThat(ctx.repoName(), Matchers.is("npm_group"));
        MatcherAssert.assertThat(ctx.repoType(), Matchers.is("npm"));
        MatcherAssert.assertThat(ctx.artifact().name(), Matchers.is("@scope/pkg"));
        MatcherAssert.assertThat(ctx.artifact().version(), Matchers.is("1.0.0"));
        MatcherAssert.assertThat(ctx.urlOriginal(), Matchers.is("/npm/@scope/pkg"));
        MatcherAssert.assertThat(ctx.urlPath(), Matchers.is("/npm/@scope/pkg"));
        MatcherAssert.assertThat("deadline synthesised", ctx.deadline(), Matchers.notNullValue());
    }

    @Test
    @DisplayName("fromMdc() returns null for missing keys and EMPTY for absent artifact")
    void fromMdcMissingKeysBecomeNull() {
        ThreadContext.put("trace.id", "only-trace");
        final RequestContext ctx = RequestContext.fromMdc();
        MatcherAssert.assertThat(ctx.traceId(), Matchers.is("only-trace"));
        MatcherAssert.assertThat(ctx.transactionId(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.spanId(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.httpRequestId(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.userName(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.clientIp(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.userAgent(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.repoName(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.repoType(), Matchers.nullValue());
        MatcherAssert.assertThat("artifact EMPTY", ctx.artifact(), Matchers.is(ArtifactRef.EMPTY));
        MatcherAssert.assertThat(ctx.urlOriginal(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.urlPath(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.deadline(), Matchers.notNullValue());
    }

    @Test
    @DisplayName("bindToMdc → fromMdc round-trips every ECS field (except the non-persisted Deadline)")
    void bindToMdcFromMdcRoundTripPreservesFieldsExceptDeadline() throws Exception {
        final RequestContext original = new RequestContext(
            "t", "tx", "sp", "r",
            "alice", "10.0.0.1", "curl/8",
            "npm_group", "npm", new ArtifactRef("@scope/pkg", "1.0.0"),
            "/npm/@scope/pkg", "/npm/@scope/pkg",
            Deadline.in(Duration.ofSeconds(5))
        );
        final RequestContext restored;
        try (AutoCloseable ignored = original.bindToMdc()) {
            restored = RequestContext.fromMdc();
        }
        MatcherAssert.assertThat(restored.traceId(), Matchers.is(original.traceId()));
        MatcherAssert.assertThat(restored.transactionId(), Matchers.is(original.transactionId()));
        MatcherAssert.assertThat(restored.spanId(), Matchers.is(original.spanId()));
        MatcherAssert.assertThat(restored.httpRequestId(), Matchers.is(original.httpRequestId()));
        MatcherAssert.assertThat(restored.userName(), Matchers.is(original.userName()));
        MatcherAssert.assertThat(restored.clientIp(), Matchers.is(original.clientIp()));
        MatcherAssert.assertThat(restored.userAgent(), Matchers.is(original.userAgent()));
        MatcherAssert.assertThat(restored.repoName(), Matchers.is(original.repoName()));
        MatcherAssert.assertThat(restored.repoType(), Matchers.is(original.repoType()));
        MatcherAssert.assertThat(restored.artifact(), Matchers.is(original.artifact()));
        MatcherAssert.assertThat(restored.urlOriginal(), Matchers.is(original.urlOriginal()));
        MatcherAssert.assertThat(restored.urlPath(), Matchers.is(original.urlPath()));
        // Deadline is synthesised — not equal to original.
        MatcherAssert.assertThat(restored.deadline(), Matchers.notNullValue());
    }

    // ================== ArtifactRef ==================

    @Test
    @DisplayName("ArtifactRef.EMPTY.isEmpty() is true; a populated one is not")
    void artifactRefEmptyIsEmpty() {
        MatcherAssert.assertThat(ArtifactRef.EMPTY.isEmpty(), Matchers.is(true));
        MatcherAssert.assertThat(ArtifactRef.EMPTY.name(), Matchers.is(""));
        MatcherAssert.assertThat(ArtifactRef.EMPTY.version(), Matchers.is(""));
        final ArtifactRef populated = new ArtifactRef("pkg", "1.0.0");
        MatcherAssert.assertThat(populated.isEmpty(), Matchers.is(false));
    }

    // ================== backward compat ==================

    @Test
    @DisplayName("Backward-compat 4-arg constructor delegates to minimal defaults")
    void backwardCompat4ArgConstructorDelegatesToMinimal() {
        final RequestContext ctx = new RequestContext(
            "t", "r", "repo", "/u"
        );
        MatcherAssert.assertThat(ctx.traceId(), Matchers.is("t"));
        MatcherAssert.assertThat(ctx.httpRequestId(), Matchers.is("r"));
        MatcherAssert.assertThat(ctx.repoName(), Matchers.is("repo"));
        MatcherAssert.assertThat(ctx.urlOriginal(), Matchers.is("/u"));
        // Safe defaults identical to minimal():
        MatcherAssert.assertThat(ctx.userName(), Matchers.is("anonymous"));
        MatcherAssert.assertThat(ctx.clientIp(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.userAgent(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.repoType(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.urlPath(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.transactionId(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.spanId(), Matchers.nullValue());
        MatcherAssert.assertThat(ctx.artifact(), Matchers.is(ArtifactRef.EMPTY));
        MatcherAssert.assertThat(ctx.deadline(), Matchers.notNullValue());
    }

    // ================== record semantics ==================

    @Test
    @DisplayName("Record equality follows canonical-component semantics")
    void recordEqualityFollowsRecordSemantics() {
        final Deadline shared = Deadline.in(Duration.ofSeconds(30));
        final ArtifactRef art = new ArtifactRef("p", "1");
        final RequestContext a = new RequestContext(
            "t", "tx", "sp", "r", "u", "ip", "ua",
            "repo", "npm", art, "/u", "/u", shared
        );
        final RequestContext b = new RequestContext(
            "t", "tx", "sp", "r", "u", "ip", "ua",
            "repo", "npm", art, "/u", "/u", shared
        );
        MatcherAssert.assertThat(a, Matchers.is(b));
        MatcherAssert.assertThat(a.hashCode(), Matchers.is(b.hashCode()));
    }
}
