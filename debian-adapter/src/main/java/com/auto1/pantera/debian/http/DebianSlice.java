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
package com.auto1.pantera.debian.http;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blob.DownloadPolicy;
import com.auto1.pantera.debian.Config;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.BasicAuthzSlice;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.rt.MethodRule;
import com.auto1.pantera.http.rt.RtRule;
import com.auto1.pantera.http.rt.RtRulePath;
import com.auto1.pantera.http.rt.SliceRoute;
import com.auto1.pantera.http.slice.*;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.scheduling.RepositoryEvents;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;

import java.util.Optional;
import java.util.Queue;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Debian slice.
 */
public final class DebianSlice extends Slice.Wrap {

    /**
     * Repository type name.
     */
    private static final String REPO_TYPE = "debian";

    /**
     * WS1.7 redirect gate for the shared catch-all GET route: only binary
     * packages ({@code .deb}), micro-packages ({@code .udeb}) and debug
     * packages ({@code .ddeb}) are redirect-eligible. Every apt index and
     * signature -- {@code Release}, {@code InRelease}, {@code Release.gpg},
     * {@code Packages(.gz/.xz)}, {@code Sources(.gz/.xz)}, {@code
     * Contents-*(.gz)} -- streams, so the {@link ReleaseSlice}-generated
     * metadata is never bypassed by a 302 (none of it ends in a package
     * suffix).
     */
    private static final Predicate<Key> REDIRECTABLE = key -> {
        final String path = key.string();
        return path.endsWith(".deb") || path.endsWith(".udeb") || path.endsWith(".ddeb");
    };

    /**
     * Ctor.
     * @param storage Storage
     * @param policy Policy
     * @param users Users
     * @param config Repository configuration
     * @param events Artifact events queue
     */
    public DebianSlice(
            final Storage storage,
            final Policy<?> policy,
            final Authentication users,
            final Config config,
            final Optional<Queue<ArtifactEvent>> events
    ) {
        this(storage, policy, users, config, events,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with synchronous artifact-index writer -- stream-only downloads.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public DebianSlice(
            final Storage storage,
            final Policy<?> policy,
            final Authentication users,
            final Config config,
            final Optional<Queue<ArtifactEvent>> events,
            final com.auto1.pantera.index.SyncArtifactIndexer syncIndex
    ) {
        this(storage, policy, users, config, events, syncIndex, DownloadPolicy.streamOnly());
    }

    /**
     * Ctor with an explicit WS1.7 download policy: {@code .deb}/{@code
     * .udeb}/{@code .ddeb} package GETs become redirect-eligible under a
     * non-{@link DownloadPolicy#streamOnly()} policy, while every apt index
     * and signature keeps streaming (see {@link #REDIRECTABLE}).
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public DebianSlice(
            final Storage storage,
            final Policy<?> policy,
            final Authentication users,
            final Config config,
            final Optional<Queue<ArtifactEvent>> events,
            final com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
            final DownloadPolicy downloadPolicy
    ) {
        super(
            new SliceRoute(
                new RtRulePath(
                    MethodRule.GET,
                    new BasicAuthzSlice(
                        new ReleaseSlice(
                            new StorageArtifactSlice(storage, downloadPolicy, DebianSlice.REDIRECTABLE),
                            storage, config
                        ),
                        users,
                        new OperationControl(
                            policy,
                            new AdapterBasicPermission(config.codename(), Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.Any(
                        MethodRule.PUT, MethodRule.POST
                    ),
                    new BasicAuthzSlice(
                        new ReleaseSlice(new UpdateSlice(storage, config, events, syncIndex), storage, config),
                        users,
                        new OperationControl(
                            policy,
                            new AdapterBasicPermission(config.codename(), Action.Standard.WRITE)
                        )
                    )
                ),
                new RtRulePath(
                    MethodRule.DELETE,
                    new BasicAuthzSlice(
                        new DeleteSlice(
                            storage, config,
                            events.map(
                                queue -> new RepositoryEvents(DebianSlice.REPO_TYPE, config.codename(), queue)
                            )
                        ),
                        users,
                        new OperationControl(
                            policy,
                            new AdapterBasicPermission(config.codename(), Action.Standard.WRITE)
                        )
                    )
                ),
                new RtRulePath(
                    RtRule.FALLBACK, new SliceSimple(ResponseBuilder.notFound().build())
                )
            )
        );
    }
}
