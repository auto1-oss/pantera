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
package com.auto1.pantera.rpm.misc;

import java.util.Map;
import org.cactoos.map.MapEntry;
import org.cactoos.map.MapOf;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link PackagesDiff}.
 * @since 1.10
 */
class PackagesDiffTest {

    @Test
    void returnsItemsToDelete() {
        final Map<String, String> primary = new MapOf<String, String>(
            new MapEntry<>("abc.rpm", "abc-checksum"),
            new MapEntry<>("nginx.rpm", "nginx-checksum")
        );
        final Map<String, String> repo = new MapOf<String, String>(
            new MapEntry<>("httpd.rpm", "httpd-checksum"),
            new MapEntry<>("nginx.rpm", "nginx-checksum"),
            new MapEntry<>("openssh.rpm", "openssh-checksum")
        );
        MatcherAssert.assertThat(
            new PackagesDiff(primary, repo).toDelete().entrySet(),
            Matchers.hasItems(new MapEntry<>("abc.rpm", "abc-checksum"))
        );
    }

    @Test
    void returnsItemsToAdd() {
        final Map<String, String> primary = new MapOf<String, String>(
            new MapEntry<>("abc.rpm", "abc-checksum"),
            new MapEntry<>("nginx.rpm", "nginx-checksum")
        );
        final Map<String, String> repo = new MapOf<String, String>(
            new MapEntry<>("httpd.rpm", "httpd-checksum"),
            new MapEntry<>("nginx.rpm", "nginx-checksum"),
            new MapEntry<>("openssh.rpm", "openssh-checksum"),
            new MapEntry<>("abc.rpm", "abc-other-checksum")
        );
        MatcherAssert.assertThat(
            new PackagesDiff(primary, repo).toAdd(),
            Matchers.hasItems("abc.rpm", "httpd.rpm", "openssh.rpm")
        );
    }
}
