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
package com.auto1.pantera.cooldown;

import com.auto1.pantera.cooldown.config.CooldownSettings;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-repository cooldown windows declared in a repository's own config
 * ({@code repo.cooldown.duration}), kept in step with the repository
 * lifecycle.
 *
 * <p>They were registered once, when the server booted, so a repository
 * created or edited at runtime (API/UI) served every version unfiltered
 * until the next restart, and a removed duration kept applying. Now every
 * repository create / update / move / delete re-syncs the repository's
 * override. Only overrides this class registered are ever removed, so a
 * per-repository override set through the cooldown settings API is not
 * dropped when an unrelated repository config changes.</p>
 *
 * @since 2.2.9
 */
public final class RepoConfigCooldownOverrides {

    /**
     * Cooldown settings holding the per-repository overrides.
     */
    private final CooldownSettings settings;

    /**
     * Repositories whose override came from their own config.
     */
    private final Set<String> fromConfig;

    /**
     * Ctor.
     * @param settings Cooldown settings
     */
    public RepoConfigCooldownOverrides(final CooldownSettings settings) {
        this.settings = settings;
        this.fromConfig = ConcurrentHashMap.newKeySet();
    }

    /**
     * Bring one repository's override in line with its current config.
     * @param name Repository name
     * @param duration Its configured cooldown window; empty when the
     *  repository has none or no longer exists
     * @return True when the effective override changed
     */
    public boolean sync(final String name, final Optional<Duration> duration) {
        final boolean changed;
        if (duration.isPresent()) {
            final boolean same = this.fromConfig.contains(name)
                && this.settings.isRepoNameOverridePresent(name)
                && this.settings.enabledForRepoName(name)
                && duration.get().equals(this.settings.minimumAllowedAgeForRepoName(name));
            if (same) {
                changed = false;
            } else {
                this.settings.setRepoNameOverride(name, true, duration.get());
                this.fromConfig.add(name);
                changed = true;
            }
        } else if (this.fromConfig.remove(name)) {
            this.settings.removeRepoNameOverride(name);
            changed = true;
        } else {
            changed = false;
        }
        return changed;
    }
}
