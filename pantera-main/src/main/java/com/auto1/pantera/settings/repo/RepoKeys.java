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
package com.auto1.pantera.settings.repo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Translation between repository names and repository keys.
 *
 * <p>A repository has a name, which clients use and which a rename changes,
 * and an immutable key, which is the directory of its data in its storage
 * and the value of {@code artifacts.repo_name}. Everything that reads or
 * writes the artifact index translates at its boundary: a name going in
 * becomes a key, a key coming out becomes the current name. For a repository
 * that was never renamed the two are equal.</p>
 *
 * <p>A name that resolves to no key belongs to no repository (never existed,
 * or deleted): writers drop what they were about to write for it, which is
 * what keeps a deleted repository's index rows from coming back from an
 * event queued on any node.</p>
 *
 * @since 2.2.14
 */
public interface RepoKeys {

    /**
     * Every name is its own key and is always known. Deployments without a
     * database, and tests, have no renames and no tombstones.
     */
    RepoKeys IDENTITY = new RepoKeys() {
        @Override
        public Optional<String> key(final String name) {
            return Optional.ofNullable(name);
        }

        @Override
        public Optional<String> name(final String key) {
            return Optional.ofNullable(key);
        }
    };

    /**
     * Key of the repository addressed by a name: its current name, or a
     * former name that is still remembered.
     *
     * @param name Repository name
     * @return Key, empty when no repository has or had that name
     */
    Optional<String> key(String name);

    /**
     * Current name of the repository with this key.
     *
     * @param key Repository key
     * @return Name, empty when the repository is deleted or unknown
     */
    Optional<String> name(String key);

    /**
     * Keys of every repository that exists, when this translation knows
     * them. Readers of the artifact index restrict themselves to these so
     * the rows of a deleted repository are invisible while its purge runs.
     *
     * @return Keys of the live repositories, empty when unknown (no filter)
     */
    default Optional<List<String>> live() {
        return Optional.empty();
    }

    /**
     * Keys of the given names; names of no repository are left out.
     *
     * @param names Repository names, nullable
     * @return Keys, {@code null} for {@code null}
     */
    default List<String> keys(final Collection<String> names) {
        final List<String> res;
        if (names == null) {
            res = null;
        } else {
            res = new ArrayList<>(names.size());
            for (final String name : names) {
                this.key(name).ifPresent(res::add);
            }
        }
        return res;
    }

    /**
     * The translation of this process.
     *
     * @return Installed translation, {@link #IDENTITY} until one is installed
     */
    static RepoKeys shared() {
        return Shared.CURRENT.get();
    }

    /**
     * Install the translation of this process (boot, tests).
     *
     * @param keys Translation, {@code null} for {@link #IDENTITY}
     */
    static void install(final RepoKeys keys) {
        Shared.CURRENT.set(keys == null ? RepoKeys.IDENTITY : keys);
    }

    /**
     * Holder of the process-wide translation.
     */
    final class Shared {

        /**
         * Installed translation.
         */
        static final AtomicReference<RepoKeys> CURRENT =
            new AtomicReference<>(RepoKeys.IDENTITY);

        private Shared() {
        }
    }
}
