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
package com.auto1.pantera.importer;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.ResponseException;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.importer.api.ChecksumPolicy;
import com.auto1.pantera.importer.api.ImportHeaders;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Parsed import request metadata.
 *
 * @since 1.0
 */
public final class ImportRequest {

    /**
     * Import URI prefix.
     */
    private static final String PREFIX = "/.import/";

    /**
     * Repository name.
     */
    private final String repo;

    /**
     * Repository type.
     */
    private final String repoType;

    /**
     * Storage path.
     */
    private final String path;

    /**
     * Artifact logical name.
     */
    private final String artifact;

    /**
     * Artifact version.
     */
    private final String version;

    /**
     * Artifact size in bytes (optional).
     */
    private final Long size;

    /**
     * Owner.
     */
    private final String owner;

    /**
     * Created timestamp.
     */
    private final Long created;

    /**
     * Release timestamp.
     */
    private final Long release;

    /**
     * SHA-1 checksum.
     */
    private final String sha1;

    /**
     * SHA-256 checksum.
     */
    private final String sha256;

    /**
     * MD5 checksum.
     */
    private final String md5;

    /**
     * Idempotency key.
     */
    private final String idempotency;

    /**
     * Checksum policy.
     */
    private final ChecksumPolicy policy;

    /**
     * Metadata-only flag.
     */
    private final boolean metadata;

    /**
     * Request headers.
     */
    private final Headers headers;

    /**
     * Ctor.
     *
     * @param repo Repository
     * @param repoType Repository type
     * @param path Storage path
     * @param artifact Artifact name
     * @param version Artifact version
     * @param size Size in bytes (optional)
     * @param owner Owner
     * @param created Created timestamp
     * @param release Release timestamp
     * @param sha1 SHA-1 checksum
     * @param sha256 SHA-256 checksum
     * @param md5 MD5 checksum
     * @param idempotency Idempotency key
     * @param policy Checksum policy
     * @param metadata Metadata-only flag
     * @param headers Request headers
     */
    private ImportRequest(
        final String repo,
        final String repoType,
        final String path,
        final String artifact,
        final String version,
        final Long size,
        final String owner,
        final Long created,
        final Long release,
        final String sha1,
        final String sha256,
        final String md5,
        final String idempotency,
        final ChecksumPolicy policy,
        final boolean metadata,
        final Headers headers
    ) {
        this.repo = repo;
        this.repoType = repoType;
        this.path = path;
        this.artifact = artifact;
        this.version = version;
        this.size = size;
        this.owner = owner;
        this.created = created;
        this.release = release;
        this.sha1 = sha1;
        this.sha256 = sha256;
        this.md5 = md5;
        this.idempotency = idempotency;
        this.policy = policy;
        this.metadata = metadata;
        this.headers = headers;
    }

    /**
     * Parse HTTP request into {@link ImportRequest}.
     *
     * @param line Request line
     * @param headers Request headers
     * @return Parsed request
     */
    public static ImportRequest parse(final RequestLine line, final Headers headers) {
        if (line.method() != RqMethod.PUT && line.method() != RqMethod.POST) {
            throw new ResponseException(ResponseBuilder.methodNotAllowed().build());
        }
        final String uri = line.uri().getPath();
        if (!uri.startsWith(PREFIX)) {
            throw new ResponseException(
                ResponseBuilder.notFound()
                    .textBody("Import endpoint is /.import/<repository>/<path>")
                    .build()
            );
        }
        final String tail = uri.substring(PREFIX.length());
        final int slash = tail.indexOf('/');
        if (slash < 0 || slash == tail.length() - 1) {
            throw new ResponseException(
                ResponseBuilder.badRequest()
                    .textBody("Repository name and artifact path are required")
                    .build()
            );
        }
        final String repo = decode(tail.substring(0, slash));
        final String path = normalizePath(tail.substring(slash + 1));
        final String repoType = requiredHeader(headers, ImportHeaders.REPO_TYPE);
        final String idempotency = requiredHeader(headers, ImportHeaders.IDEMPOTENCY_KEY);
        final ChecksumPolicy policy = ChecksumPolicy.fromHeader(optionalHeader(headers, ImportHeaders.CHECKSUM_POLICY));
        final Long size = optionalLong(headerFirst(headers, ImportHeaders.ARTIFACT_SIZE)
            .or(() -> headerFirst(headers, "Content-Length")));
        final Long created = optionalLong(headerFirst(headers, ImportHeaders.ARTIFACT_CREATED));
        final Long release = optionalLong(headerFirst(headers, ImportHeaders.ARTIFACT_RELEASE));
        final boolean metadata = headerFirst(headers, ImportHeaders.METADATA_ONLY)
            .map(value -> "true".equalsIgnoreCase(value.trim()))
            .orElse(false);
        return new ImportRequest(
            repo,
            repoType,
            path,
            headerFirst(headers, ImportHeaders.ARTIFACT_NAME).orElse(null),
            headerFirst(headers, ImportHeaders.ARTIFACT_VERSION).orElse(null),
            size,
            headerFirst(headers, ImportHeaders.ARTIFACT_OWNER).orElse(null),
            created,
            release,
            headerFirst(headers, ImportHeaders.CHECKSUM_SHA1).orElse(null),
            headerFirst(headers, ImportHeaders.CHECKSUM_SHA256).orElse(null),
            headerFirst(headers, ImportHeaders.CHECKSUM_MD5).orElse(null),
            idempotency,
            policy,
            metadata,
            headers
        );
    }

