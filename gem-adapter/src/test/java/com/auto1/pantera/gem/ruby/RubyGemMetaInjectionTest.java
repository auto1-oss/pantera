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
package com.auto1.pantera.gem.ruby;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.jruby.Ruby;
import org.jruby.javasupport.JavaEmbedUtils;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test for the JRuby gem-path code injection in
 * {@link RubyGemMeta#info(Path)}.
 *
 * <p>Before 2.2.9 the method built Ruby source by string-interpolating the
 * gem file PATH: {@code String.format("Gem::Package.new('%s').spec", path)}.
 * The path's final segment is an attacker-influenced stored gem key, so a key
 * containing a single quote closed the string literal and injected arbitrary
 * Ruby — reachable unauthenticated through the global import route
 * (now separately gated) and executed in a full JRuby runtime = server-side
 * command execution.</p>
 *
 * <p>This test feeds {@code info} a path whose text injects Ruby that sets a
 * global marker. Under the vulnerable code the marker is assigned (proving
 * arbitrary Ruby ran); the fix must treat the whole path as an opaque data
 * string, so the marker stays unset even though parsing the bogus file
 * fails.</p>
 *
 * @since 2.2.9
 */
final class RubyGemMetaInjectionTest {

    @Test
    void gemPathIsNotEvaluatedAsRubySource() {
        final Ruby ruby = JavaEmbedUtils.initialize(Collections.emptyList());
        try {
            final RubyGemMeta meta = new RubyGemMeta(ruby);
            meta.initialize();
            // A gem "path" crafted so that, once interpolated into
            // "Gem::Package.new('%s').spec", it forms valid Ruby that closes
            // the constructor call, runs an assignment (the injected payload),
            // then re-opens a Package call for the trailing ".spec":
            //   Gem::Package.new('a') ; $PANTERA_RCE_MARKER = 42 ; Gem::Package.new('b').spec
            final Path malicious = Paths.get(
                "a') ; $PANTERA_RCE_MARKER = 42 ; Gem::Package.new('b"
            );
            try {
                meta.info(malicious);
            } catch (final RuntimeException ignored) {
                // Expected either way: the bogus path is not a real gem, so
                // spec parsing fails. What matters is whether the injected
                // assignment executed BEFORE the failure.
            }
            final boolean markerUnset = ruby.getGlobalVariables()
                .get("$PANTERA_RCE_MARKER").isNil();
            MatcherAssert.assertThat(
                "the gem path must be passed as data, never evaluated as Ruby source "
                    + "(injected global marker must remain unset)",
                markerUnset, new IsEqual<>(true)
            );
        } finally {
            JavaEmbedUtils.terminate(ruby);
        }
    }
}
