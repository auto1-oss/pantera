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
package com.auto1.pantera.api.v1;

import com.auto1.pantera.api.AuthTokenRest;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.reindex.IndexReindex;
import com.auto1.pantera.index.reindex.ReindexRepos;
import com.auto1.pantera.index.reindex.ReindexStore;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * HTTP contract of POST/GET {@code /api/v1/search/reindex}, against a
 * rebuild job whose store is parked until released — no database.
 *
 * @since 2.2.9
 */
@ExtendWith(VertxExtension.class)
@Timeout(60)
final class SearchReindexEndpointTest {

    @Test
    void startsOnceRefusesConcurrentRunAndReportsStatus(final Vertx vertx) throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final IndexReindex job = new IndexReindex(
            new ParkedStore(release), new NoRepos()
        );
        try {
            final int port = SearchReindexEndpointTest.serve(
                vertx, new SearchHandler(ArtifactIndex.NOP, Policy.FREE, List::of, job)
            );
            final HttpResponse<Buffer> first = SearchReindexEndpointTest.call(
                vertx, port, HttpMethod.POST
            );
            MatcherAssert.assertThat("First POST is accepted", first.statusCode(), new IsEqual<>(202));
            MatcherAssert.assertThat(
                "First POST reports started",
                first.bodyAsJsonObject().getString("status"), new IsEqual<>("started")
            );
            final HttpResponse<Buffer> second = SearchReindexEndpointTest.call(
                vertx, port, HttpMethod.POST
            );
            MatcherAssert.assertThat("Second POST conflicts", second.statusCode(), new IsEqual<>(409));
            MatcherAssert.assertThat(
                "Conflict carries the running state",
                second.bodyAsJsonObject().getString("state"), new IsEqual<>("running")
            );
            final JsonObject running = SearchReindexEndpointTest.call(vertx, port, HttpMethod.GET)
                .bodyAsJsonObject();
            MatcherAssert.assertThat(
                "Status is running", running.getString("state"), new IsEqual<>("running")
            );
            MatcherAssert.assertThat(
                "Start time is reported", running.getString("started_at") != null,
                new IsEqual<>(true)
            );
            release.countDown();
            final JsonObject idle = SearchReindexEndpointTest.awaitIdle(vertx, port);
            MatcherAssert.assertThat(
                "Finish time is reported", idle.getString("finished_at") != null,
                new IsEqual<>(true)
            );
            MatcherAssert.assertThat(
                "Last error is reported",
                idle.getString("last_error"), new StringContains("another node")
            );
            MatcherAssert.assertThat(
                "Counters are reported", idle.getLong("rows_pruned"), new IsEqual<>(0L)
            );
        } finally {
            release.countDown();
            job.close();
        }
    }

    @Test
    void answersServiceUnavailableWithoutDatabase(final Vertx vertx) throws Exception {
        final int port = SearchReindexEndpointTest.serve(
            vertx, new SearchHandler(ArtifactIndex.NOP, Policy.FREE, List::of)
        );
        MatcherAssert.assertThat(
            "POST without a database",
            SearchReindexEndpointTest.call(vertx, port, HttpMethod.POST).statusCode(),
            new IsEqual<>(503)
        );
        MatcherAssert.assertThat(
            "GET without a database",
            SearchReindexEndpointTest.call(vertx, port, HttpMethod.GET).statusCode(),
            new IsEqual<>(503)
        );
    }

    /**
     * Poll the status until the run is over.
     * @param vertx Vert.x
     * @param port Port
     * @return Idle status
     * @throws Exception On failure
     */
    private static JsonObject awaitIdle(final Vertx vertx, final int port) throws Exception {
        JsonObject status = SearchReindexEndpointTest.call(vertx, port, HttpMethod.GET)
            .bodyAsJsonObject();
        while (!"idle".equals(status.getString("state"))) {
            Thread.sleep(20L);
            status = SearchReindexEndpointTest.call(vertx, port, HttpMethod.GET).bodyAsJsonObject();
        }
        return status;
    }

    /**
     * Serve the handler behind a fake authenticated user.
     * @param vertx Vert.x
     * @param handler Handler
     * @return Port
     * @throws Exception On failure
     */
    private static int serve(final Vertx vertx, final SearchHandler handler) throws Exception {
        final Router router = Router.router(vertx);
        router.route().handler(
            ctx -> {
                ctx.setUser(
                    User.create(
                        new JsonObject()
                            .put(AuthTokenRest.SUB, "admin")
                            .put(AuthTokenRest.CONTEXT, "test")
                    )
                );
                ctx.next();
            }
        );
        handler.register(router);
        final HttpServer server = vertx.createHttpServer().requestHandler(router)
            .listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        return server.actualPort();
    }

    /**
     * Call the reindex endpoint.
     * @param vertx Vert.x
     * @param port Port
     * @param method Method
     * @return Response
     * @throws Exception On failure
     */
    private static HttpResponse<Buffer> call(
        final Vertx vertx, final int port, final HttpMethod method
    ) throws Exception {
        return WebClient.create(vertx)
            .request(method, port, "localhost", "/api/v1/search/reindex")
            .send().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /**
     * Store whose lock parks until released, then reports another node.
     * @since 2.2.9
     */
    private static final class ParkedStore implements ReindexStore {

        /**
         * Release latch.
         */
        private final CountDownLatch release;

        ParkedStore(final CountDownLatch release) {
            this.release = release;
        }

        @Override
        public Optional<Lease> lock() {
            try {
                this.release.await();
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }

        @Override
        public List<String> indexedRepos() {
            return List.of();
        }

        @Override
        public long pruneBatch(final String repo, final int limit) {
            return 0L;
        }

        @Override
        public Session open(final String repo, final long started) {
            throw new UnsupportedOperationException("no repositories");
        }
    }

    /**
     * No configured repository.
     * @since 2.2.9
     */
    private static final class NoRepos implements ReindexRepos {

        @Override
        public Collection<String> names() {
            return List.of();
        }

        @Override
        public boolean exists(final String name) {
            return false;
        }

        @Override
        public Target target(final String name) {
            throw new UnsupportedOperationException(name);
        }
    }
}
