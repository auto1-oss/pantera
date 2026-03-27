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
import com.auto1.pantera.rpm.hm.IsXmlEqual;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.cactoos.list.ListOf;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link XmlMaid.ByPkgidAttr}.
 * @since 0.3
 */
public final class ByPkgidAttrTest {

    @Test
    void clearsFirstItem(@TempDir final Path temp) throws IOException {
        final Path file = Files.copy(
            new TestResource("repodata/other.xml.example").asPath(),
            temp.resolve("other.xml")
        );
        new XmlMaid.ByPkgidAttr(file).clean(
            new ListOf<>("7eaefd1cb4f9740558da7f12f9cb5a6141a47f5d064a98d46c29959869af1a44")
        );
        MatcherAssert.assertThat(
            file,
            new IsXmlEqual(new TestResource("repodata/other.xml.example.second").asPath())
        );
    }

    @Test
    void clearsLastItem(@TempDir final Path temp) throws IOException {
        final Path file = Files.copy(
            new TestResource("repodata/filelists.xml.example").asPath(),
            temp.resolve("filelist.xml")
        );
        new XmlMaid.ByPkgidAttr(file).clean(
            new ListOf<>("54f1d9a1114fa85cd748174c57986004857b800fe9545fbf23af53f4791b31e2")
        );
        MatcherAssert.assertThat(
            file,
            new IsXmlEqual(new TestResource("repodata/filelists.xml.example.first").asPath())
        );
    }
}
