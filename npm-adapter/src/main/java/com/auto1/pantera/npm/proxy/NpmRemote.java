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
package com.auto1.pantera.npm.proxy;

import com.auto1.pantera.npm.proxy.model.NpmAsset;
import com.auto1.pantera.npm.proxy.model.NpmPackage;
import io.reactivex.Maybe;
import java.io.Closeable;
import java.nio.file.Path;

/**
 * NPM Remote client interface.
 * @since 0.1
 */
public interface NpmRemote extends Closeable {
    /**
     * Loads package from remote repository.
     * @param name Package name
     * @return NPM package or empty
     */
    Maybe<NpmPackage> loadPackage(String name);

    /**
     * Loads asset from remote repository. Typical usage for client:
     * <pre>
     * Path tmp = &lt;create temporary file&gt;
     * NpmAsset asset = remote.loadAsset(asset, tmp);
     * ... consumes asset's data ...
     * Files.delete(tmp);
     * </pre>
     *
     * @param path Asset path
     * @param tmp Temporary file to store asset data
     * @return NpmAsset or empty
     */
    Maybe<NpmAsset> loadAsset(String path, Path tmp);
}
