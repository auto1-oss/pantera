/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.headers;

import com.auto1.pantera.http.Headers;
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
