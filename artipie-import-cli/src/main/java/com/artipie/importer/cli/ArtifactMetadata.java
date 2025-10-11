/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer.cli;

import java.util.Optional;

final class ArtifactMetadata {

    private final Optional<String> artifact;
    private final Optional<String> version;
    private final Optional<Long> release;

    ArtifactMetadata(final Optional<String> artifact, final Optional<String> version, final Optional<Long> release) {
        this.artifact = artifact;
        this.version = version;
        this.release = release;
    }

    static ArtifactMetadata of(final String artifact, final String version) {
        return new ArtifactMetadata(Optional.ofNullable(artifact), Optional.ofNullable(version), Optional.empty());
    }

    static ArtifactMetadata unknown() {
        return new ArtifactMetadata(Optional.empty(), Optional.empty(), Optional.empty());
    }

    ArtifactMetadata withRelease(final Long release) {
        return new ArtifactMetadata(this.artifact, this.version, Optional.ofNullable(release));
    }

    Optional<String> artifact() {
        return this.artifact;
    }

    Optional<String> version() {
        return this.version;
    }

    Optional<Long> release() {
        return this.release;
    }
}
