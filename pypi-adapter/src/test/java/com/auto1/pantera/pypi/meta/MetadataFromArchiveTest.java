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
package com.auto1.pantera.pypi.meta;

import com.auto1.pantera.asto.test.TestResource;
import java.io.ByteArrayInputStream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Test for {@link Metadata.FromArchive}.
 * @since 0.6
 */
class MetadataFromArchiveTest {

    @ParameterizedTest
    @CsvSource({
        "pypi_repo/pantera-sample-0.2.zip",
        "pypi_repo/pantera-sample-0.2.tar",
        "pypi_repo/pantera-sample-0.2.tar.gz",
        "pypi_repo/pantera-sample-2.1.tar.Z",
        "pypi_repo/pantera-sample-2.1.tar.bz2",
        "pypi_repo/pantera_sample-2.1-py3.7.egg",
        "pypi_repo/pantera_sample-0.2-py3-none-any.whl"
    })
    void readsFromTarGz(final String filename) {
        MatcherAssert.assertThat(
            new Metadata.FromArchive(
                new TestResource(filename).asInputStream(), filename
            ).read().name(),
            new IsEqual<>("pantera-sample")
        );
    }

    @Test
    void throwsExceptionIfArchiveIsUnsupported() {
        Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> new Metadata.FromArchive(
                new ByteArrayInputStream("any".getBytes()), "some/archive.tar.br"
            ).read()
        );
    }

    @ParameterizedTest
    @CsvSource({
        "pypi_repo/pantera-sample-0.2.zip",
        "pypi_repo/pantera-sample-0.2.tar",
        "pypi_repo/pantera-sample-0.2.tar.gz",
        "pypi_repo/pantera_sample-0.2-py3-none-any.whl"
    })
    void readWithMetadataReturnsRawBytesMatchingParsedInfo(final String filename) {
        final Metadata.Extracted extracted = new Metadata.FromArchive(
            new TestResource(filename).asInputStream(), filename
        ).readWithMetadata();
        MatcherAssert.assertThat(
            "readWithMetadata() must parse the same package name as read()",
            extracted.info().name(),
            new IsEqual<>("pantera-sample")
        );
        MatcherAssert.assertThat(
            "readWithMetadata() must capture non-empty raw METADATA/PKG-INFO bytes",
            extracted.rawMetadata().length > 0
        );
        MatcherAssert.assertThat(
            "raw metadata bytes decoded as ASCII must contain the Name: header "
                + "the parsed PackageInfo reads",
            new String(extracted.rawMetadata(), java.nio.charset.StandardCharsets.US_ASCII)
                .contains("Name: pantera-sample")
        );
    }

    @Test
    void rawMetadataIsDefensivelyCopied() {
        final String filename = "pypi_repo/pantera-sample-0.2.tar";
        final Metadata.Extracted extracted = new Metadata.FromArchive(
            new TestResource(filename).asInputStream(), filename
        ).readWithMetadata();
        final byte[] first = extracted.rawMetadata();
        first[0] = 0;
        MatcherAssert.assertThat(
            "Mutating a previously-returned array must not affect the record's state",
            extracted.rawMetadata()[0],
            new org.hamcrest.core.IsNot<>(new IsEqual<>((byte) 0))
        );
    }

}
