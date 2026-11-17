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
import com.auto1.pantera.debian.Config;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.security.policy.Policy;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * A Debian repository answers {@code HEAD} for a file it serves on
 * {@code GET}: {@code curl -I} and tools that probe a download before
 * fetching it got 404 for a package that exists.
 *
 * @since 2.2.12
 */
final class DebianSliceHeadTest {

    /**
     * Package path.
     */
    private static final String PATH = "/pool/main/a/aglfn/aglfn_1.7-3_all.deb";

    /**
     * Package bytes.
     */
    private static final byte[] DATA = "deb package".getBytes(StandardCharsets.UTF_8);

    @Test
    void answersHeadLikeGetWithoutBody() throws Exception {
        final Storage storage = new InMemoryStorage();
        storage.save(
            new Key.From(DebianSliceHeadTest.PATH.substring(1)),
            new Content.From(DebianSliceHeadTest.DATA)
        ).get(30, TimeUnit.SECONDS);
        final DebianSlice slice = new DebianSlice(
            storage, Policy.FREE, new Authentication.Single("alice", "secret"),
            new Config.FromYaml(
                "pantera",
                Yaml.createYamlMappingBuilder()
                    .add("Components", "main")
                    .add("Architectures", "amd64")
                    .build(),
                new InMemoryStorage()
            ),
            Optional.empty()
        );
        final Headers auth = Headers.from(new Authorization.Basic("alice", "secret"));
        final Response get = slice.response(
            new RequestLine(RqMethod.GET, DebianSliceHeadTest.PATH), auth, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        final Response head = slice.response(
            new RequestLine(RqMethod.HEAD, DebianSliceHeadTest.PATH), auth, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "GET serves the package", get.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "GET returns the package bytes",
            get.body().asBytesFuture().get(30, TimeUnit.SECONDS),
            new IsEqual<>(DebianSliceHeadTest.DATA)
        );
        MatcherAssert.assertThat(
            "HEAD answers like GET", head.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "HEAD announces the length GET announces",
            head.headers().values("Content-Length"),
            new IsEqual<>(get.headers().values("Content-Length"))
        );
        MatcherAssert.assertThat(
            "that length is the size of the package",
            head.headers().values("Content-Length").contains(
                String.valueOf(DebianSliceHeadTest.DATA.length)
            ),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "HEAD has no body",
            head.body().asBytesFuture().get(30, TimeUnit.SECONDS).length, new IsEqual<>(0)
        );
    }
}
