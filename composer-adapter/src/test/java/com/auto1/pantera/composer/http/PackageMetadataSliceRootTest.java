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
package com.auto1.pantera.composer.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.composer.ComposerBaseUrl;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.ClientBaseUrl;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * The root {@code packages.json} of a hosted php repository is the same
 * document for every request: it names no package and points Composer at
 * the per-package documents. It is answered without a storage write.
 *
 * @since 2.2.15
 */
final class PackageMetadataSliceRootTest {

    @Test
    void answersTheRootWithoutWritingIt() throws Exception {
        final Writes storage = new Writes(new InMemoryStorage());
        final PackageMetadataSlice slice = new PackageMetadataSlice(
            new AstoRepository(
                storage, Optional.of("http://php.example.com/php-api"), Optional.of("php-api")
            ),
            new ComposerBaseUrl(Optional.empty(), "php-api")
        );
        final JsonObject first = PackageMetadataSliceRootTest.root(slice);
        final JsonObject second = PackageMetadataSliceRootTest.root(slice);
        MatcherAssert.assertThat(
            "the root points Composer at the documents of the repository",
            first.getString("metadata-url"),
            new IsEqual<>("https://packages.example.com/php-api/p2/%package%.json")
        );
        MatcherAssert.assertThat(
            "and at the list of its packages",
            first.getString("available-packages-url"),
            new IsEqual<>("https://packages.example.com/php-api/p2/available-packages.json")
        );
        MatcherAssert.assertThat(
            "it names no package itself", first.getJsonObject("packages").size(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "every request gets the same root", second, new IsEqual<>(first)
        );
        MatcherAssert.assertThat(
            "and nothing is written for it", storage.saved(), new IsEqual<>(List.of())
        );
    }

    /**
     * Ask for the root document as a client reached under one host.
     *
     * @param slice Metadata slice of the repository
     * @return The document
     * @throws Exception On failure
     */
    private static JsonObject root(final PackageMetadataSlice slice) throws Exception {
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/packages.json"),
            Headers.from(ClientBaseUrl.HEADER, "https://packages.example.com/php-api"),
            Content.EMPTY
        ).get();
        MatcherAssert.assertThat(
            "the root is there", response.status(), new IsEqual<>(RsStatus.OK)
        );
        return Json.createReader(
            new StringReader(
                new String(response.body().asBytesFuture().get(), StandardCharsets.UTF_8)
            )
        ).readObject();
    }

    /**
     * Storage that records the keys saved into it.
     *
     * @since 2.2.15
     */
    private static final class Writes extends Storage.Wrap {

        /**
         * Keys saved, in order.
         */
        private final List<String> keys;

        Writes(final Storage delegate) {
            super(delegate);
            this.keys = new CopyOnWriteArrayList<>();
        }

        @Override
        public CompletableFuture<Void> save(final Key key, final Content content) {
            this.keys.add(key.string());
            return super.save(key, content);
        }

        List<String> saved() {
            return List.copyOf(this.keys);
        }
    }
}
