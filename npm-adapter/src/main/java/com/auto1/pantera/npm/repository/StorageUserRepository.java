/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.repository;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.npm.model.User;
import com.auto1.pantera.npm.security.PasswordHasher;
import javax.json.Json;
import javax.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Storage-based user repository.
 * Users stored as JSON files: /_users/{username}.json
 *
 * @since 1.1
 */
public final class StorageUserRepository implements UserRepository {
    
    /**
     * Users directory.
     */
    private static final Key USERS_DIR = new Key.From("_users");
    
    /**
     * Storage.
     */
    private final Storage storage;
    
    /**
     * Password hasher.
     */
    private final PasswordHasher hasher;
    
    /**
     * Constructor.
     * @param storage Storage
     * @param hasher Password hasher
     */
    public StorageUserRepository(final Storage storage, final PasswordHasher hasher) {
        this.storage = storage;
        this.hasher = hasher;
    }
    
    @Override
    public CompletableFuture<Boolean> exists(final String username) {
        return this.storage.exists(this.userKey(username));
    }
    
    @Override
    public CompletableFuture<User> save(final User user) {
        final Key key = this.userKey(user.username());
        final JsonObject json = Json.createObjectBuilder()
            .add("id", user.id())
            .add("username", user.username())
            .add("passwordHash", user.passwordHash())
            .add("email", user.email())
            .add("createdAt", user.createdAt().toString())
            .build();
            
        return this.storage.save(
            key,
            new Content.From(json.toString().getBytes(StandardCharsets.UTF_8))
        ).thenApply(v -> user);
    }
    
    @Override
    public CompletableFuture<Optional<User>> findByUsername(final String username) {
        final Key key = this.userKey(username);
        return this.storage.exists(key)
            .thenCompose(exists -> {
                if (!exists) {
                    return CompletableFuture.completedFuture(Optional.empty());
                }
                return this.storage.value(key)
                    .thenCompose(Content::asBytesFuture)
                    .thenApply(bytes -> {
                        final JsonObject json = Json.createReader(
                            new java.io.StringReader(new String(bytes, StandardCharsets.UTF_8))
                        ).readObject();
                        return Optional.of(this.jsonToUser(json));
                    });
            });
    }
    
    @Override
    public CompletableFuture<Optional<User>> authenticate(
        final String username,
        final String password
    ) {
        return this.findByUsername(username)
            .thenApply(opt -> opt.filter(user ->
                this.hasher.verify(password, user.passwordHash())
            ));
    }
    
    /**
     * Get key for user.
     * @param username Username
     * @return Storage key
     */
    private Key userKey(final String username) {
        return new Key.From(USERS_DIR, username + ".json");
    }
    
    /**
     * Convert JSON to user.
     * @param json JSON object
     * @return User
     */
    private User jsonToUser(final JsonObject json) {
        return new User(
            json.getString("id", ""),
            json.getString("username", ""),
            json.getString("passwordHash", ""),
            json.getString("email", ""),
            Instant.parse(json.getString("createdAt", Instant.now().toString()))
        );
    }
}
