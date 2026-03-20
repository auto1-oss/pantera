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
package com.auto1.pantera.helm;

import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.test.TestResource;
import java.io.IOException;
import java.util.Collections;
import java.util.Optional;
import org.cactoos.list.ListOf;
import org.hamcrest.MatcherAssert;
import org.hamcrest.collection.IsMapContaining;
import org.hamcrest.core.AllOf;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

/**
 * A test for {@link TgzArchive}.
 *
 * @since 0.2
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class TgzArchiveTest {

    @Test
    public void nameIdentifiedCorrectly() throws IOException {
        MatcherAssert.assertThat(
            new TgzArchive(
                new TestResource("tomcat-0.4.1.tgz").asBytes()
            ).name(),
            new IsEqual<>("tomcat-0.4.1.tgz")
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void hasCorrectMetadata() {
        MatcherAssert.assertThat(
            new TgzArchive(
                new TestResource("tomcat-0.4.1.tgz").asBytes()
            ).metadata(Optional.empty()),
            new AllOf<>(
                new ListOf<>(
                    new IsMapContaining<>(
                        new IsEqual<>("urls"),
                        new IsEqual<>(Collections.singletonList("tomcat/tomcat-0.4.1.tgz"))
                    ),
                    new IsMapContaining<>(
                        new IsEqual<>("digest"),
                        new IsInstanceOf(String.class)
                    )
                )
            )
        );
    }

    @Test
    void throwsExceptionForInvalidGzipFormat() {
        final byte[] invalidContent = "This is not a gzip file".getBytes();
        Assertions.assertThrows(
            PanteraIOException.class,
            () -> new TgzArchive(invalidContent).chartYaml()
        );
    }
}
