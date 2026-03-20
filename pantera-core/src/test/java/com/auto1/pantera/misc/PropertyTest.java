/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.misc;

import com.auto1.pantera.PanteraException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Property}.
 * @since 0.23
 */
final class PropertyTest {
    @Test
    void readsDefaultValue() {
        final long defval = 500L;
        MatcherAssert.assertThat(
            new Property("not.existed.value.so.use.default")
                .asLongOrDefault(defval),
            new IsEqual<>(defval)
        );
    }

    @Test
    void readsValueFromPanteraProperties() {
        MatcherAssert.assertThat(
            new Property(PanteraProperties.STORAGE_TIMEOUT)
                .asLongOrDefault(123L),
            new IsEqual<>(180_000L)
        );
    }

    @Test
    void readsValueFromSetProperties() {
        final long val = 17L;
        System.setProperty(PanteraProperties.AUTH_TIMEOUT, String.valueOf(val));
        MatcherAssert.assertThat(
            new Property(PanteraProperties.AUTH_TIMEOUT)
                .asLongOrDefault(345L),
            new IsEqual<>(val)
        );
    }

    @Test
    void failsToParseWrongValueFromSetProperties() {
        final String key = "my.property.value";
        System.setProperty(key, "can't be parsed");
        Assertions.assertThrows(
            PanteraException.class,
            () -> new Property(key).asLongOrDefault(50L)
        );
    }

    @Test
    void failsToParseWrongValueFromPanteraProperties() {
        Assertions.assertThrows(
            PanteraException.class,
            () -> new Property(PanteraProperties.VERSION_KEY)
                .asLongOrDefault(567L)
        );
    }

    @Test
    void propertiesFileDoesNotExist() {
        Assertions.assertTrue(
            new PanteraProperties("file_does_not_exist.properties")
                .valueBy("aaa").isEmpty()
        );
    }
}
