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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;

/**
 * Test for {@link LatestSlice}.
 */
public class LatestSliceTest {

    @Test
    void returnsLatestVersion() throws ExecutionException, InterruptedException {
        final Storage storage = new InMemoryStorage();
        storage.save(
            new KeyFromPath("example.com/latest/news/@v/v0.0.1.zip"), new Content.From(new byte[]{})
        ).get();
        storage.save(
            new KeyFromPath("example.com/latest/news/@v/v0.0.1.mod"), new Content.From(new byte[]{})
        ).get();
        storage.save(
            new KeyFromPath("example.com/latest/news/@v/v0.0.1.info"),
            new Content.From(new byte[]{})
        ).get();
        storage.save(
            new KeyFromPath("example.com/latest/news/@v/v0.0.2.zip"), new Content.From(new byte[]{})
        ).get();
        storage.save(
            new KeyFromPath("example.com/latest/news/@v/v0.0.2.mod"), new Content.From(new byte[]{})
        ).get();
        final String info = "{\"Version\":\"v0.0.2\",\"Time\":\"2019-06-28T10:22:31Z\"}";
        storage.save(
            new KeyFromPath("example.com/latest/news/@v/v0.0.2.info"),
            new Content.From(info.getBytes())
        ).get();
        Response response = new LatestSlice(storage).response(
            RequestLine.from("GET example.com/latest/news/@latest?a=b HTTP/1.1"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        Assertions.assertArrayEquals(info.getBytes(), response.body().asBytes());
        MatcherAssert.assertThat(
            response.headers(),
            Matchers.containsInRelativeOrder(ContentType.json())
        );
    }

    @ParameterizedTest
    @CsvSource({
        "'v1.9.0,v1.10.0', v1.10.0",
        "'v1.0.0,v1.1.0-rc.1', v1.0.0",
        "'v1.1.0-rc.9,v1.1.0-rc.10', v1.1.0-rc.10",
        "'v1.1.0-beta,v1.1.0-alpha.1', v1.1.0-beta",
        "'v0.0.0-20190101000000-abcdefabcdef,v0.0.0-20200101000000-abcdefabcdef', v0.0.0-20200101000000-abcdefabcdef",
        "'v1.2.4-0.20200101000000-abcdefabcdef,v1.2.3', v1.2.3",
        "'v1.2.4-0.20200101000000-abcdefabcdef,v1.2.4-rc.1', v1.2.4-rc.1",
        "'v2.0.0+incompatible,v1.9.9', v2.0.0+incompatible",
        "'v1.0.0,v10.0.0,v9.0.0', v10.0.0"
    })
    void picksLatestByGoVersionOrdering(final String versions, final String expected) {
        final Storage storage = new InMemoryStorage();
        for (final String version : versions.split(",")) {
            storage.save(
                new KeyFromPath(String.format("example.com/order/mod/@v/%s.info", version)),
                new Content.From(
                    String.format("{\"Version\":\"%s\"}", version)
                        .getBytes(StandardCharsets.UTF_8)
                )
            ).join();
            storage.save(
                new KeyFromPath(String.format("example.com/order/mod/@v/%s.zip", version)),
                Content.EMPTY
            ).join();
        }
        final Response response = new LatestSlice(storage).response(
            RequestLine.from("GET example.com/order/mod/@latest HTTP/1.1"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            new String(response.body().asBytes(), StandardCharsets.UTF_8),
            new IsEqual<>(String.format("{\"Version\":\"%s\"}", expected))
        );
    }

    @Test
    void ignoresInfoFilesOfNestedModules() {
        final Storage storage = new InMemoryStorage();
        storage.save(
            new KeyFromPath("example.com/nest/@v/v1.0.0.info"),
            new Content.From("{\"Version\":\"v1.0.0\"}".getBytes(StandardCharsets.UTF_8))
        ).join();
        storage.save(
            new KeyFromPath("example.com/nest/@v/v1.0.0/extra/v9.0.0.info"),
            new Content.From("{\"Version\":\"v9.0.0\"}".getBytes(StandardCharsets.UTF_8))
        ).join();
        storage.save(new KeyFromPath("example.com/nest/@v/v1.0.0.zip"), Content.EMPTY).join();
        storage.save(
            new KeyFromPath("example.com/nest/@v/v1.0.0/extra/v9.0.0.zip"), Content.EMPTY
        ).join();
        final Response response = new LatestSlice(storage).response(
            RequestLine.from("GET example.com/nest/@latest HTTP/1.1"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            new String(response.body().asBytes(), StandardCharsets.UTF_8),
            new IsEqual<>("{\"Version\":\"v1.0.0\"}")
        );
    }

    @Test
    void ignoresVersionsWithoutZip() {
        final Storage storage = new InMemoryStorage();
        storage.save(
            new KeyFromPath("example.com/half/@v/v1.0.0.info"),
            new Content.From("{\"Version\":\"v1.0.0\"}".getBytes(StandardCharsets.UTF_8))
        ).join();
        storage.save(new KeyFromPath("example.com/half/@v/v1.0.0.zip"), Content.EMPTY).join();
        storage.save(
            new KeyFromPath("example.com/half/@v/v1.1.0.info"),
            new Content.From("{\"Version\":\"v1.1.0\"}".getBytes(StandardCharsets.UTF_8))
        ).join();
        final Response response = new LatestSlice(storage).response(
            RequestLine.from("GET example.com/half/@latest HTTP/1.1"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            new String(response.body().asBytes(), StandardCharsets.UTF_8),
            new IsEqual<>("{\"Version\":\"v1.0.0\"}")
        );
    }

    @Test
    void returnsNotFondWhenModuleNotFound() {
        Response response = new LatestSlice(new InMemoryStorage()).response(
            RequestLine.from("GET example.com/first/@latest HTTP/1.1"), Headers.EMPTY, Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.NOT_FOUND, response.status());
    }

}
