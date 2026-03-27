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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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

    @Test
    void returnsNotFondWhenModuleNotFound() {
        Response response = new LatestSlice(new InMemoryStorage()).response(
            RequestLine.from("GET example.com/first/@latest HTTP/1.1"), Headers.EMPTY, Content.EMPTY
        ).join();
        Assertions.assertEquals(RsStatus.NOT_FOUND, response.status());
    }

}
