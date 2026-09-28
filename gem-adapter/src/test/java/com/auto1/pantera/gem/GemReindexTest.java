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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.jruby.Ruby;
import org.jruby.RubyString;
import org.jruby.javasupport.JavaEmbedUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link Gem#reindex()}: after gems are deleted from storage the
 * index stops listing them.
 *
 * @since 2.2.9
 */
final class GemReindexTest {

    /**
     * Built gems: qa-1.0.0, qa-2.0.0, qa-3.0.0.pre and other-1.0.0.
     */
    private static byte[][] gems;

    /**
     * Ruby runtime building the gems and decoding the (trusted, test-made)
     * Marshal indexes.
     */
    private static Ruby ruby;

    @BeforeAll
    static void build(@TempDir final Path work) throws IOException {
        final String[][] specs = {
            {"qa", "1.0.0"}, {"qa", "2.0.0"}, {"qa", "3.0.0.pre"}, {"other", "1.0.0"},
        };
        GemReindexTest.gems = new byte[specs.length][];
        GemReindexTest.ruby = JavaEmbedUtils.initialize(Collections.emptyList());
        for (int idx = 0; idx < specs.length; idx += 1) {
            final Path out = work.resolve(idx + ".gem");
            GemReindexTest.buildGem(GemReindexTest.ruby, specs[idx][0], specs[idx][1], out);
            GemReindexTest.gems[idx] = Files.readAllBytes(out);
        }
    }

    @AfterAll
    static void stop() {
        JavaEmbedUtils.terminate(GemReindexTest.ruby);
    }

    @Test
    void deletingTheLatestVersionFallsBackToTheNextHighest() throws Exception {
        final Storage repo = GemReindexTest.uploadAll();
        final BlockingStorage blocking = new BlockingStorage(repo);
        blocking.delete(new Key.From("gems", "qa-2.0.0.gem"));
        new Gem(repo).reindex().toCompletableFuture().join();
        final String latest = GemReindexTest.text(blocking.value(new Key.From("latest_specs.4.8")));
        MatcherAssert.assertThat(
            "latest_specs falls back to the next highest version",
            latest, new IsEqual<>("other-1.0.0,qa-1.0.0")
        );
        MatcherAssert.assertThat(
            "latest_specs.gz matches latest_specs",
            GemReindexTest.gunzip(blocking.value(new Key.From("latest_specs.4.8.gz"))),
            new IsEqual<>(latest)
        );
        final String specs = GemReindexTest.text(blocking.value(new Key.From("specs.4.8")));
        MatcherAssert.assertThat(
            "specs no longer lists the deleted version",
            specs, new IsEqual<>("other-1.0.0,qa-1.0.0")
        );
        MatcherAssert.assertThat(
            "specs.gz matches specs",
            GemReindexTest.gunzip(blocking.value(new Key.From("specs.4.8.gz"))),
            new IsEqual<>(specs)
        );
        MatcherAssert.assertThat(
            "the deleted version's quick spec is removed",
            blocking.exists(new Key.From("quick", "Marshal.4.8", "qa-2.0.0.gemspec.rz")),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the remaining versions' quick specs are kept",
            blocking.exists(new Key.From("quick", "Marshal.4.8", "qa-1.0.0.gemspec.rz")),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the other gems are not touched",
            blocking.exists(new Key.From("gems", "other-1.0.0.gem")),
            new IsEqual<>(true)
        );
    }

