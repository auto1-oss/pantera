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
package com.auto1.pantera.gem.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blob.DownloadMode;
import com.auto1.pantera.asto.blob.DownloadPolicy;
import com.auto1.pantera.asto.blob.Presigner;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.index.SyncArtifactIndexer;
import com.auto1.pantera.security.policy.PolicyByUsername;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * {@code HEAD} of a file a RubyGems repository serves on {@code GET}:
 * {@code curl -I} or a CI installer probing a gem before fetching it.
 *
 * @since 2.3.0
 */
final class GemSliceHeadTest {

    /**
     * User name.
     */
    private static final String USER = "alice";

    /**
     * Password.
     */
    private static final String PASS = "secret";

    /**
     * Stored gem.
     */
    private static final byte[] GEM = "gem bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    void headOfGemIsAnsweredByTheRepositoryNeverRedirected() throws Exception {
        // A presigned URL is signed for GET: the object store refuses a
        // client that follows the redirect with HEAD.
        final PresigningStorage storage = new PresigningStorage(new InMemoryStorage());
        storage.save(new Key.From("gems", "builder-3.2.4.gem"), new Content.From(GemSliceHeadTest.GEM))
            .get(30, TimeUnit.SECONDS);
        final GemSlice slice = GemSliceHeadTest.redirecting(storage);
        final Response get = GemSliceHeadTest.request(slice, RqMethod.GET, "/gems/builder-3.2.4.gem");
        final Response head = GemSliceHeadTest.request(slice, RqMethod.HEAD, "/gems/builder-3.2.4.gem");
        MatcherAssert.assertThat(
            "GET is redirected to the object store", get.status().code(), new IsEqual<>(302)
        );
        MatcherAssert.assertThat(
            "HEAD is answered by the repository", head.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "HEAD announces the length of the gem",
            head.headers().values("Content-Length").stream().distinct().toList(),
            new IsEqual<>(List.of(String.valueOf(GemSliceHeadTest.GEM.length)))
        );
        MatcherAssert.assertThat(
            "HEAD has no body",
            head.body().asBytesFuture().get(30, TimeUnit.SECONDS).length, new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "only the GET had a URL signed", storage.presigned(), new IsEqual<>(1)
        );
    }

    @Test
    void headOfMissingGemIsNotFound() throws Exception {
        MatcherAssert.assertThat(
            GemSliceHeadTest.request(
                GemSliceHeadTest.redirecting(new InMemoryStorage()),
                RqMethod.HEAD, "/gems/missing-1.0.0.gem"
            ).status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    private static GemSlice redirecting(final Storage storage) {
        return new GemSlice(
            storage,
            new PolicyByUsername(GemSliceHeadTest.USER),
            new Authentication.Single(GemSliceHeadTest.USER, GemSliceHeadTest.PASS),
            null,
            "gem-repo",
            Optional.empty(),
            SyncArtifactIndexer.NOOP,
            new DownloadPolicy(DownloadMode.REDIRECT, 600L)
        );
    }

    private static Response request(
        final GemSlice slice, final RqMethod method, final String path
    ) throws Exception {
        return slice.response(
            new RequestLine(method, path),
            Headers.from(new Authorization.Basic(GemSliceHeadTest.USER, GemSliceHeadTest.PASS)),
            Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
    }

    /**
     * Storage whose objects can be downloaded directly with a presigned URL.
     */
    private static final class PresigningStorage extends Storage.Wrap implements Presigner {

        /**
         * URLs signed.
         */
        private final AtomicInteger signed;

        PresigningStorage(final Storage delegate) {
            super(delegate);
            this.signed = new AtomicInteger();
        }

        @Override
        public URI presignGet(final Key key, final long ttl) {
            this.signed.incrementAndGet();
            return URI.create("https://blobs.example.test/" + key.string() + "?sig=abc");
        }

        int presigned() {
            return this.signed.get();
        }
    }
}
