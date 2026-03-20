/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker;

import com.auto1.pantera.docker.error.InvalidTagNameException;
import com.auto1.pantera.docker.misc.ImageTag;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.AllOf;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

/**
 * Tests for {@link ImageTag}.
 */
class ImageTagTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "latest",
        "1.0",
        "my-tag",
        "MY_TAG",
        "My.Tag.1",
        "_some_tag",
        "01234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567"
    })
    void shouldGetValueWhenValid(final String tag) {
        Assertions.assertEquals(tag, ImageTag.validate(tag));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        ".0",
        "*",
        "\u00ea",
        "-my-tag",
        "012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678"
    })
    void shouldFailToGetValueWhenInvalid(final String tag) {
        final Throwable throwable = Assertions.assertThrows(
            InvalidTagNameException.class, () -> ImageTag.validate(tag)
        );
        MatcherAssert.assertThat(
            throwable.getMessage(),
            new AllOf<>(
                Arrays.asList(
                    new StringContains(true, "Invalid tag"),
                    new StringContains(false, tag)
                )
            )
        );
    }
}
