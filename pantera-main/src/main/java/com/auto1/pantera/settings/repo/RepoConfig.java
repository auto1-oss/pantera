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

import com.amihaiemil.eoyaml.Scalar;
import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlNode;
import com.amihaiemil.eoyaml.YamlSequence;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.asto.blob.DownloadMode;
import com.auto1.pantera.asto.blob.DownloadPolicy;
import com.auto1.pantera.cache.StoragesCache;
import com.auto1.pantera.http.client.HttpClientSettings;
import com.auto1.pantera.http.client.RemoteConfig;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.settings.StorageByAlias;
import com.google.common.base.Strings;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Repository configuration.
 */
public final class RepoConfig {

    public static RepoConfig from(
        YamlMapping yaml,
        StorageByAlias aliases,
        Key prefix,
        StoragesCache cache,
        boolean metrics
    ) {
        YamlMapping repoYaml = Objects.requireNonNull(
            yaml.yamlMapping("repo"), "Invalid repo configuration"
        );

        String type = repoYaml.string("type");
        if (Strings.isNullOrEmpty(type)) {
            throw new IllegalStateException("yaml repo.type is absent");
        }

        Storage storage = null;
        YamlNode storageNode = repoYaml.value("storage");
        if (storageNode != null) {
            // Direct storage without wrappers at THIS layer:
            // - No generic MicrometerStorage/LoggingStorage decorator around
            //   the outer Storage surface (exists/value/save/list/...) --
            //   that would re-add per-call overhead on the exact hot path
            //   WS1.1 built CachedBlobStorage's index to avoid (a metrics
            //   wrapper here cannot tell a zero-round-trip disk hit from a
            //   genuine blob-store call, so it would only add cost, not signal).
            // - No LoggingStorage (already bypassed, 2-50% overhead on writes)
            // Request-level logging and metrics still active via Vert.x HTTP.
            // WS1.6 instead meters the ACTUAL blob-store tier (S3 GET/HEAD/
            // PUT/DELETE/LIST count+latency+error/throttle) one layer lower,
            // in S3StorageFactory (MeteredBlobStore wraps the reference
            // BlobStore before CachedBlobStorage/cache.mode:index ever sees
            // it) plus CachedBlobStorage's own cache-tier gauges/counters
            // (disk hit ratio, eviction bytes, write-back queue depth) --
            // see docs/admin-guide/storage-backends.md.
            storage = new SubStorage(prefix, storage(cache, aliases, storageNode));
        }

        return new RepoConfig(repoYaml, prefix.string(), type, storage);
    }

    static Storage storage(StoragesCache storages, StorageByAlias aliases, YamlNode node) {
        final Storage res;
        if (node instanceof Scalar) {
            res = aliases.storage(storages, ((Scalar) node).value());
        } else if (node instanceof YamlMapping) {
            res = storages.storage((YamlMapping) node);
        } else {
            throw new IllegalStateException(
                String.format("Invalid storage config: %s", node)
            );
        }
        return res;
    }

    private final YamlMapping repoYaml;
    private final String name;
    private final String type;
    private final Storage storage;

    RepoConfig(YamlMapping repoYaml, String name, String type, Storage storage) {
        this.repoYaml = repoYaml;
        this.name = name;
        this.type = type;
        this.storage = storage;
    }

    /**
     * Repository name.
     *
     * @return Name string.
     */
    public String name() {
        return this.name;
    }

    /**
     * Repository type.
     * @return Async string of type
     */
    public String type() {
        return this.type;
    }

    /**
     * Repository port.
     *
     * @return Repository port.
     */
    public OptionalInt port() {
        return Stream.ofNullable(this.repoYaml().string("port"))
            .mapToInt(Integer::parseInt)
            .findFirst();
    }

    /**
     * Start repo on http3 version?
     * @return True if so
     */
    public boolean startOnHttp3() {
        return Boolean.parseBoolean(this.repoYaml().string("http3"));
    }

    /**
     * Repository path.
     * @return Async string of path
     */
    public String path() {
        return this.string("path");
    }

    /**
     * Repository URL.
     *
     * @return Async string of URL
     */
    public URL url() {
        final String str = this.string("url");
        try {
            return URI.create(str).toURL();
        } catch (final MalformedURLException ex) {
            throw new IllegalArgumentException(
                String.format("Failed to build URL from '%s'", str),
                ex
            );
        }
    }

    /**
     * Read maximum allowed Content-Length value for incoming requests.
     *
     * @return Maximum allowed value, empty if none specified.
     */
    public Optional<Long> contentLengthMax() {
        return this.stringOpt("content-length-max").map(Long::valueOf);
    }

