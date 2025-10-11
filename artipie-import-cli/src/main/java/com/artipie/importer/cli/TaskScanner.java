/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer.cli;

import com.artipie.importer.api.ChecksumPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class TaskScanner {

    private static final Map<String, String> TYPE_ALIASES = new HashMap<>();

    static {
        TYPE_ALIASES.put("MAVEN", "maven");
        TYPE_ALIASES.put("GRADLE", "gradle");
        TYPE_ALIASES.put("NPM", "npm");
        TYPE_ALIASES.put("PYPI", "pypi");
        TYPE_ALIASES.put("NUGET", "nuget");
        TYPE_ALIASES.put("DOCKER", "docker");
        TYPE_ALIASES.put("OCI", "docker");
        TYPE_ALIASES.put("FILES", "file");
        TYPE_ALIASES.put("GENERIC", "file");
        TYPE_ALIASES.put("COMPOSER", "php");
        TYPE_ALIASES.put("GEM", "gem");
        TYPE_ALIASES.put("GO", "go");
        TYPE_ALIASES.put("DEBIAN", "deb");
        TYPE_ALIASES.put("RPM", "rpm");
        TYPE_ALIASES.put("HELM", "helm");
    }

    private final Path root;
    private final String owner;
    private final ChecksumPolicy policy;

    TaskScanner(final Path root, final String owner, final ChecksumPolicy policy) {
        this.root = root;
        this.owner = owner;
        this.policy = policy;
    }

    Optional<UploadTask> analyze(final Path file) {
        final Path relative;
        try {
            relative = this.root.relativize(file);
        } catch (final IllegalArgumentException ignored) {
            return Optional.empty();
        }
        if (relative.getNameCount() < 3) {
            return Optional.empty();
        }
        final String top = relative.getName(0).toString();
        final String repoType = TYPE_ALIASES.getOrDefault(top.toUpperCase(Locale.ROOT), top.toLowerCase(Locale.ROOT));
        final String repoName = relative.getName(1).toString();
        if (repoName.isBlank()) {
            return Optional.empty();
        }
        final Path repoRelative = relative.subpath(2, relative.getNameCount());
        final ArtifactMetadata metadata = MetadataHeuristics.extract(repoType, repoRelative, file);
        try {
            final long size = Files.size(file);
            final FileTime time = Files.getLastModifiedTime(file);
            return Optional.of(new UploadTask(file, repoType, repoName, repoRelative, metadata, size, time.toMillis(), this.policy));
        } catch (final IOException err) {
            return Optional.empty();
        }
    }
}
