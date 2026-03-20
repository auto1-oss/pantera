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
package com.auto1.pantera.api.verifier;

import com.auto1.pantera.api.RepositoryName;
import java.util.Set;

/**
 * Checks if the repository name is valid.
 * The name is considered valid if it does not contain
 * reserved words `_storages` in it.
 * @since 0.26
 */
public final class ReservedNamesVerifier implements Verifier {
    /**
     * Words that should not be present inside repository name.
     */
    private static final Set<String> RESERVED = Set.of("_storages");

    /**
     * The name to test.
     */
    private final String name;

    /**
     * Ctor.
     * @param name The name to test
     */
    public ReservedNamesVerifier(final String name) {
        this.name = name;
    }

    /**
     * Ctor.
     * @param name The name to test
     */
    public ReservedNamesVerifier(final RepositoryName name) {
        this(name.toString());
    }

    /**
     * Set of reserved words.
     * @return Reserved words
     */
    @SuppressWarnings("PMD.ProhibitPublicStaticMethods")
    public static Set<String> reservedWords() {
        return ReservedNamesVerifier.RESERVED;
    }

    /**
     * Validate repository name.
     * @return True if valid
     */
    public boolean valid() {
        return ReservedNamesVerifier.RESERVED.stream()
            .filter(this.name::contains).findAny().isEmpty();
    }

    /**
     * Get error message.
     * @return Error message
     */
    public String message() {
        return
            new StringBuilder()
                .append(String.format("Wrong repository name '%s'. ", this.name))
                .append("Repository name should not include following words: ")
                .append(reservedWords())
                .toString();
    }
}
