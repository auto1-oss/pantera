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
package com.auto1.pantera.rpm.hm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.cactoos.scalar.Unchecked;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.xmlunit.matchers.CompareMatcher;

/**
 * Matcher for XMLs.
 * @since 0.10
 */
public final class IsXmlEqual extends TypeSafeMatcher<Path> {

    /**
     * Body matcher.
     */
    private final byte[] xml;

    /**
     * Ctor.
     * @param xml Xml bytes
     */
    public IsXmlEqual(final byte[] xml) {
        this.xml = xml;
    }

    /**
     * Ctor.
     * @param xml Xml string
     */
    public IsXmlEqual(final String xml) {
        this(xml.getBytes());
    }

    /**
     * Ctor.
     * @param xml Xml file
     */
    public IsXmlEqual(final Path xml) {
        this(new Unchecked<>(() -> Files.readAllBytes(xml)).value());
    }

    @Override
    public boolean matchesSafely(final Path item) {
        return CompareMatcher
            .isIdenticalTo(item.toFile())
            .ignoreWhitespace().normalizeWhitespace().matches(this.xml);
    }

    @Override
    public void describeTo(final Description description) {
        description.appendText(new String(this.xml, StandardCharsets.US_ASCII));
    }

    @Override
    public void describeMismatchSafely(final Path item, final Description mismatch) {
        mismatch.appendText(
            new Unchecked<>(
                () -> new String(Files.readAllBytes(item), StandardCharsets.US_ASCII)
            ).value()
        );
    }
}
