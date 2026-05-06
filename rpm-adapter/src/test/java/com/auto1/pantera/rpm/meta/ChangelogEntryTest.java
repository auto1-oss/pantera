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
package com.auto1.pantera.rpm.meta;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link ChangelogEntry}.
 *
 * @since 0.8.3
 */
class ChangelogEntryTest {

    @Test
    void shouldParseAuthor() {
        MatcherAssert.assertThat(
            new ChangelogEntry(
                "* Wed May 12 2020 John Doe <johndoe@pantera.local> - 0.1-2\n- Second pantera package"
            ).author(),
            new IsEqual<>("John Doe <johndoe@pantera.local>")
        );
    }

    @Test
    void shouldParseDate() {
        final int unixtime = 1589328000;
        MatcherAssert.assertThat(
            new ChangelogEntry(
                "* Wed May 13 2020 John Doe <johndoe@pantera.local> - 0.1-2\n- Second pantera package"
            ).date(),
            new IsEqual<>(unixtime)
        );
    }

    @Test
    void shouldFailParseBadDate() {
        final ChangelogEntry entry = new ChangelogEntry(
            "* Abc March 41 20 John Doe <johndoe@pantera.local> - 0.1-2\n- Second pantera package"
        );
        Assertions.assertThrows(IllegalStateException.class, entry::date);
    }

    @Test
    void shouldParseContent() {
        MatcherAssert.assertThat(
            new ChangelogEntry(
                "* Wed May 14 2020 John Doe <johndoe@pantera.local> - 0.1-2\n- Second pantera package"
            ).content(),
            new IsEqual<>("- 0.1-2\n- Second pantera package")
        );
    }
}
