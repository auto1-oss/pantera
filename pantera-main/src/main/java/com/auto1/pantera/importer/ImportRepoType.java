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
package com.auto1.pantera.importer;

import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.ResponseException;
import java.util.Locale;
import java.util.Map;

/**
 * The repository type an import actually runs as.
 *
 * <p>SECURITY (2.2.9): the importer used to take the caller's
 * {@code X-Artipie-Repo-Type} header verbatim, and that value drove path
 * rewriting, digest policy, shard writers and the metadata-regeneration
 * switch — so declaring {@code gem} against a {@code file} repository routed
 * the import into the RubyGems (JRuby) indexer, and declaring {@code npm}
 * against a maven repository rewrote paths for the wrong format. The type is
 * now derived from the target repository's authoritative configuration; a
 * declared type is accepted only when it names the same format (aliases and
 * a {@code -proxy}/{@code -group}/{@code -local} suffix on the configured
 * value are tolerated), otherwise the request is refused with 400.</p>
 *
 * @since 2.2.9
 */
final class ImportRepoType {

    /**
     * Alias → canonical format name, mirroring the aliases the metadata
     * regenerator already accepts.
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("files", "file"),
        Map.entry("generic", "file"),
        Map.entry("gems", "gem"),
        Map.entry("ruby", "gem"),
        Map.entry("composer", "php"),
        Map.entry("golang", "go"),
        Map.entry("python", "pypi"),
        Map.entry("gradle", "maven"),
        Map.entry("debian", "deb"),
        Map.entry("oci", "docker")
    );

    /**
     * Type from the repository's configuration.
     */
    private final String configured;

    /**
     * Type the caller declared, may be {@code null}.
     */
    private final String declared;

    /**
     * Ctor.
     *
     * @param configured Type from the target repository's configuration
     * @param declared Type the caller declared (nullable)
     */
    ImportRepoType(final String configured, final String declared) {
        this.configured = configured;
        this.declared = declared;
    }

    /**
     * The type the import runs as: the configured repository's format.
     *
     * @return Canonical format name
     * @throws ResponseException 400 when the declared type names a
     *  different format than the repository is configured with
     */
    String effective() {
        final String format = canonical(stripSuffix(this.configured));
        if (this.declared != null && !this.declared.isBlank()
            && !format.equals(canonical(this.declared))) {
            throw new ResponseException(
                ResponseBuilder.badRequest()
                    .textBody(
                        String.format(
                            "Repository type mismatch: repository is '%s', request declared '%s'",
                            this.configured, this.declared
                        )
                    )
                    .build()
            );
        }
        return format;
    }

    /**
     * Drop a {@code -proxy} / {@code -group} / {@code -local} suffix.
     *
     * @param type Configured type
     * @return Base type
     */
    private static String stripSuffix(final String type) {
        String base = type == null ? "" : type.toLowerCase(Locale.ROOT).trim();
        for (final String suffix : new String[]{"-proxy", "-group", "-local"}) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
            }
        }
        return base;
    }

    /**
     * Canonical format name for a (possibly aliased) type.
     *
     * @param type Type
     * @return Canonical name
     */
    private static String canonical(final String type) {
        final String lower = type == null ? "" : type.toLowerCase(Locale.ROOT).trim();
        return ALIASES.getOrDefault(lower, lower);
    }
}