    /**
     * Group members list (for *-group repositories).
     * The order of members defines resolution priority.
     *
     * @return List of member repository names or empty list if not specified.
     */
    public List<String> members() {
        final YamlSequence seq = this.repoYaml.yamlSequence("members");
        if (seq == null) {
            return Collections.emptyList();
        }
        final List<String> res = new ArrayList<>(seq.size());
        seq.forEach(node -> {
            if (node instanceof Scalar scalar) {
                res.add(scalar.value());
            } else {
                throw new IllegalStateException("`members` element is not scalar in group config");
            }
        });
        return res;
    }

    /**
     * Group member request timeout in seconds (for *-group repositories).
     * Controls how long to wait for each member repository to respond.
     *
     * @return Timeout in seconds, or empty if not specified (uses default).
     */
    public Optional<Long> groupMemberTimeout() {
        return this.stringOpt("member_timeout").map(Long::valueOf);
    }

    /**
     * A single remote configuration.
     * <p>Fails if there are more than one remote configs or no remotes specified.
     *
     * @return Remote configuration
     */
    public RemoteConfig remoteConfig() {
        final List<RemoteConfig> remotes = remotes();
        if (remotes.isEmpty()) {
            throw new IllegalArgumentException("No remotes specified");
        }
        if (remotes.size() > 1) {
            throw new IllegalArgumentException("Only one remote is allowed");
        }
        return remotes.getFirst();
    }

    /**
     * Remote configurations.
     *
     * @return List of remote configurations
     */
    public List<RemoteConfig> remotes() {
        YamlSequence seq = repoYaml.yamlSequence("remotes");
        if (seq != null) {
            List<RemoteConfig> res = new ArrayList<>(seq.size());
            seq.forEach(node -> {
                if (node instanceof YamlMapping mapping) {
                    res.add(RemoteConfig.form(mapping));
                } else {
                    throw new IllegalStateException("`remotes` element is not mapping in proxy config");
                }
            });
            res.sort((c1, c2) -> Integer.compare(c2.priority(), c1.priority()));
            return res;
        }
        return Collections.emptyList();
    }

    /**
     * Storage.
     * @return Async storage for repo
     */
    public Storage storage() {
        return this.storageOpt().orElseThrow(
            () -> new IllegalStateException("Storage is not configured")
        );
    }

    /**
     * Create storage if configured in given YAML.
     *
     * @return Async storage for repo
     */
    public Optional<Storage> storageOpt() {
        return Optional.ofNullable(this.storage);
    }

    /**
     * Custom repository configuration.
     *
     * @return Async custom repository config or Optional.empty
     */
    public Optional<YamlMapping> settings() {
        return Optional.ofNullable(this.repoYaml().yamlMapping("settings"));
    }

    /**
     * Group routing rules for directing requests to specific members
     * based on path prefix or pattern matching.
     *
     * @return List of routing rules or empty list if not specified.
     */
    public List<com.auto1.pantera.group.RoutingRule> routingRules() {
        final YamlSequence seq = this.repoYaml.yamlSequence("routing");
        if (seq == null) {
            return Collections.emptyList();
        }
        final List<com.auto1.pantera.group.RoutingRule> rules = new ArrayList<>(seq.size());
        seq.forEach(node -> {
            if (node instanceof YamlMapping mapping) {
                final String member = mapping.string("member");
                if (member == null || member.isEmpty()) {
                    throw new IllegalStateException("routing rule missing 'member' field");
                }
                final String prefix = mapping.string("prefix");
                final String pattern = mapping.string("pattern");
                if (prefix != null && !prefix.isEmpty()) {
                    rules.add(new com.auto1.pantera.group.RoutingRule.PathPrefix(member, prefix));
                } else if (pattern != null && !pattern.isEmpty()) {
                    rules.add(new com.auto1.pantera.group.RoutingRule.PathPattern(member, pattern));
                } else {
                    throw new IllegalStateException(
                        "routing rule for member '" + member
                            + "' must have 'prefix' or 'pattern'"
                    );
                }
            } else {
                throw new IllegalStateException("`routing` element is not mapping in group config");
            }
        });
        return rules;
    }

    /**
     * Per-repo cooldown window configured in this repository's YAML.
     * Present when the repo has {@code cooldown.duration} set (ISO-8601, e.g. {@code P30D}).
     * When present, this duration overrides the global and per-type cooldown settings.
     *
     * @return Cooldown duration if configured, empty otherwise
     */
    public Optional<Duration> cooldownDuration() {
        final YamlMapping cooldown = this.repoYaml().yamlMapping("cooldown");
        if (cooldown == null) {
            return Optional.empty();
        }
        final String duration = cooldown.string("duration");
        if (duration == null) {
            return Optional.empty();
        }
        return Optional.of(Duration.parse(duration));
    }

    public Optional<HttpClientSettings> httpClientSettings() {
        final YamlMapping client = this.repoYaml().yamlMapping("http_client");
        return client != null ? Optional.of(HttpClientSettings.from(client)) : Optional.empty();
    }

