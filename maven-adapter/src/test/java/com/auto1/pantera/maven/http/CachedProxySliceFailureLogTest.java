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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import com.auto1.pantera.maven.cooldown.MavenCooldownResponseFactory;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.ThreadContext;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.IsNull;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every 5xx the Maven proxy synthesises for a primary artifact leaves exactly
 * one application record naming its cause, correlated with the request's
 * {@code trace.id}, while the client body names nothing internal.
 *
 * @since 2.2.11
 */
final class CachedProxySliceFailureLogTest {

    /**
     * Logger of the Maven proxy's application records.
     */
    private static final String LOGGER = "com.auto1.pantera.maven.http";

    /**
     * Trace id of the request (internal context header).
     */
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    /**
     * Request path.
     */
    private static final String PATH = "/com/example/foo/1.0/foo-1.0.jar";

    /**
     * Log capture.
     */
    private LogCapture logs;

    @BeforeEach
    void init() {
        CooldownResponseRegistry.instance()
            .register("maven-proxy", new MavenCooldownResponseFactory());
        this.logs = LogCapture.of(LOGGER);
    }

    @AfterEach
    void tearDown() {
        this.logs.close();
        ThreadContext.clearMap();
    }

    @Test
    void connectionFailureLogsCauseOnceAndKeepsItOutOfTheBody() {
        // The exception text names an internal host + address: it must reach
        // the log and never the client.
        final Slice refused = (line, headers, body) -> CompletableFuture.failedFuture(
            new ConnectException("Connection refused: mirror.internal.corp/10.20.30.40:443")
        );
        final Response rsp = this.invoke(refused);
        MatcherAssert.assertThat(
            "connection failure answers 502",
            rsp.status(), new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        final String body = new String(rsp.body().asBytes(), StandardCharsets.UTF_8);
        MatcherAssert.assertThat(
            "the body is the generic text",
            body, new IsEqual<>("Upstream temporarily unavailable")
        );
        MatcherAssert.assertThat(
            "the body names no internal host",
            body, new IsNot<>(new StringContains("mirror.internal.corp"))
        );
        final List<Map<String, Object>> records = this.logs.action("proxy_upstream");
        MatcherAssert.assertThat(
            "exactly one cause record", records.size(), new IsEqual<>(1)
        );
        final Map<String, Object> rec = records.get(0);
        MatcherAssert.assertThat(
            "event.reason classifies the failure",
            rec.get("event.reason"), new IsEqual<>("connection_refused")
        );
        MatcherAssert.assertThat(
            "answered status", rec.get("http.response.status_code"), new IsEqual<>(502)
        );
        MatcherAssert.assertThat(
            "error.type is the real cause, not CompletionException",
            rec.get("error.type"), new IsEqual<>("java.net.ConnectException")
        );
        MatcherAssert.assertThat(
            "error.message keeps the cause text for operators",
            String.valueOf(rec.get("error.message")),
            new StringContains("mirror.internal.corp")
        );
        MatcherAssert.assertThat(
            "stack trace attached",
            rec.get("error.stack_trace"), new IsNot<>(new IsNull<>())
        );
        MatcherAssert.assertThat(
            "trace.id of this request",
            rec.get("trace.id"), new IsEqual<>(TRACE)
        );
        MatcherAssert.assertThat(
            "upstream URL", rec.get("url.full"),
            new IsEqual<>("https://repo.maven.apache.org/maven2" + PATH)
        );
        MatcherAssert.assertThat(
            "upstream host", rec.get("destination.address"),
            new IsEqual<>("repo.maven.apache.org")
        );
        MatcherAssert.assertThat(
            "request path", rec.get("url.path"), new IsEqual<>(PATH)
        );
        MatcherAssert.assertThat(
            "outcome", rec.get("event.outcome"), new IsEqual<>("failure")
        );
    }

    @Test
    void upstream5xxLogsStatusWithoutStackTrace() {
        final Response rsp = this.invoke(constant(RsStatus.SERVICE_UNAVAILABLE));
        MatcherAssert.assertThat(
            "503 without Retry-After answers 502",
            rsp.status(), new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "the body is the generic text",
            new String(rsp.body().asBytes(), StandardCharsets.UTF_8),
            new IsEqual<>("Upstream temporarily unavailable")
        );
        final List<Map<String, Object>> records = this.logs.action("proxy_upstream");
        MatcherAssert.assertThat(
            "exactly one cause record", records.size(), new IsEqual<>(1)
        );
        final Map<String, Object> rec = records.get(0);
        MatcherAssert.assertThat(
            "event.reason carries the upstream status",
            rec.get("event.reason"), new IsEqual<>("upstream_status_503")
        );
        MatcherAssert.assertThat(
            "answered status", rec.get("http.response.status_code"), new IsEqual<>(502)
        );
        MatcherAssert.assertThat(
            "a plain upstream answer has no stack trace",
            rec.get("error.stack_trace"), new IsNull<>()
        );
        MatcherAssert.assertThat(
            "trace.id", rec.get("trace.id"), new IsEqual<>(TRACE)
        );
        MatcherAssert.assertThat(
            "upstream host", rec.get("destination.address"),
            new IsEqual<>("repo.maven.apache.org")
        );
    }

    @Test
    void circuitOpenLogsReasonAndKeepsMarker() {
        final Slice fastfail = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.from(RsStatus.BAD_GATEWAY)
                .header(UpstreamCircuitOpenException.HEADER, "true")
                .header("Retry-After", "7")
                .build()
        );
        final Response rsp = this.invoke(fastfail);
        MatcherAssert.assertThat(
            "marker survives",
            rsp.headers().values(UpstreamCircuitOpenException.HEADER),
            new IsEqual<>(List.of("true"))
        );
        final List<Map<String, Object>> records = this.logs.action("proxy_upstream");
        MatcherAssert.assertThat(
            "exactly one cause record", records.size(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "event.reason", records.get(0).get("event.reason"),
            new IsEqual<>("circuit_open")
        );
    }

    @Test
    void upstream404LogsNothing() {
        this.invoke(constant(RsStatus.NOT_FOUND));
        MatcherAssert.assertThat(
            this.logs.action("proxy_upstream").size(), new IsEqual<>(0)
        );
    }

    private static Slice constant(final RsStatus status) {
        return (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.from(status).build());
    }

    private Response invoke(final Slice upstream) {
        final CachedProxySlice slice = new CachedProxySlice(
            upstream,
            (cacheKey, supplier, control) -> supplier.get(),
            Optional.of(new LinkedList<ProxyArtifactEvent>()), "maven-proxy",
            "https://repo.maven.apache.org/maven2", "maven-proxy",
            NoopCooldownService.INSTANCE, noopInspector(),
            Optional.of(new InMemoryStorage())
        );
        return slice.response(
            new RequestLine(RqMethod.GET, PATH),
            new Headers().add(EcsLoggingSlice.CTX_TRACE_ID_HEADER, TRACE),
            Content.EMPTY
        ).join();
    }

    private static CooldownInspector noopInspector() {
        return new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(
                final String artifact, final String version
            ) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(
                final String artifact, final String version
            ) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
    }
}
