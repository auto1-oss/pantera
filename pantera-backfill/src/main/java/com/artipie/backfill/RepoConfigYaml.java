/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.backfill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Parses one Artipie YAML repo config file into a {@link RepoEntry}.
 *
 * <p>Expected minimal YAML structure:
 * <pre>
 * repo:
 *   type: docker
 * </pre>
 * Additional fields (storage, remotes, url, etc.) are ignored.
 * </p>
 *
 * @since 1.20.13
 */
final class RepoConfigYaml {

    /**
     * Private ctor — utility class, not instantiable.
     */
    private RepoConfigYaml() {
    }

    /**
     * Parse a single {@code .yaml} Artipie repo config file.
     *
     * @param file Path to the {@code .yaml} file
     * @return Parsed {@link RepoEntry} with repo name (filename stem) and raw type
     * @throws IOException if the file is unreadable, YAML is malformed,
     *     or {@code repo.type} is missing
     */
    @SuppressWarnings("unchecked")
    static RepoEntry parse(final Path file) throws IOException {
        final String filename = file.getFileName().toString();
        final String repoName;
        if (filename.endsWith(".yaml")) {
            repoName = filename.substring(0, filename.length() - ".yaml".length());
        } else {
            repoName = filename;
        }
        final Map<String, Object> doc;
        try (InputStream in = Files.newInputStream(file)) {
            doc = new Yaml().load(in);
        } catch (final Exception ex) {
            throw new IOException(
                String.format("Failed to parse YAML in '%s': %s", filename, ex.getMessage()),
                ex
            );
        }
        if (doc == null) {
            throw new IOException(
                String.format("Empty YAML file: '%s'", filename)
            );
        }
        final Object repoObj = doc.get("repo");
        if (!(repoObj instanceof Map)) {
            throw new IOException(
                String.format("Missing or invalid 'repo' key in '%s'", filename)
            );
        }
        final Map<String, Object> repo = (Map<String, Object>) repoObj;
        final Object typeObj = repo.get("type");
        if (typeObj == null) {
            throw new IOException(
                String.format("Missing 'repo.type' in '%s'", filename)
            );
        }
        return new RepoEntry(repoName, typeObj.toString());
    }
}
