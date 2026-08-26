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
package com.auto1.pantera.rpm.http;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blob.DownloadPolicy;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.BasicAuthzSlice;
import com.auto1.pantera.http.auth.CombinedAuthzSliceWrap;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.rt.MethodRule;
import com.auto1.pantera.http.rt.RtRule;
import com.auto1.pantera.http.rt.RtRulePath;
import com.auto1.pantera.http.rt.SliceRoute;
import com.auto1.pantera.http.slice.StorageArtifactSlice;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.rpm.RepoConfig;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;

import java.util.Optional;
import java.util.Queue;
import java.util.function.Predicate;

/**
 * Pantera {@link Slice} for RPM repository HTTP API.
 * @since 0.7
 */
public final class RpmSlice extends Slice.Wrap {

    /**
     * WS1.7 redirect gate for the shared catch-all GET route: only binary
     * packages ({@code .rpm}) and delta packages ({@code .drpm}) are
     * redirect-eligible. Everything under {@code repodata/} ({@code
     * repomd.xml}, {@code *-primary.xml.gz}, {@code *.sqlite.bz2}, ...) is
     * metadata and MUST stream -- none of it ends in {@code .rpm}/{@code
     * .drpm}, so this predicate never leaks an index into a 302.
     */
    private static final Predicate<Key> REDIRECTABLE = key -> {
        final String path = key.string();
        return path.endsWith(".rpm") || path.endsWith(".drpm");
    };

    /**
     * Ctor.
     * @param storage Storage
     * @param policy Access policy.
     * @param auth Auth details.
     * @param config Repository configuration.
     * @param events Artifact events queue
     */
    public RpmSlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication auth,
        final RepoConfig config,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this(storage, policy, auth, null, config, events,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with both basic and token authentication support.
     * @param storage Storage
     * @param policy Access policy.
     * @param basicAuth Basic authentication.
     * @param tokenAuth Token authentication.
     * @param config Repository configuration.
     * @param events Artifact events queue
     */
    public RpmSlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final RepoConfig config,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this(storage, policy, basicAuth, tokenAuth, config, events,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with synchronous artifact-index writer -- stream-only downloads.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public RpmSlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final RepoConfig config,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex
    ) {
        this(storage, policy, basicAuth, tokenAuth, config, events, syncIndex,
            DownloadPolicy.streamOnly());
    }

    /**
     * Ctor with an explicit WS1.7 download policy: {@code .rpm}/{@code .drpm}
     * package GETs become redirect-eligible under a non-{@link
     * DownloadPolicy#streamOnly()} policy, while {@code repodata/} metadata
     * keeps streaming (see {@link #REDIRECTABLE}).
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public RpmSlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final RepoConfig config,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
        final DownloadPolicy downloadPolicy
    ) {
        super(
            RpmSlice.createSliceRoute(
                storage, policy, basicAuth, tokenAuth, config, events, syncIndex, downloadPolicy
            )
        );
    }

    /**
     * Creates slice route with appropriate authentication.
     * @param storage Storage
     * @param policy Access policy
     * @param basicAuth Basic authentication
     * @param tokenAuth Token authentication
     * @param config Repository configuration
     * @param events Artifact events queue
     * @param syncIndex Synchronous artifact-index writer
     * @param downloadPolicy WS1.7 download policy for the catch-all GET route
     * @return Slice route
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private static SliceRoute createSliceRoute(
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final RepoConfig config,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
        final DownloadPolicy downloadPolicy
    ) {
        return new SliceRoute(
            new RtRulePath(
                MethodRule.GET,
                RpmSlice.createAuthSlice(
                    new StorageArtifactSlice(storage, downloadPolicy, RpmSlice.REDIRECTABLE),
                    basicAuth,
                    tokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(config.name(), Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(
                MethodRule.PUT,
                RpmSlice.createAuthSlice(
                    new RpmUpload(storage, config, events, syncIndex),
                    basicAuth,
                    tokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(config.name(), Action.Standard.WRITE)
                    )
                )
            ),
            new RtRulePath(
                MethodRule.DELETE,
                RpmSlice.createAuthSlice(
                    new RpmRemove(storage, config, events),
                    basicAuth,
                    tokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(config.name(), Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(RtRule.FALLBACK, new SliceSimple(ResponseBuilder.notFound().build()))
        );
    }

    /**
     * Creates appropriate authentication slice based on available authentication methods.
     * @param origin Origin slice
     * @param basicAuth Basic authentication
     * @param tokenAuth Token authentication
     * @param control Access control
     * @return Authentication slice
     */
    private static Slice createAuthSlice(
        final Slice origin,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final OperationControl control
    ) {
        if (tokenAuth != null) {
            return new CombinedAuthzSliceWrap(origin, basicAuth, tokenAuth, control);
        } else {
            return new BasicAuthzSlice(origin, basicAuth, control);
        }
    }
}
