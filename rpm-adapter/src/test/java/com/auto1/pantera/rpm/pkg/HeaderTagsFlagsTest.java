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
package com.auto1.pantera.rpm.pkg;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Test for {@link HeaderTags.Flags}.
 * @since 1.10
 */
class HeaderTagsFlagsTest {

    @ParameterizedTest
    @EnumSource(HeaderTags.Flags.class)
    void findsFlagsByCode(final HeaderTags.Flags flag) {
        MatcherAssert.assertThat(
            HeaderTags.Flags.find(flag.code()).get(),
            new IsEqual<>(flag.notation())
        );
    }

    @Test
    void returnsEmptyWhenNotFound() {
        MatcherAssert.assertThat(
            HeaderTags.Flags.find(0).isPresent(),
            new IsEqual<>(false)
        );
    }

}
