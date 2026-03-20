/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.headers;

import com.artipie.http.Headers;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

final class LoginTest {

    @Test
    void fallsBackToBasicAuthorization() {
        final String encoded = Base64.getEncoder()
            .encodeToString("alice:secret".getBytes(StandardCharsets.UTF_8));
        final Login login = new Login(Headers.from("Authorization", "Basic " + encoded));
        MatcherAssert.assertThat(login.getValue(), Matchers.is("alice"));
    }
}
