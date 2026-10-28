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
package com.auto1.pantera.docker.http.manifest;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link ManifestAccept} narrower than the end-to-end
 * slice matrix in {@code GetManifestSliceAcceptTest} -- pins the subtype
 * wildcard and multi-value {@code Accept} behaviour the slice tests don't
 * exercise directly.
 */
final class ManifestAcceptTest {

    private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";

    @Test
    void acceptsWhenHeaderAbsent() {
        MatcherAssert.assertThat(
            new ManifestAccept(Headers.EMPTY).accepts(OCI_MANIFEST), new IsEqual<>(true)
        );
    }

    @Test
    void acceptsUniversalWildcard() {
        MatcherAssert.assertThat(
            new ManifestAccept(Headers.from(new Header("Accept", "*/*"))).accepts(OCI_MANIFEST),
            new IsEqual<>(true)
        );
    }

    @Test
    void acceptsBareSubtypeWildcardMatchingType() {
        MatcherAssert.assertThat(
            new ManifestAccept(Headers.from(new Header("Accept", "application/*"))).accepts(OCI_MANIFEST),
            new IsEqual<>(true)
        );
    }

    @Test
    void rejectsSubtypeWildcardOfDifferentType() {
        MatcherAssert.assertThat(
            new ManifestAccept(Headers.from(new Header("Accept", "text/*"))).accepts(OCI_MANIFEST),
            new IsEqual<>(false)
        );
    }

    @Test
    void acceptsWhenOneOfSeveralValuesMatchesExactly() {
        MatcherAssert.assertThat(
            new ManifestAccept(
                Headers.from(
                    new Header(
                        "Accept",
                        "application/vnd.docker.distribution.manifest.v2+json," + OCI_MANIFEST
                    )
                )
            ).accepts(OCI_MANIFEST),
            new IsEqual<>(true)
        );
    }

    @Test
    void rejectsWhenNoValueMatches() {
        MatcherAssert.assertThat(
            new ManifestAccept(
                Headers.from(new Header("Accept", "application/vnd.docker.distribution.manifest.v2+json"))
            ).accepts(OCI_MANIFEST),
            new IsEqual<>(false)
        );
    }
}
