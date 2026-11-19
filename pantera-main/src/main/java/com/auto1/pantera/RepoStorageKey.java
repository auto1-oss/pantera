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
package com.auto1.pantera;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.slice.KeyFromPath;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Storage key of the object a repository request path names, for dating a
 * download with the object's storage time
 * ({@link com.auto1.pantera.http.slice.LastModifiedSlice}).
 *
 * <p>The key is the path below the repository root, the part
 * {@link RepositorySlices} hands to the adapter: below the repository name,
 * or the whole path for the two types served from the root of a dedicated
 * port. Paths that name no object worth a storage read yield nothing: the
 * repository root, and checksum and signature files, which every Maven
 * client requests next to each artifact and no client dates.</p>
 *
 * @since 2.2.12
 */
final class RepoStorageKey implements Function<String, Optional<Key>> {

    /**
     * Repository name segment, then the path below it.
     */
    private static final Pattern BELOW_NAME = Pattern.compile("/[^/]+(/.*)?");

    /**
     * conda URL token ({@code /t/<token>}), then the path below it.
     */
    private static final Pattern BELOW_TOKEN = Pattern.compile("/t/[^/]+(/.+)");

    /**
     * Types {@link RepositorySlices} serves from the root on a dedicated port.
     */
    private static final List<String> ROOTED = List.of("conda", "conan");

    /**
     * Checksum and signature file extensions.
     */
    private static final List<String> SIDECARS = List.of(
        ".sha1", ".md5", ".sha256", ".sha512", ".asc", ".sig"
    );

    /**
     * Repository type.
     */
    private final String type;

    /**
     * Whether the repository listens on its own port.
     */
    private final boolean port;

    /**
     * Ctor.
     * @param type Repository type
     * @param port Whether the repository listens on its own port
     */
    RepoStorageKey(final String type, final boolean port) {
        this.type = type;
        this.port = port;
    }

    @Override
    public Optional<Key> apply(final String path) {
        return this.below(path)
            .map(this::withoutToken)
            .filter(object -> object.length() > 1)
            .filter(object -> !RepoStorageKey.sidecar(object))
            .map(KeyFromPath::new);
    }

    /**
     * Path below the repository root.
     * @param path Request path
     * @return Path below the root, empty when the request names the root
     */
    private Optional<String> below(final String path) {
        final Optional<String> res;
        if (this.port && RepoStorageKey.ROOTED.contains(this.type)) {
            res = Optional.of(path);
        } else {
            final Matcher matcher = RepoStorageKey.BELOW_NAME.matcher(path);
            res = matcher.matches() ? Optional.ofNullable(matcher.group(1)) : Optional.empty();
        }
        return res;
    }

    /**
     * Path without conda's URL token.
     * @param object Path below the repository root
     * @return Path the object is stored under
     */
    private String withoutToken(final String object) {
        final Matcher matcher = RepoStorageKey.BELOW_TOKEN.matcher(object);
        return "conda".equals(this.type) && matcher.matches() ? matcher.group(1) : object;
    }

    /**
     * Whether the path names a checksum or signature file.
     * @param object Path below the repository root
     * @return True for a checksum or signature file
     */
    private static boolean sidecar(final String object) {
        final String lower = object.toLowerCase(Locale.ROOT);
        return RepoStorageKey.SIDECARS.stream().anyMatch(lower::endsWith);
    }
}
