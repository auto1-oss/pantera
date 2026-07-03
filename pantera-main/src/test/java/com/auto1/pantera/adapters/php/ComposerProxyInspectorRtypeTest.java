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
package com.auto1.pantera.adapters.php;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Static-source regression check: {@code ComposerProxy} (the production
 * wiring in pantera-main) must not construct the admission-gate
 * {@code RegistryBackedInspector} with a hardcoded string literal as the
 * repository-type argument. The value must flow from {@code cfg.type()}
 * so cooldown lookups key against the same {@code artifact_publish_dates}
 * rows that {@code DbConsumer} writes.
 *
 * <p>Convenience constructors inside {@code composer-adapter}
 * ({@code CachedProxySlice} / {@code ComposerProxySlice}) intentionally
 * hardcode rtype="composer"/"php" for tests that use
 * {@code NoopCooldownService}; the inspector mismatch there is dormant
 * because the inspector is never invoked.
 *
 * @since 2.2.0
 */
final class ComposerProxyInspectorRtypeTest {

    private static final Pattern HARDCODED_LITERAL_FIRST_ARG = Pattern.compile(
        "new\\s+(?:com\\.auto1\\.pantera\\.publishdate\\.)?RegistryBackedInspector\\s*\\(\\s*\"[^\"]+\""
    );

    @Test
    void registryBackedInspectorFirstArgIsNotAHardcodedLiteral() throws Exception {
        final Path source = Path.of(
            "src/main/java/com/auto1/pantera/adapters/php/ComposerProxy.java"
        );
        final String content = Files.readString(source, StandardCharsets.UTF_8);
        MatcherAssert.assertThat(
            "ComposerProxy must construct RegistryBackedInspector with a "
                + "variable (cfg.type()), never a hardcoded literal",
            HARDCODED_LITERAL_FIRST_ARG.matcher(content).find(),
            new IsEqual<>(false)
        );
    }
}
