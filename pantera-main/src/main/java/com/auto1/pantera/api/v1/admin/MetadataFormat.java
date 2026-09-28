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
package com.auto1.pantera.api.v1.admin;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a package's version listing lives for each format family, and how
 * to read the versions a client is shown out of it: npm packument
 * {@code versions}, PyPI simple-index file links, maven-metadata.xml
 * {@code <version>}s, go {@code @v/list}, composer p2 {@code version}s.
 * Other families are unsupported.
 *
 * @since 2.2.9
 */
public final class MetadataFormat {

    /**
     * Anchor text of a simple-index link.
     */
    private static final Pattern ANCHOR = Pattern.compile(
        "<a\\b[^>]*>([^<]+)</a>", Pattern.CASE_INSENSITIVE
    );

    /**
     * maven-metadata.xml version element.
     */
    private static final Pattern MAVEN_VERSION = Pattern.compile(
        "<version>\\s*([^<\\s]+)\\s*</version>"
    );

    /**
     * PEP 503 separator runs.
     */
    private static final Pattern PYPI_SEPARATORS = Pattern.compile("[-_.]+");

    /**
     * Format family.
     */
    private final String family;

    /**
     * Ctor.
     *
     * @param family Format family ({@code npm}, {@code pypi}, ...)
     */
    public MetadataFormat(final String family) {
        this.family = family == null ? "" : family.toLowerCase(Locale.ROOT);
    }

    /**
     * The listing request for a package.
     *
     * @param pkg Package
     * @return Request, empty when the family is unsupported or the name
     *  cannot address a listing
     */
    public Optional<Listing> listing(final PackageName pkg) {
        final String name = pkg.raw();
        final Optional<Listing> res;
        switch (this.family) {
            case "npm":
                res = Optional.of(new Listing(
                    "/" + name.replace("/", "%2F"), "application/json"
                ));
                break;
            case "pypi":
                res = Optional.of(new Listing(
                    "/simple/" + PYPI_SEPARATORS.matcher(name).replaceAll("-")
                        .toLowerCase(Locale.ROOT) + "/",
                    "text/html"
                ));
                break;
            case "maven":
            case "gradle":
                res = MetadataFormat.mavenPath(name).map(
                    path -> new Listing("/" + path + "/maven-metadata.xml", "application/xml")
                );
                break;
            case "go":
                res = Optional.of(new Listing(
                    "/" + MetadataFormat.goEscape(name) + "/@v/list", "text/plain"
                ));
                break;
            case "php":
                res = name.contains("/")
                    ? Optional.of(new Listing(
                        "/p2/" + name.toLowerCase(Locale.ROOT) + ".json", "application/json"
                    ))
                    : Optional.empty();
                break;
            default:
                res = Optional.empty();
                break;
        }
        return res;
    }

    /**
     * Versions a listing body shows.
     *
     * @param pkg Package
     * @param body Listing body
     * @return Versions in document order, no duplicates
     * @throws IllegalArgumentException When the body is not a listing
     */
    public List<String> versions(final PackageName pkg, final byte[] body) {
        final String text = new String(body, StandardCharsets.UTF_8);
        final Set<String> out = new LinkedHashSet<>();
        switch (this.family) {
            case "npm":
                final JsonObject versions = new JsonObject(text).getJsonObject("versions");
                if (versions != null) {
                    out.addAll(versions.fieldNames());
                }
                break;
            case "pypi":
                final Matcher anchors = ANCHOR.matcher(text);
                while (anchors.find()) {
                    MetadataFormat.pypiVersion(anchors.group(1).trim()).ifPresent(out::add);
                }
                break;
            case "maven":
            case "gradle":
                final Matcher mvn = MAVEN_VERSION.matcher(text);
                while (mvn.find()) {
                    out.add(mvn.group(1));
                }
                break;
            case "go":
                for (final String line : text.split("\\R")) {
                    if (!line.isBlank()) {
                        out.add(line.trim());
                    }
                }
                break;
            case "php":
                out.addAll(MetadataFormat.composerVersions(new JsonObject(text), pkg));
                break;
            default:
                break;
        }
        return new ArrayList<>(out);
    }

    /**
     * Version of a PyPI distribution filename: the second dash-separated
     * field of a wheel, the part after the last dash of an sdist.
     *
     * @param file Filename
     * @return Version, empty for non-distribution names
     */
    private static Optional<String> pypiVersion(final String file) {
        final String lower = file.toLowerCase(Locale.ROOT);
        final Optional<String> res;
        if (lower.endsWith(".whl")) {
            final String[] parts = file.split("-");
            res = parts.length >= 3 ? Optional.of(parts[1]) : Optional.empty();
        } else {
            String base = null;
            for (final String ext : List.of(".tar.gz", ".tar.bz2", ".zip", ".tgz", ".egg")) {
                if (lower.endsWith(ext)) {
                    base = file.substring(0, file.length() - ext.length());
                    break;
                }
            }
            if (base == null || base.lastIndexOf('-') <= 0) {
                res = Optional.empty();
            } else {
                res = Optional.of(base.substring(base.lastIndexOf('-') + 1));
            }
        }
        return res;
    }

    /**
     * Composer versions: p2 lists ({@code packages.name[].version}) and
     * legacy maps ({@code packages.name.{version}}).
     *
     * @param json Document
     * @param pkg Package
     * @return Versions
     */
    private static List<String> composerVersions(final JsonObject json, final PackageName pkg) {
        final List<String> out = new ArrayList<>();
        final JsonObject packages = json.getJsonObject("packages");
        if (packages == null) {
            return out;
        }
        final Object entry = packages.getValue(pkg.raw().toLowerCase(Locale.ROOT));
        if (entry instanceof JsonArray arr) {
            for (int idx = 0; idx < arr.size(); idx = idx + 1) {
                final JsonObject item = arr.getJsonObject(idx);
                if (item != null && item.getString("version") != null) {
                    out.add(item.getString("version"));
                }
            }
        } else if (entry instanceof JsonObject obj) {
            out.addAll(obj.fieldNames());
        }
        return out;
    }

    /**
     * Maven storage path of {@code groupId:artifactId} (or an already
     * slash-separated path).
     *
     * @param name Coordinate
     * @return Path, empty when no artifact is named
     */
    private static Optional<String> mavenPath(final String name) {
        final int colon = name.indexOf(':');
        final Optional<String> res;
        if (colon > 0 && colon < name.length() - 1) {
            res = Optional.of(
                name.substring(0, colon).replace('.', '/') + "/" + name.substring(colon + 1)
            );
        } else if (name.indexOf('/') > 0) {
            res = Optional.of(name.replaceAll("^/+|/+$", ""));
        } else {
            res = Optional.empty();
        }
        return res;
    }

    /**
     * Go module path case-encoding ({@code A} becomes {@code !a}).
     *
     * @param module Module path
     * @return Escaped path
     */
    private static String goEscape(final String module) {
        final StringBuilder out = new StringBuilder(module.length() + 4);
        for (int idx = 0; idx < module.length(); idx = idx + 1) {
            final char chr = module.charAt(idx);
            if (Character.isUpperCase(chr)) {
                out.append('!').append(Character.toLowerCase(chr));
            } else {
                out.append(chr);
            }
        }
        return out.toString();
    }

    /**
     * A listing request.
     *
     * @param path Repository-relative raw path
     * @param accept Accept header value
     * @since 2.2.9
     */
    public record Listing(String path, String accept) {
    }
}
