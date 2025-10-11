/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer.cli;

import com.artipie.importer.api.ChecksumPolicy;
import com.artipie.importer.api.DigestType;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

final class UploadTask {

    private final Path file;
    private final String repoType;
    private final String repoName;
    private final String relativePath;
    private final ArtifactMetadata metadata;
    private final long size;
    private final long created;
    private final ChecksumPolicy policy;
    private final String idempotencyKey;
    private final EnumMap<DigestType, String> digests;

    UploadTask(
        final Path file,
        final String repoType,
        final String repoName,
        final Path relative,
        final ArtifactMetadata metadata,
        final long size,
        final long created,
        final ChecksumPolicy policy
    ) {
        this.file = file;
        this.repoType = repoType;
        this.repoName = repoName;
        this.relativePath = relative.toString().replace('\\', '/');
        this.metadata = metadata;
        this.size = size;
        this.created = created;
        this.policy = policy;
        this.idempotencyKey = UUID.nameUUIDFromBytes((repoName + '/' + this.relativePath).getBytes(StandardCharsets.UTF_8)).toString();
        this.digests = new EnumMap<>(DigestType.class);
    }

    Path file() {
        return this.file;
    }

    String repoType() {
        return this.repoType;
    }

    String repoName() {
        return this.repoName;
    }

    String relativePath() {
        return this.relativePath;
    }

    ArtifactMetadata metadata() {
        return this.metadata;
    }

    long size() {
        return this.size;
    }

    long created() {
        return this.created;
    }

    String idempotencyKey() {
        return this.idempotencyKey;
    }

    EnumMap<DigestType, String> digests() {
        return this.digests;
    }

    String derivedName() {
        final String name = this.file.getFileName().toString();
        final int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * For file repositories, build artifact name as dot-separated repo-relative path
     * including the filename with extension, e.g. "a/b/c/file.zip" -> "a.b.c.file.zip".
     *
     * @return Dot-separated name derived from {@code relativePath}
     */
    String dotSeparatedName() {
        return this.relativePath.replace('/', '.');
    }

    void computeDigests() throws IOException {
        final MessageDigest sha1 = digest("SHA-1");
        final MessageDigest sha256 = digest("SHA-256");
        final MessageDigest md5 = digest("MD5");
        final byte[] buffer = new byte[8192];
        try (InputStream stream = Files.newInputStream(this.file)) {
            int read;
            while ((read = stream.read(buffer)) != -1) {
                sha1.update(buffer, 0, read);
                sha256.update(buffer, 0, read);
                md5.update(buffer, 0, read);
            }
        }
        this.digests.put(DigestType.SHA1, hex(sha1.digest()));
        this.digests.put(DigestType.SHA256, hex(sha256.digest()));
        this.digests.put(DigestType.MD5, hex(md5.digest()));
    }

    void readMetadataChecksums() {
        final Map<String, DigestType> suffixes = Map.of(
            ".sha1", DigestType.SHA1,
            ".sha256", DigestType.SHA256,
            ".md5", DigestType.MD5
        );
        suffixes.forEach((suffix, type) -> {
            final Path sibling = this.file.resolveSibling(this.file.getFileName().toString() + suffix);
            if (Files.exists(sibling)) {
                try {
                    final String value = Files.readString(sibling).trim();
                    if (!value.isBlank()) {
                        this.digests.put(type, value.toLowerCase(Locale.ROOT));
                    }
                } catch (final IOException ignored) {
                    // Best effort
                }
            }
        });
    }

    URI buildUri(final URI base) {
        final String encodedRepo = encodeSegment(this.repoName);
        final String encodedPath = Arrays.stream(this.relativePath.split("/"))
            .map(UploadTask::encodeSegment)
            .collect(Collectors.joining("/"));
        final String path = "/.import/" + encodedRepo + "/" + encodedPath;
        return base.resolve(path);
    }

    private static MessageDigest digest(final String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (final NoSuchAlgorithmException err) {
            throw new IllegalStateException(err);
        }
    }

    private static String hex(final byte[] bytes) {
        final StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (final byte value : bytes) {
            final String hex = Integer.toHexString(value & 0xFF);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
    }

    private static String encodeSegment(final String segment) {
        try {
            return URLEncoder.encode(segment, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/");
        } catch (final Exception err) {
            throw new IllegalStateException(err);
        }
    }
}
