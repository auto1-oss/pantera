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
package com.auto1.pantera.layout;

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
