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
package com.auto1.pantera.maven.http;

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
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.maven.metadata.ArtifactEventInfo;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;

import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.regex.Pattern;

/**
 * Maven API entry point.
 * @since 0.1
 */
public final class MavenSlice extends Slice.Wrap {

    /**
     * Instance of {@link ArtifactEventInfo}.
     */
    public static final ArtifactEventInfo EVENT_INFO = new ArtifactEventInfo();

    /**
     * Supported artifacts extensions. According to
     * <a href="https://maven.apache.org/ref/3.6.3/maven-core/artifact-handlers.html">Artifact
     * handlers</a> by maven-core and <a href="https://maven.apache.org/pom.html">Maven docs</a>.
     *
     * <p>{@code maven-plugin} and {@code ejb} are Maven <em>packaging types</em>, not file
     * suffixes — a {@code maven-plugin}/{@code ejb}-packaged artifact is still physically a
     * {@code .jar}, so those two tokens never matched anything here and were removed
     * (WS4-maven.12). {@code rar} is kept: {@code rar}-packaged projects do emit a literal
     * {@code .rar} file.
     */
    public static final List<String> EXT =
        List.of("jar", "war", "ear", "rar", "zip", "aar", "pom");

    /**
     * Pattern to obtain artifact name and version from key. The regex DOES NOT match
     * checksum files, xmls, javadoc and sources archives. Uses list of supported extensions
     * from above.
     */
    public static final Pattern ARTIFACT = Pattern.compile(
        String.format(
            "^(?<pkg>.+)/.+(?<!sources|javadoc)\\.(?<ext>%s)$", String.join("|", MavenSlice.EXT)
        )
    );

    /**
     * H1 fix: {@link UploadSlice#STAGING_PREFIX} is a plain {@code Storage}
     * key namespace, not an access-controlled one — without this guard a
     * request whose path directly addresses {@code /.pgp-pending/<...>}
     * would reach {@link LocalMavenSlice} (GET/HEAD) or {@link UploadSlice}
     * (PUT) exactly like any other path, defeating the quarantine: a
     * not-yet-signature-verified primary could be read straight out of
     * staging, or a client could plant/overwrite bytes inside it directly.
     * Matched against the full request path with a literal path-segment
     * boundary on both sides, so a real artifact whose name merely
     * <em>contains</em> {@code .pgp-pending} as a substring (not as its own
     * path segment) is unaffected.
     */
    private static final Pattern STAGING_PATH_GUARD = Pattern.compile(
        ".*/" + Pattern.quote(UploadSlice.STAGING_PREFIX) + "/.*"
    );

    /**
     * Private ctor since Pantera doesn't know about `Identities` implementation.
     * @param storage The storage.
     * @param policy Access policy.
     * @param users Concrete identities.
     * @param name Repository name
     * @param events Artifact events
     */
    public MavenSlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication users,
        final String name,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this(storage, policy, users, null, name, events,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with both basic and token authentication support.
     * @param storage The storage.
     * @param policy Access policy.
     * @param basicAuth Basic authentication.
     * @param tokenAuth Token authentication.
     * @param name Repository name
     * @param events Artifact events
     */
    public MavenSlice(
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
     * Ctor with synchronous index writer for read-after-write consistency.
     * Uses {@link MavenHostedPolicy#DEFAULT} (no PGP verify, no release
     * immutability) — byte-identical to pre-2.3.0 hosted-write behaviour.
     * @param storage The storage.
     * @param policy Access policy.
     * @param basicAuth Basic authentication.
     * @param tokenAuth Token authentication.
     * @param name Repository name
     * @param events Artifact events
     * @param syncIndex Synchronous artifact-index writer
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public MavenSlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex
    ) {
        this(storage, policy, basicAuth, tokenAuth, name, events, syncIndex, MavenHostedPolicy.DEFAULT);
    }

    /**
     * Ctor with synchronous index writer AND hosted-write policy
     * (WS4-maven.2/.6 — PGP verify, release immutability).
     * @param storage The storage.
     * @param policy Access policy.
     * @param basicAuth Basic authentication.
     * @param tokenAuth Token authentication.
     * @param name Repository name
     * @param events Artifact events
     * @param syncIndex Synchronous artifact-index writer
     * @param hostedPolicy Hosted-write policy (verifyPgp / releaseImmutable)
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public MavenSlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
        final MavenHostedPolicy hostedPolicy
    ) {
        this(storage, policy, basicAuth, tokenAuth, name, events, syncIndex, hostedPolicy,
            DownloadPolicy.streamOnly());
    }

    /**
     * Ctor with synchronous index writer, hosted-write policy AND an explicit
     * WS1.7 (spec {@code WS1-storage-for-scale.md} &sect;3.B2) download policy.
     * Only the local artifact-byte GET path ({@link LocalMavenSlice}) reads the
     * policy, and only for real binary artifacts -- {@code maven-metadata.xml},
     * checksum and signature sidecars always stream.
     * @param storage The storage.
     * @param policy Access policy.
     * @param basicAuth Basic authentication.
     * @param tokenAuth Token authentication.
     * @param name Repository name
     * @param events Artifact events
     * @param syncIndex Synchronous artifact-index writer
     * @param hostedPolicy Hosted-write policy (verifyPgp / releaseImmutable)
     * @param downloadPolicy WS1.7 presigned-direct-download policy
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public MavenSlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
        final MavenHostedPolicy hostedPolicy,
        final DownloadPolicy downloadPolicy
    ) {
        super(
            MavenSlice.createSliceRoute(
                storage, policy, basicAuth, tokenAuth, name, events, syncIndex, hostedPolicy,
                downloadPolicy
            )
        );
    }

    /**
     * Creates slice route with appropriate authentication.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private static SliceRoute createSliceRoute(
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
        final MavenHostedPolicy hostedPolicy,
        final DownloadPolicy downloadPolicy
    ) {
        return new SliceRoute(
            // H1 fix — must stay first: see STAGING_PATH_GUARD. Matched
            // before auth so a probe of the quarantine namespace gets an
            // identical 404 whether or not the caller can authenticate,
            // same as any other nonexistent path.
            new RtRulePath(
                new RtRule.ByPath(STAGING_PATH_GUARD),
                new SliceSimple(ResponseBuilder.notFound().build())
            ),
            new RtRulePath(
                new RtRule.Any(
                    MethodRule.GET, MethodRule.HEAD
                ),
                MavenSlice.createAuthSlice(
                    new LocalMavenSlice(storage, name, downloadPolicy),
                    basicAuth,
                    tokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(
                new RtRule.All(
                    MethodRule.PUT,
                    new RtRule.ByPath(".*SNAPSHOT.*")
                ),
                MavenSlice.createAuthSlice(
                    new UploadSlice(storage, events, name, syncIndex, hostedPolicy),
                    basicAuth,
                    tokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                    )
                )
            ),
            new RtRulePath(
                MethodRule.PUT,
                MavenSlice.createAuthSlice(
                    new UploadSlice(storage, events, name, syncIndex, hostedPolicy),
                    basicAuth,
                    tokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                    )
                )
            ),
            new RtRulePath(
                RtRule.FALLBACK, new SliceSimple(ResponseBuilder.notFound().build())
            )
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
