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
package com.auto1.pantera.nuget.metadata;

import java.util.Locale;

/**
 * Package id nuspec field.
 * See <a href="https://docs.microsoft.com/en-us/dotnet/api/system.string.tolowerinvariant?view=netstandard-2.0#System_String_ToLowerInvariant">.NET's System.String.ToLowerInvariant()</a>.
 * @since 0.6
 */
public final class PackageId implements NuspecField {

    /**
     * Raw value of package id tag.
     */
    private final String val;

    /**
     * Ctor.
     * @param val Raw value of package id tag
     */
    public PackageId(final String val) {
        this.val = val;
    }

    @Override
    public String raw() {
        return this.val;
    }

    @Override
    public String normalized() {
        return this.val.toLowerCase(Locale.getDefault());
    }

    @Override
    public String toString() {
        return this.val;
    }
}
