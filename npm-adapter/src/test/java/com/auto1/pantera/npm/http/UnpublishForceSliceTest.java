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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.npm.misc.PackumentRevision;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link UnpublishForceSlice}.
 * @since 0.8
 */
final class UnpublishForceSliceTest {

    @Test
    void deletesWhenRevisionMatches() {
        final Storage storage = new InMemoryStorage();
        storage.save(new Key.From("pkg", ".versions", "1.0.0.json"),
            new Content.From("{}".getBytes(StandardCharsets.UTF_8))).join();
        final String rev = new PackumentRevision(storage, "pkg").value().join();
        final Response response = new UnpublishForceSlice(storage, Optional.empty(), "npm")
            .response(
                new RequestLine(RqMethod.DELETE, String.format("/pkg/-rev/%s", rev)),
                Headers.EMPTY, Content.EMPTY
            ).join();
        MatcherAssert.assertThat(
            "deletes on a matching revision",
            response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "package is gone",
            storage.exists(new Key.From("pkg", ".versions", "1.0.0.json")).join(),
            new IsEqual<>(false)
        );
    }

    @Test
    void refusesOnRevisionMismatch() {
        final Storage storage = new InMemoryStorage();
        storage.save(new Key.From("pkg", ".versions", "1.0.0.json"),
            new Content.From("{}".getBytes(StandardCharsets.UTF_8))).join();
        final Response response = new UnpublishForceSlice(storage, Optional.empty(), "npm")
            .response(
                new RequestLine(RqMethod.DELETE, "/pkg/-rev/9-deadbeef"),
                Headers.EMPTY, Content.EMPTY
            ).join();
        MatcherAssert.assertThat(
            "answers 409 on mismatch",
            response.status(), new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "package survives",
            storage.exists(new Key.From("pkg", ".versions", "1.0.0.json")).join(),
            new IsEqual<>(true)
        );
    }

    @Test
    void refusesTheLiteralUndefinedRevision() {
        final Storage storage = new InMemoryStorage();
        storage.save(new Key.From("pkg", ".versions", "1.0.0.json"),
            new Content.From("{}".getBytes(StandardCharsets.UTF_8))).join();
        final Response response = new UnpublishForceSlice(storage, Optional.empty(), "npm")
            .response(
                new RequestLine(RqMethod.DELETE, "/pkg/-rev/undefined"),
                Headers.EMPTY, Content.EMPTY
            ).join();
        MatcherAssert.assertThat(
            "answers 428 when no usable revision was sent",
            response.status(), new IsEqual<>(RsStatus.PRECONDITION_REQUIRED)
        );
        MatcherAssert.assertThat(
            "package survives",
            storage.exists(new Key.From("pkg", ".versions", "1.0.0.json")).join(),
            new IsEqual<>(true)
        );
    }

    @Test
    void answersNotFoundForAnUnknownPackage() {
        final Response response =
            new UnpublishForceSlice(new InMemoryStorage(), Optional.empty(), "npm")
                .response(
                    new RequestLine(RqMethod.DELETE, "/absent/-rev/1-abc"),
                    Headers.EMPTY, Content.EMPTY
                ).join();
        MatcherAssert.assertThat(
            response.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    @Test
    void deletesOnlyTheTarballOnTheTarballForm() {
        final Storage storage = new InMemoryStorage();
        storage.save(new Key.From("pkg", ".versions", "2.0.0.json"),
            new Content.From("{}".getBytes(StandardCharsets.UTF_8))).join();
        storage.save(new Key.From("pkg/-/pkg-1.0.0.tgz"),
            new Content.From("tgz".getBytes(StandardCharsets.UTF_8))).join();
        final String rev = new PackumentRevision(storage, "pkg").value().join();
        final Response response = new UnpublishForceSlice(storage, Optional.empty(), "npm")
            .response(
                new RequestLine(
                    RqMethod.DELETE, String.format("/pkg/-/pkg-1.0.0.tgz/-rev/%s", rev)
                ),
                Headers.EMPTY, Content.EMPTY
            ).join();
        MatcherAssert.assertThat(
            "deletes the tarball on a matching revision",
            response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "tarball is gone",
            storage.exists(new Key.From("pkg/-/pkg-1.0.0.tgz")).join(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the package's remaining versions survive",
            storage.exists(new Key.From("pkg", ".versions", "2.0.0.json")).join(),
            new IsEqual<>(true)
        );
    }

    @Test
    void deletesAScopedTarballOnTheTarballForm() {
        final Storage storage = new InMemoryStorage();
        storage.save(new Key.From("@scope/pkg", ".versions", "2.0.0.json"),
            new Content.From("{}".getBytes(StandardCharsets.UTF_8))).join();
        storage.save(new Key.From("@scope/pkg/-/@scope/pkg-1.0.0.tgz"),
            new Content.From("tgz".getBytes(StandardCharsets.UTF_8))).join();
        final String rev = new PackumentRevision(storage, "@scope/pkg").value().join();
        final Response response = new UnpublishForceSlice(storage, Optional.empty(), "npm")
            .response(
                new RequestLine(
                    RqMethod.DELETE,
                    String.format("/@scope/pkg/-/@scope/pkg-1.0.0.tgz/-rev/%s", rev)
                ),
                Headers.EMPTY, Content.EMPTY
            ).join();
        MatcherAssert.assertThat(
            "deletes the scoped tarball on a matching revision",
            response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "scoped tarball is gone",
            storage.exists(new Key.From("@scope/pkg/-/@scope/pkg-1.0.0.tgz")).join(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the scoped package's remaining versions survive",
            storage.exists(new Key.From("@scope/pkg", ".versions", "2.0.0.json")).join(),
            new IsEqual<>(true)
        );
    }

    @Test
    void refusesTheTarballFormOnRevisionMismatch() {
        final Storage storage = new InMemoryStorage();
        storage.save(new Key.From("pkg", ".versions", "2.0.0.json"),
            new Content.From("{}".getBytes(StandardCharsets.UTF_8))).join();
        storage.save(new Key.From("pkg/-/pkg-1.0.0.tgz"),
            new Content.From("tgz".getBytes(StandardCharsets.UTF_8))).join();
        final Response response = new UnpublishForceSlice(storage, Optional.empty(), "npm")
            .response(
                new RequestLine(RqMethod.DELETE, "/pkg/-/pkg-1.0.0.tgz/-rev/9-deadbeef"),
                Headers.EMPTY, Content.EMPTY
            ).join();
        MatcherAssert.assertThat(
            "answers 409 on a stale revision",
            response.status(), new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "tarball survives",
            storage.exists(new Key.From("pkg/-/pkg-1.0.0.tgz")).join(),
            new IsEqual<>(true)
        );
    }

    @Test
    void answersNotFoundForAnAbsentTarball() {
        final Storage storage = new InMemoryStorage();
        storage.save(new Key.From("pkg", ".versions", "2.0.0.json"),
            new Content.From("{}".getBytes(StandardCharsets.UTF_8))).join();
        final String rev = new PackumentRevision(storage, "pkg").value().join();
        final Response response = new UnpublishForceSlice(storage, Optional.empty(), "npm")
            .response(
                new RequestLine(
                    RqMethod.DELETE, String.format("/pkg/-/pkg-1.0.0.tgz/-rev/%s", rev)
                ),
                Headers.EMPTY, Content.EMPTY
            ).join();
        MatcherAssert.assertThat(
            response.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }
}
