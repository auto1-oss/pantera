/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http;

import com.artipie.asto.Content;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/**
 * Test case for {@link HealthSlice}.
 *
 * @since 0.10
 */
final class HealthSliceTest {

    private static final RequestLine REQ_LINE = new RequestLine(RqMethod.GET, "/.health");

    @Test
    void returnsOkImmediately() {
        final Response response = new HealthSlice().response(
            REQ_LINE, Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "status should be OK",
            response.status(), Matchers.is(RsStatus.OK)
        );
        final String body = new String(response.body().asBytes(), StandardCharsets.UTF_8);
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            final JsonObject json = reader.readObject();
            MatcherAssert.assertThat(
                "status field should be ok",
                json.getString("status"), Matchers.is("ok")
            );
        }
    }
}
