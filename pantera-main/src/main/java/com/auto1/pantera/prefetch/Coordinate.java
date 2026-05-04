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
package com.auto1.pantera.prefetch;

/**
 * Identifies a prefetchable artifact within a single ecosystem.
 *
 * <p>Use the static factories {@link #maven(String, String, String)} or
 * {@link #npm(String, String)} to construct instances; the {@link #path()}
 * method renders the canonical upstream path for the coordinate.</p>
 *
 * @param ecosystem        Ecosystem this coordinate belongs to.
 * @param groupOrNamespace Maven {@code groupId} or npm scope (e.g. {@code "@types"}); empty for unscoped npm.
 * @param name             Maven {@code artifactId} or npm package name.
 * @param version          Artifact version.
 * @since 2.2.0
 */
public record Coordinate(
    Ecosystem ecosystem,
    String groupOrNamespace,
    String name,
    String version
) {

    /**
     * Supported ecosystems for prefetch.
     */
    public enum Ecosystem {
        /**
         * Maven / Gradle artifact.
         */
        MAVEN,
        /**
         * npm package (scoped or unscoped).
         */
        NPM
    }

    /**
     * Build a Maven coordinate.
     *
     * @param groupId    Maven groupId, e.g. {@code "com.google.guava"}.
     * @param artifactId Maven artifactId, e.g. {@code "guava"}.
     * @param version    Maven version, e.g. {@code "33.5.0-jre"}.
     * @return Maven coordinate.
     */
    public static Coordinate maven(final String groupId, final String artifactId, final String version) {
        return new Coordinate(Ecosystem.MAVEN, groupId, artifactId, version);
    }

    /**
     * Build an npm coordinate. Scoped names (starting with {@code @}) are
     * split into scope and bare name; unscoped names use an empty scope.
     *
     * @param packageName npm package name, e.g. {@code "@types/node"} or {@code "react"}.
     * @param version     npm version, e.g. {@code "20.10.0"}.
     * @return npm coordinate.
     */
    public static Coordinate npm(final String packageName, final String version) {
        final String scope;
        final String bare;
        if (packageName.startsWith("@") && packageName.contains("/")) {
            final int slash = packageName.indexOf('/');
            scope = packageName.substring(0, slash);
            bare = packageName.substring(slash + 1);
        } else {
            scope = "";
            bare = packageName;
        }
        return new Coordinate(Ecosystem.NPM, scope, bare, version);
    }

    /**
     * Render the canonical upstream path for this coordinate.
     *
     * <ul>
     *   <li>Maven: {@code group/with/slashes/artifactId/version/artifactId-version.jar}</li>
     *   <li>npm scoped: {@code @scope/name/-/name-version.tgz}</li>
     *   <li>npm unscoped: {@code name/-/name-version.tgz}</li>
     * </ul>
     *
     * @return Path (no leading slash).
     */
    public String path() {
        return switch (this.ecosystem) {
            case MAVEN -> this.groupOrNamespace.replace('.', '/')
                + "/" + this.name
                + "/" + this.version
                + "/" + this.name + "-" + this.version + ".jar";
            case NPM -> {
                final String prefix = this.groupOrNamespace.isEmpty()
                    ? ""
                    : this.groupOrNamespace + "/";
                yield prefix + this.name + "/-/" + this.name + "-" + this.version + ".tgz";
            }
        };
    }
}
