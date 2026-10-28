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
package com.auto1.pantera.npm.misc;

import com.auto1.pantera.asto.Content;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The parts of a packument that cooldown filtering decides on: {@code name},
 * {@code dist-tags}, {@code time} and the keys of {@code versions} (each
 * version's object replaced by {@code {}}).
 *
 * <p>Only string values and only object-valued sections are kept, so the
 * skeleton is at most three levels deep whatever the document holds: nothing
 * deep reaches the cooldown parser and rewriter. {@code name} and each
 * dist-tag are kept only when they are strings; a non-string dist-tag is not
 * valid npm metadata and is dropped from the served document too. Every
 * {@code time} entry keeps its key: a string value is kept, any other value
 * (the {@code unpublished} object of an unpublished package) is replaced by
 * {@code {}}, which the cooldown parser never reads as a release date. The
 * cooldown filter can then drop the entries of withheld versions by key, and
 * {@link PackumentDecision#apply} serves every kept entry with its stored
 * value. Verdict envelopes cached before the stubs were added lack them and
 * keep dropping such entries until their TTL expires.</p>
 *
 * <p>Extracted by streaming the stored document through
 * {@link PackumentTransform}; the skeleton itself is small (well under 1 MB
 * for a package with ten thousand versions), so the whole cooldown pipeline
 * (parse, evaluate, re-point {@code latest}, cache) runs on it instead of on
 * a document of tens of MB.</p>
 *
 * @since 2.2.11
 */
public final class PackumentSkeleton {

    /**
     * Stored packument.
     */
    private final Content packument;

    /**
     * Ctor.
     *
     * @param packument Stored packument (full or abbreviated)
     */
    public PackumentSkeleton(final Content packument) {
        this.packument = packument;
    }

    /**
     * Extract the skeleton.
     *
     * @return Skeleton JSON bytes
     */
    public CompletableFuture<byte[]> bytes() {
        final PackumentTransform skeleton = new PackumentTransform(
            this.packument,
            new PackumentTransform.Rules(
                Map.of(
                    "name", PackumentTransform.Section.STRING,
                    "dist-tags", PackumentTransform.Section.STRINGS,
                    "time", PackumentTransform.Section.STRINGS_OR_STUBS,
                    "versions", PackumentTransform.Section.STUBS
                ),
                PackumentTransform.Section.SKIP,
                Map.of(),
                Map.of(),
                Optional.empty()
            )
        );
        return new SmallJson(skeleton).bytes();
    }
}
