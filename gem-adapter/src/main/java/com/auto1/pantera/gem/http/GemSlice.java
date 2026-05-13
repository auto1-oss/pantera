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
package com.auto1.pantera.gem.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.gem.GemApiKeyAuth;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.AuthzSlice;
import com.auto1.pantera.http.auth.CombinedAuthScheme;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rt.MethodRule;
import com.auto1.pantera.http.rt.RtRule;
import com.auto1.pantera.http.rt.RtRulePath;
import com.auto1.pantera.http.rt.SliceRoute;
import com.auto1.pantera.http.slice.SliceDownload;
import com.auto1.pantera.http.slice.StorageArtifactSlice;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPOutputStream;

/**
 * A slice, which servers gem packages.
 * Ruby HTTP layer.
 */
public final class GemSlice extends Slice.Wrap {

    /**
     * Specs file names required by the RubyGems protocol.
     */
    private static final String[] SPECS_FILES = {
        "specs.4.8", "specs.4.8.gz",
        "latest_specs.4.8", "latest_specs.4.8.gz",
        "prerelease_specs.4.8", "prerelease_specs.4.8.gz",
    };

    /**
     * Empty Ruby Marshal array: Marshal.dump([]) = \x04\x08[\x00.
     */
    private static final byte[] EMPTY_MARSHAL_ARRAY = {0x04, 0x08, 0x5b, 0x00};

    /**
     * Gzipped empty Ruby Marshal array.
     */
    private static final byte[] EMPTY_MARSHAL_ARRAY_GZ;

    static {
        try {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzout = new GZIPOutputStream(bos)) {
                gzout.write(EMPTY_MARSHAL_ARRAY);
            }
            EMPTY_MARSHAL_ARRAY_GZ = bos.toByteArray();
        } catch (final IOException err) {
            throw new UncheckedIOException(err);
        }
    }

    /**
     * @param storage The storage.
     * @param policy The policy.
     * @param auth The auth.
     * @param name Repository name
     */
    public GemSlice(Storage storage, Policy<?> policy, Authentication auth, String name) {
        this(storage, policy, auth, null, name, Optional.empty(),
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor.
     *
     * @param storage The storage.
     * @param policy The policy.
     * @param auth The auth.
     * @param name Repository name
     * @param events Artifact events queue
     */
    public GemSlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication auth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this(storage, policy, auth, null, name, events,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with combined authentication support.
     *
     * @param storage The storage.
     * @param policy The policy.
     * @param basicAuth Basic authentication.
     * @param tokenAuth Token authentication.
     * @param name Repository name
     * @param events Artifact events queue
     */
    public GemSlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this(storage, policy, basicAuth, tokenAuth, name, events,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with synchronous artifact-index writer.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public GemSlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex
    ) {
        super(
            new SliceRoute(
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.POST,
                        new RtRule.ByPath("/api/v1/gems")
                    ),
                    GemSlice.createAuthSlice(
                        new SubmitGemSlice(storage, events, name, syncIndex),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.GET,
                        new RtRule.ByPath("/api/v1/dependencies")
                    ),
                    new DepsGemSlice(storage)
                ),
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.GET,
                        new RtRule.ByPath("/api/v1/api_key")
                    ),
                    new ApiKeySlice(basicAuth)
                ),
                new RtRulePath(
                    new RtRule.All(
                        MethodRule.GET,
                        new RtRule.ByPath(ApiGetSlice.PATH_PATTERN)
                    ),
                    new ApiGetSlice(storage)
                ),
                new RtRulePath(
                    MethodRule.GET,
                    GemSlice.createAuthSlice(
                        new StorageArtifactSlice(storage),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    RtRule.FALLBACK,
                    new SliceSimple(ResponseBuilder.notFound().build())
                )
            )
        );
        GemSlice.initEmptySpecs(storage);
    }

    /**
     * Initialize empty specs files if they don't exist in storage.
     * The RubyGems protocol requires specs.4.8.gz to be present even
     * for empty repositories. Without them, all gem client operations fail.
     *
     * @param storage Repository storage
     */
    private static void initEmptySpecs(final Storage storage) {
        for (final String name : GemSlice.SPECS_FILES) {
            final Key key = new Key.From(name);
            try {
                if (!storage.exists(key).join()) {
                    final byte[] data = name.endsWith(".gz")
                        ? EMPTY_MARSHAL_ARRAY_GZ
                        : EMPTY_MARSHAL_ARRAY;
                    storage.save(key, new Content.From(data)).join();
                }
            } catch (final Exception err) {
                EcsLogger.warn("com.auto1.pantera.gem")
                    .message("Failed to init specs file")
                    .field("file", name)
                    .error(err)
                    .log();
            }
        }
    }

    /**
     * Creates appropriate auth slice based on available authentication methods.
     * @param origin Original slice to wrap
     * @param basicAuth Basic authentication
     * @param tokenAuth Token authentication
     * @param control Operation control
     * @return Auth slice
     */
    private static Slice createAuthSlice(
        final Slice origin, final Authentication basicAuth, 
        final TokenAuthentication tokenAuth, final OperationControl control
    ) {
        if (tokenAuth != null) {
            return new AuthzSlice(origin, new CombinedAuthScheme(basicAuth, tokenAuth), control);
        }
        return new AuthzSlice(origin, new GemApiKeyAuth(basicAuth), control);
    }
}
