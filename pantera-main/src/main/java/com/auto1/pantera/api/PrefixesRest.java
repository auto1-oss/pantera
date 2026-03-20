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
package com.auto1.pantera.api;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlMappingBuilder;
import com.amihaiemil.eoyaml.YamlSequenceBuilder;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.settings.PrefixesConfig;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * REST API endpoints for managing global URL prefixes.
 *
 * @since 1.0
 */
public final class PrefixesRest {

    /**
     * Prefixes configuration.
     */
    private final PrefixesConfig prefixes;

    /**
     * Configuration storage.
     */
    private final Storage storage;

    /**
     * Path to pantera.yml file.
     */
    private final Path configPath;

    /**
     * Constructor.
     *
     * @param prefixes Prefixes configuration
     * @param storage Configuration storage
     * @param configPath Path to pantera.yml
     */
    public PrefixesRest(
        final PrefixesConfig prefixes,
        final Storage storage,
        final Path configPath
    ) {
        this.prefixes = prefixes;
        this.storage = storage;
        this.configPath = configPath;
    }

    /**
     * GET /api/admin/prefixes - List active prefixes.
     *
     * @param ctx Routing context
     */
    public void list(final RoutingContext ctx) {
        final JsonObject response = new JsonObject()
            .put("global_prefixes", new JsonArray(this.prefixes.prefixes()))
            .put("version", this.prefixes.version());
        
        ctx.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(response.encode());
    }

