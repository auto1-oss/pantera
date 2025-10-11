/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer.cli;

import java.nio.file.Path;
import java.util.Optional;

final class MetadataHeuristics {

    private MetadataHeuristics() {
    }

    static ArtifactMetadata extract(final String repoType, final Path relative, final Path file) {
        return switch (repoType) {
            case "maven" -> maven(relative);
            case "gradle" -> gradle(relative);
            case "npm" -> npm(relative);
            case "pypi" -> pypi(relative);
            case "nuget" -> nuget(relative);
            case "go" -> go(relative);
            default -> generic(file);
        };
    }

    private static ArtifactMetadata maven(final Path relative) {
        if (relative.getNameCount() >= 3) {
            final String version = relative.getName(relative.getNameCount() - 2).toString();
            final String artifact = relative.getName(relative.getNameCount() - 3).toString();
            return ArtifactMetadata.of(artifact, version);
        }
        return ArtifactMetadata.unknown();
    }

    private static ArtifactMetadata gradle(final Path relative) {
        return maven(relative);
    }

    private static ArtifactMetadata npm(final Path relative) {
        final String filename = relative.getFileName().toString();
        final int dash = filename.lastIndexOf('-');
        final int dot = filename.lastIndexOf('.');
        if (dash > 0 && dot > dash) {
            final String version = filename.substring(dash + 1, dot);
            final String name = filename.substring(0, dash);
            return ArtifactMetadata.of(name, version);
        }
        return ArtifactMetadata.unknown();
    }

    private static ArtifactMetadata pypi(final Path relative) {
        if (relative.getNameCount() >= 3) {
            final String version = relative.getName(relative.getNameCount() - 2).toString();
            final String name = relative.getName(relative.getNameCount() - 3).toString();
            return ArtifactMetadata.of(name, version);
        }
        return ArtifactMetadata.unknown();
    }

    private static ArtifactMetadata nuget(final Path relative) {
        final String filename = relative.getFileName().toString();
        final int dot = filename.indexOf('.');
        if (dot > 0) {
            final String prefix = filename.substring(0, dot);
            final int last = prefix.lastIndexOf('.');
            if (last > 0) {
                final String version = prefix.substring(last + 1);
                final String name = prefix.substring(0, last);
                return ArtifactMetadata.of(name, version);
            }
        }
        return ArtifactMetadata.unknown();
    }

    private static ArtifactMetadata go(final Path relative) {
        if (relative.getNameCount() >= 2) {
            final String version = relative.getName(relative.getNameCount() - 1).toString();
            final String name = relative.getName(relative.getNameCount() - 2).toString();
            return ArtifactMetadata.of(name, version);
        }
        return ArtifactMetadata.unknown();
    }

    private static ArtifactMetadata generic(final Path file) {
        final String name = file.getFileName().toString();
        return ArtifactMetadata.of(name, "");
    }
}
