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
package com.auto1.pantera.settings.repo;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.json.JsonObject;
import javax.json.JsonValue;

/**
 * Approved base directories for inline {@code fs} storage submitted through
 * the REST repository API.
 *
 * <p>SECURITY (2.2.9, SecOps capability-escalation.repository-filesystem-
 * root): a principal holding only repository CREATE/UPDATE could submit
 * {@code {"type":"fs","path":"/"}} and Pantera mounted the host root as a
 * browsable, downloadable repository (the JWT private key under
 * {@code /etc/pantera/keys}, {@code /proc/self/environ}, ...). A raw
 * filesystem path is now accepted only when it normalises to a location
 * under an approved root; anything else — including a path that escapes
 * via {@code ..} — is refused before it is persisted.</p>
 *
 * <p>Roots come from the {@code pantera.fs.storage.roots} system property,
 * else the {@value #ENV} environment variable (path-separator delimited),
 * else the documented data directory {@value #DEFAULT}. YAML-file
 * repositories loaded by {@code ConfigWatchService} are not REST-managed
 * and are unaffected.</p>
 *
 * @since 2.2.9
 */
public final class FsStorageRootPolicy {

    /**
     * Environment variable listing approved roots.
     */
    public static final String ENV = "PANTERA_FS_STORAGE_ROOTS";

    /**
     * System property override (tests, ops).
     */
    public static final String PROPERTY = "pantera.fs.storage.roots";

    /**
     * Default approved root — the documented data directory.
     */
    public static final String DEFAULT = "/var/pantera/data";

    /**
     * Normalised, absolute approved roots.
     */
    private final List<Path> roots;

    /**
     * Ctor.
     *
     * @param roots Approved base directories
     */
    public FsStorageRootPolicy(final List<Path> roots) {
        final List<Path> normalised = new ArrayList<>(roots.size());
        for (final Path root : roots) {
            normalised.add(root.toAbsolutePath().normalize());
        }
        this.roots = List.copyOf(normalised);
    }

    /**
     * Policy from the process environment.
     *
     * @return Policy honouring the property, the env var, or the default
     */
    public static FsStorageRootPolicy fromEnvironment() {
        final String prop = System.getProperty(PROPERTY);
        if (prop != null && !prop.isBlank()) {
            return parse(prop);
        }
        final String env = System.getenv(ENV);
        if (env != null && !env.isBlank()) {
            return parse(env);
        }
        return parse(DEFAULT);
    }

    /**
     * Parse a path-separator delimited root list.
     *
     * @param spec Delimited roots
     * @return Policy
     */
    public static FsStorageRootPolicy parse(final String spec) {
        final List<Path> parsed = new ArrayList<>();
        for (final String item : spec.split(File.pathSeparator)) {
            if (!item.isBlank()) {
                parsed.add(Path.of(item.trim()));
            }
        }
        return new FsStorageRootPolicy(parsed);
    }

