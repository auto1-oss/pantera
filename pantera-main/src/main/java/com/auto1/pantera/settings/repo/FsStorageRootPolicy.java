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
import java.util.List;
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
        for (final Path root : this.roots) {
            if (normalised.startsWith(root)) {
                return Optional.empty();
            }
        }
        return Optional.of(
            "fs storage path must be under an approved root (" + ENV + ")"
        );
    }

    /**
     * Check the inline storage block of a repository config, if it is a
     * raw {@code fs} mapping. Alias references (strings) and other storage
     * types are out of scope.
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
        final JsonObject block = storage.asJsonObject();
        if (!"fs".equals(block.getString("type", ""))) {
            return Optional.empty();
        }
        return this.reject(block.getString("path", null));
    }
}
