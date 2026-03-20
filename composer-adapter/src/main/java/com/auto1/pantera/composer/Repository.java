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
package com.auto1.pantera.composer;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.composer.http.Archive;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * PHP Composer repository.
 *
 * @since 0.3
 */
@SuppressWarnings("PMD.TooManyMethods")
public interface Repository {

    /**
     * Reads packages description from storage.
     *
     * @return Packages found by name, might be empty.
     */
    CompletionStage<Optional<Packages>> packages();

    /**
     * Reads packages description from storage.
     *
     * @param name Package name.
     * @return Packages found by name, might be empty.
     */
    CompletionStage<Optional<Packages>> packages(Name name);

    /**
     * Adds package described in JSON format from storage.
     *
     * @param content Package content.
     * @param version Version in case of absence version in content with package. If package
     *  does not contain version, this value should be passed as a parameter.
     * @return Completion of adding package to repository.
     */
    CompletableFuture<Void> addJson(Content content, Optional<String> version);

    /**
     * Adds package described in archive with ZIP or TAR.GZ
     * format from storage.
     *
     * @param archive Archive with package content.
     * @param content Package content.
     * @return Completion of adding package to repository.
     */
    CompletableFuture<Void> addArchive(Archive archive, Content content);

    /**
     * Obtain bytes by key.
     * @param key The key
     * @return Bytes.
     */
    CompletableFuture<Content> value(Key key);

    /**
     * Obtains storage for repository. It can be useful for implementation cache
     * or in other places where {@link Storage} instance is required for
     * using classes which are created in asto module.
     * @return Storage instance
     */
    Storage storage();

    /**
     * This file exists?
     *
     * @param key The key (file name)
     * @return TRUE if exists, FALSE otherwise
     */
    CompletableFuture<Boolean> exists(Key key);

    /**
     * Saves the bytes to the specified key.
     * @param key The key
     * @param content Bytes to save
     * @return Completion or error signal.
     */
    CompletableFuture<Void> save(Key key, Content content);

    /**
     * Moves value from one location to another.
     * @param source Source key.
     * @param destination Destination key.
     * @return Completion or error signal.
     */
    CompletableFuture<Void> move(Key source, Key destination);

    /**
     * Removes value from storage. Fails if value does not exist.
     * @param key Key for value to be deleted.
     * @return Completion or error signal.
     */
    CompletableFuture<Void> delete(Key key);

    /**
     * Runs operation exclusively for specified key.
     * @param key Key which is scope of operation.
     * @param operation Operation to be performed exclusively.
     * @param <T> Operation result type.
     * @return Result of operation.
     */
    <T> CompletionStage<T> exclusively(
        Key key,
        Function<Storage, CompletionStage<T>> operation
    );
}
