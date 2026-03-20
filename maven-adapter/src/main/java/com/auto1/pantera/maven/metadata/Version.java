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
package com.auto1.pantera.maven.metadata;

import org.apache.maven.artifact.versioning.ComparableVersion;
import java.util.Objects;

/**
 * Artifact version using Maven's official version comparison algorithm.
 * 
 * <p>Uses {@link ComparableVersion} which handles all Maven version formats:</p>
 * <ul>
 *   <li>Qualifiers: alpha, beta, milestone, rc, snapshot, ga, final, sp</li>
 *   <li>Mixed separators: dots and hyphens</li>
 *   <li>Character/digit transitions: 1.0alpha1 → [1, 0, alpha, 1]</li>
 *   <li>Unlimited version components</li>
 * </ul>
 * 
 * <p>This is the same algorithm used by Maven CLI for dependency resolution.</p>
 *
 * @since 0.5
 */
public final class Version implements Comparable<Version> {

    /**
     * Version value as string.
     */
    private final String value;

    /**
     * Cached ComparableVersion for efficient repeated comparisons.
     */
    private final ComparableVersion comparable;

    /**
     * Ctor.
     * @param value Version as string
     */
    public Version(final String value) {
        this.value = value;
        this.comparable = new ComparableVersion(value);
    }

    @Override
    public int compareTo(final Version another) {
        final Version other = Objects.requireNonNull(another, "another version");
        return this.comparable.compareTo(other.comparable);
    }

    @Override
    public String toString() {
        return this.value;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Version)) {
            return false;
        }
        final Version other = (Version) obj;
        return this.comparable.equals(other.comparable);
    }

    @Override
    public int hashCode() {
        return this.comparable.hashCode();
    }
}
