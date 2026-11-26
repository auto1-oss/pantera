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
package com.auto1.pantera.auth.signin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The lines in memory, for an installation without a database: per node,
 * gone with a restart, and at most {@link #MAX_LINES} of them.
 *
 * @since 2.2.15
 */
public final class InMemorySignInFailureStore implements SignInFailureStore {

    /**
     * Most lines kept. When it is reached, the oldest lines make room.
     */
    static final int MAX_LINES = 20_000;

    /**
     * Lines by their key.
     */
    private final Map<String, SignInFailure> lines = new ConcurrentHashMap<>();

    @Override
    public void save(final Collection<SignInFailure> events) {
        for (final SignInFailure event : events) {
            this.lines.merge(event.line(), event, SignInFailure::plus);
        }
        if (this.lines.size() > InMemorySignInFailureStore.MAX_LINES) {
            final List<SignInFailure> all = new ArrayList<>(this.lines.values());
            all.sort(Comparator.comparing(SignInFailure::lastSeen));
            final int excess = all.size() - InMemorySignInFailureStore.MAX_LINES;
            for (int idx = 0; idx < excess; idx += 1) {
                this.lines.remove(all.get(idx).line());
            }
        }
    }

    @Override
    public Page list(final Query query) {
        final List<SignInFailure> found = new ArrayList<>(16);
        for (final SignInFailure line : this.lines.values()) {
            if (query.matches(line)) {
                found.add(line);
            }
        }
        found.sort(
            Comparator.comparing(SignInFailure::lastSeen).reversed()
                .thenComparing(SignInFailure::line)
        );
        final int from = Math.min(Math.max(query.offset(), 0), found.size());
        final int upto = Math.min(from + Math.max(query.limit(), 0), found.size());
        return new Page(List.copyOf(found.subList(from, upto)), found.size());
    }

    @Override
    public int purge(final Instant before) {
        int removed = 0;
        for (final SignInFailure line : new ArrayList<>(this.lines.values())) {
            if (line.lastSeen().isBefore(before) && this.lines.remove(line.line()) != null) {
                removed += 1;
            }
        }
        return removed;
    }
}
