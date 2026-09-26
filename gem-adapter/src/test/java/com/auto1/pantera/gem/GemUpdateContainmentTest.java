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
package com.auto1.pantera.gem;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.jruby.Ruby;
import org.jruby.javasupport.JavaEmbedUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exploit-regression test for the Gem indexer writing an uploaded gem to a
 * spec-controlled filename without containment.
 *
 * <p>{@code Gem#update} renames the uploaded blob to
 * {@code <name>-<version>.gem} inside the indexing temp dir, where name and
 * version come from the gem's own YAML spec. RubyGems validates spec names
 * only when BUILDING a gem, not when reading one, so an uploader who builds
 * with validation skipped ships a spec named {@code ../../<x>}; before 2.2.9
 * {@code Files.move} then resolved that straight out of the temp dir and
 * wrote the gem to a host path of the uploader's choosing.</p>
 *
 * <p>This test builds exactly such a gem through a throwaway JRuby runtime
 * ({@code Gem::Package#build(skip_validation=true)}) and asserts the file is
 * never written outside the indexing directory: under the vulnerable code
 * it lands at {@code java.io.tmpdir/<x>-1.0.0.gem}.</p>
 *
 * @since 2.2.9
 */
final class GemUpdateContainmentTest {

    @Test
    void traversalGemNameCannotEscapeTheIndexingDirectory(@TempDir final Path work)
        throws Exception {
        final String marker = "pantera-escape-" + UUID.randomUUID();
        final Path escaped = Paths.get(System.getProperty("java.io.tmpdir"))
            .resolve(marker + "-1.0.0.gem");
        Files.deleteIfExists(escaped);
        final Path crafted = work.resolve("crafted.gem");
        buildGemNamed("../../" + marker, crafted);

        final Storage repo = new InMemoryStorage();
        final Key target = new Key.From("gems", UUID.randomUUID().toString());
        new BlockingStorage(repo).save(target, Files.readAllBytes(crafted));
        try {
            new Gem(repo).update(target).toCompletableFuture().join();
        } catch (final CompletionException rejected) {
            // A rejected update is the expected fail-closed outcome; what
            // must never happen is the escaped write asserted below.
        }
        try {
            MatcherAssert.assertThat(
                "a gem whose spec name traverses out of the indexing directory "
                    + "must never be written to a host path outside it",
                Files.exists(escaped), new IsEqual<>(false)
            );
        } finally {
            Files.deleteIfExists(escaped);
        }
    }

    /**
     * Build a gem whose spec carries the given (unvalidated) name, the way an
     * uploader who skips {@code spec.validate} would.
     *
     * @param name Spec name to embed
     * @param out Output gem path
     */
    private static void buildGemNamed(final String name, final Path out) {
        final Ruby ruby = JavaEmbedUtils.initialize(Collections.emptyList());
        try {
            ruby.getGlobalVariables().set(
                "$PANTERA_TEST_NAME", JavaEmbedUtils.javaToRuby(ruby, name)
            );
            ruby.getGlobalVariables().set(
                "$PANTERA_TEST_OUT", JavaEmbedUtils.javaToRuby(ruby, out.toString())
            );
            JavaEmbedUtils.newRuntimeAdapter().eval(
                ruby,
                String.join(
                    "\n",
                    "require 'rubygems/package'",
                    "spec = Gem::Specification.new do |s|",
                    "  s.name = $PANTERA_TEST_NAME",
                    "  s.version = '1.0.0'",
                    "  s.summary = 'crafted'",
                    "  s.description = 'crafted'",
                    "  s.authors = ['crafted']",
                    "  s.licenses = ['MIT']",
                    "  s.homepage = 'https://example.invalid'",
                    "  s.files = []",
                    "end",
                    "pkg = Gem::Package.new($PANTERA_TEST_OUT)",
                    "pkg.spec = spec",
                    "pkg.build(true)"
                )
            );
        } finally {
            JavaEmbedUtils.terminate(ruby);
        }
    }
}