    /**
     * Check one raw filesystem path.
     *
     * @param raw Submitted path
     * @return Rejection reason, or empty when the path is under an approved root
     */
    public Optional<String> reject(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of("fs storage path is required");
        }
        final Path candidate;
        try {
            candidate = Path.of(raw);
        } catch (final InvalidPathException bad) {
            return Optional.of("fs storage path is not a valid path");
        }
        if (!candidate.isAbsolute()) {
            return Optional.of("fs storage path must be absolute");
        }
        final Path normalised = candidate.normalize();
        final Path real = realLocation(normalised);
        for (final Path root : this.roots) {
            if (normalised.startsWith(root) && real.startsWith(realLocation(root))) {
                return Optional.empty();
            }
        }
        return Optional.of(
            "fs storage path must be under an approved root (" + ENV + ")"
        );
    }

    /**
     * Reject a repository whose fs storage location strictly nests within — or
     * strictly contains — another repository's fs storage location.
     *
     * <p>SECURITY (2.2.9): every repository is namespaced under its own name
     * inside its configured storage, so two repositories that share the exact
     * same location (the default shared-root model) or sit in sibling
     * directories never collide. But if one repository's path is a
     * sub-directory of another's, its whole tree lives inside the other's
     * backing storage — a repository named after a sub-directory of the
     * victim's root would then read or write the victim's artifacts. Equal and
     * sibling locations are allowed; only a genuine parent/child nesting is
     * refused. Comparison is element-wise (so {@code /data} is not treated as a
     * parent of {@code /database}) and symlink-resolved.</p>
     *
     * <p>Each repository's data lives under its own name inside its configured
     * storage, so the comparison is between name-namespaced roots
     * ({@code path/name}), not bare paths: two repositories under the same
     * parent directory but different names (e.g. {@code /tmp} and a
     * {@code /tmp/junitNNN} temp dir) do not collide, while a repository whose
     * path is {@code <root>/<victim-name>} lands inside the victim's tree.</p>
     *
     * @param name This repository's name
     * @param path This repository's submitted fs storage path (already checked
     *  to sit under an approved root)
     * @param others Other repositories' fs storage paths, keyed by repository
     *  name
     * @return the rejection reason naming the conflicting repository, or empty
     */
    public Optional<String> rejectOverlap(
        final String name, final String path, final Map<String, String> others
    ) {
        final Path self = FsStorageRootPolicy.repoBase(name, path);
        if (self == null) {
            return Optional.empty();
        }
        for (final Map.Entry<String, String> entry : others.entrySet()) {
            final Path other = FsStorageRootPolicy.repoBase(entry.getKey(), entry.getValue());
            if (other != null && !self.equals(other)
                && (self.startsWith(other) || other.startsWith(self))) {
                return Optional.of(
                    "fs storage path overlaps the storage of repository '" + entry.getKey()
                    + "'; it must be neither inside nor a parent of another repository's storage"
                );
            }
        }
        return Optional.empty();
    }

    /**
     * A repository's real, name-namespaced storage root: its configured path
     * with the repository name appended (that is where the repository's data
     * actually lives), symlink-resolved. {@code null} for an unparseable path.
     * @param name Repository name
     * @param path Configured fs storage path
     * @return The storage base, or {@code null}
     */
    private static Path repoBase(final String name, final String path) {
        try {
            return FsStorageRootPolicy.realLocation(
                Path.of(path).normalize().resolve(name).normalize()
            );
        } catch (final InvalidPathException bad) {
            return null;
        }
    }

    /**
     * Where a (possibly not yet existing) path really lives: the deepest
     * existing ancestor is resolved through symlinks and the remaining
     * segments re-applied, so a link planted under an approved root cannot
     * smuggle its target directory past the lexical check.
     *
     * @param normalised Absolute, normalised path
     * @return Real location, or the input when nothing of it exists yet
     */
    private static Path realLocation(final Path normalised) {
        Path existing = normalised;
        while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return normalised;
        }
        try {
            final Path real = existing.toRealPath();
            return existing.equals(normalised)
                ? real
                : real.resolve(existing.relativize(normalised)).normalize();
        } catch (final IOException unreadable) {
            return normalised;
        }
    }

    /**
     * Check the inline storage block of a repository config, if it is a
     * local-filesystem mapping. Alias references (strings) are checked when
     * the alias itself is written (see {@link #rejectBlock(JsonObject)}).
     *
     * @param repo The {@code repo} section of the submitted config
     * @return Rejection reason, or empty
     */
    public Optional<String> rejectStorage(final JsonObject repo) {
        if (repo == null || !repo.containsKey("storage")) {
            return Optional.empty();
        }
        final JsonValue storage = repo.get("storage");
        if (storage.getValueType() != JsonValue.ValueType.OBJECT) {
            return Optional.empty();
        }
        return this.rejectBlock(storage.asJsonObject());
    }

    /**
     * Check one storage block (an inline repository storage or a storage
     * alias definition). Every storage type that addresses the local
     * filesystem by {@code path} is checked -- {@code fs} and
     * {@code vertx-file} alike: checking {@code fs} only let
     * {@code vertx-file} mount any host directory.
     *
     * @param block Storage block
     * @return Rejection reason, or empty
     */
    public Optional<String> rejectBlock(final JsonObject block) {
        if (block == null || !FsStorageRootPolicy.isLocalPath(block)) {
            return Optional.empty();
        }
        final JsonValue path = block.get("path");
        if (path == null || path.getValueType() != JsonValue.ValueType.STRING) {
            return this.reject(null);
        }
        return this.reject(block.getString("path"));
    }

    /**
     * The local path of a storage block, if it addresses the local
     * filesystem by path.
     *
     * @param block Storage block
     * @return Path, or empty for any other storage (or a non-string path)
     */
    public Optional<String> localPath(final JsonObject block) {
        if (block == null || !FsStorageRootPolicy.isLocalPath(block)) {
            return Optional.empty();
        }
        final JsonValue path = block.get("path");
        if (path == null || path.getValueType() != JsonValue.ValueType.STRING) {
            return Optional.empty();
        }
        return Optional.of(block.getString("path"));
    }

    /**
     * Whether a storage block is one of the local-filesystem types.
     *
     * @param block Storage block
     * @return True for {@code fs} and {@code vertx-file}
     */
    private static boolean isLocalPath(final JsonObject block) {
        final JsonValue type = block.get("type");
        if (type == null || type.getValueType() != JsonValue.ValueType.STRING) {
            return false;
        }
        final String name = block.getString("type");
        return "fs".equals(name) || "vertx-file".equals(name);
    }
}
