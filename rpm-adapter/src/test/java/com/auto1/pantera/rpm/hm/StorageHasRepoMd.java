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
package com.auto1.pantera.rpm.hm;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.rpm.Digest;
import com.auto1.pantera.rpm.RepoConfig;
import com.auto1.pantera.rpm.meta.XmlPackage;
import com.jcabi.xml.XMLDocument;
import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.llorllale.cactoos.matchers.MatcherOf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Matcher for checking rempomd.xml file presence and information in the storage.
 */
public final class StorageHasRepoMd extends AllOf<Storage> {

    /**
     * Repodata key.
     */
    private static final Key BASE = new Key.From("repodata");

    /**
     * Repomd rey.
     */
    private static final Key.From REPOMD = new Key.From(StorageHasRepoMd.BASE, "repomd.xml");

    /**
     * Ctor.
     * @param config Rmp repo config
     */
    public StorageHasRepoMd(final RepoConfig config) {
        super(matchers(config));
    }

    /**
     * List of matchers.
     * @param config Rmp repo config
     * @return Matchers list
     */
    private static List<Matcher<? super Storage>> matchers(final RepoConfig config) {
        final List<Matcher<? super Storage>> res = new ArrayList<>(4);
        res.add(
            new MatcherOf<>(
                storage -> storage.exists(StorageHasRepoMd.REPOMD).join(),
                desc -> desc.appendText("Repomd is present"),
                (sto, desc) ->  desc.appendText("Repomd is not present")
            )
        );
        new XmlPackage.Stream(config.filelists()).get().forEach(
            pkg -> res.add(
                new MatcherOf<>(
                    storage -> hasRecord(storage, pkg, config.digest()),
                    desc -> desc.appendText(
                        String.format("Repomd has record for %s xml", pkg.name())
                    ),
                    (sto, desc) ->  String.format("Repomd has not record for %s xml", pkg.name())
                )
            )
        );
        return res;
    }

    /**
     * Has repomd record for xml metadata package?
     * @param storage Storage
     * @param pckg Metadata package
     * @param digest Digest algorithm
     * @return True if record is present
     */
    private static boolean hasRecord(final Storage storage, final XmlPackage pckg,
        final Digest digest) {
        final Optional<Content> repomd = storage.list(StorageHasRepoMd.BASE).join().stream()
            .filter(item -> item.string().contains(pckg.lowercase())).findFirst()
            .map(item -> storage.value(new Key.From(item)).join());
        if (repomd.isPresent()) {
            final String checksum = new ContentDigest(
                repomd.get(),
                digest::messageDigest
            ).hex().toCompletableFuture().join();
            return  !new XMLDocument(
                storage.value(StorageHasRepoMd.REPOMD).join().asString()
            ).nodes(
                String.format(
                    "/*[name()='repomd']/*[name()='data' and @type='%s']/*[name()='checksum' and @type='%s' and text()='%s']",
                    pckg.name().toLowerCase(Locale.US),
                    digest.type(),
                    checksum
                )
            ).isEmpty();
        }
        return false;
    }
}
