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
package com.auto1.pantera.asto.lock.storage;

import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ValueNotFoundException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Proposals for acquiring storage lock.
 *
 * @since 0.24
 */
final class Proposals {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(Proposals.class.getName());

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Target key.
     */
    private final Key target;

    /**
     * Ctor.
     *
     * @param storage Storage.
     * @param target Target key.
     */
    Proposals(final Storage storage, final Key target) {
        this.storage = storage;
        this.target = target;
    }

    /**
     * Create proposal with specified UUID.
     *
     * @param uuid UUID.
     * @param expiration Expiration time.
     * @return Completion of proposal create operation.
     */
    public CompletionStage<Void> create(final String uuid, final Optional<Instant> expiration) {
        return this.storage.save(
            this.proposalKey(uuid),
            expiration.<Content>map(
                instant -> new Content.From(instant.toString().getBytes(StandardCharsets.US_ASCII))
            ).orElse(Content.EMPTY)
        );
    }

    /**
     * Check that there is single proposal with specified UUID.
     *
     * @param uuid UUID.
     * @return Completion of proposals check operation.
     */
    public CompletionStage<Void> checkSingle(final String uuid) {
        final Instant now = Instant.now();
        final Key own = this.proposalKey(uuid);
        return this.storage.list(new RootKey(this.target)).thenCompose(
            proposals -> CompletableFuture.allOf(
                proposals.stream()
                    .filter(key -> !key.equals(own))
                    .map(
                        proposal -> this.valueIfPresent(proposal).thenCompose(
                            value -> value.map(
                                content -> content.asStringFuture().thenCompose(
                                    expiration -> {
                                        if (isNotExpired(expiration, now)) {
                                            throw new PanteraIOException(
                                                String.join(
                                                    "\n",
                                                    "Failed to acquire lock.",
                                                    String.format("Own: `%s`", own),
                                                    String.format(
                                                        "Others: %s",
                                                        proposals.stream()
                                                            .map(Key::toString)
                                                            .map(str -> String.format("`%s`", str))
                                                            .collect(Collectors.joining(", "))
                                                    ),
                                                    String.format(
                                                        "Not expired: `%s` `%s`",
                                                        proposal,
                                                        expiration
                                                    )
                                                )
                                            );
                                        }
                                        return CompletableFuture.allOf();
                                    }
                                )
                            ).orElse(CompletableFuture.allOf())
                        )
                    )
                    .toArray(CompletableFuture[]::new)
            )
        );
    }

    /**
     * Delete proposal with specified UUID.
     *
     * @param uuid UUID.
     * @return Completion of proposal delete operation.
     */
    public CompletionStage<Void> delete(final String uuid) {
        return this.storage.delete(this.proposalKey(uuid));
    }

    /**
     * Remove all expired proposals for this target key.
     * Proposals that have no expiration (empty content) are never removed.
     *
     * @return Completion of cleanup operation.
     */
    public CompletionStage<Void> cleanExpired() {
        final Instant now = Instant.now();
        return this.storage.list(new RootKey(this.target)).thenCompose(
            keys -> CompletableFuture.allOf(
                keys.stream()
                    .map(
                        key -> this.valueIfPresent(key).thenCompose(
                            value -> value.map(
                                content -> content.asStringFuture().thenCompose(
                                    expiration -> {
                                        if (!expiration.isEmpty()
                                            && !Instant.parse(expiration).isAfter(now)) {
                                            LOGGER.log(
                                                Level.FINE,
                                                "Deleting expired lock proposal: {0}",
                                                key
                                            );
                                            return this.storage.delete(key);
                                        }
                                        return CompletableFuture.allOf();
                                    }
                                )
                            ).orElse(CompletableFuture.allOf())
                        ).toCompletableFuture()
                    )
                    .toArray(CompletableFuture[]::new)
            )
        );
    }

    /**
     * Construct proposal key with specified UUID.
     *
     * @param uuid UUID.
     * @return Proposal key.
     */
    private Key proposalKey(final String uuid) {
        return new Key.From(new RootKey(this.target), uuid);
    }

    /**
     * Checks that instant in string format is not expired, e.g. is after current time.
     * Empty string considered to never expire.
     *
     * @param instant Instant in string format.
     * @param now Current time.
     * @return True if instant is not expired, false - otherwise.
     */
    private static boolean isNotExpired(final String instant, final Instant now) {
        return instant.isEmpty() || Instant.parse(instant).isAfter(now);
    }

    /**
     * Loads value content is it is present.
     *
     * @param key Key for the value.
     * @return Content if value presents, empty otherwise.
     */
    private CompletableFuture<Optional<Content>> valueIfPresent(final Key key) {
        final CompletableFuture<Optional<Content>> value = this.storage.value(key)
            .thenApply(Optional::of);
        return value.handle(
            (content, throwable) -> {
                final CompletableFuture<Optional<Content>> result;
                if (throwable != null && throwable.getCause() instanceof ValueNotFoundException) {
                    result = CompletableFuture.completedFuture(Optional.empty());
                } else {
                    result = value;
                }
                return result;
            }
        ).thenCompose(Function.identity());
    }

    /**
     * Root key for lock proposals.
     *
     * @since 0.24
     */
    static class RootKey extends Key.Wrap {

        /**
         * Ctor.
         *
         * @param target Target key.
         */
        protected RootKey(final Key target) {
            super(new From(new From(".pantera-locks"), new From(target)));
        }
    }
}
