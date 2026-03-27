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
package com.auto1.pantera.group;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the artifact name from a raw URL path based on the repository type.
 * Each adapter stores artifacts with a specific {@code name} format in the DB.
 * This parser reverses the URL path back to that format so GroupSlice can do
 * an indexed lookup via {@code WHERE name = ?} instead of expensive fan-out.
 *
 * @since 1.21.0
 */
public final class ArtifactNameParser {

    /**
     * Docker v2 path pattern: /v2/{name}/(manifests|blobs|tags)/...
     */
    private static final Pattern DOCKER_PATH =
        Pattern.compile("/v2/(.+?)/(manifests|blobs|tags)/.*");

    /**
     * Maven file extensions (artifact files, checksums, signatures, metadata).
     */
    private static final Pattern MAVEN_FILE_EXT = Pattern.compile(
        ".*\\.(jar|pom|xml|war|aar|ear|module|sha1|sha256|sha512|md5|asc|sig)$"
    );

    private ArtifactNameParser() {
    }

    /**
     * Parse artifact name from URL path based on repository type.
     *
     * @param repoType Repository type (e.g., "maven-group", "npm-proxy", "docker-group")
     * @param urlPath Raw URL path from HTTP request (may have leading slash)
     * @return Parsed artifact name matching the DB {@code name} column, or empty if unparseable
     */
    public static Optional<String> parse(final String repoType, final String urlPath) {
        if (repoType == null || urlPath == null || urlPath.isEmpty()) {
            return Optional.empty();
        }
        final String base = normalizeType(repoType);
        return switch (base) {
            case "maven", "gradle" -> parseMaven(urlPath);
            case "npm" -> parseNpm(urlPath);
            case "docker" -> parseDocker(urlPath);
            case "pypi" -> parsePypi(urlPath);
            case "go" -> parseGo(urlPath);
            case "gem" -> parseGem(urlPath);
            case "php" -> parseComposer(urlPath);
            case "helm" -> parseHelm(urlPath);
            case "debian", "apt" -> parseDebian(urlPath);
            case "hex" -> parseHex(urlPath);
            case "file", "raw" -> parseFile(urlPath);
            default -> Optional.empty();
        };
    }

    /**
     * Strip group/proxy/local suffix: "maven-group" -> "maven", "npm-proxy" -> "npm".
     */
    static String normalizeType(final String repoType) {
        return repoType.replaceAll("-(group|proxy|local|remote)$", "");
    }

    /**
     * Maven URL path to artifact name.
     * <p>
     * Maven URLs follow: {groupId-path}/{artifactId}/{version}/{filename}
     * DB name format: groupId.artifactId (slashes replaced with dots)
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code com/google/guava/guava/31.1/guava-31.1.jar} -> {@code com.google.guava.guava}</li>
     *   <li>{@code com/google/guava/guava/maven-metadata.xml} -> {@code com.google.guava.guava}</li>
     *   <li>{@code org/apache/maven/plugins/maven-compiler-plugin/3.11.0/maven-compiler-plugin-3.11.0.pom}
     *       -> {@code org.apache.maven.plugins.maven-compiler-plugin}</li>
     * </ul>
     */
    static Optional<String> parseMaven(final String urlPath) {
        final String clean = stripLeadingSlash(urlPath);
        final String[] segments = clean.split("/");
        if (segments.length < 2) {
            return Optional.empty();
        }
        int end = segments.length;
        // Strip filename if last segment looks like a file
        if (MAVEN_FILE_EXT.matcher(segments[end - 1]).matches()) {
            end--;
        }
        if (end < 1) {
            return Optional.empty();
        }
        // Strip version directory if it starts with a digit
        if (end > 1 && !segments[end - 1].isEmpty()
            && Character.isDigit(segments[end - 1].charAt(0))) {
            end--;
        }
        if (end < 1) {
            return Optional.empty();
        }
        // Join remaining segments with dots
        final StringBuilder name = new StringBuilder();
        for (int i = 0; i < end; i++) {
            if (i > 0) {
                name.append('.');
            }
            name.append(segments[i]);
        }
        final String result = name.toString();
        return result.isEmpty() ? Optional.empty() : Optional.of(result);
    }

