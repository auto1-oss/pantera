/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.cache;

import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.fake.FakeLayers;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link CacheLayers}.
 *
 * @since 0.3
 */
final class CacheLayersTest {
    @ParameterizedTest
    @CsvSource({
        "empty,empty,false",
        "empty,full,true",
        "full,empty,true",
        "faulty,full,true",
        "full,faulty,true",
        "faulty,empty,false",
        "empty,faulty,false"
    })
    void shouldReturnExpectedValue(
        final String origin,
        final String cache,
        final boolean expected
    ) {
        MatcherAssert.assertThat(
            new CacheLayers(
                new FakeLayers(origin),
                new FakeLayers(cache)
            ).get(new Digest.FromString("123"))
                .toCompletableFuture().join()
                .isPresent(),
            new IsEqual<>(expected)
        );
    }
}
