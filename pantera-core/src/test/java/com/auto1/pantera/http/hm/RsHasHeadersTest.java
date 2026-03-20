/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.hm;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.headers.Header;
import org.cactoos.map.MapEntry;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RsHasHeaders}.
 */
class RsHasHeadersTest {

    @Test
    void shouldMatchHeaders() {
        final MapEntry<String, String> type = new MapEntry<>(
            "Content-Type", "application/json"
        );
        final MapEntry<String, String> length = new MapEntry<>(
            "Content-Length", "123"
        );
        final Response response = ResponseBuilder.ok().headers(Headers.from(type, length)).build();
        final RsHasHeaders matcher = new RsHasHeaders(Headers.from(length, type));
        Assertions.assertTrue(matcher.matches(response));
    }

    @Test
    void shouldMatchOneHeader() {
        Header header = new Header("header1", "value1");
        final Response response = ResponseBuilder.ok()
            .header(header)
            .header(new Header("header2", "value2"))
            .header(new Header("header3", "value3"))
            .build();
        final RsHasHeaders matcher = new RsHasHeaders(header);
        MatcherAssert.assertThat(
            matcher.matches(response),
            new IsEqual<>(true)
        );
    }
}
