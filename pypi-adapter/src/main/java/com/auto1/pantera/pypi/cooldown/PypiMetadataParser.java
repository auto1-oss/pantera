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
package com.auto1.pantera.pypi.cooldown;

import com.auto1.pantera.cooldown.metadata.MetadataParseException;
import com.auto1.pantera.cooldown.metadata.MetadataParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PyPI Simple Index metadata parser implementing cooldown SPI.
 * Parses the HTML Simple API response (PEP 503) and extracts version information
 * from {@code <a>} tags.
 *
 * <p>PyPI Simple Index HTML structure:</p>
 * <pre>
 * &lt;!DOCTYPE html&gt;&lt;html&gt;&lt;body&gt;
 * &lt;a href="../../packages/my-pkg-1.0.0.tar.gz#sha256=abc123"&gt;my-pkg-1.0.0.tar.gz&lt;/a&gt;
 * &lt;a href="../../packages/my-pkg-1.1.0-py3-none-any.whl#sha256=def456"
 *    data-requires-python="&amp;gt;=3.8"&gt;my-pkg-1.1.0-py3-none-any.whl&lt;/a&gt;
 * &lt;a href="../../packages/my-pkg-2.0.0.tar.gz#sha256=ghi789"&gt;my-pkg-2.0.0.tar.gz&lt;/a&gt;
 * &lt;/body&gt;&lt;/html&gt;
 * </pre>
 *
 * <p>The parsed representation is a {@link PypiSimpleIndex} record containing
 * the list of link records extracted from the HTML.</p>
 *
 * @since 2.2.0
 */
public final class PypiMetadataParser implements MetadataParser<PypiSimpleIndex> {

