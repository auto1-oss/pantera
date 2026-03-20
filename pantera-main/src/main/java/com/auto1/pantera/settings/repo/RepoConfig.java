/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.settings.repo;

import com.amihaiemil.eoyaml.Scalar;
import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlNode;
import com.amihaiemil.eoyaml.YamlSequence;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.cache.StoragesCache;
import com.auto1.pantera.http.client.HttpClientSettings;
import com.auto1.pantera.http.client.RemoteConfig;
import com.auto1.pantera.settings.StorageByAlias;
import com.google.common.base.Strings;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
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
            // Direct storage without wrappers:
            // - No MicrometerStorage (metrics overhead, bypassed by optimized slices)
            // - No LoggingStorage (already bypassed, 2-50% overhead on writes)
            // Request-level logging and metrics still active via Vert.x HTTP
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
