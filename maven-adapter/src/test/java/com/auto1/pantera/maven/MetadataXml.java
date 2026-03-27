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
package com.auto1.pantera.maven;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.cactoos.list.ListOf;

/**
 * Maven artifact metadata xml.
 */
public final class MetadataXml {

    /**
     * Group id.
     */
    private final String group;

    /**
     * Artifact id.
     */
    private final String artifact;

    /**
     * Ctor.
     * @param group Group id
     * @param artifact Artifact id
     */
    public MetadataXml(final String group, final String artifact) {
        this.group = group;
        this.artifact = artifact;
    }

    /**
     * Adds xml to storage.
     * @param storage Where to add
     * @param key Key to save xml by
     * @param versions Version to generage xml
     */
    public void addXmlToStorage(final Storage storage, final Key key, final VersionTags versions) {
        storage.save(key, new Content.From(this.get(versions).getBytes(StandardCharsets.UTF_8)))
            .join();
    }

    /**
     * Get xml as string.
     * @param versions Versions info
     * @return Maven metadata xml
     */
    public String get(final VersionTags versions) {
        return String.join(
            "\n",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<metadata>",
            String.format("  <groupId>%s</groupId>", this.group),
            String.format("  <artifactId>%s</artifactId>", this.artifact),
            "  <versioning>",
            versions.latest.map(val -> String.format("    <latest>%s</latest>", val)).orElse(""),
            versions.release.map(val -> String.format("    <release>%s</release>", val)).orElse(""),
            "    <versions>",
            versions.list.stream().map(val -> String.format("      <version>%s</version>", val))
                .collect(Collectors.joining("\n")),
            "    </versions>",
            "    <lastUpdated>20200804141716</lastUpdated>",
            "  </versioning>",
            "</metadata>"
        );
    }

    /**
     * Maven metadata tags with versions: latest, release, versions list.
     */
    public static final class VersionTags {

        /**
         * Latest version.
         */
        private final Optional<String> latest;

        /**
         * Release version.
         */
        private final Optional<String> release;

        /**
         * Versions list.
         */
        private final List<String> list;

        /**
         * Ctor.
         * @param latest Latest version
         * @param release Release version
         * @param list Versions list
         */
        public VersionTags(final Optional<String> latest, final Optional<String> release,
            final List<String> list) {
            this.latest = latest;
            this.release = release;
            this.list = list;
        }

        /**
         * Ctor.
         * @param latest Latest version
         * @param release Release version
         * @param list Versions list
         */
        public VersionTags(final String latest, final String release, final List<String> list) {
            this(Optional.of(latest), Optional.of(release), list);
        }

        /**
         * Ctor.
         * @param list Versions list
         */
        public VersionTags(final String... list) {
            this(Optional.empty(), Optional.empty(), new ListOf<>(list));
        }
    }
}
