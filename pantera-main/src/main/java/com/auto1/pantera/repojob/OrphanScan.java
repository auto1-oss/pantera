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
package com.auto1.pantera.repojob;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.sql.DataSource;

/**
 * Finds storage directories no repository owns.
 *
 * <p>Deletes made before 2.2.14 could leave a repository's files behind (a
 * failed or interrupted removal, an unresolvable storage, files written
 * while the removal ran), and an interrupted rename could leave a partial
 * copy. Such a directory sits at the top level of a storage that
 * repositories use and matches no repository key and no tombstone.</p>
 *
 * <p>The scan only reports. A storage may hold data Pantera does not own,
 * so nothing is removed until an administrator names a reported directory;
 * names starting with {@code .} or {@code _} are never reported.</p>
 *
 * @since 2.2.14
 */
public final class OrphanScan {

    /**
     * Objects counted per directory before the count is reported as "at least".
     */
    private static final int SAMPLE = 1000;

    /**
     * Database.
     */
    private final DataSource source;

    /**
     * Storage of a storage config.
     */
    private final Function<JsonObject, Storage> storages;

    /**
     * Ctor.
     * @param source Database
     * @param storages Storage of a storage config
     */
    public OrphanScan(final DataSource source, final Function<JsonObject, Storage> storages) {
        this.source = source;
        this.storages = storages;
    }

    /**
     * Scan every storage that a repository or a tombstone uses.
     * @return Orphaned directories
     */
    public List<Orphan> scan() {
        final Set<String> owned = new HashSet<>();
        final Map<String, JsonObject> configs = this.storageConfigs(owned);
        final List<Orphan> res = new ArrayList<>();
        for (final Map.Entry<String, JsonObject> entry : configs.entrySet()) {
            final Storage storage = this.storages.apply(entry.getValue());
            for (final String dir : OrphanScan.topLevel(storage)) {
                if (owned.contains(dir) || dir.startsWith(".") || dir.startsWith("_")) {
                    continue;
                }
                final int sample = OrphanScan.await(
                    storage.listPage(new Key.From(dir), Optional.empty(), OrphanScan.SAMPLE)
                ).keys().size();
                if (sample > 0) {
                    res.add(
                        new Orphan(
                            entry.getKey(), OrphanScan.describe(entry.getValue()), dir,
                            sample, sample >= OrphanScan.SAMPLE
                        )
                    );
                }
            }
        }
        return res;
    }

    /**
     * The storage config of a reported orphan, checked again: the directory
     * must still be unowned.
     * @param storage Storage id from a scan
     * @param dir Directory
     * @return Storage config, empty when the directory is not an orphan of
     *  that storage (any more)
     */
    public Optional<JsonObject> confirm(final String storage, final String dir) {
        final Set<String> owned = new HashSet<>();
        final Map<String, JsonObject> configs = this.storageConfigs(owned);
        final JsonObject config = configs.get(storage);
        final Optional<JsonObject> res;
        if (config == null || owned.contains(dir) || dir.isEmpty() || dir.contains("/")
            || dir.startsWith(".") || dir.startsWith("_")) {
            res = Optional.empty();
        } else {
            res = Optional.of(config);
        }
        return res;
    }

