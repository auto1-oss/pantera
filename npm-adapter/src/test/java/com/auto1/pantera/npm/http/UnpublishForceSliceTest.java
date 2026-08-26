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
}
