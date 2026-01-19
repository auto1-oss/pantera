/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.gradle.http;

/**
 * Builds paths for Gradle artifacts.
 *
 * @since 1.0
 */
final class GradlePathBuilder {

    private GradlePathBuilder() {
    }

    static String modulePath(final String artifact, final String version) {
        return artifactPath(artifact, version, "module");
    }

    static String pomPath(final String artifact, final String version) {
        return artifactPath(artifact, version, "pom");
    }

    static String jarPath(final String artifact, final String version) {
        return artifactPath(artifact, version, "jar");
    }

    static String artifactPath(final String artifact, final String version, final String ext) {
        final int idx = artifact.lastIndexOf('.');
        final String group;
        final String name;
        if (idx == -1) {
            group = "";
            name = artifact;
        } else {
            group = artifact.substring(0, idx).replace('.', '/');
            name = artifact.substring(idx + 1);
        }
        final StringBuilder path = new StringBuilder();
        path.append('/');
        if (!group.isEmpty()) {
            path.append(group).append('/');
        }
        path.append(name).append('/').append(version).append('/').append(name)
            .append('-').append(version).append('.').append(ext);
        return path.toString();
    }
}
