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
import com.auto1.pantera.adapters.docker.DockerTagRefreshSettingsLoader;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.SubStorage;
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
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Repository configuration.
 */
public final class RepoConfig {

    /**
     * Flat key of a docker-proxy repository's own tag refresh age.
     */
    private static final String TAG_REFRESH = "tag_refresh_seconds";

    /**
     * Largest tag refresh age a repository may set, in seconds.
     */
    private static final long TAG_REFRESH_MAX = DockerTagRefreshSettingsLoader.MAX_SECONDS;

    public static RepoConfig from(
        YamlMapping yaml,
        StorageByAlias aliases,
        Key prefix,
        StoragesCache cache,
        boolean metrics
    ) {
        return RepoConfig.from(yaml, aliases, prefix.string(), prefix, cache, metrics);
    }

    /**
     * Repository config whose data directory is not its name: a repository
     * keeps the key it was created with, so a rename moves no data.
     *
     * @param yaml Repository settings
     * @param aliases Storage aliases
     * @param name Repository name, as clients address it
     * @param key Immutable repository key: the directory of its data in its
     *  storage and its identity in the artifact index
     * @param cache Storages cache
     * @param metrics Whether storage metrics are enabled
     * @return Repository config
     */
    public static RepoConfig from(
        YamlMapping yaml,
        StorageByAlias aliases,
        String name,
        Key key,
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
            // Direct storage without wrappers:
            // - No MicrometerStorage (metrics overhead, bypassed by optimized slices)
            // - No LoggingStorage (already bypassed, 2-50% overhead on writes)
            // Request-level logging and metrics still active via Vert.x HTTP
            storage = new SubStorage(key, storage(cache, aliases, storageNode));
        }

        return new RepoConfig(repoYaml, name, key.string(), type, storage);
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
    private final String key;
    private final String type;
    private final Storage storage;

    /**
     * The repository's own tag refresh age once read: null until then, so
     * an invalid value is logged once per loaded config.
     */
    private final AtomicReference<Optional<Duration>> tagRefresh = new AtomicReference<>();

    RepoConfig(YamlMapping repoYaml, String name, String type, Storage storage) {
        this(repoYaml, name, name, type, storage);
    }

    RepoConfig(YamlMapping repoYaml, String name, String key, String type, Storage storage) {
        this.repoYaml = repoYaml;
        this.name = name;
        this.key = key;
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
     * Immutable repository key: the directory of the repository's data in
     * its storage and its identity in the artifact index. Equal to the name
     * until the repository is renamed.
     *
     * @return Repository key
     */
    public String repoKey() {
        return this.key;
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
     * Repository URL, if explicitly configured.
     *
     * <p>Unlike {@link #url()} this never throws: group repositories have no
     * {@code url:} key, and callers deriving a client-facing base must be able
     * to ask without handling an exception.</p>
     *
     * @return Configured URL, or empty
     */
    public Optional<String> urlOpt() {
        return this.stringOpt("url");
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

    /**
     * Whether published artifacts of this repository are immutable: an
     * existing artifact can never be overwritten. Flat {@code repo.immutable}
     * key; a missing key means {@code true}. Only an explicit {@code false}
     * (any case) makes the repository overwritable -- any other value keeps
     * the safe default. Database configs reach here through
     * {@code Json2Yaml}, so a JSON boolean arrives as the scalar
     * {@code true} / {@code false}.
     *
     * @return False only when {@code immutable: false} is configured
     */
    public boolean immutable() {
        return !"false".equalsIgnoreCase(this.repoYaml().string("immutable"));
    }

    /**
     * Tag refresh age of a docker-proxy repository: how long a cached image
     * tag is served before the upstream is asked again for its digest. Flat
     * {@code repo.tag_refresh_seconds} key, an integer of seconds in
     * {@code 0..31536000} ({@code 0} = every request). Database configs reach
     * here through {@code Json2Yaml}, so a JSON number arrives as a scalar.
     * The repository API refuses any other value on save; one that reaches
     * here anyway (a YAML file) is ignored with one WARN per loaded config,
     * and the global {@code docker_tag_refresh_seconds} setting applies.
     *
     * @return The repository's own age, or empty when the global setting applies
     */
    public Optional<Duration> tagRefresh() {
        final Optional<Duration> known = this.tagRefresh.get();
        if (known != null) {
            return known;
        }
        final YamlNode node = this.repoYaml().value(RepoConfig.TAG_REFRESH);
        final String raw;
        if (node instanceof Scalar scalar) {
            raw = scalar.value();
        } else if (node == null) {
            raw = null;
        } else {
            raw = node.toString();
        }
        final Optional<Duration> parsed = RepoConfig.tagRefreshSeconds(raw).map(Duration::ofSeconds);
        if (this.tagRefresh.compareAndSet(null, parsed) && raw != null && parsed.isEmpty()) {
            EcsLogger.warn("com.auto1.pantera.settings")
                .message(
                    String.format(
                        "Repository '%s' ignores %s '%s': not an integer from 0 to %d; "
                            + "the global docker_tag_refresh_seconds setting applies",
                        this.name, RepoConfig.TAG_REFRESH, raw, RepoConfig.TAG_REFRESH_MAX
                    )
                )
                .eventCategory("configuration")
                .eventAction("repository_config_invalid")
                .eventOutcome("failure")
                .field("event.reason", RepoConfig.TAG_REFRESH)
                .field("repository.name", this.name)
                .field("repository.type", this.type)
                .field("log.source", "application")
                .log();
        }
        return this.tagRefresh.get();
    }

    /**
     * Parse a {@code tag_refresh_seconds} value.
     *
     * @param raw Scalar value, or null when the key is absent
     * @return Seconds when the value is an integer in {@code 0..31536000}
     */
    private static Optional<Long> tagRefreshSeconds(final String raw) {
        Optional<Long> res = Optional.empty();
        if (raw != null) {
            try {
                final long seconds = Long.parseLong(raw.trim());
                if (seconds >= 0 && seconds <= RepoConfig.TAG_REFRESH_MAX) {
                    res = Optional.of(seconds);
                }
            } catch (final NumberFormatException ignored) {
                // not an integer: ignored, the global setting applies
            }
        }
        return res;
    }

    public Optional<HttpClientSettings> httpClientSettings() {
        final YamlMapping client = this.repoYaml().yamlMapping("http_client");
        return client != null ? Optional.of(HttpClientSettings.from(client)) : Optional.empty();
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
