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
package com.auto1.pantera.api.v1.admin;

import com.auto1.pantera.http.cache.NegativeCacheKey;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A package as an operator types it, resolved to every spelling the
 * serving path stores it under.
 *
 * <p>Producers name the same package differently: group resolvers use the
 * {@code ArtifactNameParser} form (maven {@code com.example.foo}, pypi
 * normalised {@code typing-extensions}), proxy slices the URL form
 * ({@code com/example/foo}, the wheel's {@code typing_extensions}), the
 * cooldown tables and envelope caches their adapter's canonical form. This
 * type compares names in a canonical shape — lower case, every run of
 * {@code - _ . / :} collapsed — so {@code com.example:foo},
 * {@code com/example/foo} and {@code com.example.foo} are one package.</p>
 *
 * @since 2.2.9
 */
public final class PackageName {

    /**
     * Separator runs collapsed by {@link #canonical(String)}.
     */
    private static final Pattern SEPARATORS = Pattern.compile("[-_./:]+");

    /**
     * PEP 503 separator runs.
     */
    private static final Pattern PYPI_SEPARATORS = Pattern.compile("[-_.]+");

    /**
     * Format family ({@code npm}, {@code maven}, ...), may be empty.
     */
    private final String family;

    /**
     * Name as typed.
     */
    private final String raw;

    /**
     * Ctor.
     *
     * @param family Format family or repository type (suffix ignored), may be null
     * @param raw Name as typed
     */
    public PackageName(final String family, final String raw) {
        this.family = family == null ? ""
            : family.toLowerCase(Locale.ROOT).replaceAll("-(group|proxy|local|remote)$", "");
        this.raw = raw == null ? "" : raw.trim();
    }

    /**
     * Name as typed.
     *
     * @return Name
     */
    public String raw() {
        return this.raw;
    }

    /**
     * Family.
     *
     * @return Family, may be empty
     */
    public String family() {
        return this.family;
    }

    /**
     * Names the cooldown tables and filtered-metadata envelopes may use.
     *
     * @return Distinct spellings, the typed one first
     */
    public Set<String> storedForms() {
        final Set<String> out = new LinkedHashSet<>();
        out.add(this.raw);
        if (this.maven()) {
            String name = this.raw;
            while (name.startsWith("/")) {
                name = name.substring(1);
            }
            out.add(name.replace(':', '.').replace('/', '.'));
            out.add(this.mavenPath());
        } else if ("pypi".equals(this.family)) {
            out.add(PYPI_SEPARATORS.matcher(this.raw).replaceAll("-").toLowerCase(Locale.ROOT));
        } else if ("npm".equals(this.family) || "php".equals(this.family)) {
            out.add(this.raw.toLowerCase(Locale.ROOT));
        }
        out.remove("");
        return out;
    }

    /**
     * Whether a negative-cache key belongs to this package (any spelling,
     * or a child path such as a go sub-module or a maven file path).
     *
     * @param key Key
     * @return True when it names this package
     */
    public boolean matches(final NegativeCacheKey key) {
        if (this.raw.isEmpty()) {
            return false;
        }
        if (!this.family.isEmpty() && !this.family.equals(
            key.repoType().toLowerCase(Locale.ROOT)
                .replaceAll("-(group|proxy|local|remote)$", "")
        )) {
            return false;
        }
        final String cached = canonical(key.artifactName());
        for (final String form : this.storedForms()) {
            final String mine = canonical(form);
            if (cached.equals(mine) || key.artifactName().startsWith(form + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Canonical comparison shape of a name.
     *
     * @param name Name
     * @return Lower-case name with separator runs collapsed to {@code -}
     */
    static String canonical(final String name) {
        return SEPARATORS.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("-")
            .replaceAll("^-+|-+$", "");
    }

    /**
     * Whether this is a maven-layout family.
     *
     * @return True for maven and gradle
     */
    private boolean maven() {
        return "maven".equals(this.family) || "gradle".equals(this.family);
    }

    /**
     * Maven storage path form ({@code com/example/foo}) of a
     * {@code groupId:artifactId} coordinate; the typed name otherwise.
     *
     * @return Path form
     */
    private String mavenPath() {
        final int colon = this.raw.indexOf(':');
        final String result;
        if (colon > 0) {
            result = this.raw.substring(0, colon).replace('.', '/') + "/"
                + this.raw.substring(colon + 1);
        } else {
            result = this.raw;
        }
        return result;
    }
}
