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
package com.auto1.pantera.security.perms;

import com.amihaiemil.eoyaml.Node;
import com.amihaiemil.eoyaml.Scalar;
import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlNode;
import com.amihaiemil.eoyaml.YamlSequence;
import com.auto1.pantera.asto.factory.Config;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;

/**
 * Permission configuration.
 * @since 1.2
 */
public interface PermissionConfig extends Config {

    /**
     * Gets sequence of keys.
     *
     * @return Keys sequence.
     */
    Set<String> keys();

    /**
     * Yaml permission config.
     * Implementation note:
     * Yaml permission config allows {@link AdapterBasicPermission#WILDCARD} yaml sequence.In
     * yamls `*` sign can be quoted. Thus, we need to handle various quotes properly.
     * @since 1.2
     */
    final class FromYamlMapping implements PermissionConfig {

        /**
         * Yaml mapping to read permission from.
         */
        private final YamlMapping yaml;

        /**
         * Ctor.
         * @param yaml Yaml mapping to read permission from
         */
        public FromYamlMapping(final YamlMapping yaml) {
            this.yaml = yaml;
        }

        @Override
        public String string(final String key) {
            return this.yaml.string(key);
        }

        @Override
        public Set<String> sequence(final String key) {
            final Set<String> res;
            if (AdapterBasicPermission.WILDCARD.equals(key)) {
                res = this.yaml.yamlSequence(this.getWildcardKey(key)).values().stream()
                    .map(item -> item.asScalar().value()).collect(Collectors.toSet());
            } else {
                res = this.yaml.yamlSequence(key).values().stream().map(
                    item -> item.asScalar().value()
                ).collect(Collectors.toSet());
            }
            return res;
        }

        @Override
        public Set<String> keys() {
            return this.yaml.keys().stream().map(node -> node.asScalar().value())
                .map(FromYamlMapping::cleanName).collect(Collectors.toSet());
        }

        @Override
        public PermissionConfig config(final String key) {
            final PermissionConfig res;
            if (AdapterBasicPermission.WILDCARD.equals(key)) {
                res = FromYamlMapping.configByNode(this.yaml.value(this.getWildcardKey(key)));
            } else {
                res = FromYamlMapping.configByNode(this.yaml.value(key));
            }
            return res;
        }

        @Override
        public boolean isEmpty() {
            return this.yaml == null || this.yaml.isEmpty();
        }

        /**
         * Find wildcard key as it can be escaped in various ways.
         * @param key The key
         * @return Escaped key to get sequence or mapping with it
         */
        private Scalar getWildcardKey(final String key) {
            return this.yaml.keys().stream().map(YamlNode::asScalar).filter(
                item -> item.value().contains(AdapterBasicPermission.WILDCARD)
            ).findFirst().orElseThrow(
                () -> new IllegalStateException(
                    String.format("Sequence %s not found", key)
                )
            );
        }

        /**
         * Cleans wildcard value from various escape signs.
         * @param value Value to check and clean
         * @return Cleaned value
         */
        private static String cleanName(final String value) {
            String res = value;
            if (value.contains(AdapterBasicPermission.WILDCARD)) {
                res = value.replace("\"", "").replace("'", "").replace("\\", "");
            }
            return res;
        }

        /**
         * Config by yaml node with respect to this node type.
         * @param node Yaml node to create config from
         * @return Sub-config
         */
        private static PermissionConfig configByNode(final YamlNode node) {
            final PermissionConfig res;
            if (node.type() == Node.MAPPING) {
                res = new FromYamlMapping(node.asMapping());
            } else if (node.type() == Node.SEQUENCE) {
                res = new FromYamlSequence(node.asSequence());
            } else {
                throw new IllegalArgumentException("Yaml sub-config not found!");
            }
            return res;
        }
    }

    /**
     * Permission config from yaml sequence. In this implementation, string parameter represents
     * sequence index, thus integer value is expected. Method {@link FromYamlSequence#keys()}
     * returns the sequence as a set of strings.
     * @since 1.3
     */
    final class FromYamlSequence implements PermissionConfig {

        /**
         * Yaml sequence.
         */
        private final YamlSequence seq;

        /**
         * Ctor.
         * @param seq Sequence
         */
        public FromYamlSequence(final YamlSequence seq) {
            this.seq = seq;
        }

