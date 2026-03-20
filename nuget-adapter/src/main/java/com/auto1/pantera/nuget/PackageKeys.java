/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */

package com.auto1.pantera.nuget;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.nuget.metadata.NuspecField;
import com.auto1.pantera.nuget.metadata.PackageId;

/**
 * Package identifier.
 *
 * @since 0.1
 */
public final class PackageKeys {

    /**
     * Package identifier string.
     */
    private final NuspecField raw;

    /**
     * Ctor.
     * @param id Package id
     */
    public PackageKeys(final NuspecField id) {
        this.raw = id;
    }

    /**
     * Ctor.
     *
     * @param raw Raw package identifier string.
     */
    public PackageKeys(final String raw) {
        this(new PackageId(raw));
    }

    /**
     * Get key for package root.
     *
     * @return Key for package root.
     */
    public Key rootKey() {
        return new Key.From(this.raw.normalized());
    }

    /**
     * Get key for package versions registry.
     *
     * @return Get key for package versions registry.
     */
    public Key versionsKey() {
        return new Key.From(this.rootKey(), "index.json");
    }

    @Override
    public String toString() {
        return this.raw.raw();
    }
}
