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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Entity headers of a local Maven repository: HEAD carries the same
 * Content-Length as GET, and every served file has a Content-Type.
 *
 * @since 2.2.9
 */
final class LocalMavenSliceHeadersTest {

    /**
     * Artifact directory.
     */
    private static final String DIR = "com/example/lib/1.0/";

    /**
     * Storage.
     */
    private InMemoryStorage storage;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
        for (final String name : List.of(
            "lib-1.0.jar", "lib-1.0.module", "lib-1.0.jar.sha1", "lib-1.0.pom"
        )) {
            this.storage.save(
                new Key.From(DIR + name),
                new Content.From(body(name))
            ).join();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "GET,lib-1.0.jar,application/java-archive",
        "HEAD,lib-1.0.jar,application/java-archive",
        "GET,lib-1.0.module,application/json",
        "HEAD,lib-1.0.module,application/json",
        "GET,lib-1.0.jar.sha1,text/plain",
        "HEAD,lib-1.0.jar.sha1,text/plain"
    })
    void servesContentTypeAndLength(final String method, final String name, final String type) {
        final Response resp = new LocalMavenSlice(this.storage, "maven").response(
            new RequestLine(RqMethod.valueOf(method), "/" + DIR + name),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "Content-Type of " + name,
            resp.headers().values("Content-Type"), new IsEqual<>(List.of(type))
        );
        MatcherAssert.assertThat(
            "Content-Length of " + name,
            resp.headers().values("Content-Length"),
            new IsEqual<>(List.of(String.valueOf(body(name).length)))
        );
    }

    private static byte[] body(final String name) {
        return ("content of " + name).getBytes(StandardCharsets.UTF_8);
    }
}
