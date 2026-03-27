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
package com.auto1.pantera.composer;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * PHP Composer packages registry.
 *
 * @since 0.1
 */
public interface Packages {
    /**
     * Add package.
     *
     * @param pack Package.
     * @param version Version in case of absence version in package. If package does not
     *  contain version, this value should be passed as a parameter.
     * @return Updated packages.
     */
    CompletionStage<Packages> add(Package pack, Optional<String> version);

    /**
     * Saves packages registry binary content to storage.
     *
     * @param storage Storage to use for saving.
     * @param key Key to store packages.
     * @return Completion of saving.
     */
    CompletionStage<Void> save(Storage storage, Key key);

    /**
     * Reads packages registry binary content.
     *
     * @return Content.
     */
    CompletionStage<Content> content();
}
