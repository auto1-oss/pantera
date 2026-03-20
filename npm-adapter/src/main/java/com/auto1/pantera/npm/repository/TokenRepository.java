/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.repository;

import com.auto1.pantera.npm.model.NpmToken;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Token repository interface.
 *
 * @since 1.1
 */
public interface TokenRepository {
    
    /**
     * Save token.
     * @param token Token to save
     * @return Future with saved token
     */
    CompletableFuture<NpmToken> save(NpmToken token);
    
    /**
     * Find token by value.
     * @param tokenValue Token string
     * @return Future with optional token
     */
    CompletableFuture<Optional<NpmToken>> findByToken(String tokenValue);
    
    /**
     * List tokens for user.
     * @param username Username
     * @return Future with list of tokens
     */
    CompletableFuture<List<NpmToken>> findByUsername(String username);
    
    /**
     * Delete token.
     * @param tokenId Token ID
     * @return Future that completes when deleted
     */
    CompletableFuture<Void> delete(String tokenId);
}
