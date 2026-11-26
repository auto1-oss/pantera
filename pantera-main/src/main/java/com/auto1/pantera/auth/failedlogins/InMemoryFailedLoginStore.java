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
package com.auto1.pantera.auth.failedlogins;

import com.auto1.pantera.settings.policy.FailedLoginsConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Failed logins kept in the memory of this process: for an installation
 * without a database. The counts are per node and do not survive a restart.
 *
 * @since 2.2.15
 */
public final class InMemoryFailedLoginStore implements FailedLoginStore {

    /**
     * Counted names.
     */
    private final Map<String, Entry> entries;

    /**
     * Clock.
     */
    private final Supplier<Instant> clock;

    /**
     * Ctor with the system clock.
     */
    public InMemoryFailedLoginStore() {
        this(Instant::now);
    }

    /**
     * Ctor.
     *
     * @param clock Clock
     */
    public InMemoryFailedLoginStore(final Supplier<Instant> clock) {
        this.entries = new ConcurrentHashMap<>();
        this.clock = clock;
    }

    @Override
    public Seen read(final String name) {
        final LoginState state = new Counted(name).name()
            .map(this.entries::get)
            .map(Entry::state)
            .orElse(LoginState.NONE);
        return new Seen(state, this.clock.get());
    }

    @Override
    public Seen fail(final String name, final FailedLoginsConfig policy, final LoginOrigin origin) {
        final Instant now = this.clock.get();
        final Optional<String> counted = new Counted(name).name();
        LoginState state = LoginState.NONE;
        if (counted.isPresent()) {
            state = this.entries.compute(
                counted.get(),
                (key, held) -> {
                    final LoginState before;
                    if (held == null) {
                        before = LoginState.NONE;
                    } else {
                        before = held.state();
                    }
                    return new Entry(before.failed(now, policy), origin);
                }
            ).state();
        }
        return new Seen(state, now);
    }

    @Override
    public LoginState forget(final String name) {
        return new Counted(name).name()
            .map(this.entries::remove)
            .map(Entry::state)
            .orElse(LoginState.NONE);
    }

    @Override
    public Optional<LockedName> entry(final String name) {
        return new Counted(name).name().flatMap(
            counted -> Optional.ofNullable(this.entries.get(counted)).map(
                held -> new LockedName(
                    counted, held.state().lockedAt(), held.state().failures(),
                    held.state().lastFailure(), held.origin()
                )
            )
        );
    }

    @Override
    public List<LockedName> locked() {
        final List<LockedName> res = new ArrayList<>(this.entries.size());
        for (final Map.Entry<String, Entry> one : this.entries.entrySet()) {
            final LoginState state = one.getValue().state();
            if (state.locked()) {
                res.add(
                    new LockedName(
                        one.getKey(), state.lockedAt(), state.failures(),
                        state.lastFailure(), one.getValue().origin()
                    )
                );
            }
        }
        res.sort(Comparator.comparing(LockedName::lockedAt).thenComparing(LockedName::name));
        return res;
    }

    @Override
    public List<String> unlockAll() {
        final List<String> res = new ArrayList<>(this.entries.size());
        for (final LockedName one : this.locked()) {
            if (this.entries.remove(one.name()) != null) {
                res.add(one.name());
            }
        }
        return res;
    }

    /**
     * State of a name with the origin of its last failure.
     *
     * @param state State
     * @param origin Origin of the last failed login
     * @since 2.2.15
     */
    private record Entry(LoginState state, LoginOrigin origin) {
    }
}
