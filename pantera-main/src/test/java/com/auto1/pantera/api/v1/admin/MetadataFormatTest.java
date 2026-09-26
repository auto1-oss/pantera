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
package com.auto1.pantera.api.v1.admin;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * {@link MetadataFormat}: listing locations and served versions.
 *
 * @since 2.2.9
 */
final class MetadataFormatTest {

    @Test
    void readsNpmPackumentVersions() {
        final PackageName pkg = new PackageName("npm", "@scope/pkg");
        final MetadataFormat fmt = new MetadataFormat("npm");
        MatcherAssert.assertThat(
            "scoped packument path",
            fmt.listing(pkg).orElseThrow().path(), new IsEqual<>("/@scope%2Fpkg")
        );
        MatcherAssert.assertThat(
            "versions",
            fmt.versions(pkg, bytes("{\"name\":\"@scope/pkg\",\"versions\":{\"1.0.0\":{},\"1.1.0\":{}}}")),
            new IsEqual<>(List.of("1.0.0", "1.1.0"))
        );
    }

    @Test
    void readsPypiSimpleIndexVersions() {
        final PackageName pkg = new PackageName("pypi", "Typing_Extensions");
        final MetadataFormat fmt = new MetadataFormat("pypi");
        MatcherAssert.assertThat(
            "normalised simple path",
            fmt.listing(pkg).orElseThrow().path(), new IsEqual<>("/simple/typing-extensions/")
        );
        MatcherAssert.assertThat(
            "wheel and sdist versions",
            fmt.versions(pkg, bytes(
                "<a href=\"a\">typing_extensions-4.7.0-py3-none-any.whl</a>"
                    + "<a href=\"b\">typing_extensions-4.8.0.tar.gz</a>"
            )),
            new IsEqual<>(List.of("4.7.0", "4.8.0"))
        );
    }

    @Test
    void readsMavenMetadataVersions() {
        final PackageName pkg = new PackageName("maven", "com.example:foo");
        final MetadataFormat fmt = new MetadataFormat("maven");
        MatcherAssert.assertThat(
            "metadata path",
            fmt.listing(pkg).orElseThrow().path(),
            new IsEqual<>("/com/example/foo/maven-metadata.xml")
        );
        MatcherAssert.assertThat(
            "versions",
            fmt.versions(pkg, bytes(
                "<metadata><versioning><versions><version>1.0</version>"
                    + "<version>1.1</version></versions></versioning></metadata>"
            )),
            new IsEqual<>(List.of("1.0", "1.1"))
        );
    }

    @Test
    void readsGoAndComposerListings() {
        MatcherAssert.assertThat(
            "go module path is case-encoded",
            new MetadataFormat("go").listing(new PackageName("go", "github.com/Foo/bar"))
                .orElseThrow().path(),
            new IsEqual<>("/github.com/!foo/bar/@v/list")
        );
        MatcherAssert.assertThat(
            "composer p2 versions",
            new MetadataFormat("php").versions(
                new PackageName("php", "vendor/pkg"),
                bytes("{\"packages\":{\"vendor/pkg\":[{\"version\":\"2.0.0\"},{\"version\":\"1.0.0\"}]}}")
            ),
            new IsEqual<>(List.of("2.0.0", "1.0.0"))
        );
    }

    @Test
    void otherFamiliesAreUnsupported() {
        MatcherAssert.assertThat(
            new MetadataFormat("docker").listing(new PackageName("docker", "library/nginx"))
                .isPresent(),
            new IsEqual<>(false)
        );
    }

    /**
     * UTF-8 bytes.
     *
     * @param text Text
     * @return Bytes
     */
    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
