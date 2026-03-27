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
package com.auto1.pantera.nuget;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.nuget.metadata.Nuspec;
import com.auto1.pantera.nuget.metadata.NuspecField;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * NuGet repository.
 *
 * @since 0.5
 */
public interface Repository {

    /**
     * Read package content.
     *
     * @param key Package content key.
     * @return Content if exists, empty otherwise.
     */
    CompletionStage<Optional<Content>> content(Key key);

    /**
     * Adds NuGet package in .nupkg file format from storage.
     *
     * @param content Content of .nupkg package.
     * @return Completion of adding package.
     */
    CompletionStage<PackageInfo> add(Content content);

    /**
     * Enumerates package versions.
     *
     * @param id Package identifier.
     * @return Versions of package.
     */
    CompletionStage<Versions> versions(PackageKeys id);

    /**
     * Read package description in .nuspec format.
     *
     * @param identity Package identity consisting of package id and version.
     * @return Package description in .nuspec format.
     */
    CompletionStage<Nuspec> nuspec(PackageIdentity identity);

    /**
     * Package info.
     * @since 1.6
     */
    class PackageInfo {

        /**
         * Package name.
         */
        private final String name;

        /**
         * Version.
         */
        private final String version;

        /**
         * Package tar archive size.
         */
        private final long size;

        /**
         * Ctor.
         * @param name Package name
         * @param version Version
         * @param size Package tar archive size
         */
        public PackageInfo(final NuspecField name, final NuspecField version, final long size) {
            this.name = name.normalized();
            this.version = version.normalized();
            this.size = size;
        }

        /**
         * Package name (unique id).
         * @return String name
         */
        public String packageName() {
            return this.name;
        }

        /**
         * Package version.
         * @return String SemVer compatible version
         */
        public String packageVersion() {
            return this.version;
        }

        /**
         * Package zip archive (nupkg) size.
         * @return Long size
         */
        public long zipSize() {
            return this.size;
        }
    }
}
