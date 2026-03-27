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
package com.auto1.pantera.conda.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.headers.ContentDisposition;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.hm.RsHasBody;
import com.auto1.pantera.http.hm.RsHasHeaders;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Test for {@link DownloadRepodataSlice}.
 * @since 0.4
 */
class DownloadRepodataSliceTest {

    /**
     * Test storage.
     */
    private Storage asto;

    @BeforeEach
    void init() {
        this.asto = new InMemoryStorage();
    }

    @Test
    void returnsItemFromStorageIfExists() {
        final byte[] bytes = "data".getBytes();
        this.asto.save(
            new Key.From("linux-64/repodata.json"), new Content.From(bytes)
        ).join();
        MatcherAssert.assertThat(
            new DownloadRepodataSlice(this.asto),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasBody(bytes),
                    new RsHasHeaders(
                        new ContentDisposition("attachment; filename=\"repodata.json\""),
                        new ContentLength(bytes.length)
                    )
                ),
                new RequestLine(RqMethod.GET, "any/other/parts/linux-64/repodata.json")
            )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"current_repodata.json", "repodata.json"})
    void returnsEmptyJsonIfNotExists(final String filename) {
        final byte[] bytes = "{\"info\":{\"subdir\":\"noarch\"}}".getBytes();
        MatcherAssert.assertThat(
            new DownloadRepodataSlice(this.asto),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasBody(bytes),
                    new RsHasHeaders(
                        new ContentDisposition(
                            String.format("attachment; filename=\"%s\"", filename)
                        ),
                        new ContentLength(bytes.length)
                    )
                ),
                new RequestLine(RqMethod.GET, String.format("/noarch/%s", filename))
            )
        );
    }
}
