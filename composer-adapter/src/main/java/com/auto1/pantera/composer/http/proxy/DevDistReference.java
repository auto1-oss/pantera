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
package com.auto1.pantera.composer.http.proxy;

import com.auto1.pantera.asto.Key;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Source reference of a proxied dev-branch dist.
 *
 * <p>A dev version ({@code dev-master}, {@code 1.x-dev}) names a moving
 * branch: the same version string points at a different commit after every
 * push. Its dist is therefore addressed by the version AND the
 * {@code dist.reference} it was built from — in the rewritten dist URL
 * ({@code ...dist/<vendor>/<pkg>/<version>.zip?ref=<reference>}) and in the
 * cache key ({@code dist/<vendor>/<pkg>/<version>@<reference>.zip}) — so a
 * moved branch is fetched again instead of serving the code of an older
 * commit under the new reference. Tagged versions keep the plain
 * version-only URL and key.</p>
 *
 * @since 2.2.9
 */
final class DevDistReference {

    /**
     * Query parameter carrying the reference.
     */
    private static final String PARAM = "ref=";

    /**
     * Accepted references: VCS commit ids and similar tokens.
     */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    /**
     * Whether a version names a moving branch.
     *
     * @param version Composer version
     * @return True for {@code dev-*} and {@code *-dev} versions
     */
    boolean mutable(final String version) {
        final String lower = version.toLowerCase(Locale.ROOT);
        return lower.startsWith("dev-") || lower.endsWith("-dev");
    }

    /**
     * Whether a reference is safe to put in a URL and a storage key.
     *
     * @param reference Reference, may be null
     * @return True when usable
     */
    boolean valid(final String reference) {
        return reference != null && VALID.matcher(reference).matches();
    }

    /**
     * Query string for a rewritten dist URL.
     *
     * @param version Version
     * @param reference Dist reference, may be null
     * @return {@code ?ref=<reference>} for a dev version with a valid
     *     reference, else an empty string
     */
    String query(final String version, final String reference) {
        if (this.mutable(version) && this.valid(reference)) {
            return "?" + PARAM + reference;
        }
        return "";
    }

    /**
     * Reference requested by a dist download.
     *
     * @param version Requested version
     * @param rawQuery Raw query of the request, may be null
     * @return Reference for a dev version, else empty
     */
    Optional<String> requested(final String version, final String rawQuery) {
        if (rawQuery == null || !this.mutable(version)) {
            return Optional.empty();
        }
        for (final String param : rawQuery.split("&")) {
            if (param.startsWith(PARAM)) {
                final String ref = param.substring(PARAM.length());
                return this.valid(ref) ? Optional.of(ref) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Cache key of a dev dist built from a given reference.
     *
     * @param vendor Vendor
     * @param pkg Package
     * @param version Version
     * @param reference Reference
     * @return Storage key
     */
    Key key(final String vendor, final String pkg, final String version, final String reference) {
        return new Key.From("dist", vendor, pkg, version + "@" + reference + ".zip");
    }
}