    /**
     * Distinct storage configs of the repositories and tombstones, by id;
     * collects every repository key in use on the way.
     * @param owned Receives the keys in use
     * @return Storage configs by id
     */
    private Map<String, JsonObject> storageConfigs(final Set<String> owned) {
        final Map<String, JsonObject> configs = new LinkedHashMap<>();
        try (Connection conn = this.source.getConnection()) {
            final Map<String, JsonObject> aliases = new LinkedHashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT COALESCE(repo_name, ''), name, config FROM storage_aliases"
            );
                ResultSet rows = stmt.executeQuery()) {
                while (rows.next()) {
                    aliases.put(
                        rows.getString(1) + '\n' + rows.getString(2),
                        OrphanScan.json(rows.getString(3))
                    );
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT name, repo_key, config FROM repositories"
            );
                ResultSet rows = stmt.executeQuery()) {
                while (rows.next()) {
                    owned.add(rows.getString(2));
                    OrphanScan.storageOf(rows.getString(1), OrphanScan.json(rows.getString(3)), aliases)
                        .ifPresent(cfg -> configs.putIfAbsent(OrphanScan.id(cfg), cfg));
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT repo_key, storage_config FROM repository_tombstones"
            );
                ResultSet rows = stmt.executeQuery()) {
                while (rows.next()) {
                    owned.add(rows.getString(1));
                    if (rows.getString(2) != null) {
                        final JsonObject cfg = OrphanScan.json(rows.getString(2));
                        configs.putIfAbsent(OrphanScan.id(cfg), cfg);
                    }
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT repo_key FROM repo_jobs WHERE type = 'orphan_purge'"
                    + " AND state IN ('pending', 'running', 'failed')"
            );
                ResultSet rows = stmt.executeQuery()) {
                while (rows.next()) {
                    owned.add(rows.getString(1));
                }
            }
        } catch (final SQLException ex) {
            throw new IllegalStateException("Failed to read the storages in use", ex);
        }
        return configs;
    }

    /**
     * Resolved storage config of a repository.
     * @param name Repository name
     * @param config Repository config
     * @param aliases Alias configs by {@code <repo>\n<alias>} (empty repo for global)
     * @return Storage config, empty for a group or an unknown alias
     */
    private static Optional<JsonObject> storageOf(
        final String name, final JsonObject config, final Map<String, JsonObject> aliases
    ) {
        final JsonObject repo = config.getJsonObject("repo");
        Optional<JsonObject> res = Optional.empty();
        if (repo != null && repo.containsKey("storage")) {
            final JsonValue storage = repo.get("storage");
            if (storage.getValueType() == JsonValue.ValueType.OBJECT) {
                res = Optional.of(storage.asJsonObject());
            } else if (storage.getValueType() == JsonValue.ValueType.STRING) {
                final String alias = ((JsonString) storage).getString();
                res = Optional.ofNullable(
                    aliases.getOrDefault(name + '\n' + alias, aliases.get('\n' + alias))
                );
            }
        }
        return res;
    }

    /**
     * Names of the top-level directories of a storage.
     * @param storage Storage
     * @return Directory names
     */
    private static List<String> topLevel(final Storage storage) {
        final List<String> res = new ArrayList<>();
        for (final Key dir : OrphanScan.await(storage.list(Key.ROOT, "/")).directories()) {
            String text = dir.string();
            while (text.endsWith("/")) {
                text = text.substring(0, text.length() - 1);
            }
            if (!text.isEmpty() && !text.contains("/")) {
                res.add(text);
            }
        }
        return res;
    }

    /**
     * Stable id of a storage: of the place it keeps its objects, so the
     * configs of two repositories that write one storage differently
     * ({@code /data} and {@code /data/}, other credentials or timeouts) are
     * scanned, and their directories reported, once.
     * @param config Storage config
     * @return Short hash of the storage's location
     */
    private static String id(final JsonObject config) {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(OrphanScan.location(config).getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(16);
            for (int idx = 0; idx < 8; idx += 1) {
                hex.append(String.format("%02x", hash[idx]));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Where a storage keeps its objects, one text for every spelling of it:
     * the normalized directory of a file storage, the endpoint and bucket of
     * an S3 storage. Another type is known by its whole config.
     * @param config Storage config
     * @return Location
     */
    private static String location(final JsonObject config) {
        final String type = config.getString("type", "");
        final String path = OrphanScan.text(config, "path");
        final String bucket = OrphanScan.text(config, "bucket");
        final String res;
        if (path != null && ("fs".equals(type) || "vertx-file".equals(type))) {
            res = "file:" + Paths.get(path).toAbsolutePath().normalize();
        } else if (bucket != null && type.startsWith("s3")) {
            String endpoint = Optional.ofNullable(OrphanScan.text(config, "endpoint"))
                .orElse("").toLowerCase(Locale.ROOT);
            while (endpoint.endsWith("/")) {
                endpoint = endpoint.substring(0, endpoint.length() - 1);
            }
            res = String.join("\n", "s3", endpoint, bucket);
        } else {
            res = config.toString();
        }
        return res;
    }

    /**
     * A string field of a config.
     * @param config Config
     * @param field Field name
     * @return Its value, null when absent or not a string
     */
    private static String text(final JsonObject config, final String field) {
        final String res;
        if (config.containsKey(field)
            && config.get(field).getValueType() == JsonValue.ValueType.STRING) {
            res = config.getString(field);
        } else {
            res = null;
        }
        return res;
    }

    /**
     * What an operator needs to recognise a storage: its type and location,
     * never its credentials.
     * @param config Storage config
     * @return Description
     */
    private static String describe(final JsonObject config) {
        final StringBuilder text = new StringBuilder(config.getString("type", "storage"));
        for (final String field : new String[] {"bucket", "path", "region", "endpoint"}) {
            if (config.containsKey(field)
                && config.get(field).getValueType() == JsonValue.ValueType.STRING) {
                text.append(' ').append(field).append('=').append(config.getString(field));
            }
        }
        return text.toString();
    }

    /**
     * Wait for a storage call.
     * @param call Call
     * @param <T> Result type
     * @return Result
     */
    private static <T> T await(final java.util.concurrent.CompletableFuture<T> call) {
        try {
            return call.get(2, TimeUnit.MINUTES);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while scanning the storage", ex);
        } catch (final java.util.concurrent.ExecutionException
            | java.util.concurrent.TimeoutException ex) {
            throw new IllegalStateException("Storage scan failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Parse a JSON object.
     * @param text JSON text
     * @return Object
     */
    private static JsonObject json(final String text) {
        return Json.createReader(new StringReader(text)).readObject();
    }

    /**
     * A directory no repository owns.
     * @param storage Id of the storage it is in
     * @param location Type and location of that storage
     * @param directory Directory name
     * @param objects Objects found, counted up to a limit
     * @param more Whether there are more objects than counted
     */
    public record Orphan(
        String storage, String location, String directory, int objects, boolean more
    ) {
    }
}
