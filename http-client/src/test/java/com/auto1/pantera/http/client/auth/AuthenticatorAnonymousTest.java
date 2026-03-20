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
import org.hamcrest.collection.IsEmptyCollection;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Authenticator#ANONYMOUS}.
 *
 * @since 0.4
 */
class AuthenticatorAnonymousTest {

    @Test
    void shouldProduceEmptyHeader() {
        MatcherAssert.assertThat(
            StreamSupport.stream(
                Authenticator.ANONYMOUS.authenticate(Headers.EMPTY)
                    .toCompletableFuture().join()
                    .spliterator(),
                false
            ).map(Header::new).collect(Collectors.toList()),
            new IsEmptyCollection<>()
        );
    }
}
