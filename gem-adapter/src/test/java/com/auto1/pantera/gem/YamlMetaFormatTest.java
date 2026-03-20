/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.gem;

import org.cactoos.iterable.IterableOf;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.text.StringContainsInOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Test case for {@link YamlMetaFormat}.
 */
@DisabledOnOs(OS.WINDOWS)
final class YamlMetaFormatTest {
    @Test
    void addPlainString() {
        final String key = "os";
        final String val = "macos";
        final YamlMetaFormat.Yamler yaml = new YamlMetaFormat.Yamler();
        new YamlMetaFormat(yaml).print(key, val);
        MatcherAssert.assertThat(
            yaml.build().toString(), new IsEqual<>("os: macos")
        );
    }

    @Test
    void addArray() {
        final String key = "deps";
        final String[] items = new String[] {"java", "ruby", "go"};
        final YamlMetaFormat.Yamler yaml = new YamlMetaFormat.Yamler();
        new YamlMetaFormat(yaml).print(key, items);
        MatcherAssert.assertThat(
            yaml.build().toString(),
            new StringContainsInOrder(
                new IterableOf<>("deps:", "java", "ruby", "go")
            )
        );
    }

    @Test
    void addNestedObjects() {
        final String root = "root";
        final String child = "child";
        final String key = "key";
        final String val = "value";
        final YamlMetaFormat.Yamler yaml = new YamlMetaFormat.Yamler();
        new YamlMetaFormat(yaml).print(
            root, first -> first.print(child, second -> second.print(key, val))
        );
        MatcherAssert.assertThat(
            yaml.build().toString(),
            new StringContainsInOrder(
                new IterableOf<>("root:", "child:", "key: value")
            )
        );
    }
}
