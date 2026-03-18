/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.backfill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link RepoConfigYaml}.
 *
 * @since 1.20.13
 */
final class RepoConfigYamlTest {

    /**
     * Happy path: a well-formed config file is parsed correctly.
     * Repo name is derived from the filename stem; rawType from repo.type.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file creation fails
     */
    @Test
    void parsesValidConfig(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("go.yaml");
        Files.writeString(file, "repo:\n  type: go\n");
        final RepoEntry entry = RepoConfigYaml.parse(file);
        MatcherAssert.assertThat(
            "repoName should be the filename stem",
            entry.repoName(),
            Matchers.is("go")
        );
        MatcherAssert.assertThat(
            "rawType should match repo.type in YAML",
            entry.rawType(),
            Matchers.is("go")
        );
    }

    /**
     * Proxy type is preserved as-is (normalisation is done by RepoTypeNormalizer).
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file creation fails
     */
    @Test
    void parsesProxyType(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("docker_proxy.yaml");
        Files.writeString(file, "repo:\n  type: docker-proxy\n");
        final RepoEntry entry = RepoConfigYaml.parse(file);
        MatcherAssert.assertThat(
            "rawType should be preserved without normalisation",
            entry.rawType(),
            Matchers.is("docker-proxy")
        );
        MatcherAssert.assertThat(
            "repoName should match filename stem",
            entry.repoName(),
            Matchers.is("docker_proxy")
        );
    }

    /**
     * Missing {@code repo.type} key must throw {@link IOException}.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file creation fails
     */
    @Test
    void throwsWhenRepoTypeMissing(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("bad.yaml");
        Files.writeString(file, "repo:\n  storage:\n    type: fs\n");
        Assertions.assertThrows(
            IOException.class,
            () -> RepoConfigYaml.parse(file),
            "Missing repo.type should throw IOException"
        );
    }

    /**
     * Malformed YAML (not parseable) must throw {@link IOException}.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file creation fails
     */
    @Test
    void throwsOnMalformedYaml(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("broken.yaml");
        Files.writeString(file, "repo: [\nunclosed bracket\n");
        Assertions.assertThrows(
            IOException.class,
            () -> RepoConfigYaml.parse(file),
            "Malformed YAML should throw IOException"
        );
    }

    /**
     * Empty YAML file must throw {@link IOException}.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file creation fails
     */
    @Test
    void throwsOnEmptyFile(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("empty.yaml");
        Files.writeString(file, "");
        Assertions.assertThrows(
            IOException.class,
            () -> RepoConfigYaml.parse(file),
            "Empty YAML should throw IOException"
        );
    }

    /**
     * YAML with additional fields alongside repo.type parses without error.
     *
     * @param tmp JUnit temp directory
     * @throws IOException if file creation fails
     */
    @Test
    void toleratesExtraFields(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("npm.yaml");
        Files.writeString(
            file,
            "repo:\n  type: npm\n  url: http://example.com\n  storage:\n    type: fs\n    path: /data\n"
        );
        final RepoEntry entry = RepoConfigYaml.parse(file);
        MatcherAssert.assertThat(entry.rawType(), Matchers.is("npm"));
    }
}
