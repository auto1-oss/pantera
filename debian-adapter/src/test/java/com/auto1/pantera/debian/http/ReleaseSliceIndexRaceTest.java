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
package com.auto1.pantera.debian.http;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.debian.AstoGzArchive;
import com.auto1.pantera.debian.Config;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A /dists/ read creates a configured Packages index that does not exist
 * yet. That exists-then-save must not overwrite an index an upload wrote
 * in between: the read has to decide under the index lock the upload
 * path holds while it writes the index.
 *
 * @since 2.2.9
 */
final class ReleaseSliceIndexRaceTest {

    /**
     * Packages index.
     */
    private static final Key INDEX = new Key.From("dists/my_repo/main/binary-amd64/Packages.gz");

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void emptyIndexNeverOverwritesIndexWrittenByUpload() throws IOException {
        final Storage asto = new InMemoryStorage();
        asto.save(
            new Key.From("dists/my_repo/Release"),
            new Content.From("Codename: my_repo\n".getBytes(StandardCharsets.UTF_8))
        ).join();
        final CompletableFuture<Void> gate = new CompletableFuture<>();
        final CompletableFuture<Void> upload = new CompletableFuture<>();
        // The upload holds the index lock while it writes the index.
        new IndexLock(asto, ReleaseSliceIndexRaceTest.INDEX).run(() -> upload);
        final CompletableFuture<Response> read = new ReleaseSlice(
            (line, headers, body) -> CompletableFuture.completedFuture(ResponseBuilder.ok().build()),
            new GatedExists(asto, gate),
            new Config.FromYaml(
                "my_repo",
                Yaml.createYamlMappingBuilder()
                    .add("Architectures", "amd64")
                    .add("Components", "main").build(),
                new InMemoryStorage()
            )
        ).response(
            new RequestLine(RqMethod.GET, "/dists/my_repo/InRelease"),
            Headers.EMPTY, Content.EMPTY
        );
        asto.save(ReleaseSliceIndexRaceTest.INDEX, new Content.From(ReleaseSliceIndexRaceTest.index()))
            .join();
        gate.complete(null);
        upload.complete(null);
        read.join();
        MatcherAssert.assertThat(
            new AstoGzArchive(asto).unpack(ReleaseSliceIndexRaceTest.INDEX),
            new StringContains("Package: aglfn")
        );
    }

    /**
     * Gzipped Packages index with one package.
     * @return Bytes
     * @throws IOException On error
     */
    private static byte[] index() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(
                "Package: aglfn\nVersion: 1.7-3\nArchitecture: all\n\n"
                    .getBytes(StandardCharsets.UTF_8)
            );
        }
        return out.toByteArray();
    }

    /**
     * Storage whose answer to "does the index exist" is computed at call
     * time but delivered only once the gate opens.
     * @since 2.2.9
     */
    private static final class GatedExists extends Storage.Wrap {

        /**
         * Gate.
         */
        private final CompletableFuture<Void> gate;

        /**
         * Ctor.
         * @param origin Storage
         * @param gate Gate
         */
        GatedExists(final Storage origin, final CompletableFuture<Void> gate) {
            super(origin);
            this.gate = gate;
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            final CompletableFuture<Boolean> res = super.exists(key);
            final CompletableFuture<Boolean> out;
            if (ReleaseSliceIndexRaceTest.INDEX.equals(key)) {
                final boolean now = res.join();
                out = this.gate.thenApply(nothing -> now);
            } else {
                out = res;
            }
            return out;
        }
    }
}
