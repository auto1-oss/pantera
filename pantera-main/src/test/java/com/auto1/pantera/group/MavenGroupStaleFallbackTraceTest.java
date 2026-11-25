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
package com.auto1.pantera.group;

import com.auto1.pantera.LogCapture;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.ThreadContext;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * When no member has the metadata the group asks Valkey for a stale copy,
 * and the answer arrives on a Lettuce event loop. The {@code metadata_fetch}
 * record written there must carry the request's {@code trace.id} (70% of
 * those production records had none), and the Lettuce thread must not keep
 * a context the request bound on it.
 *
 * @since 2.2.14
 */
@Timeout(30)
final class MavenGroupStaleFallbackTraceTest {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";

    private static final String LOGGER = "com.auto1.pantera.group";

    /**
     * Stands for the Lettuce event loop.
     */
    private ExecutorService lettuce;

    private LogCapture capture;

    @BeforeEach
    void setUp() {
        ThreadContext.clearAll();
        this.lettuce = Executors.newSingleThreadExecutor();
        this.capture = LogCapture.of(LOGGER);
    }

    @AfterEach
    void tearDown() {
        this.capture.close();
        this.lettuce.shutdownNow();
        ThreadContext.clearAll();
    }

    @Test
    void staleMissIsLoggedUnderTheRequestAndLeavesTheValkeyThreadClean()
        throws Exception {
        final CompletableFuture<byte[]> valkey = new CompletableFuture<>();
        final GroupMetadataCache cache = new GroupMetadataCache("trace-group");
        MavenGroupStaleFallbackTraceTest.staleL2(cache, valkey);
        final Slice missing = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        final Headers headers = Headers.from(EcsLoggingSlice.CTX_TRACE_ID_HEADER, TRACE);
        final CompletableFuture<Response> answer = new MavenGroupSlice(
            missing, "trace-group", List.of("member"),
            (name, port, depth) -> missing, 8080, 0, cache
        ).response(
            new RequestLine("GET", "/com/acme/trace/maven-metadata.xml"),
            headers, Content.EMPTY
        ).thenApply(
            resp -> {
                // What slices further out do on the answering thread.
                RequestContextHeaders.bindToMdc(headers);
                return resp;
            }
        );
        this.lettuce.submit(() -> valkey.complete(null)).get(10, TimeUnit.SECONDS);
        answer.get(10, TimeUnit.SECONDS);
        this.lettuce.submit(
            () -> EcsLogger.warn(LOGGER)
                .message("unrelated work of the same thread")
                .eventCategory("process")
                .eventAction("unrelated")
                .log()
        ).get(10, TimeUnit.SECONDS);
        final List<Map<String, Object>> fetch = this.capture.action("metadata_fetch");
        MatcherAssert.assertThat(
            "the stale-miss record carries the request's trace id",
            fetch.get(fetch.size() - 1).get("trace.id"), new IsEqual<>(TRACE)
        );
        MatcherAssert.assertThat(
            "the Valkey thread does not keep the request's trace id",
            this.capture.action("unrelated").get(0).get("trace.id"),
            new IsEqual<>(null)
        );
    }

    /**
     * Give the cache a stale L2 tier whose {@code GET} answers with the
     * given future.
     * @param cache Cache
     * @param answer Answer of Valkey
     */
    @SuppressWarnings("unchecked")
    private static void staleL2(
        final GroupMetadataCache cache, final CompletableFuture<byte[]> answer
    ) throws Exception {
        final InvocationHandler future = (proxy, method, args) -> {
            if ("toCompletableFuture".equals(method.getName())) {
                return answer;
            }
            throw new UnsupportedOperationException(method.getName());
        };
        final InvocationHandler commands = (proxy, method, args) -> {
            if ("get".equals(method.getName())) {
                return Proxy.newProxyInstance(
                    RedisFuture.class.getClassLoader(),
                    new Class<?>[] {RedisFuture.class}, future
                );
            }
            throw new UnsupportedOperationException(method.getName());
        };
        final Field two = GroupMetadataCache.class.getDeclaredField("staleTwoTier");
        two.setAccessible(true);
        two.setBoolean(cache, true);
        final Field tier = GroupMetadataCache.class.getDeclaredField("staleL2");
        tier.setAccessible(true);
        tier.set(
            cache,
            (RedisAsyncCommands<String, byte[]>) Proxy.newProxyInstance(
                RedisAsyncCommands.class.getClassLoader(),
                new Class<?>[] {RedisAsyncCommands.class}, commands
            )
        );
    }
}
