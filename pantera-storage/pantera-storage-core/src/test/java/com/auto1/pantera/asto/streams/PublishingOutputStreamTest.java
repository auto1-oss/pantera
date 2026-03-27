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
import com.auto1.pantera.asto.ext.ContentAs;
import io.reactivex.Single;
import java.nio.charset.StandardCharsets;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StorageValuePipeline.PublishingOutputStream}.
 *
 * @since 1.12
 */
public final class PublishingOutputStreamTest {
    @Test
    void shouldPublishContentWhenDataIsWroteToOutputStream() throws Exception {
        final Content content;
        try (StorageValuePipeline.PublishingOutputStream output =
            new StorageValuePipeline.PublishingOutputStream()) {
            content = new Content.From(output.publisher());
            output.write("test data".getBytes(StandardCharsets.UTF_8));
            output.write(" test data 2".getBytes(StandardCharsets.UTF_8));
        }
        MatcherAssert.assertThat(
            ContentAs.STRING.apply(Single.just(content)).toFuture().get(),
            new IsEqual<>("test data test data 2")
        );
    }
}