    /**
     * Pattern to extract {@code <a ...>text</a>} elements from Simple Index HTML.
     * Group 1: all attributes inside the opening tag.
     * Group 2: link text (the filename).
     */
    private static final Pattern LINK_PATTERN = Pattern.compile(
        "<a\\s+([^>]*)>([^<]*)</a>",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Pattern to extract {@code href="value"} from tag attributes.
     */
    private static final Pattern HREF_PATTERN = Pattern.compile(
        "href\\s*=\\s*\"([^\"]*)\"",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Pattern to extract {@code data-requires-python="value"} from tag attributes.
     */
    private static final Pattern DATA_REQ_PYTHON_PATTERN = Pattern.compile(
        "data-requires-python\\s*=\\s*\"([^\"]*)\"",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Pattern to extract {@code data-dist-info-metadata="value"} from tag attributes.
     */
    private static final Pattern DATA_METADATA_PATTERN = Pattern.compile(
        "data-dist-info-metadata\\s*=\\s*\"([^\"]*)\"",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Pattern to extract a version from a PyPI distribution filename.
     * Handles sdist (.tar.gz, .zip) and wheel (.whl) naming conventions.
     *
     * <p>Sdist: {@code {name}-{version}.tar.gz} or {@code {name}-{version}.zip}</p>
     * <p>Wheel: {@code {name}-{version}(-{build})?-{python}-{abi}-{platform}.whl}</p>
     *
     * The name part can contain letters, digits, dots, hyphens, and underscores.
     * The version starts at the first segment that begins with a digit.
     */
    private static final Pattern SDIST_VERSION_PATTERN = Pattern.compile(
        "^.+?-(" + versionRegex() + ")(?:\\.tar\\.gz|\\.zip|\\.tar\\.bz2)$"
    );

    /**
     * Wheel filename pattern per PEP 427:
     * {@code {distribution}-{version}(-{build})?-{python}-{abi}-{platform}.whl}
     */
    private static final Pattern WHEEL_VERSION_PATTERN = Pattern.compile(
        "^.+?-(" + versionRegex() + ")(?:-\\d[^-]*)?-[^-]+-[^-]+-[^-]+\\.whl$"
    );

    /**
     * Content type for PyPI Simple Index responses.
     */
    private static final String CONTENT_TYPE = "text/html";

    @Override
    public PypiSimpleIndex parse(final byte[] bytes) throws MetadataParseException {
        final String html;
        try {
            html = new String(bytes, StandardCharsets.UTF_8);
        } catch (final Exception ex) {
            throw new MetadataParseException("Failed to decode PyPI Simple Index HTML", ex);
        }
        final List<PypiSimpleIndex.Link> links = new ArrayList<>();
        final Matcher linkMatcher = LINK_PATTERN.matcher(html);
        while (linkMatcher.find()) {
            final String attrs = linkMatcher.group(1);
            final String text = linkMatcher.group(2).trim();
            final String href = extractAttr(HREF_PATTERN, attrs);
            if (href == null || text.isEmpty()) {
                continue;
            }
            final String requiresPython = extractAttr(DATA_REQ_PYTHON_PATTERN, attrs);
            final String distInfoMetadata = extractAttr(DATA_METADATA_PATTERN, attrs);
            final String version = extractVersionFromFilename(text);
            links.add(new PypiSimpleIndex.Link(
                href, text, version, requiresPython, distInfoMetadata
            ));
        }
        return new PypiSimpleIndex(html, links);
    }

    @Override
    public List<String> extractVersions(final PypiSimpleIndex metadata) {
        if (metadata == null || metadata.links().isEmpty()) {
            return Collections.emptyList();
        }
        final List<String> versions = new ArrayList<>();
        for (final PypiSimpleIndex.Link link : metadata.links()) {
            final String ver = link.version();
            if (ver != null && !ver.isEmpty() && !versions.contains(ver)) {
                versions.add(ver);
            }
        }
        return versions;
    }

    @Override
    public Optional<String> getLatestVersion(final PypiSimpleIndex metadata) {
        // PyPI Simple Index does not have a "latest" tag concept.
        // The last link in the index is typically the most recent release,
        // but this is not guaranteed. Return empty — the orchestrator
        // uses version comparison to determine the latest unblocked version.
        return Optional.empty();
    }

    @Override
    public String contentType() {
        return CONTENT_TYPE;
    }

    /**
     * Extract a version string from a PyPI distribution filename.
     *
     * @param filename The distribution filename (e.g., "my-pkg-1.0.0.tar.gz")
     * @return Extracted version, or {@code null} if version cannot be determined
     */
    static String extractVersionFromFilename(final String filename) {
        if (filename == null || filename.isEmpty()) {
            return null;
        }
        // Try wheel pattern first (more specific)
        final Matcher wheelMatcher = WHEEL_VERSION_PATTERN.matcher(filename);
        if (wheelMatcher.matches()) {
            return wheelMatcher.group(1);
        }
        // Try sdist pattern
        final Matcher sdistMatcher = SDIST_VERSION_PATTERN.matcher(filename);
        if (sdistMatcher.matches()) {
            return sdistMatcher.group(1);
        }
        return null;
    }

    /**
     * Extract an attribute value using the given pattern.
     *
     * @param pattern Attribute regex pattern with group 1 = value
     * @param attrs Tag attributes string
     * @return Attribute value, or null if not found
     */
    private static String extractAttr(final Pattern pattern, final String attrs) {
        final Matcher matcher = pattern.matcher(attrs);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * PEP 440 compatible version regex (simplified).
     * Matches versions like: 1.0.0, 1.0.0a1, 1.0.0.post1, 1.0.0rc1, 1.0.0.dev3,
     * 2024.1.15, 0.1, 1.0.0b2, etc.
     *
     * <p>Structure: N(.N)* followed by optional pre/post/dev suffixes.
     * Suffixes may be preceded by a dot (e.g. {@code .post1}, {@code .dev4})
     * or directly concatenated (e.g. {@code a1}, {@code rc2}).</p>
     *
     * @return Version regex string
     */
    private static String versionRegex() {
        return "\\d+(?:\\.\\d+)*(?:\\.?(?:a|alpha|b|beta|c|rc|pre|preview|dev|post)\\.?\\d*)*";
    }
}
