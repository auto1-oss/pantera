/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.nuget;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PackageKeys}.
 *
 * @since 0.1
 */
public class PackageKeysTest {

    @Test
    void shouldGenerateRootKey() {
        MatcherAssert.assertThat(
            new PackageKeys("Pantera.Module").rootKey().string(),
            new IsEqual<>("pantera.module")
        );
    }

    @Test
    void shouldGenerateVersionsKey() {
        MatcherAssert.assertThat(
            new PackageKeys("Newtonsoft.Json").versionsKey().string(),
            Matchers.is("newtonsoft.json/index.json")
        );
    }
}