    /**
     * npm URL path to package name.
     * <p>
     * npm URLs follow:
     * <ul>
     *   <li>{@code /lodash} -> {@code lodash} (metadata)</li>
     *   <li>{@code /lodash/-/lodash-4.17.21.tgz} -> {@code lodash} (tarball)</li>
     *   <li>{@code /@babel/core} -> {@code @babel/core} (scoped metadata)</li>
     *   <li>{@code /@babel/core/-/@babel/core-7.23.0.tgz} -> {@code @babel/core} (scoped tarball)</li>
     * </ul>
     */
    static Optional<String> parseNpm(final String urlPath) {
        final String clean = stripLeadingSlash(urlPath);
        if (clean.isEmpty()) {
            return Optional.empty();
        }
        // Tarball URLs contain /-/ separator
        final int sep = clean.indexOf("/-/");
        if (sep > 0) {
            return Optional.of(clean.substring(0, sep));
        }
        // Metadata URLs: the path IS the package name
        // Scoped: @scope/package (2 segments)
        // Unscoped: package (1 segment)
        if (clean.startsWith("@")) {
            // Scoped package: take first two segments
            final String[] parts = clean.split("/", 3);
            if (parts.length >= 2) {
                return Optional.of(parts[0] + "/" + parts[1]);
            }
            return Optional.empty();
        }
        // Unscoped: take first segment only
        final String[] parts = clean.split("/", 2);
        return Optional.of(parts[0]);
    }

