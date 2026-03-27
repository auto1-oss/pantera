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
package com.auto1.pantera.npm.repository;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Repository for managing npm package stars (user favorites).
 * 
 * <p>Stores star information in `.stars/PACKAGE_NAME.json` files:
 * <pre>
 * {
 *   "users": ["alice", "bob", "charlie"]
 * }
 * </pre>
 * </p>
 * 
 * <p>P1.2: Implements star/unstar functionality required by npm clients.</p>
 *
 * @since 1.19
 */
public final class NpmStarRepository {
    
    /**
     * Storage for star data.
     */
    private final Storage storage;
    
    /**
     * Ctor.
     * 
     * @param storage Storage for star data
     */
    public NpmStarRepository(final Storage storage) {
        this.storage = storage;
    }
    
    /**
     * Star a package for a user.
     * 
     * @param packageName Package name
     * @param username Username
     * @return Completion stage
     */
    public CompletableFuture<Void> star(final String packageName, final String username) {
        final Key starKey = this.starKey(packageName);
        
        return this.storage.exists(starKey).thenCompose(exists -> {
            if (exists) {
                // Load existing stars and add user
                return this.storage.value(starKey)
                    .thenCompose(Content::asStringFuture)
                    .thenCompose(json -> {
                        final Set<String> users = this.parseUsers(json);
                        users.add(username);
                        return this.saveUsers(starKey, users);
                    });
            } else {
                // Create new star file with single user
                final Set<String> users = new HashSet<>();
                users.add(username);
                return this.saveUsers(starKey, users);
            }
        });
    }
    
    /**
     * Unstar a package for a user.
     * 
     * @param packageName Package name
     * @param username Username
     * @return Completion stage
     */
    public CompletableFuture<Void> unstar(final String packageName, final String username) {
        final Key starKey = this.starKey(packageName);
        
        return this.storage.exists(starKey).thenCompose(exists -> {
            if (!exists) {
                // Already not starred, nothing to do
                return CompletableFuture.completedFuture(null);
            }
            
            return this.storage.value(starKey)
                .thenCompose(Content::asStringFuture)
                .thenCompose(json -> {
                    final Set<String> users = this.parseUsers(json);
                    users.remove(username);
                    
                    if (users.isEmpty()) {
                        // No users left, delete star file
                        return this.storage.delete(starKey);
                    } else {
                        // Save remaining users
                        return this.saveUsers(starKey, users);
                    }
                });
        });
    }
    
    /**
     * Get all users who starred a package.
     * 
     * @param packageName Package name
     * @return Set of usernames
     */
    public CompletableFuture<Set<String>> getStars(final String packageName) {
        final Key starKey = this.starKey(packageName);
        
        return this.storage.exists(starKey).thenCompose(exists -> {
            if (!exists) {
                return CompletableFuture.completedFuture(new HashSet<>());
            }
            
            return this.storage.value(starKey)
                .thenCompose(Content::asStringFuture)
                .thenApply(this::parseUsers);
        });
    }
    
    /**
     * Get users object for metadata (compatible with npm registry format).
     * 
     * @param packageName Package name
     * @return JsonObject with users who starred ({"alice": true, "bob": true})
     */
    public CompletableFuture<JsonObject> getUsersObject(final String packageName) {
        return this.getStars(packageName).thenApply(users -> {
            final JsonObjectBuilder builder = Json.createObjectBuilder();
            for (String user : users) {
                builder.add(user, true);
            }
            return builder.build();
        });
    }
    
    /**
     * Get star key for package.
     * 
     * @param packageName Package name
     * @return Key to star file
     */
    private Key starKey(final String packageName) {
        return new Key.From(".stars", packageName + ".json");
    }
    
    /**
     * Parse users from JSON star file.
     * 
     * @param json JSON content
     * @return Set of usernames
     */
    private Set<String> parseUsers(final String json) {
        try {
            final JsonObject obj = Json.createReader(new StringReader(json)).readObject();
            final Set<String> users = new HashSet<>();
            
            if (obj.containsKey("users")) {
                obj.getJsonArray("users").forEach(value -> {
                    users.add(value.toString().replaceAll("\"", ""));
                });
            }
            
            return users;
        } catch (Exception ex) {
            // Corrupted file, return empty set
            return new HashSet<>();
        }
    }
    
    /**
     * Save users to star file.
     * 
     * @param starKey Key to star file
     * @param users Set of usernames
     * @return Completion stage
     */
    private CompletableFuture<Void> saveUsers(final Key starKey, final Set<String> users) {
        final JsonObjectBuilder builder = Json.createObjectBuilder();
        final var arrayBuilder = Json.createArrayBuilder();
        users.forEach(arrayBuilder::add);
        builder.add("users", arrayBuilder.build());
        
        final String json = builder.build().toString();
        final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        final Content content = new Content.From(bytes);
        
        return this.storage.save(starKey, content);
    }
}
