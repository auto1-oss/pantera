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
package com.auto1.pantera.api.v1;

import com.auto1.pantera.index.reindex.IndexReindex;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * The one index rebuild job of the process. {@link AsyncApiVerticle} is
 * deployed as several instances; each builds its own {@link SearchHandler},
 * but they must share the job so a second POST is refused with 409 whichever
 * instance serves it, and the status is the same on every instance.
 *
 * <p>Keyed by data source: a new data source (tests redeploying the
 * verticle against a fresh pool) replaces the job.</p>
 *
 * @since 2.2.9
 */
final class SharedReindex {

    /**
     * Current data source.
     */
    private static DataSource source;

    /**
     * Current job.
     */
    private static IndexReindex job;

    /**
     * Utility class.
     */
    private SharedReindex() {
    }

    /**
     * The job for a data source, created on first use.
     * @param ds Data source
     * @param factory Job factory
     * @return Shared job
     */
    static synchronized IndexReindex obtain(
        final DataSource ds, final Supplier<IndexReindex> factory
    ) {
        if (SharedReindex.job == null || !ds.equals(SharedReindex.source)) {
            if (SharedReindex.job != null) {
                SharedReindex.job.close();
            }
            SharedReindex.job = factory.get();
            SharedReindex.source = ds;
        }
        return SharedReindex.job;
    }
}
