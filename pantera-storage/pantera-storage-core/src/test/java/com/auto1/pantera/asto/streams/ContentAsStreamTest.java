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
package com.auto1.pantera.asto.streams;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.misc.UncheckedIOFunc;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.io.IOUtils;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link ContentAsStream}.
 * @since 1.4
 */
class ContentAsStreamTest {

    @Test
    void processesItem() {
        final Charset charset = StandardCharsets.UTF_8;
        MatcherAssert.assertThat(
            new ContentAsStream<List<String>>(new Content.From("one\ntwo\nthree".getBytes(charset)))
                .process(new UncheckedIOFunc<>(input -> IOUtils.readLines(input, charset)))
                .toCompletableFuture().join(),
            Matchers.contains("one", "two", "three")
        );
    }

    @Test
    void testContentAsStream() {
        final Charset charset = StandardCharsets.UTF_8;
        final Key kfrom = new Key.From("kfrom");
        final Storage storage = new InMemoryStorage();
        storage.save(kfrom, new Content.From("one\ntwo\nthree".getBytes(charset))).join();
        final List<String> res = storage.value(kfrom).thenCompose(
            content -> new ContentAsStream<List<String>>(content)
                .process(new UncheckedIOFunc<>(
                    input -> org.apache.commons.io.IOUtils.readLines(input, charset)
                ))).join();
        MatcherAssert.assertThat(res, Matchers.contains("one", "two", "three"));
    }
}
