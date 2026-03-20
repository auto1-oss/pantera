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
package com.auto1.pantera.maven.metadata;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import java.nio.charset.StandardCharsets;

import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.xembly.Directive;
import org.xembly.Directives;
import org.xembly.Xembler;

/**
 * Maven metadata generator.
 * @since 0.3
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class MavenMetadata {

    /**
     * Current Xembler state.
     */
    private final Directives dirs;

    /**
     * Ctor.
     * @param source Source xembler directives
     */
    public MavenMetadata(final Iterable<Directive> source) {
        this.dirs = new Directives(source);
    }

    /**
     * Update versions.
     * @param items Version names
     * @return Updated metadata
     */
    public MavenMetadata versions(final Set<String> items) {
        final Directives copy = new Directives(this.dirs);
        copy.xpath("/metadata")
            .push().xpath("versioning").remove().pop()
            .xpath("/metadata")
            .add("versioning");
        items.stream().max(Comparator.comparing(Version::new))
            .ifPresent(latest -> copy.add("latest").set(latest).up());
        items.stream().filter(version -> !version.endsWith("SNAPSHOT"))
            .max(Comparator.comparing(Version::new))
            .ifPresent(latest -> copy.add("release").set(latest).up());
        copy.add("versions");
        items.forEach(version -> copy.add("version").set(version).up());
        copy.up();
        copy.addIf("lastUpdated").set(MavenTimestamp.now()).up();
        copy.up();
        return new MavenMetadata(copy);
    }

    /**
     * Save metadata to storage.
     * @param storage Storage to save
     * @param base Base key where to save
     * @return Completion action with key for saved maven-metadata
     */
    public CompletionStage<Key> save(final Storage storage, final Key base) {
        final Key res = new Key.From(base, "maven-metadata.xml");
        return CompletableFuture.supplyAsync(
            () -> new Xembler(this.dirs).xmlQuietly().getBytes(StandardCharsets.UTF_8)
        )
            .thenCompose(data -> storage.save(res, new Content.From(data)))
            .thenApply(nothing -> res);
    }
}
