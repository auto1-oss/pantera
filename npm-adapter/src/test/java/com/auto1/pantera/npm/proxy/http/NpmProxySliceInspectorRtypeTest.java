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
package com.auto1.pantera.npm.proxy.http;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Static-source regression check: {@code NpmProxySlice} must not construct
 * its admission-gate {@code RegistryBackedInspector} with a hardcoded
 * string literal as the repository-type argument. The repository-type
 * value must flow from the slice's {@code repoType} constructor parameter
 * so cooldown lookups key against the same {@code artifact_publish_dates}
 * rows that {@code DbConsumer} writes.
 *
 * <p>Behavioural slice-level tests for npm are awkward because tarball
 * admission flows through {@code DownloadAssetSlice} which needs a real
 * {@code NpmProxy}. The source-check captures the contract directly:
 * the inspector's first argument must be a variable, not a literal.
 *
 * @since 2.2.0
 */
final class NpmProxySliceInspectorRtypeTest {

    private static final Pattern HARDCODED_LITERAL_FIRST_ARG = Pattern.compile(
        "new\\s+RegistryBackedInspector\\s*\\(\\s*\"[^\"]+\""
    );

    @Test
    void registryBackedInspectorFirstArgIsNotAHardcodedLiteral() throws Exception {
        final Path source = Path.of(
            "src/main/java/com/auto1/pantera/npm/proxy/http/NpmProxySlice.java"
        );
        final String content = Files.readString(source, StandardCharsets.UTF_8);
        MatcherAssert.assertThat(
            "NpmProxySlice must construct RegistryBackedInspector with a "
                + "variable (repoType), never a hardcoded literal",
            HARDCODED_LITERAL_FIRST_ARG.matcher(content).find(),
            new IsEqual<>(false)
        );
    }
}
