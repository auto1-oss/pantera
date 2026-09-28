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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.api.v1.download.DownloadTokens;
import com.auto1.pantera.api.v1.download.InMemoryNonceStore;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.security.policy.PolicyByUsername;
import com.auto1.pantera.settings.RepoData;
import com.auto1.pantera.test.TestStoragesCache;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exploit-regression test for the predictable download-token signing key,
 * driven through the REAL {@link ArtifactHandler} on an in-process router.
 *
 * <p>Before 2.2.9 the HMAC key fell back to
 * {@code pantera-download-<pid>-<user.name>} when
 * {@code PANTERA_DOWNLOAD_TOKEN_SECRET} was unset — a value with zero
 * entropy that the shipped container fixes at
 * {@code pantera-download-1-pantera}. The JWT-exempt
 * {@code /artifact/download-direct} route trusted that MAC alone, so anyone
 * able to compute the key could mint a token for any repository path and
 * read it with no credentials at all. This test forges exactly such a token
 * from the legacy derivation (the same environment the handler runs in, so
 * the forged key equals the vulnerable handler's key) and expects the
 * registry to refuse it.</p>
 *
 * @since 2.2.9
 */
@ExtendWith(VertxExtension.class)
final class DownloadDirectForgeryTest {

    private static final String REPO = "repo-x";

    private static final String FILE = "docs/secret.txt";

    @Test
    void tokenForgedWithTheLegacyPredictableKeyIsRejected(
        final Vertx vertx, @TempDir final Path tmp
    ) throws Exception {
        Files.createDirectories(tmp.resolve(REPO).resolve("docs"));
        Files.writeString(tmp.resolve(REPO).resolve(FILE), "TOP SECRET");
        final InMemoryStorage configs = new InMemoryStorage();
        configs.save(
            new Key.From(REPO + ".yaml"),
            new Content.From(
                ("repo:\n  type: file\n  storage:\n    type: fs\n    path: "
                    + tmp + "\n").getBytes(StandardCharsets.UTF_8)
            )
        ).join();
        final Router router = Router.router(vertx);
        new ArtifactHandler(
            null, new RepoData(configs, new TestStoragesCache()), Policy.FREE
        ).register(router);
        final HttpServer server = vertx.createHttpServer().requestHandler(router)
            .listen(0).toCompletionStage().toCompletableFuture()
            .get(30, TimeUnit.SECONDS);
        try {
            final String payload = REPO + "\n" + FILE + "\n" + System.currentTimeMillis();
            final String legacyKey = "pantera-download-" + ProcessHandle.current().pid()
                + "-" + System.getProperty("user.name", "default");
            final String forged = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + hmac(legacyKey, payload);
            final HttpResponse<Buffer> response = WebClient.create(vertx)
                .get(
                    server.actualPort(), "localhost",
                    "/api/v1/repositories/" + REPO + "/artifact/download-direct"
                )
                .addQueryParam("token", forged)
                .send().toCompletionStage().toCompletableFuture()
                .get(30, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                "a token forged with the legacy pid/username key must be rejected (401) — "
                    + "it must never stream the artifact",
                response.statusCode(), new IsEqual<>(401)
            );
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void genuineTokenStreamsOnceAndReplayIsRefused(
        final Vertx vertx, @TempDir final Path tmp
    ) throws Exception {
        Files.createDirectories(tmp.resolve(REPO).resolve("docs"));
        Files.writeString(tmp.resolve(REPO).resolve(FILE), "TOP SECRET");
        final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        final DownloadTokens tokens = new DownloadTokens(
            java.util.concurrent.CompletableFuture.completedFuture(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)
            ),
            clock::get,
            new InMemoryNonceStore(Duration.ofMinutes(2), clock::get)
        );
        final HttpServer server = this.serve(vertx, tmp, new PolicyByUsername("alice"), tokens);
        try {
            final String token = tokens.issue(REPO, FILE, "alice", "test")
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
            final HttpResponse<Buffer> first = fetch(vertx, server.actualPort(), token);
            MatcherAssert.assertThat(
                "a genuine token from an authorized issuer streams the artifact",
                first.statusCode(), new IsEqual<>(200)
            );
            MatcherAssert.assertThat(
                "the streamed bytes are the artifact",
                first.bodyAsString(), new IsEqual<>("TOP SECRET")
            );
            final HttpResponse<Buffer> replay = fetch(vertx, server.actualPort(), token);
            MatcherAssert.assertThat(
                "replaying the same token must be refused — single-use is real now",
                replay.statusCode(), new IsEqual<>(401)
            );
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void issuerWithoutRepositoryReadIsRefusedAtRedemption(
        final Vertx vertx, @TempDir final Path tmp
    ) throws Exception {
        Files.createDirectories(tmp.resolve(REPO).resolve("docs"));
        Files.writeString(tmp.resolve(REPO).resolve(FILE), "TOP SECRET");
        final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        final DownloadTokens tokens = new DownloadTokens(
            java.util.concurrent.CompletableFuture.completedFuture(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)
            ),
            clock::get,
            new InMemoryNonceStore(Duration.ofMinutes(2), clock::get)
        );
        // Only alice may read; a token naming mallory (e.g. issued before her
        // grant was revoked) must not unlock the artifact.
        final HttpServer server = this.serve(vertx, tmp, new PolicyByUsername("alice"), tokens);
        try {
            final String token = tokens.issue(REPO, FILE, "mallory", "test")
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                "a valid token proves possession, not authorization — the issuer must "
                    + "still hold repository READ at redemption",
                fetch(vertx, server.actualPort(), token).statusCode(), new IsEqual<>(403)
            );
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Start the real handler over an fs repo rooted at {@code tmp}.
     */
    private HttpServer serve(
        final Vertx vertx, final Path tmp, final Policy<?> policy, final DownloadTokens tokens
    ) throws Exception {
        final InMemoryStorage configs = new InMemoryStorage();
        configs.save(
            new Key.From(REPO + ".yaml"),
            new Content.From(
                ("repo:\n  type: file\n  storage:\n    type: fs\n    path: "
                    + tmp + "\n").getBytes(StandardCharsets.UTF_8)
            )
        ).join();
        final Router router = Router.router(vertx);
        new ArtifactHandler(
            null, new RepoData(configs, new TestStoragesCache()), policy, null,
            com.auto1.pantera.index.ArtifactIndex.NOP, null, tokens
        ).register(router);
        return vertx.createHttpServer().requestHandler(router)
            .listen(0).toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static HttpResponse<Buffer> fetch(
        final Vertx vertx, final int port, final String token
    ) throws Exception {
        return WebClient.create(vertx)
            .get(port, "localhost", "/api/v1/repositories/" + REPO + "/artifact/download-direct")
            .addQueryParam("token", token)
            .send().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static String hmac(final String key, final String payload) throws Exception {
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
