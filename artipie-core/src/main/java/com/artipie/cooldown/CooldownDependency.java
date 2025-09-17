/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown;

import java.util.Objects;

/**
 * Dependency that was blocked together with the main artifact.
 */
public final class CooldownDependency {

    private final String artifact;
    private final String version;

    public CooldownDependency(final String artifact, final String version) {
        this.artifact = Objects.requireNonNull(artifact);
        this.version = Objects.requireNonNull(version);
    }

    public String artifact() {
        return this.artifact;
    }

    public String version() {
        return this.version;
    }
}
