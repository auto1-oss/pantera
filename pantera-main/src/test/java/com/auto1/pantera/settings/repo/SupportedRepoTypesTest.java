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
package com.auto1.pantera.settings.repo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link SupportedRepoTypes}.
 * @since 2.2.9
 */
final class SupportedRepoTypesTest {

    @Test
    void matchesTheTypesRepositorySlicesWires() throws Exception {
        // Drift guard: the API accepts exactly the types the wiring switch
        // can serve -- a type added there must be added here, and back.
        final String source = Files.readString(
            Path.of("src/main/java/com/auto1/pantera/RepositorySlices.java")
        );
        final Matcher cases = Pattern.compile("\\n {12}case \"([a-z0-9-]+)\":").matcher(source);
        final Set<String> wired = new TreeSet<>();
        while (cases.find()) {
            wired.add(cases.group(1));
        }
        MatcherAssert.assertThat(new SupportedRepoTypes().all(), new IsEqual<>(wired));
    }

    @Test
    void refusesUnknownType() {
        MatcherAssert.assertThat(new SupportedRepoTypes().contains("binary"), new IsEqual<>(false));
    }
}