    /**
     * Docker URL path to image name.
     * <p>
     * Docker URLs follow: /v2/{name}/(manifests|blobs|tags)/...
     * DB name format: the image name as-is (e.g., "library/nginx")
     */
    static Optional<String> parseDocker(final String urlPath) {
        final Matcher matcher = DOCKER_PATH.matcher(urlPath);
        if (matcher.matches()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    /**
     * PyPI URL path to package name.
     * <p>
     * PyPI URLs follow:
     * <ul>
     *   <li>{@code /simple/numpy/} -> {@code numpy}</li>
     *   <li>{@code /simple/my-package/} -> {@code my-package}</li>
     *   <li>{@code /packages/numpy-1.24.0.whl} -> {@code numpy}</li>
     *   <li>{@code /packages/my_package-1.0.0.tar.gz} -> {@code my-package} (normalized)</li>
     * </ul>
     * PyPI normalizes names: underscores, dots, and hyphens collapse to hyphens,
     * then lowercased.
     */
    static Optional<String> parsePypi(final String urlPath) {
        final String clean = stripLeadingSlash(urlPath);
        // /simple/{name}/ pattern
        if (clean.startsWith("simple/")) {
            final String rest = clean.substring("simple/".length());
            final String name = rest.endsWith("/")
                ? rest.substring(0, rest.length() - 1) : rest.split("/")[0];
            return name.isEmpty() ? Optional.empty()
                : Optional.of(normalizePypiName(name));
        }
        // /packages/{filename} pattern — extract name from filename
        if (clean.startsWith("packages/")) {
            final String filename = clean.substring("packages/".length());
            // Remove nested paths if any
            final String base = filename.contains("/")
                ? filename.substring(filename.lastIndexOf('/') + 1) : filename;
            return extractPypiNameFromFilename(base);
        }
        return Optional.empty();
    }

    /**
     * Go module URL path to module name.
     * <p>
     * Go URLs follow: /{module}/@v/{version}.{ext}
     * or /{module}/@latest
     */
    static Optional<String> parseGo(final String urlPath) {
        final String clean = stripLeadingSlash(urlPath);
        final int atv = clean.indexOf("/@v/");
        if (atv > 0) {
            return Optional.of(clean.substring(0, atv));
        }
        final int atl = clean.indexOf("/@latest");
        if (atl > 0) {
            return Optional.of(clean.substring(0, atl));
        }
        return Optional.empty();
    }

    /**
     * RubyGems URL path to gem name.
     * <p>
     * Gem URLs follow:
     * <ul>
     *   <li>{@code /gems/rails-7.1.2.gem} -> {@code rails}</li>
     *   <li>{@code /api/v1/dependencies?gems=rails} -> {@code rails}</li>
     *   <li>{@code /api/v1/gems/rails.json} -> {@code rails}</li>
     *   <li>{@code /quick/Marshal.4.8/rails-7.1.2.gemspec.rz} -> {@code rails}</li>
     * </ul>
     */
    static Optional<String> parseGem(final String urlPath) {
        final String clean = stripLeadingSlash(urlPath);
        // /gems/{name}-{version}.gem
        if (clean.startsWith("gems/")) {
            final String filename = clean.substring("gems/".length());
            return extractGemName(filename);
        }
        // /api/v1/dependencies?gems={name}
        if (clean.contains("dependencies")) {
            final int qmark = clean.indexOf("gems=");
            if (qmark >= 0) {
                final String names = clean.substring(qmark + "gems=".length());
                final String first = names.split(",")[0].trim();
                return first.isEmpty() ? Optional.empty() : Optional.of(first);
            }
        }
        // /api/v1/gems/{name}.json
        if (clean.startsWith("api/v1/gems/")) {
            final String rest = clean.substring("api/v1/gems/".length());
            if (rest.endsWith(".json")) {
                return Optional.of(rest.substring(0, rest.length() - ".json".length()));
            }
        }
        // /quick/Marshal.4.8/{name}-{version}.gemspec.rz
        if (clean.startsWith("quick/")) {
            final int lastSlash = clean.lastIndexOf('/');
            if (lastSlash >= 0) {
                return extractGemName(clean.substring(lastSlash + 1));
            }
        }
        return Optional.empty();
    }

    /**
     * Composer/PHP URL path to package name.
     * <p>
     * Composer URLs follow:
     * <ul>
     *   <li>{@code /p2/vendor/package.json} -> {@code vendor/package}</li>
     *   <li>{@code /p2/vendor/package$hash.json} -> {@code vendor/package}</li>
     *   <li>{@code /p/vendor/package.json} -> {@code vendor/package}</li>
     * </ul>
     */
    static Optional<String> parseComposer(final String urlPath) {
        final String clean = stripLeadingSlash(urlPath);
        // /p2/vendor/package.json or /p/vendor/package.json
        final Matcher matcher = Pattern.compile(
            "p2?/([^/]+)/([^/$]+)(?:\\$[a-f0-9]+)?\\.json$"
        ).matcher(clean);
        if (matcher.find()) {
            return Optional.of(matcher.group(1) + "/" + matcher.group(2));
        }
        return Optional.empty();
    }

    /**
     * Helm URL path to chart name.
     * <p>
     * Helm URLs follow:
     * <ul>
     *   <li>{@code /charts/nginx-1.2.3.tgz} -> {@code nginx}</li>
     *   <li>{@code /charts/my-chart-2.5.1.tgz.prov} -> {@code my-chart}</li>
     *   <li>{@code /index.yaml} -> empty (index metadata)</li>
     * </ul>
     */
    static Optional<String> parseHelm(final String urlPath) {
        final String clean = stripLeadingSlash(urlPath);
        if (!clean.startsWith("charts/")) {
            return Optional.empty();
        }
        String filename = clean.substring("charts/".length());
        // Strip .prov suffix if present
        if (filename.endsWith(".tgz.prov")) {
            filename = filename.substring(0, filename.length() - ".tgz.prov".length());
        } else if (filename.endsWith(".tgz")) {
            filename = filename.substring(0, filename.length() - ".tgz".length());
        } else {
            return Optional.empty();
        }
        // filename is now "{name}-{version}"; extract name before last "-{digit}"
        final Matcher m = Pattern.compile("^(.+)-\\d").matcher(filename);
        if (m.find()) {
            return Optional.of(m.group(1));
        }
        return Optional.of(filename);
    }

    /**
     * Debian URL path to package name.
     * <p>
     * Debian URLs follow:
     * <ul>
     *   <li>{@code /pool/main/n/nginx/nginx_1.18.0_amd64.deb} -> {@code nginx}</li>
     *   <li>{@code /dists/stable/Release} -> empty (distribution metadata)</li>
     * </ul>
     * Package name is the part before the first underscore in the filename.
     */
    static Optional<String> parseDebian(final String urlPath) {
        final String clean = stripLeadingSlash(urlPath);
        if (clean.startsWith("dists/")) {
            return Optional.empty();
        }
        if (!clean.startsWith("pool/")) {
            return Optional.empty();
        }
        final int lastSlash = clean.lastIndexOf('/');
        if (lastSlash < 0) {
            return Optional.empty();
        }
        final String filename = clean.substring(lastSlash + 1);
        if (!filename.endsWith(".deb")) {
            return Optional.empty();
        }
        // Debian filename format: {pkg}_{version}_{arch}.deb
        // DB stores: "{pkg}_{arch}" — matching UpdateSlice/DebianScanner formatName()
        final String withoutExt =
            filename.substring(0, filename.length() - ".deb".length());
        final String[] parts = withoutExt.split("_");
        if (parts.length < 2) {
            return Optional.empty();
        }
        final String pkg = parts[0];
        final String arch = parts[parts.length - 1];
        return Optional.of(pkg + "_" + arch);
    }

    /**
     * Hex (Elixir/Erlang) URL path to package name.
     * <p>
     * Hex URLs follow:
     * <ul>
     *   <li>{@code /api/packages/phoenix} -> {@code phoenix}</li>
     *   <li>{@code /api/packages/phoenix/releases/1.7.0} -> {@code phoenix}</li>
     *   <li>{@code /repo/tarballs/phoenix-1.7.0.tar} -> {@code phoenix}</li>
     *   <li>{@code /names} -> empty (registry metadata)</li>
     * </ul>
     */
    static Optional<String> parseHex(final String urlPath) {
        final String clean = stripLeadingSlash(urlPath);
        // /api/packages/{name}[/releases/{version}]
        if (clean.startsWith("api/packages/")) {
            final String rest = clean.substring("api/packages/".length());
            if (rest.isEmpty()) {
                return Optional.empty();
            }
            final String name = rest.split("/")[0];
            return name.isEmpty() ? Optional.empty() : Optional.of(name);
        }
        // /repo/tarballs/{name}-{version}.tar
        if (clean.startsWith("repo/tarballs/")) {
            String filename = clean.substring("repo/tarballs/".length());
            if (!filename.endsWith(".tar")) {
                return Optional.empty();
            }
            filename = filename.substring(0, filename.length() - ".tar".length());
            final Matcher m = Pattern.compile("^(.+)-\\d").matcher(filename);
            if (m.find()) {
                return Optional.of(m.group(1));
            }
            return Optional.of(filename);
        }
        // /repo/packages/{name}
        if (clean.startsWith("repo/packages/")) {
            final String name = clean.substring("repo/packages/".length());
            return name.isEmpty() ? Optional.empty() : Optional.of(name.split("/")[0]);
        }
        return Optional.empty();
    }

    /**
     * File/raw URL path to artifact name.
     * <p>
     * File repositories have no canonical artifact structure. The URL path itself
     * (stripped of the leading slash) is used directly as the artifact name,
     * matching how the file adapter stores artifacts in the DB.
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code /reports/2024/q1.pdf} -> {@code reports.2024.q1.pdf}</li>
     *   <li>{@code /artifacts/v1.0.0/myapp.zip} -> {@code artifacts.v1.0.0.myapp.zip}</li>
     *   <li>{@code /} -> empty (root path has no artifact meaning)</li>
     * </ul>
     * Slashes are converted to dots to match what {@code FileProxySlice} and
     * {@code FileScanner} store in the DB ({@code aname.replace('/', '.')}).
     */
    static Optional<String> parseFile(final String urlPath) {
        final String clean = stripLeadingSlash(urlPath);
        return clean.isEmpty() ? Optional.empty()
            : Optional.of(clean.replace('/', '.'));
    }

    /**
     * Extract gem name from a filename like "rails-7.1.2.gem" or "rails-7.1.2.gemspec.rz".
     * Name is everything before the last hyphen-followed-by-digit.
     */
    private static Optional<String> extractGemName(final String filename) {
        // Remove extensions
        String base = filename;
        if (base.endsWith(".gem")) {
            base = base.substring(0, base.length() - ".gem".length());
        } else if (base.endsWith(".gemspec.rz")) {
            base = base.substring(0, base.length() - ".gemspec.rz".length());
        } else {
            return Optional.empty();
        }
        // Name is everything before the LAST "-{digit}" pattern
        final Matcher m = Pattern.compile("^(.+)-\\d").matcher(base);
        if (m.find()) {
            return Optional.of(m.group(1));
        }
        return Optional.of(base);
    }

    /**
     * Normalize a PyPI project name: replace [-_.] runs with single hyphen, lowercase.
     */
    private static String normalizePypiName(final String name) {
        return name.replaceAll("[-_.]+", "-").toLowerCase();
    }

    /**
     * Extract PyPI package name from a distribution filename.
     * Wheel: {name}-{version}(-{build})?-{python}-{abi}-{platform}.whl
     * Sdist: {name}-{version}.tar.gz or {name}-{version}.zip
     */
    private static Optional<String> extractPypiNameFromFilename(final String filename) {
        // Remove extension
        String base = filename;
        if (base.endsWith(".tar.gz")) {
            base = base.substring(0, base.length() - ".tar.gz".length());
        } else if (base.endsWith(".whl") || base.endsWith(".zip") || base.endsWith(".egg")) {
            base = base.substring(0, base.lastIndexOf('.'));
        } else {
            return Optional.empty();
        }
        // Name is everything before the first hyphen followed by a digit
        // e.g., "numpy-1.24.0" -> "numpy", "my_package-2.0.0rc1" -> "my_package"
        final Matcher m = Pattern.compile("^(.+?)-\\d").matcher(base);
        if (m.find()) {
            return Optional.of(normalizePypiName(m.group(1)));
        }
        return Optional.empty();
    }

    private static String stripLeadingSlash(final String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
