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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     * Pattern to extract {@code data-upload-time="value"} (PEP 700) from
     * tag attributes. PyPI itself emits ISO 8601 UTC timestamps here on
     * every link — the cooldown filter consumes them via
     * {@link #extractReleaseDates(PypiSimpleIndex)} so it can skip the
     * inspector release-date fetch.
     */
    private static final Pattern DATA_UPLOAD_TIME_PATTERN = Pattern.compile(
        "data-upload-time\\s*=\\s*\"([^\"]*)\"",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Pattern to extract {@code data-yanked="value"} (PEP 592) from tag
     * attributes. Absence of the attribute means "not yanked"; presence
     * (even with an empty value) means yanked, with the value as the
     * human-readable reason.
     */
    private static final Pattern DATA_YANKED_PATTERN = Pattern.compile(
        "data-yanked\\s*=\\s*\"([^\"]*)\"",
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

    /**
     * Shared Jackson mapper for the PEP 691 JSON branch. RCA-pypi-A
     * (v2.2.0): pypi.org's PEP 503 HTML response omits the
     * {@code data-upload-time} attribute that the cooldown filter needs
     * to decide whether each version is fresh. The PEP 691 JSON variant
     * carries {@code upload-time} on every file, so {@link PypiSimpleHandler}
     * now requests JSON from upstream — and this parser routes accordingly.
     */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @Override
    public PypiSimpleIndex parse(final byte[] bytes) throws MetadataParseException {
        if (looksLikeJson(bytes)) {
            return parseJson(bytes);
        }
        return parseHtml(bytes);
    }

    /**
     * Heuristic to pick the JSON parse branch without relying on an
     * upstream Content-Type header (which {@link PypiSimpleHandler} does
     * not thread through). The first non-whitespace byte decides:
     * {@code '{'} → PEP 691 JSON; anything else → PEP 503 HTML. Empty
     * bodies fall through to the HTML path which returns an empty index.
     */
    private static boolean looksLikeJson(final byte[] bytes) {
        byte first = 0;
        boolean found = false;
        for (final byte b : bytes) {
            if (b != ' ' && b != '\t' && b != '\n' && b != '\r') {
                first = b;
                found = true;
                break;
            }
        }
        return found && first == '{';
    }

    private static PypiSimpleIndex parseHtml(final byte[] bytes) throws MetadataParseException {
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
            final String uploadTime = extractAttr(DATA_UPLOAD_TIME_PATTERN, attrs);
            final String yanked = extractAttr(DATA_YANKED_PATTERN, attrs);
            final String version = extractVersionFromFilename(text);
            links.add(new PypiSimpleIndex.Link(
                href, text, version, requiresPython, distInfoMetadata, uploadTime, yanked
            ));
        }
        return new PypiSimpleIndex(html, links);
    }

    /**
     * PEP 691 JSON shape: {@code {"meta": {...}, "name": "...", "files":
     * [{"filename": "...", "url": "...", "hashes": {...},
     * "requires-python": "...", "upload-time": "..."}, ...]}}.
     * The {@code url} field has been pre-rewritten by the proxy to point
     * at the local cache (see {@code ProxySlice#JSON_PACKAGES}) so the
     * generated HTML's hrefs stay on the local repo.
     */
    private static PypiSimpleIndex parseJson(final byte[] bytes) throws MetadataParseException {
        final JsonNode root;
        try {
            root = JSON_MAPPER.readTree(bytes);
        } catch (final Exception ex) {
            throw new MetadataParseException("Failed to decode PyPI Simple Index JSON", ex);
        }
        final JsonNode files = root.path("files");
        if (!files.isArray()) {
            return new PypiSimpleIndex("", List.of());
        }
        final List<PypiSimpleIndex.Link> links = new ArrayList<>(files.size());
        for (final JsonNode file : files) {
            final String filename = textOrNull(file, "filename");
            final String url = textOrNull(file, "url");
            if (filename == null || url == null || filename.isEmpty() || url.isEmpty()) {
                continue;
            }
            final String hashFragment = extractSha256Fragment(file);
            final String href = hashFragment == null ? url : url + "#" + hashFragment;
            final String requiresPython = textOrNull(file, "requires-python");
            final String distInfoMetadata = extractDistInfoMetadata(file);
            final String uploadTime = textOrNull(file, "upload-time");
            final String yanked = extractYanked(file);
            final String version = extractVersionFromFilename(filename);
            links.add(new PypiSimpleIndex.Link(
                href, filename, version, requiresPython, distInfoMetadata, uploadTime, yanked
            ));
        }
        return new PypiSimpleIndex("", links);
    }

    private static String textOrNull(final JsonNode node, final String field) {
        final JsonNode v = node.path(field);
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        return v.asText();
    }

    private static String extractSha256Fragment(final JsonNode file) {
        final JsonNode hashes = file.path("hashes");
        if (hashes.isObject()) {
            final JsonNode sha256 = hashes.path("sha256");
            if (sha256.isTextual() && !sha256.asText().isEmpty()) {
                return "sha256=" + sha256.asText();
            }
        }
        return null;
    }

    /**
     * PEP 658 advertises distribution metadata either via the legacy
     * boolean field or the newer {@code core-metadata} object containing
     * its hashes. Either form maps to the HTML {@code
     * data-dist-info-metadata} attribute the rewriter emits.
     */
    private static String extractDistInfoMetadata(final JsonNode file) {
        final JsonNode metadata = file.has("core-metadata")
            ? file.get("core-metadata")
            : file.path("dist-info-metadata");
        if (metadata == null || metadata.isNull() || metadata.isMissingNode()) {
            return null;
        }
        if (metadata.isBoolean()) {
            return metadata.booleanValue() ? "true" : null;
        }
        if (metadata.isObject()) {
            final JsonNode sha256 = metadata.path("sha256");
            if (sha256.isTextual() && !sha256.asText().isEmpty()) {
                return "sha256=" + sha256.asText();
            }
            return "true";
        }
        return null;
    }

    /**
     * PEP 691 {@code yanked} field: boolean {@code false} (not yanked,
     * mapped to {@code null}) or a string (yanked — the reason, possibly
     * empty). A non-compliant boolean {@code true} is treated as "yanked,
     * no reason" (empty string) rather than dropped, since some mirrors
     * emit the legacy boolean form.
     */
    private static String extractYanked(final JsonNode file) {
        final JsonNode yanked = file.path("yanked");
        if (yanked.isMissingNode() || yanked.isNull()) {
            return null;
        }
        if (yanked.isTextual()) {
            return yanked.asText();
        }
        if (yanked.isBoolean()) {
            return yanked.booleanValue() ? "" : null;
        }
        return null;
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

    /**
     * Extract a {@code version -> upload-time} map from the parsed
     * Simple Index. PEP 700 specifies a {@code data-upload-time}
     * attribute per link in ISO 8601 / RFC 3339 form
     * ({@code 2024-09-09T15:12:34.567890Z}). pypi.org emits it on every
     * link; some private mirrors don't — versions whose links lack a
     * parseable timestamp are omitted from the result, and the cooldown
     * filter then treats them as release-date-unknown (allow), matching
     * the npm/composer packument-inline semantics established in
     * {@code dbdde1736}.
     *
     * <p>When a version has multiple links (e.g. sdist + wheels), the
     * <em>earliest</em> upload time wins — that's the moment the version
     * first appeared upstream, which is what cooldown is gating on.</p>
     *
     * @param metadata Parsed Simple Index
     * @return Immutable {@code version -> Instant} map (may be empty)
     */
    @Override
    public Map<String, Instant> extractReleaseDates(final PypiSimpleIndex metadata) {
        if (metadata == null || metadata.links().isEmpty()) {
            return Map.of();
        }
        final Map<String, Instant> result = new HashMap<>();
        for (final PypiSimpleIndex.Link link : metadata.links()) {
            final String version = link.version();
            final String uploadTime = link.uploadTime();
            if (version == null || version.isEmpty()
                || uploadTime == null || uploadTime.isEmpty()) {
                continue;
            }
            final Instant parsed = tryParseInstant(uploadTime);
            if (parsed != null) {
                result.merge(version, parsed,
                    (existing, candidate) ->
                        candidate.isBefore(existing) ? candidate : existing
                );
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Parse an ISO 8601 timestamp; return {@code null} on failure so the
     * caller can skip the version. Extracted to a helper to avoid an
     * empty catch block, which PMD's EmptyCatchBlock rule fails on.
     */
    private static Instant tryParseInstant(final String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (final DateTimeParseException ex) {
            return null;
        }
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