    /**
     * POST /api/admin/prefixes/validate - Validate candidate prefix list.
     *
     * @param ctx Routing context
     */
    public void validate(final RoutingContext ctx) {
        try {
            final JsonObject body = ctx.body().asJsonObject();
            if (body == null || !body.containsKey("global_prefixes")) {
                ctx.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("valid", false)
                        .put("error", "Missing 'global_prefixes' field")
                        .encode()
                    );
                return;
            }

            final JsonArray prefixesArray = body.getJsonArray("global_prefixes");
            final List<String> candidatePrefixes = new ArrayList<>();
            final Set<String> seen = new HashSet<>();
            final List<String> errors = new ArrayList<>();

            for (int i = 0; i < prefixesArray.size(); i++) {
                final String prefix = prefixesArray.getString(i);
                if (prefix == null || prefix.isBlank()) {
                    errors.add("Prefix at index " + i + " is blank");
                    continue;
                }
                if (prefix.contains("/")) {
                    errors.add("Prefix '" + prefix + "' contains invalid character '/'");
                }
                if (seen.contains(prefix)) {
                    errors.add("Duplicate prefix: '" + prefix + "'");
                }
                seen.add(prefix);
                candidatePrefixes.add(prefix);
            }

            final JsonObject response = new JsonObject()
                .put("valid", errors.isEmpty())
                .put("prefixes", new JsonArray(candidatePrefixes));
            
            if (!errors.isEmpty()) {
                response.put("errors", new JsonArray(errors));
            }

            ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(response.encode());
        } catch (final Exception ex) {
            EcsLogger.error("com.auto1.pantera.api")
                .message("Failed to validate prefixes")
                .eventCategory("api")
                .eventAction("prefixes_validate")
                .eventOutcome("failure")
                .error(ex)
                .log();
            ctx.response()
                .setStatusCode(400)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("valid", false)
                    .put("error", ex.getMessage())
                    .encode()
                );
        }
    }

    /**
     * PUT /api/admin/prefixes - Update prefixes in pantera.yml and trigger reload.
     *
     * @param ctx Routing context
     */
    public void update(final RoutingContext ctx) {
        try {
            final JsonObject body = ctx.body().asJsonObject();
            if (body == null || !body.containsKey("global_prefixes")) {
                ctx.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("success", false)
                        .put("error", "Missing 'global_prefixes' field")
                        .encode()
                    );
                return;
            }

            final JsonArray prefixesArray = body.getJsonArray("global_prefixes");
            final List<String> newPrefixes = new ArrayList<>();
            for (int i = 0; i < prefixesArray.size(); i++) {
                final String prefix = prefixesArray.getString(i);
                if (prefix != null && !prefix.isBlank()) {
                    newPrefixes.add(prefix);
                }
            }

            // Update pantera.yml
            this.updateConfigFile(newPrefixes)
                .thenAccept(updated -> {
                    if (updated) {
                        EcsLogger.info("com.auto1.pantera.api")
                            .message("Updated global_prefixes in pantera.yml: " + newPrefixes.toString())
                            .eventCategory("api")
                            .eventAction("prefixes_update")
                            .eventOutcome("success")
                            .log();
                        ctx.response()
                            .setStatusCode(200)
                            .putHeader("Content-Type", "application/json")
                            .end(new JsonObject()
                                .put("success", true)
                                .put("message", "Prefixes updated successfully")
                                .put("global_prefixes", new JsonArray(newPrefixes))
                                .encode()
                            );
                    } else {
                        ctx.response()
                            .setStatusCode(500)
                            .putHeader("Content-Type", "application/json")
                            .end(new JsonObject()
                                .put("success", false)
                                .put("error", "Failed to update configuration file")
                                .encode()
                            );
                    }
                })
                .exceptionally(ex -> {
                    EcsLogger.error("com.auto1.pantera.api")
                        .message("Failed to update prefixes")
                        .eventCategory("api")
                        .eventAction("prefixes_update")
                        .eventOutcome("failure")
                        .error(ex)
                        .log();
                    ctx.response()
                        .setStatusCode(500)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject()
                            .put("success", false)
                            .put("error", ex.getMessage())
                            .encode()
                        );
                    return null;
                });
        } catch (final Exception ex) {
            EcsLogger.error("com.auto1.pantera.api")
                .message("Failed to update prefixes")
                .eventCategory("api")
                .eventAction("prefixes_update")
                .eventOutcome("failure")
                .error(ex)
                .log();
            ctx.response()
                .setStatusCode(400)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("success", false)
                    .put("error", ex.getMessage())
                    .encode()
                );
        }
    }

    /**
     * Update pantera.yml with new prefixes.
     *
     * @param newPrefixes New list of prefixes
     * @return CompletableFuture indicating success
     */
    private CompletableFuture<Boolean> updateConfigFile(final List<String> newPrefixes) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Read current config
                final YamlMapping current = Yaml.createYamlInput(
                    this.configPath.toFile()
                ).readYamlMapping();

                // Build new meta section with updated prefixes
                YamlMapping currentMeta = current.yamlMapping("meta");
                if (currentMeta == null) {
                    throw new IllegalStateException("No meta section in pantera.yml");
                }

                // Build prefixes sequence
                YamlSequenceBuilder seqBuilder = Yaml.createYamlSequenceBuilder();
                for (final String prefix : newPrefixes) {
                    seqBuilder = seqBuilder.add(prefix);
                }

                // Rebuild meta section with new prefixes
                final YamlMapping newMeta = this.rebuildMeta(currentMeta, seqBuilder.build());

                // Rebuild root with new meta
                final YamlMapping updated = this.rebuildRoot(current, newMeta);
                
                // Write back to storage
                final Key configKey = new Key.From(this.configPath.getFileName().toString());
                this.storage.save(
                    configKey,
                    new Content.From(updated.toString().getBytes(StandardCharsets.UTF_8))
                ).join();

                return true;
            } catch (final IOException ex) {
                EcsLogger.error("com.auto1.pantera.api")
                    .message("Failed to update config file")
                    .eventCategory("api")
                    .eventAction("config_update")
                    .eventOutcome("failure")
                    .field("file.path", this.configPath.toString())
                    .error(ex)
                    .log();
                return false;
            }
        });
    }

    /**
     * Rebuild meta section with new prefixes.
     *
     * @param currentMeta Current meta section
     * @param prefixesSeq New prefixes sequence
     * @return Rebuilt meta mapping
     */
    private YamlMapping rebuildMeta(
        final YamlMapping currentMeta,
        final com.amihaiemil.eoyaml.YamlSequence prefixesSeq
    ) {
        YamlMappingBuilder builder = Yaml.createYamlMappingBuilder();
        currentMeta.keys().forEach(key -> {
            final String keyStr = key.asScalar().value();
            if (!"global_prefixes".equals(keyStr)) {
                builder.add(keyStr, currentMeta.value(keyStr));
            }
        });
        return builder.add("global_prefixes", prefixesSeq).build();
    }

    /**
     * Rebuild root mapping with new meta section.
     *
     * @param current Current root mapping
     * @param newMeta New meta section
     * @return Rebuilt root mapping
     */
    private YamlMapping rebuildRoot(final YamlMapping current, final YamlMapping newMeta) {
        YamlMappingBuilder builder = Yaml.createYamlMappingBuilder();
        current.keys().forEach(key -> {
            final String keyStr = key.asScalar().value();
            if (!"meta".equals(keyStr)) {
                builder.add(keyStr, current.value(keyStr));
            }
        });
        return builder.add("meta", newMeta).build();
    }
}
