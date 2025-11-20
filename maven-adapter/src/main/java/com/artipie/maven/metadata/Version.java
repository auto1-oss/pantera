/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven.metadata;

import com.artipie.http.log.EcsLogger;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import java.util.Objects;

/**
 * Artifact version.
 * @since 0.5
 */
public final class Version implements Comparable<Version> {

    /**
     * Version value as string.
     */
    private final String value;

    /**
     * Ctor.
     * @param value Version as string
     */
    public Version(final String value) {
        this.value = value;
    }

    @Override
    public int compareTo(final Version another) {
        final Version other = Objects.requireNonNull(another, "another version");
        try {
            // Try to parse as semantic version first
            return new Semver(this.value, Semver.SemverType.LOOSE)
                .compareTo(new Semver(other.value, Semver.SemverType.LOOSE));
        } catch (final SemverException ex) {
            // Fall back to string comparison for non-semver versions (common in Maven)
            EcsLogger.debug("com.artipie.maven")
                .message("Failed to compare versions as semver, falling back to string comparison (" + this.value + " vs " + other.value + ")")
                .eventCategory("repository")
                .eventAction("version_comparison")
                .field("error.message", ex.getMessage())
                .log();
            return this.value.compareTo(other.value);
        }
    }

}
