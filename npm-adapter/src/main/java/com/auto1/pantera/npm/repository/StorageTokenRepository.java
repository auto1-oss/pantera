/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.repository;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.npm.model.NpmToken;
import javax.json.Json;
import javax.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Storage-based token repository.
 * Tokens stored as: /_tokens/{token-id}.json
 *
 * @since 1.1
 */
public final class StorageTokenRepository implements TokenRepository {
    
    /**
     * Tokens directory.
     */
    private static final Key TOKENS_DIR = new Key.From("_tokens");
    
    /**
     * Storage.
     */
    private final Storage storage;
    
    /**
     * Constructor.
     * @param storage Storage
     */
    public StorageTokenRepository(final Storage storage) {
        this.storage = storage;
    }
    
    @Override
    public CompletableFuture<NpmToken> save(final NpmToken token) {
        final Key key = this.tokenKey(token.id());
        final JsonObject json = Json.createObjectBuilder()
            .add("id", token.id())
            .add("token", token.token())
            .add("username", token.username())
            .add("createdAt", token.createdAt().toString())
            .add("expiresAt", token.expiresAt() != null ? token.expiresAt().toString() : "")
            .build();
            
        return this.storage.save(
            key,
            new Content.From(json.toString().getBytes(StandardCharsets.UTF_8))
        ).thenApply(v -> token);
    }
    
    @Override
    public CompletableFuture<Optional<NpmToken>> findByToken(final String tokenValue) {
        // List all tokens and find matching one
        return this.storage.list(TOKENS_DIR)
            .thenCompose(keys -> {
                final List<CompletableFuture<Optional<NpmToken>>> futures = keys.stream()
                    .map(this::loadToken)
                    .collect(Collectors.toList());
                    
                return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .filter(token -> token.token().equals(tokenValue))
                        .findFirst()
                    );
            });
    }
    
    @Override
    public CompletableFuture<List<NpmToken>> findByUsername(final String username) {
        return this.storage.list(TOKENS_DIR)
            .thenCompose(keys -> {
                final List<CompletableFuture<Optional<NpmToken>>> futures = keys.stream()
                    .map(this::loadToken)
                    .collect(Collectors.toList());
                    
                return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .filter(token -> token.username().equals(username))
                        .collect(Collectors.toList())
                    );
            });
    }
    
    @Override
    public CompletableFuture<Void> delete(final String tokenId) {
        return this.storage.delete(this.tokenKey(tokenId));
    }
    
    /**
     * Load token from key.
     * @param key Storage key
     * @return Future with optional token
     */
    private CompletableFuture<Optional<NpmToken>> loadToken(final Key key) {
        return this.storage.value(key)
            .thenCompose(Content::asBytesFuture)
            .thenApply(bytes -> {
                final JsonObject json = Json.createReader(
                    new java.io.StringReader(new String(bytes, StandardCharsets.UTF_8))
                ).readObject();
                return Optional.of(this.jsonToToken(json));
            })
            .exceptionally(ex -> Optional.empty());
    }
    
    /**
     * Get key for token.
     * @param tokenId Token ID
     * @return Storage key
     */
    private Key tokenKey(final String tokenId) {
        return new Key.From(TOKENS_DIR, tokenId + ".json");
    }
    
    /**
     * Convert JSON to token.
     * @param json JSON object
     * @return Token
     */
    private NpmToken jsonToToken(final JsonObject json) {
        final String expiresStr = json.getString("expiresAt", "");
        final Instant expires = expiresStr.isEmpty() ? null : Instant.parse(expiresStr);
        
        return new NpmToken(
            json.getString("id", ""),
            json.getString("token", ""),
            json.getString("username", ""),
            Instant.parse(json.getString("createdAt", Instant.now().toString())),
            expires
        );
    }
}
