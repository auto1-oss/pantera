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
package com.auto1.pantera.conda.http;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link UploadTickets}.
 *
 * <p>Tickets are signed with the cluster key pair, so every node accepts
 * them. Single use must therefore hold across nodes: a ticket redeemed on
 * one node must not be accepted again on another.</p>
 *
 * @since 2.2.9
 */
final class UploadTicketsTest {

    @Test
    void ticketRedeemedOnOneNodeIsRejectedOnAnother() throws NoSuchAlgorithmException {
        final KeyPair keys = UploadTicketsTest.keys();
        final Function<String, CompletionStage<Boolean>> shared = UploadTicketsTest.ledger();
        final UploadTickets first = new UploadTickets(keys.getPrivate(), keys.getPublic(), shared);
        final UploadTickets second = new UploadTickets(keys.getPrivate(), keys.getPublic(), shared);
        final String ticket = first.issue("alice", "repo", "noarch/pkg.tar.bz2");
        MatcherAssert.assertThat(
            "first redemption, on the other node, succeeds",
            second.redeem(ticket, "repo", "noarch/pkg.tar.bz2").toCompletableFuture().join(),
            new IsEqual<>(Optional.of("alice"))
        );
        MatcherAssert.assertThat(
            "replay on the issuing node is rejected",
            first.redeem(ticket, "repo", "noarch/pkg.tar.bz2").toCompletableFuture().join(),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void ledgerFailureRejectsTicket() throws NoSuchAlgorithmException {
        final KeyPair keys = UploadTicketsTest.keys();
        final UploadTickets tickets = new UploadTickets(
            keys.getPrivate(), keys.getPublic(),
            nonce -> CompletableFuture.failedFuture(new IllegalStateException("valkey down"))
        );
        MatcherAssert.assertThat(
            tickets.redeem(
                tickets.issue("alice", "repo", "noarch/pkg.tar.bz2"), "repo", "noarch/pkg.tar.bz2"
            ).toCompletableFuture().join(),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void localLedgerIsSingleUse() {
        final UploadTickets tickets = new UploadTickets();
        final String ticket = tickets.issue("alice", "repo", "noarch/pkg.tar.bz2");
        tickets.redeem(ticket, "repo", "noarch/pkg.tar.bz2").toCompletableFuture().join();
        MatcherAssert.assertThat(
            tickets.redeem(ticket, "repo", "noarch/pkg.tar.bz2").toCompletableFuture().join(),
            new IsEqual<>(Optional.empty())
        );
    }

    /**
     * Ledger shared by the nodes, like a Valkey SET NX.
     * @return Ledger
     */
    private static Function<String, CompletionStage<Boolean>> ledger() {
        final Set<String> used = ConcurrentHashMap.newKeySet();
        return nonce -> CompletableFuture.completedFuture(used.add(nonce));
    }

    /**
     * Fresh RSA key pair.
     * @return Key pair
     * @throws NoSuchAlgorithmException If RSA is unavailable
     */
    private static KeyPair keys() throws NoSuchAlgorithmException {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }
}