    public String repo() {
        return this.repo;
    }

    public String repoType() {
        return this.repoType;
    }

    /**
     * Copy of this request bound to the given repository type.
     *
     * <p>SECURITY (2.2.9): the importer rebinds every request to the type
     * from the target repository's authoritative configuration (see
     * {@link ImportRepoType}) so the caller's declared type never drives
     * format-specific behaviour.</p>
     *
     * @param type Authoritative repository type
     * @return Rebound request
     */
    ImportRequest withRepoType(final String type) {
        return new ImportRequest(
            this.repo, type, this.path, this.artifact, this.version, this.size,
            this.owner, this.created, this.release, this.sha1, this.sha256, this.md5,
            this.idempotency, this.policy, this.metadata, this.headers
        );
    }

    public String path() {
        return this.path;
    }

    public Optional<String> artifact() {
        return Optional.ofNullable(this.artifact);
    }

    public Optional<String> version() {
        return Optional.ofNullable(this.version);
    }

    public Optional<Long> size() {
        return Optional.ofNullable(this.size);
    }

    public Optional<String> owner() {
        return Optional.ofNullable(this.owner);
    }

    public Optional<Long> created() {
        return Optional.ofNullable(this.created);
    }

    public Optional<Long> release() {
        return Optional.ofNullable(this.release);
    }

    public Optional<String> sha1() {
        return Optional.ofNullable(this.sha1);
    }

    public Optional<String> sha256() {
        return Optional.ofNullable(this.sha256);
    }

    public Optional<String> md5() {
        return Optional.ofNullable(this.md5);
    }

    public String idempotency() {
        return this.idempotency;
    }

    /**
     * The authenticated principal this import runs as, resolved from the
     * {@code pantera_login} header the authorization slice stamps after a
     * successful credential check (never from a caller-supplied metadata
     * header). Used to bind the idempotency key to its creator.
     *
     * @return Caller name
     */
    public String caller() {
        return new Login(this.headers).getValue();
    }

    public ChecksumPolicy policy() {
        return this.policy;
    }

    public boolean metadataOnly() {
        return this.metadata;
    }

    Headers headers() {
        return this.headers;
    }

    /**
     * Decode URL component.
     *
     * @param value Value to decode
     * @return Decoded string
     */
    private static String decode(final String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /**
     * Normalize artifact path.
     *
     * @param path Raw path
     * @return Normalized, decoded path without leading slash
     */
    private static String normalizePath(final String path) {
        final String[] segments = path.split("/");
        final StringBuilder normalized = new StringBuilder();
        for (final String raw : segments) {
            // SECURITY (2.2.9): decode BEFORE the containment check. The old
            // code checked the still-encoded segment for a literal ".." then
            // decoded afterwards, so a double-encoded parent (%252e%252e ->
            // %2e%2e -> "..") or separator (%252f -> "/") survived and escaped
            // the repository root.
            final String segment = decode(raw);
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment) || segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0) {
                throw new ResponseException(
                    ResponseBuilder.badRequest()
                        .textBody("Parent directory or separator segments are not allowed in paths")
                        .build()
                );
            }
            if (!normalized.isEmpty()) {
                normalized.append('/');
            }
            normalized.append(segment);
        }
        if (normalized.isEmpty()) {
            throw new ResponseException(
                ResponseBuilder.badRequest().textBody("Artifact path must not be empty").build()
            );
        }
        return normalized.toString();
    }

    /**
     * Retrieve first header value.
     *
     * @param headers Headers
     * @param name Header name
     * @return Optional value
     */
    private static Optional<String> headerFirst(final Headers headers, final String name) {
        final List<String> values = headers.values(name);
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(values.getFirst());
    }

    /**
     * Header to string optional.
     *
     * @param headers Headers
     * @param name Name
     * @return Value or null
     */
    private static String optionalHeader(final Headers headers, final String name) {
        return headerFirst(headers, name).orElse(null);
    }

    /**
     * Read long from header.
     *
     * @param value Header value
     * @return Parsed long or {@code null}
     */
    private static Long optionalLong(final Optional<String> value) {
        return value.map(val -> Long.parseLong(val.trim())).orElse(null);
    }

    /**
     * Obtain header or fail with 400.
     *
     * @param headers Headers
     * @param name Header name
     * @return Value
     */
    private static String requiredHeader(final Headers headers, final String name) {
        return headerFirst(headers, name).map(val -> {
            if (val.isBlank()) {
                throw new ResponseException(
                    ResponseBuilder.badRequest()
                        .textBody(String.format("%s header must not be blank", name))
                        .build()
                );
            }
            return val.trim();
        }).orElseThrow(
            () -> new ResponseException(
                ResponseBuilder.badRequest()
                    .textBody(String.format("Missing required header %s", name))
                    .build()
            )
        );
    }
}
