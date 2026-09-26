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
import java.util.Collections;
import org.apache.commons.lang3.tuple.Pair;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.jruby.Ruby;
import org.jruby.javasupport.JavaEmbedUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A gemspec only needs name, version, summary, authors and files: gems
 * without {@code homepage} or {@code description} (both optional in
 * RubyGems) must index instead of failing with a JRuby TypeError.
 *
 * @since 2.2.9
 */
final class GemMinimalSpecTest {

    @Test
    void indexesGemWithoutHomepageAndDescription(@TempDir final Path work) throws Exception {
        final Path gem = work.resolve("minimal.gem");
        GemMinimalSpecTest.buildMinimalGem(gem);
        final Storage repo = new InMemoryStorage();
        final Key target = new Key.From("gems", "upload.gem");
        new BlockingStorage(repo).save(target, Files.readAllBytes(gem));
        final Pair<String, String> res = new Gem(repo).update(target)
            .toCompletableFuture().join();
        MatcherAssert.assertThat(
            "gem indexed",
            new BlockingStorage(repo).exists(new Key.From("gems", "minimal-1.0.0.gem")),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat("name", res.getKey(), new IsEqual<>("minimal"));
    }

    /**
     * Build a gem with only the mandatory spec fields.
     * @param out Output gem path
     */
    static void buildMinimalGem(final Path out) {
        final Ruby ruby = JavaEmbedUtils.initialize(Collections.emptyList());
        try {
            ruby.getGlobalVariables().set(
                "$PANTERA_TEST_OUT", JavaEmbedUtils.javaToRuby(ruby, out.toString())
            );
            JavaEmbedUtils.newRuntimeAdapter().eval(
                ruby,
                String.join(
                    "\n",
                    "require 'rubygems/package'",
                    "spec = Gem::Specification.new do |s|",
                    "  s.name = 'minimal'",
                    "  s.version = '1.0.0'",
                    "  s.summary = 'minimal'",
                    "  s.authors = ['someone']",
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
