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

import com.auto1.pantera.asto.test.TestResource;
import org.cactoos.map.MapEntry;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link XmlPrimaryChecksums}.
 * @since 0.8
 */
public class XmlPrimaryChecksumsTest {

    @Test
    void readsChecksums() {
        MatcherAssert.assertThat(
            new XmlPrimaryChecksums(new TestResource("repodata/primary.xml.example").asPath())
                .read().entrySet(),
            Matchers.hasItems(
                new MapEntry<>(
                    "aom-1.0.0-8.20190810git9666276.el8.aarch64.rpm",
                    "7eaefd1cb4f9740558da7f12f9cb5a6141a47f5d064a98d46c29959869af1a44"
                ),
                new MapEntry<>(
                    "nginx-1.16.1-1.el8.ngx.x86_64.rpm",
                    "54f1d9a1114fa85cd748174c57986004857b800fe9545fbf23af53f4791b31e2"
                )
            )
        );
    }

}