        @Override
        public Set<String> keys() {
            return this.seq.values().stream().map(YamlNode::asScalar).map(Scalar::value)
                .collect(Collectors.toSet());
        }

        @Override
        public String string(final String index) {
            return this.seq.string(Integer.parseInt(index));
        }

        @Override
        public Collection<String> sequence(final String index) {
            return this.seq.yamlSequence(Integer.parseInt(index)).values().stream()
                .map(YamlNode::asScalar).map(Scalar::value).collect(Collectors.toSet());
        }

        @Override
        public PermissionConfig config(final String index) {
            final int ind = Integer.parseInt(index);
            final PermissionConfig res;
            if (this.seq.yamlSequence(ind) != null) {
                res = new FromYamlSequence(this.seq.yamlSequence(ind));
            } else if (this.seq.yamlMapping(ind) != null) {
                res = new FromYamlMapping(this.seq.yamlMapping(ind));
            } else {
                throw new IllegalArgumentException(
                    String.format("Sub config by index %s not found", index)
                );
            }
            return res;
        }

        @Override
        public boolean isEmpty() {
            return this.seq == null || this.seq.isEmpty();
        }
    }

    /**
     * Permission config from JSON object. Used by database-backed policy
     * to bridge JSON permission data into the PermissionConfig interface.
     * @since 1.21
     */
    final class FromJsonObject implements PermissionConfig {

        /**
         * JSON object to read permission from.
         */
        private final JsonObject json;

        /**
         * Ctor.
         * @param json JSON object to read permission from
         */
        public FromJsonObject(final JsonObject json) {
            this.json = json;
        }

        @Override
        public String string(final String key) {
            if (this.json.containsKey(key)) {
                return this.json.getString(key);
            }
            return null;
        }

        @Override
        public Set<String> sequence(final String key) {
            final JsonArray arr = this.json.getJsonArray(key);
            return arr.stream()
                .map(v -> ((JsonString) v).getString())
                .collect(Collectors.toSet());
        }

        @Override
        public Set<String> keys() {
            return this.json.keySet();
        }

        @Override
        public PermissionConfig config(final String key) {
            final JsonValue val = this.json.get(key);
            if (val instanceof JsonObject) {
                return new FromJsonObject((JsonObject) val);
            } else if (val instanceof JsonArray) {
                return new FromJsonArray((JsonArray) val);
            }
            throw new IllegalArgumentException(
                String.format("JSON sub-config not found for key: %s", key)
            );
        }

        @Override
        public boolean isEmpty() {
            return this.json == null || this.json.isEmpty();
        }
    }

    /**
     * Permission config from JSON array. Used by database-backed policy
     * to bridge JSON permission data into the PermissionConfig interface.
     * In this implementation, method {@link FromJsonArray#keys()} returns
     * the array elements as a set of strings.
     * @since 1.21
     */
    final class FromJsonArray implements PermissionConfig {

        /**
         * JSON array.
         */
        private final JsonArray arr;

        /**
         * Ctor.
         * @param arr JSON array
         */
        public FromJsonArray(final JsonArray arr) {
            this.arr = arr;
        }

        @Override
        public Set<String> keys() {
            return this.arr.stream()
                .map(v -> ((JsonString) v).getString())
                .collect(Collectors.toSet());
        }

        @Override
        public String string(final String index) {
            return this.arr.getString(Integer.parseInt(index));
        }

        @Override
        public Collection<String> sequence(final String index) {
            return this.arr.getJsonArray(Integer.parseInt(index)).stream()
                .map(v -> ((JsonString) v).getString())
                .collect(Collectors.toSet());
        }

        @Override
        public PermissionConfig config(final String index) {
            final int ind = Integer.parseInt(index);
            final JsonValue val = this.arr.get(ind);
            if (val instanceof JsonObject) {
                return new FromJsonObject((JsonObject) val);
            } else if (val instanceof JsonArray) {
                return new FromJsonArray((JsonArray) val);
            }
            throw new IllegalArgumentException(
                String.format("Sub config by index %s not found", index)
            );
        }

        @Override
        public boolean isEmpty() {
            return this.arr == null || this.arr.isEmpty();
        }
    }
}
