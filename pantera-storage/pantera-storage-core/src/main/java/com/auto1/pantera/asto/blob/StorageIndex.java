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
package com.auto1.pantera.asto.blob;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListResult;
import com.auto1.pantera.asto.log.EcsLogger;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * In-memory metadata index that lets {@link CachedBlobStorage} answer
 * {@code exists}/{@code metadata}/{@code list} with zero blob-store round
 * trips (spec {@code WS1-storage-for-scale.md} &sect;3.A).
 *
 * <p><strong>Design (fixed for WS1.1, do not swap for an embedded KV):</strong>
 * a plain {@code ConcurrentHashMap<String, Entry>} keyed by {@link Key#string()}.
 * No RocksDB/LMDB/SQLite. The on-disk cache directory that {@link
 * CachedBlobStorage} maintains is the source of truth; this index is a
 * rebuildable accelerator, hydrated by {@link #rebuildFromDisk(Path)} on boot
 * (by scanning the per-file sidecars {@link CachedBlobStorage} writes next to
 * each cached file) and kept current incrementally as entries are written,
 * cold-filled, or removed.</p>
 *
 * <p>Negative entries ({@code negative=true} + {@code negativeUntil}) record a
 * confirmed blob-store miss for a short TTL so a repeated {@code exists()}/
 * {@code value()} on a key that does not exist does not re-hit the blob store
 * on every call.</p>
 *
 * <p>Thread-safety: every method here is safe for unsynchronized concurrent
 * use. {@link #rebuildFromDisk(Path)} performs a blocking recursive directory
 * walk and must only be called from a boot thread (e.g. storage-factory
 * construction), never from the Vert.x event loop -- see CLAUDE.md's thread
 * model.</p>
 *
 * @since 2.3.0
 */
public final class StorageIndex {

    /**
     * Sidecar file suffix, mirroring the convention {@code DiskCacheStorage}
     * already uses for its own per-file metadata (a {@code .meta} properties
     * file next to the cached data file).
     */
    static final String SIDECAR_SUFFIX = ".meta";

    /**
     * Name of the staging directory {@link CachedBlobStorage} (via {@code
     * FileStorage}) uses for atomic writes; entries under it are in-flight
     * temp files, never a materialized cache entry, and must be skipped by
     * the boot scan.
     */
    private static final String STAGING_DIR = ".tmp";

    /**
     * The index itself: key string -&gt; entry.
     */
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Clock, injected for deterministic tests (fast-forwardable fake clock
     * instead of {@code Thread.sleep} -- CLAUDE.md "never assert wall-clock").
     */
    private final Clock clock;

    /**
     * New index using the system clock.
     */
    public StorageIndex() {
        this(Clock.systemUTC());
    }

    /**
     * New index using an injected clock.
     *
     * @param clock Clock used for {@code lastModified}/negative-TTL bookkeeping.
     */
    public StorageIndex(final Clock clock) {
        this.clock = clock;
    }

    /**
     * Look up a key without contacting the blob store.
     *
     * @param key Key.
     * @return The entry if known (positive or still-valid negative); {@link
     *  Optional#empty()} if unknown or the negative entry's TTL has elapsed
     *  (in which case it is evicted so the caller re-resolves it).
     */
    public Optional<Entry> knownEntry(final Key key) {
        final String raw = key.string();
        final Entry current = this.entries.get(raw);
        final Optional<Entry> result;
        if (current == null) {
            result = Optional.empty();
        } else if (current.negative() && current.negativeUntilEpochMilli() <= this.clock.millis()) {
            this.entries.remove(raw, current);
            result = Optional.empty();
        } else {
            result = Optional.of(current);
        }
        return result;
    }

    /**
     * Look up a key that is both known-positive, present on the local disk
     * tier, and still inside its freshness window -- i.e. safe to serve
     * bytes from disk with no blob-store re-validation.
     *
     * @param key Key.
     * @param freshnessTtl How long a disk copy is trusted without re-validation.
     * @return The entry if it qualifies for a disk-served hit.
     */
    public Optional<Entry> freshEntry(final Key key, final Duration freshnessTtl) {
        return this.knownEntry(key).filter(
            entry -> !entry.negative() && entry.presentOnDisk()
                && this.clock.millis() - entry.lastModifiedEpochMilli() <= freshnessTtl.toMillis()
        );
    }

    /**
     * Record (or overwrite) a positive entry.
     *
     * @param key Key.
     * @param size Object size in bytes.
     * @param etag Backend etag, or {@code null} if unavailable.
     * @param digest Content digest (hex), or {@code null} if not computed.
     * @param presentOnDisk Whether the bytes are cached on the local disk tier.
     */
    public void putPresent(
        final Key key,
        final long size,
        final String etag,
        final String digest,
        final boolean presentOnDisk
    ) {
        this.entries.put(
            key.string(),
            Entry.present(size, etag, digest, this.clock.millis(), presentOnDisk)
        );
    }

    /**
     * Record a confirmed blob-store miss for {@code negativeTtl}.
     *
     * @param key Key confirmed absent in the blob store.
     * @param negativeTtl How long to remember the miss.
     */
    public void putNegative(final Key key, final Duration negativeTtl) {
        this.entries.put(key.string(), Entry.negative(this.clock.millis() + negativeTtl.toMillis()));
    }

    /**
     * Drop any entry for {@code key} (e.g. on delete or local eviction).
     *
     * @param key Key.
     */
    public void remove(final Key key) {
        this.entries.remove(key.string());
    }

    /**
     * Recursive prefix listing, answered purely from the index.
     *
     * <p>Scoped to what this index has observed (boot disk scan plus
     * subsequent writes/cold-fills) -- see the {@code cache.mode: index}
     * section of {@code docs/admin-guide/storage-backends.md} for the
     * consistency trade-off this implies versus a live blob-store listing.</p>
     *
     * @param prefix Prefix.
     * @return Matching keys.
     */
    public Collection<Key> listPrefix(final Key prefix) {
        final String raw = prefix.string();
        final List<Key> matches = new ArrayList<>();
        for (final Map.Entry<String, Entry> candidate : this.entries.entrySet()) {
            if (StorageIndex.matchesPrefix(candidate.getKey(), raw) && !candidate.getValue().negative()) {
                matches.add(new Key.From(candidate.getKey()));
            }
        }
        return matches;
    }

    /**
     * Hierarchical (one-level, delimited) prefix listing, answered purely
     * from the index.
     *
     * @param prefix Prefix.
     * @param delimiter Delimiter, typically {@code "/"}.
     * @return Files and directories one level below {@code prefix}.
     */
    public ListResult listPrefix(final Key prefix, final String delimiter) {
        final List<Key> files = new ArrayList<>();
        final LinkedHashSet<Key> dirs = new LinkedHashSet<>();
        final String raw = prefix.string();
        final int skip = raw.isEmpty() ? 0 : raw.length() + delimiter.length();
        for (final Key key : this.listPrefix(prefix)) {
            final String str = key.string();
            if (str.length() <= skip) {
                continue;
            }
            final String relative = str.substring(skip);
            final int idx = relative.indexOf(delimiter);
            if (idx < 0) {
                files.add(key);
            } else {
                dirs.add(new Key.From(str.substring(0, skip + idx + delimiter.length())));
            }
        }
        return new ListResult.Simple(files, new ArrayList<>(dirs));
    }

    /**
     * Current number of tracked entries (positive and negative). Intended
     * for tests and future metrics, not a correctness dependency.
     *
     * @return Approximate entry count.
     */
    public int size() {
        return this.entries.size();
    }

    /**
     * Boot-time rehydration: recursively scans {@code root} for cached data
     * files and their {@code .meta} sidecars (written by {@link
     * CachedBlobStorage} next to each cached file, the same convention {@code
     * DiskCacheStorage} already uses) and repopulates the index from them.
     *
     * <p>Blocking; boot-thread only (see class javadoc). Best-effort: a
     * sidecar that fails to parse falls back to filesystem attributes so one
     * corrupt sidecar cannot abort the whole rebuild; a directory that
     * cannot be walked at all leaves the index empty for that subtree,
     * self-healing via cold fills.</p>
     *
     * @param root Cache namespace root directory.
     */
    public void rebuildFromDisk(final Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(StorageIndex::isCacheableDataFile)
                .forEach(dataFile -> this.hydrateFromDataFile(root, dataFile));
        } catch (final IOException ex) {
            EcsLogger.warn("com.auto1.pantera.asto.blob")
                .message("StorageIndex boot rebuild: failed to walk cache directory")
                .eventCategory("file")
                .eventAction("storage_index_rebuild")
                .eventOutcome("failure")
                .error(ex)
                .field("file.path", root.toString())
                .field("log.source", "application")
                .log();
        }
        EcsLogger.info("com.auto1.pantera.asto.blob")
            .message("StorageIndex boot rebuild complete")
            .eventCategory("file")
            .eventAction("storage_index_rebuild")
            .eventOutcome("success")
            .field("file.path", root.toString())
            .field("pantera.storage.index.entries", this.entries.size())
            .field("log.source", "application")
            .log();
    }

    private void hydrateFromDataFile(final Path root, final Path dataFile) {
        final Path sidecar = Path.of(dataFile + StorageIndex.SIDECAR_SUFFIX);
        final Optional<Sidecar> parsed = Files.exists(sidecar)
            ? Sidecar.read(sidecar)
            : Optional.empty();
        final String relKey = root.relativize(dataFile).toString().replace('\\', '/');
        final long size = parsed.map(Sidecar::size).orElseGet(() -> StorageIndex.safeSize(dataFile));
        final long lastModified = parsed.map(Sidecar::lastModifiedEpochMilli)
            .orElseGet(() -> StorageIndex.safeLastModified(dataFile));
        this.entries.put(
            relKey,
            Entry.present(
                size,
                parsed.map(Sidecar::etag).orElse(null),
                parsed.map(Sidecar::digest).orElse(null),
                lastModified,
                true
            )
        );
    }

    private static boolean isCacheableDataFile(final Path path) {
        final String name = path.getFileName().toString();
        final Path parent = path.getParent();
        final boolean staging = parent != null
            && StorageIndex.STAGING_DIR.equals(parent.getFileName().toString());
        return !name.endsWith(StorageIndex.SIDECAR_SUFFIX) && !staging;
    }

    private static long safeSize(final Path dataFile) {
        long size = 0L;
        try {
            size = Files.size(dataFile);
        } catch (final IOException ex) { // NOPMD EmptyCatchBlock - best-effort fallback; entry keeps size=0 and self-heals on next write
            // EXPECTED: file may have vanished mid-scan; size defaults to 0,
            // the entry self-heals on the next successful write/cold-fill.
        }
        return size;
    }

    private static long safeLastModified(final Path dataFile) {
        long millis = 0L;
        try {
            millis = Files.getLastModifiedTime(dataFile).toMillis();
        } catch (final IOException ex) { // NOPMD EmptyCatchBlock - best-effort fallback; entry treated as stale (epoch 0) and re-validated on next access
            // EXPECTED: file may have vanished mid-scan; treating it as
            // maximally stale (epoch 0) is safe -- it just forces a
            // freshness re-check rather than serving bad data.
        }
        return millis;
    }

    private static boolean matchesPrefix(final String candidate, final String prefix) {
        return prefix.isEmpty() || candidate.equals(prefix) || candidate.startsWith(prefix);
    }

    /**
     * Immutable index entry.
     *
     * @param size Object size in bytes (meaningless for negative entries).
     * @param etag Backend etag, or {@code null} if unavailable.
     * @param digest Content digest (hex), or {@code null} if not computed.
     * @param lastModifiedEpochMilli When this entry was (re)confirmed positive.
     * @param presentOnDisk Whether bytes are cached on the local disk tier
     *  (as opposed to a metadata-only entry hydrated from a HEAD).
     * @param negative Whether this is a negative (confirmed-absent) entry.
     * @param negativeUntilEpochMilli Expiry of a negative entry; meaningless
     *  for positive entries.
     * @since 2.3.0
     */
    public record Entry(
        long size,
        String etag,
        String digest,
        long lastModifiedEpochMilli,
        boolean presentOnDisk,
        boolean negative,
        long negativeUntilEpochMilli
    ) {
        /**
         * Build a positive entry.
         *
         * @param size Object size in bytes.
         * @param etag Backend etag, or {@code null}.
         * @param digest Content digest (hex), or {@code null}.
         * @param lastModifiedEpochMilli Confirmation timestamp.
         * @param presentOnDisk Whether bytes are on the local disk tier.
         * @return New positive entry.
         */
        public static Entry present(
            final long size,
            final String etag,
            final String digest,
            final long lastModifiedEpochMilli,
            final boolean presentOnDisk
        ) {
            return new Entry(size, etag, digest, lastModifiedEpochMilli, presentOnDisk, false, 0L);
        }

        /**
         * Build a negative (confirmed-absent) entry.
         *
         * @param negativeUntilEpochMilli Epoch millis after which the miss
         *  must be re-confirmed against the blob store.
         * @return New negative entry.
         */
        public static Entry negative(final long negativeUntilEpochMilli) {
            return new Entry(0L, null, null, 0L, false, true, negativeUntilEpochMilli);
        }
    }

    /**
     * Per-file sidecar metadata, persisted next to each cached data file as
     * a {@code .meta} properties file -- the same convention {@code
     * DiskCacheStorage} uses for its own cache, so {@link #rebuildFromDisk}
     * can hydrate size/etag/digest/last-modified without re-deriving them
     * all from raw filesystem attributes. Package-visible: written by {@link
     * CachedBlobStorage} immediately after a successful disk write, read
     * here during the boot scan.
     *
     * @since 2.3.0
     */
    record Sidecar(long size, String etag, String digest, long lastModifiedEpochMilli) {

        private static final String KEY_SIZE = "size";
        private static final String KEY_ETAG = "etag";
        private static final String KEY_DIGEST = "digest";
        private static final String KEY_LAST_MODIFIED = "lastModified";

        /**
         * Persist an entry's sidecar next to its data file.
         *
         * @param sidecarPath Path to write (data file path + {@code .meta}).
         * @param entry Entry to persist.
         * @throws IOException If the sidecar cannot be written.
         */
        static void write(final Path sidecarPath, final Entry entry) throws IOException {
            final Properties props = new Properties();
            props.setProperty(KEY_SIZE, Long.toString(entry.size()));
            if (entry.etag() != null) {
                props.setProperty(KEY_ETAG, entry.etag());
            }
            if (entry.digest() != null) {
                props.setProperty(KEY_DIGEST, entry.digest());
            }
            props.setProperty(KEY_LAST_MODIFIED, Long.toString(entry.lastModifiedEpochMilli()));
            try (OutputStream out = Files.newOutputStream(sidecarPath)) {
                props.store(out, "pantera CachedBlobStorage index sidecar");
            }
        }

        /**
         * Read a sidecar, tolerating corruption by returning empty.
         *
         * @param sidecarPath Sidecar file path.
         * @return Parsed sidecar, or empty if unreadable/corrupt.
         */
        static Optional<Sidecar> read(final Path sidecarPath) {
            Optional<Sidecar> result;
            try (InputStream in = Files.newInputStream(sidecarPath)) {
                final Properties props = new Properties();
                props.load(in);
                result = Optional.of(
                    new Sidecar(
                        Long.parseLong(props.getProperty(KEY_SIZE, "0")),
                        props.getProperty(KEY_ETAG),
                        props.getProperty(KEY_DIGEST),
                        Long.parseLong(props.getProperty(KEY_LAST_MODIFIED, "0"))
                    )
                );
            } catch (final IOException | NumberFormatException ex) {
                result = Optional.empty();
            }
            return result;
        }
    }
}
