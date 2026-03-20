/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto.ext;

import com.auto1.pantera.asto.Key;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Test for {@link KeyLastPart}.
 * @since 0.24
 */
class KeyLastPartTest {

    @ParameterizedTest
    @CsvSource({
        "abc/def/some_file.txt,some_file.txt",
        "a/b/c/e/c,c",
        "one,one",
        "four/,four",
        "'',''"
    })
    void normalisesNames(final String key, final String expected) {
        MatcherAssert.assertThat(
            new KeyLastPart(new Key.From(key)).get(),
            new IsEqual<>(expected)
        );
    }

}
