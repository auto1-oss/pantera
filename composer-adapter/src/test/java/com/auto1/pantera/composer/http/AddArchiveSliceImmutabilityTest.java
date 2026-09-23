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
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * A published Composer release is immutable: re-uploading the same version
 * with different content is a conflict, re-uploading identical content is
 * idempotent, dev branches may move. Malformed uploads are client errors.
 *
 * @since 2.2.9
 */
final class AddArchiveSliceImmutabilityTest {

    @Test
    void differentContentForAPublishedReleaseIsAConflict() throws Exception {
        final InMemoryStorage storage = new InMemoryStorage();
        final AddArchiveSlice slice = AddArchiveSliceImmutabilityTest.slice(storage);
        MatcherAssert.assertThat(
            "first upload is created",
            AddArchiveSliceImmutabilityTest.put(slice, "1.0.0", "hi").status().code(),
            new IsEqual<>(201)
        );
        final Key stored = new Key.From(
            "artifacts", "qa", "helper", "1.0.0", "qa-helper-1.0.0.zip"
        );
        final byte[] before = storage.value(stored).join().asBytes();
        MatcherAssert.assertThat(
            "a different archive for the same release is rejected",
            AddArchiveSliceImmutabilityTest.put(slice, "1.0.0", "hi-TAMPERED").status().code(),
            new IsEqual<>(409)
        );
        MatcherAssert.assertThat(
            "the published archive is untouched",
            storage.value(stored).join().asBytes(), new IsEqual<>(before)
        );
    }

    @Test
    void identicalReuploadIsIdempotent() throws Exception {
        final AddArchiveSlice slice = AddArchiveSliceImmutabilityTest.slice(new InMemoryStorage());
        AddArchiveSliceImmutabilityTest.put(slice, "1.0.0", "hi");
        MatcherAssert.assertThat(
            AddArchiveSliceImmutabilityTest.put(slice, "1.0.0", "hi").status().code(),
            new IsEqual<>(201)
        );
    }

    @Test
    void devBranchMayBeReuploaded() throws Exception {
        final AddArchiveSlice slice = AddArchiveSliceImmutabilityTest.slice(new InMemoryStorage());
        AddArchiveSliceImmutabilityTest.put(slice, "dev-master", "one");
        MatcherAssert.assertThat(
            AddArchiveSliceImmutabilityTest.put(slice, "dev-master", "two").status().code(),
            new IsEqual<>(201)
        );
    }

    @Test
    void garbageArchiveIsBadRequestWithoutExceptionText() {
        final Response resp = AddArchiveSliceImmutabilityTest.slice(new InMemoryStorage())
            .response(
                new RequestLine(RqMethod.PUT, "/qa-junk-1.0.0.zip"), Headers.EMPTY,
                new Content.From("garbage".getBytes(StandardCharsets.UTF_8))
            ).join();
        MatcherAssert.assertThat(
            "client error", resp.status().code(), new IsEqual<>(400)
        );
        MatcherAssert.assertThat(
            "no Java exception details in the body",
            new String(resp.body().asBytes(), StandardCharsets.UTF_8).contains("Exception"),
            new IsEqual<>(false)
        );
    }

    @Test
    void invalidComposerJsonIsBadRequest() throws Exception {
        final Response resp = AddArchiveSliceImmutabilityTest.slice(new InMemoryStorage())
            .response(
                new RequestLine(RqMethod.PUT, "/qa-bad-1.0.0.zip"), Headers.EMPTY,
                new Content.From(AddArchiveSliceImmutabilityTest.zip("{\"name\":\"qa/bad\", s}", "x"))
            ).join();
        MatcherAssert.assertThat(resp.status().code(), new IsEqual<>(400));
    }

    @Test
    void garbageTarGzIsBadRequest() {
        final Response resp = AddArchiveSliceImmutabilityTest.slice(new InMemoryStorage())
            .response(
                new RequestLine(RqMethod.PUT, "/qa-junk-1.0.0.tar.gz"), Headers.EMPTY,
                new Content.From("garbage".getBytes(StandardCharsets.UTF_8))
            ).join();
        MatcherAssert.assertThat(resp.status().code(), new IsEqual<>(400));
    }

    @Test
    void jsonRegistrationCannotRewriteAPublishedRelease() {
        final AddSlice slice = new AddSlice(
            new AstoRepository(new InMemoryStorage(), Optional.of("http://pantera:8080/php"))
        );
        final String first = "{\"name\":\"qa/meta\",\"version\":\"1.0.0\","
            + "\"dist\":{\"url\":\"https://example.org/a.zip\",\"type\":\"zip\"}}";
        final String swapped = "{\"name\":\"qa/meta\",\"version\":\"1.0.0\","
            + "\"dist\":{\"url\":\"https://evil.example/b.zip\",\"type\":\"zip\"}}";
        MatcherAssert.assertThat(
            "first registration is created",
            AddArchiveSliceImmutabilityTest.json(slice, first), new IsEqual<>(201)
        );
        MatcherAssert.assertThat(
            "the same registration again is idempotent",
            AddArchiveSliceImmutabilityTest.json(slice, first), new IsEqual<>(201)
        );
        MatcherAssert.assertThat(
            "a different entry for the published version is rejected",
            AddArchiveSliceImmutabilityTest.json(slice, swapped), new IsEqual<>(409)
        );
    }

    private static int json(final AddSlice slice, final String body) {
        return slice.response(
            new RequestLine(RqMethod.PUT, "/"), Headers.EMPTY,
            new Content.From(body.getBytes(StandardCharsets.UTF_8))
        ).join().status().code();
    }

    private static AddArchiveSlice slice(final InMemoryStorage storage) {
        return new AddArchiveSlice(
            new AstoRepository(storage, Optional.of("http://pantera:8080/php")), "php"
        );
    }

    private static Response put(
        final AddArchiveSlice slice, final String version, final String code
    ) throws Exception {
        return slice.response(
            new RequestLine(RqMethod.PUT, "/qa-helper.zip"), Headers.EMPTY,
            new Content.From(
                AddArchiveSliceImmutabilityTest.zip(
                    String.format("{\"name\":\"qa/helper\",\"version\":\"%s\"}", version), code
                )
            )
        ).join();
    }

    private static byte[] zip(final String composer, final String code) throws Exception {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("composer.json"));
            zos.write(composer.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("src/Helper.php"));
            zos.write(("<?php return '" + code + "';").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return bos.toByteArray();
    }
}