    @Test
    void deletingAPrereleaseDropsItFromPrereleaseSpecs() throws Exception {
        final Storage repo = GemReindexTest.uploadAll();
        final BlockingStorage blocking = new BlockingStorage(repo);
        MatcherAssert.assertThat(
            "precondition: the prerelease is listed",
            GemReindexTest.text(blocking.value(new Key.From("prerelease_specs.4.8"))),
            new IsEqual<>("qa-3.0.0.pre")
        );
        blocking.delete(new Key.From("gems", "qa-3.0.0.pre.gem"));
        new Gem(repo).reindex().toCompletableFuture().join();
        MatcherAssert.assertThat(
            "prerelease_specs no longer lists the deleted prerelease",
            GemReindexTest.text(blocking.value(new Key.From("prerelease_specs.4.8"))),
            new IsEqual<>("")
        );
        MatcherAssert.assertThat(
            "the prerelease quick spec is removed",
            blocking.exists(new Key.From("quick", "Marshal.4.8", "qa-3.0.0.pre.gemspec.rz")),
            new IsEqual<>(false)
        );
    }

    @Test
    void deletingEveryGemLeavesAnEmptyIndex() throws Exception {
        final Storage repo = GemReindexTest.uploadAll();
        final BlockingStorage blocking = new BlockingStorage(repo);
        for (final Key key : blocking.list(new Key.From("gems"))) {
            blocking.delete(key);
        }
        new Gem(repo).reindex().toCompletableFuture().join();
        final String specs = GemReindexTest.text(blocking.value(new Key.From("specs.4.8")));
        MatcherAssert.assertThat("specs lists no gem", specs, new IsEqual<>(""));
        MatcherAssert.assertThat(
            "no quick spec is left",
            blocking.list(new Key.From("quick")).isEmpty(), new IsEqual<>(true)
        );
    }

    /**
     * Upload every built gem through {@link Gem#update}.
     * @return Storage
     */
    private static Storage uploadAll() {
        final Storage repo = new InMemoryStorage();
        final BlockingStorage blocking = new BlockingStorage(repo);
        for (final byte[] gem : GemReindexTest.gems) {
            final Key key = new Key.From(
                "gems", UUID.randomUUID().toString().replace("-", "") + ".gem"
            );
            blocking.save(key, gem);
            new Gem(repo).update(key).toCompletableFuture().join();
            blocking.delete(key);
        }
        return repo;
    }

    /**
     * Decode a Marshal spec index into sorted {@code name-version} entries.
     * @param bytes Index bytes
     * @return Entries joined with commas
     */
    private static String text(final byte[] bytes) {
        GemReindexTest.ruby.getGlobalVariables().set(
            "$PANTERA_TEST_INDEX",
            RubyString.newString(GemReindexTest.ruby, bytes)
        );
        return JavaEmbedUtils.newRuntimeAdapter().eval(
            GemReindexTest.ruby,
            String.join(
                "\n",
                "require 'rubygems'",
                "Marshal.load($PANTERA_TEST_INDEX.force_encoding('BINARY'))",
                "  .map { |n, v, _p| \"#{n}-#{v}\" }.sort.join(',')"
            )
        ).toString();
    }

    /**
     * Gunzip bytes as text.
     * @param bytes Gzipped bytes
     * @return Text
     * @throws IOException On error
     */
    private static String gunzip(final byte[] bytes) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return GemReindexTest.text(in.readAllBytes());
        }
    }

    /**
     * Build a gem.
     * @param ruby Runtime
     * @param name Gem name
     * @param version Gem version
     * @param out Output path
     */
    private static void buildGem(
        final Ruby ruby, final String name, final String version, final Path out
    ) {
        ruby.getGlobalVariables().set("$PANTERA_TEST_NAME", JavaEmbedUtils.javaToRuby(ruby, name));
        ruby.getGlobalVariables().set(
            "$PANTERA_TEST_VERSION", JavaEmbedUtils.javaToRuby(ruby, version)
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
                "  s.version = $PANTERA_TEST_VERSION",
                "  s.summary = 'qa'",
                "  s.description = 'qa'",
                "  s.authors = ['qa']",
                "  s.licenses = ['MIT']",
                "  s.homepage = 'https://example.invalid'",
                "  s.files = []",
                "end",
                "pkg = Gem::Package.new($PANTERA_TEST_OUT)",
                "pkg.spec = spec",
                "pkg.build"
            )
        );
    }
}
