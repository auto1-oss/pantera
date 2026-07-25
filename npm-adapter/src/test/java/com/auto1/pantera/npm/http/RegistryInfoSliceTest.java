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
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.io.StringReader;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link RegistryInfoSlice}: {@code GET /npm} previously a
 * standing-@todo empty 200 stub; now non-empty and honest.
 */
final class RegistryInfoSliceTest {

    @Test
    void answersWithNonEmptyRegistryInfo() throws Exception {
        final JsonObject body = Json.createReader(
            new StringReader(
                new RegistryInfoSlice("npm-local").response(
                    new RequestLine(RqMethod.GET, "/npm"), Headers.EMPTY, Content.EMPTY
                ).join().body().asString()
            )
        ).readObject();
        MatcherAssert.assertThat(body.getString("registry"), new IsEqual<>("npm-local"));
        MatcherAssert.assertThat(body.getBoolean("pantera"), new IsEqual<>(true));
        MatcherAssert.assertThat(
            "the response actually names the endpoints it serves, not an empty stub",
            body.getJsonObject("endpoints").isEmpty(),
            new IsEqual<>(false)
        );
    }

    @Test
    void answersOk() {
        MatcherAssert.assertThat(
            new RegistryInfoSlice("npm-local").response(
                new RequestLine(RqMethod.GET, "/npm"), Headers.EMPTY, Content.EMPTY
            ).join().status(),
            new IsEqual<>(RsStatus.OK)
        );
    }
}