    /**
     * Maven/Gradle {@code .asc} PGP signature verification (WS4-maven.1).
     * When {@code true}, a proxy fetch or hosted store of a primary artifact
     * with a detached signature is verified against the admin-managed
     * {@code pgp_keyring} before the primary is cached/persisted; an empty
     * keyring rejects every signed artifact (fail-closed). Default
     * {@code false} — byte-identical to pre-2.3.0 behaviour, no keyring
     * lookups occur.
     *
     * @return True when {@code .asc} verification is required for this repo
     */
    public boolean verifyPgp() {
        return Boolean.parseBoolean(this.repoYaml().string("verifyPgp"));
    }

    /**
     * Release-redeploy immutability (WS4-maven.6). When {@code true}, a
     * hosted deploy that would overwrite an existing non-SNAPSHOT primary
     * artifact is rejected with 409 Conflict instead of silently
     * overwriting it. SNAPSHOT redeploys are always allowed regardless of
     * this setting. Default {@code false} (legacy overwrite behaviour).
     *
     * @return True when release redeploys are rejected for this repo
     */
    public boolean releaseImmutable() {
        return Boolean.parseBoolean(this.repoYaml().string("releaseImmutable"));
    }

    /**
     * Recognized {@code download-mode} values, used only to distinguish "not
     * configured" from "configured but unrecognized" for the warning logged
     * by {@link #downloadMode()} -- the parse itself ({@link
     * DownloadMode#from}) already defaults either case to {@link
     * DownloadMode#STREAM}.
     */
    private static final Set<String> VALID_DOWNLOAD_MODES = Set.of("redirect", "stream", "auto");

    /**
     * WS1.7 (spec {@code WS1-storage-for-scale.md} &sect;3.B2): this repo's
     * presigned-direct-download mode for redirect-eligible immutable byte
     * routes (Docker blob GET is the first wired case -- see
     * docker-adapter's {@code AstoDocker}). Default {@link
     * DownloadMode#STREAM}: byte-identical to pre-2.3.0 behaviour until an
     * operator opts in. Never applies to metadata routes -- those are never
     * wired to consult this at all, regardless of value.
     *
     * @return Configured download mode, defaulting to {@link
     *  DownloadMode#STREAM}.
     */
    public DownloadMode downloadMode() {
        final Optional<String> raw = this.stringOpt("download-mode");
        raw.filter(val -> !VALID_DOWNLOAD_MODES.contains(val.trim().toLowerCase(Locale.ROOT)))
            .ifPresent(val -> EcsLogger.warn("com.auto1.pantera.settings")
                .message("Unrecognized download-mode '" + val + "' for repo '" + this.name
                    + "'; defaulting to stream")
                .eventCategory("configuration")
                .eventAction("repo_config_invalid_value")
                .eventOutcome("failure")
                .field("repository.name", this.name)
                .field("log.source", "application")
                .log());
        return DownloadMode.from(raw.orElse(null));
    }

    /**
     * WS1.7: this repo's presigned-URL validity window, in seconds. Falls
     * back to {@link DownloadPolicy#DEFAULT_PRESIGN_TTL_SECONDS} when unset
     * or non-positive.
     *
     * @return Configured (or default) presign TTL, in seconds.
     */
    public long presignTtlSeconds() {
        return this.stringOpt("presign-ttl-seconds")
            .map(Long::parseLong)
            .filter(val -> val > 0)
            .orElse(DownloadPolicy.DEFAULT_PRESIGN_TTL_SECONDS);
    }

    /**
     * WS1.7: this repo's resolved download policy -- {@link #downloadMode()}
     * plus {@link #presignTtlSeconds()} -- ready to hand to a serving-side
     * {@code Docker}/route implementation that has opted into redirect
     * eligibility.
     *
     * @return Resolved download policy.
     */
    public DownloadPolicy downloadPolicy() {
        return new DownloadPolicy(this.downloadMode(), this.presignTtlSeconds());
    }

    /**
     * Repo part of YAML.
     *
     * @return Async YAML mapping
     */
    public YamlMapping repoYaml() {
        return repoYaml;
    }

    @Override
    public String toString() {
        return "RepoConfig{" +
            "name='" + name + '\'' +
            ", type='" + type + '\'' +
            '}';
    }

    /**
     * Reads string by key from repo part of YAML.
     *
     * @param key String key.
     * @return String value.
     */
    private String string(final String key) {
        return this.stringOpt(key).orElseThrow(
            () -> new IllegalStateException(String.format("yaml repo.%s is absent", key))
        );
    }

    /**
     * Reads string by key from repo part of YAML.
     *
     * @param key String key.
     * @return String value, empty if none present.
     */
    private Optional<String> stringOpt(final String key) {
        return Optional.ofNullable(this.repoYaml().string(key));
    }
}
