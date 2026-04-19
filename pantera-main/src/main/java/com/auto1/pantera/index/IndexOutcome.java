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
package com.auto1.pantera.index;

import java.util.List;
import java.util.Optional;

/**
 * Sealed return type for index lookup operations, replacing the ambiguous
 * {@code Optional<List<String>>} contract on
 * {@link ArtifactIndex#locateByName(String)}.
 *
 * <p>Four branches:
 * <ul>
 *   <li>{@link Hit} -- one or more repos contain the artifact.</li>
 *   <li>{@link Miss} -- successful query, zero repos matched.</li>
 *   <li>{@link Timeout} -- statement-timeout or deadline exceeded.</li>
 *   <li>{@link DBFailure} -- any other DB exception.</li>
 * </ul>
 *
 * <p>Since the {@link ArtifactIndex} interface lives in {@code pantera-core}
 * (frozen for this release), this type is not wired into the interface
 * signature directly. {@code GroupResolver} adapts the
 * {@code Optional<List<String>>} return into an {@code IndexOutcome} via
 * {@link #fromLegacy(Optional)}.
 *
 * @since 2.2.0
 */
public sealed interface IndexOutcome {

    /**
     * Successful lookup -- at least one repository contains the artifact.
     *
     * @param repos Non-empty, unmodifiable list of repository names.
     */
    record Hit(List<String> repos) implements IndexOutcome {
        public Hit {
            if (repos == null || repos.isEmpty()) {
                throw new IllegalArgumentException("Hit must have at least one repo");
            }
            repos = List.copyOf(repos);
        }
    }

    /**
     * Successful lookup -- the artifact is not in any indexed repository.
     */
    record Miss() implements IndexOutcome {
    }

    /**
     * The index query timed out (statement-timeout, deadline, etc.).
     *
     * @param cause Underlying throwable.
     */
    record Timeout(Throwable cause) implements IndexOutcome {
    }

    /**
     * The index query failed for a reason other than timeout.
     *
     * @param cause Underlying throwable.
     * @param query Human-readable description of the query that failed.
     */
    record DBFailure(Throwable cause, String query) implements IndexOutcome {
    }

    /**
     * Adapt the legacy {@code Optional<List<String>>} contract used by
     * {@link ArtifactIndex#locateByName(String)} into the new sealed type.
     *
     * <ul>
     *   <li>{@code Optional.empty()} (DB error) maps to {@link DBFailure}.</li>
     *   <li>{@code Optional.of(emptyList)} (confirmed miss) maps to {@link Miss}.</li>
     *   <li>{@code Optional.of(nonEmptyList)} maps to {@link Hit}.</li>
     * </ul>
     *
     * @param legacy The legacy return value.
     * @return Corresponding {@link IndexOutcome}.
     */
    static IndexOutcome fromLegacy(final Optional<List<String>> legacy) {
        if (legacy.isEmpty()) {
            return new DBFailure(null, "locateByName (legacy empty Optional)");
        }
        final List<String> repos = legacy.get();
        if (repos.isEmpty()) {
            return new Miss();
        }
        return new Hit(repos);
    }
}
