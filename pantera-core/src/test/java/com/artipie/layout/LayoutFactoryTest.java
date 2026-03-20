/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.layout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

/**
 * Tests for {@link LayoutFactory}.
 */
class LayoutFactoryTest {

    @Test
    void testForTypeMaven() {
        final StorageLayout layout = LayoutFactory.forType(
            LayoutFactory.RepositoryType.MAVEN
        );
        Assertions.assertInstanceOf(MavenLayout.class, layout);
    }

    @Test
    void testForTypePypi() {
        final StorageLayout layout = LayoutFactory.forType(
            LayoutFactory.RepositoryType.PYPI
        );
        Assertions.assertInstanceOf(PypiLayout.class, layout);
    }

    @Test
    void testForTypeHelm() {
        final StorageLayout layout = LayoutFactory.forType(
            LayoutFactory.RepositoryType.HELM
        );
        Assertions.assertInstanceOf(HelmLayout.class, layout);
    }

    @Test
    void testForTypeFile() {
        final StorageLayout layout = LayoutFactory.forType(
            LayoutFactory.RepositoryType.FILE
        );
        Assertions.assertInstanceOf(FileLayout.class, layout);
    }

    @Test
    void testForTypeNpm() {
        final StorageLayout layout = LayoutFactory.forType(
            LayoutFactory.RepositoryType.NPM
        );
        Assertions.assertInstanceOf(NpmLayout.class, layout);
    }

    @Test
    void testForTypeGradle() {
        final StorageLayout layout = LayoutFactory.forType(
            LayoutFactory.RepositoryType.GRADLE
        );
        Assertions.assertInstanceOf(MavenLayout.class, layout);
    }

    @Test
    void testForTypeComposer() {
        final StorageLayout layout = LayoutFactory.forType(
            LayoutFactory.RepositoryType.COMPOSER
        );
        Assertions.assertInstanceOf(ComposerLayout.class, layout);
    }

    @Test
    void testForTypeStringMaven() {
        final StorageLayout layout = LayoutFactory.forType("maven");
        Assertions.assertInstanceOf(MavenLayout.class, layout);
    }

    @Test
    void testForTypeStringCaseInsensitive() {
        final StorageLayout layout = LayoutFactory.forType("PYPI");
        Assertions.assertInstanceOf(PypiLayout.class, layout);
    }

    @Test
    void testForTypeStringInvalidThrowsException() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> LayoutFactory.forType("invalid-type")
        );
    }
}
