/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.client.auth;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Authenticator} instances.
 *
 * @since 0.3
 */
class BasicAuthenticatorTest {

    @Test
    void shouldProduceBasicHeader() {
        MatcherAssert.assertThat(
            StreamSupport.stream(
                new BasicAuthenticator("Aladdin", "open sesame")
                    .authenticate(Headers.EMPTY)
                    .toCompletableFuture().join()
                    .spliterator(),
                false
            ).map(Header::new).collect(Collectors.toList()),
            Matchers.contains(new Header("Authorization", "Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ=="))
        );
    }
}
