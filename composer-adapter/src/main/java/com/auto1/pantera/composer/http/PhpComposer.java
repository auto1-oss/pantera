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
package com.auto1.pantera.composer.http;

import com.auto1.pantera.asto.blob.DownloadPolicy;
import com.auto1.pantera.composer.ComposerBaseUrl;
import com.auto1.pantera.composer.Repository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.BasicAuthzSlice;
import com.auto1.pantera.http.auth.CombinedAuthzSliceWrap;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.rt.MethodRule;
import com.auto1.pantera.http.rt.RtRule;
import com.auto1.pantera.http.rt.RtRulePath;
import com.auto1.pantera.http.rt.SliceRoute;
import com.auto1.pantera.http.slice.HeadAsGetSlice;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import java.util.Optional;
import java.util.Queue;
import java.util.regex.Pattern;

/**
 * PHP Composer repository HTTP front end.
 *
 * @since 0.1
 */
public final class PhpComposer extends Slice.Wrap {
    /**
     * Ctor.
     * @param repository Repository
     * @param policy Access permissions
     * @param auth Authentication
     * @param name Repository name
     * @param events Artifact repository events
     */
    public PhpComposer(
        final Repository repository,
        final Policy<?> policy,
        final Authentication auth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this(repository, policy, auth, null, name, events,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with combined authentication support.
     * @param repository Repository
     * @param policy Access permissions
     * @param basicAuth Basic authentication
     * @param tokenAuth Token authentication
     * @param name Repository name
     * @param events Artifact repository events
     */
    public PhpComposer(
        final Repository repository,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this(repository, policy, basicAuth, tokenAuth, name, events,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with synchronous artifact-index writer, for a repository without a
     * configured {@code url:}.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public PhpComposer(
        final Repository repository,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex
    ) {
        this(repository, policy, basicAuth, tokenAuth, name, events, syncIndex, ArtifactIndex.NOP);
    }

    /**
     * Full ctor with the read-side shared artifact index (WS4-composer.5/.6:
     * {@code available-packages.json} / {@code packages/list.json}).
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public PhpComposer(
        final Repository repository,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
        final ArtifactIndex artifactIndex
    ) {
        this(repository, policy, basicAuth, tokenAuth, name, events, syncIndex, artifactIndex,
            DownloadPolicy.streamOnly());
    }

    /**
     * Ctor with the repository's {@code immutable} setting, for a repository
     * without a configured {@code url:}.
     * @param repository Repository
     * @param policy Access permissions
     * @param basicAuth Basic authentication
     * @param tokenAuth Token authentication
     * @param name Repository name
     * @param events Artifact repository events
     * @param syncIndex Synchronous artifact-index writer
     * @param immutable When true a published release cannot be overwritten
     *  (identical re-upload: 201, different content: 409); when false release
     *  uploads overwrite. Dev versions are always mutable.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public PhpComposer(
        final Repository repository,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
        final boolean immutable
    ) {
        this(repository, policy, basicAuth, tokenAuth, name, events, syncIndex, immutable,
            new ComposerBaseUrl(Optional.empty(), name));
    }

    /**
     * Ctor with the repository's {@code immutable} setting and the
     * client-facing base its served links are rooted at.
     * @param repository Repository
     * @param policy Access permissions
     * @param basicAuth Basic authentication
     * @param tokenAuth Token authentication
     * @param name Repository name
     * @param events Artifact repository events
     * @param syncIndex Synchronous artifact-index writer
     * @param immutable When true a published release cannot be overwritten
     *  (identical re-upload: 201, different content: 409); when false release
     *  uploads overwrite. Dev versions are always mutable.
     * @param base Client-facing base URL the served metadata links are rooted at
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public PhpComposer(
        final Repository repository,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
        final boolean immutable,
        final ComposerBaseUrl base
    ) {
        this(repository, policy, basicAuth, tokenAuth, name, events, syncIndex, ArtifactIndex.NOP,
            DownloadPolicy.streamOnly(), immutable, base);
    }

    /**
     * Ctor additionally carrying the WS1.7 (spec {@code
     * WS1-storage-for-scale.md} &sect;3.B2) download policy. Only the two
     * dist-archive download routes ({@link DownloadArchiveSlice}) become
     * redirect-eligible under a non-{@link DownloadPolicy#streamOnly()} policy;
     * every metadata route ({@code /p2/}, {@code available-packages.json},
     * {@code packages/list.json}) always streams. Releases are immutable.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public PhpComposer(
        final Repository repository,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
        final ArtifactIndex artifactIndex,
        final DownloadPolicy downloadPolicy
    ) {
        this(repository, policy, basicAuth, tokenAuth, name, events, syncIndex, artifactIndex,
            downloadPolicy, true);
    }

    /**
     * Ctor with the read-side artifact index, WS1.7 download policy and the
     * repository's {@code immutable} setting, for a repository without a
     * configured {@code url:}.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public PhpComposer(
        final Repository repository,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
        final ArtifactIndex artifactIndex,
        final DownloadPolicy downloadPolicy,
        final boolean immutable
    ) {
        this(repository, policy, basicAuth, tokenAuth, name, events, syncIndex, artifactIndex,
            downloadPolicy, immutable, new ComposerBaseUrl(Optional.empty(), name));
    }

    /**
     * Primary ctor: read-side artifact index, WS1.7 download policy, the
     * repository's {@code immutable} setting and the client-facing base its
     * served links are rooted at.
     * @param repository Repository
     * @param policy Access permissions
     * @param basicAuth Basic authentication
     * @param tokenAuth Token authentication
     * @param name Repository name
     * @param events Artifact repository events
     * @param syncIndex Synchronous artifact-index writer
     * @param artifactIndex Read-side shared artifact index
     * @param downloadPolicy Per-repo download policy (dist archives only)
     * @param immutable When true a published release cannot be overwritten
     *  (identical re-upload: 201, different content: 409); when false release
     *  uploads overwrite. Dev versions are always mutable.
     * @param base Client-facing base URL the served metadata links are rooted at
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public PhpComposer(
        final Repository repository,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
        final ArtifactIndex artifactIndex,
        final DownloadPolicy downloadPolicy,
        final boolean immutable,
        final ComposerBaseUrl base
    ) {
        super(
            new SliceRoute(
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.Any(
                            new RtRule.ByPath(PackageMetadataSlice.PACKAGE),
                            new RtRule.ByPath(PackageMetadataSlice.ALL_PACKAGES)
                        ),
                        new RtRule.Any(MethodRule.GET, MethodRule.HEAD)
                    ),
                    new HeadAsGetSlice(
                        PhpComposer.createAuthSlice(
                            new PackageMetadataSlice(repository, base),
                            basicAuth,
                            tokenAuth,
                            new OperationControl(
                                policy, new AdapterBasicPermission(name, Action.Standard.READ)
                            )
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath("^/p2/available-packages\\.json$"),
                        new RtRule.Any(MethodRule.GET, MethodRule.HEAD)
                    ),
                    new HeadAsGetSlice(
                        PhpComposer.createAuthSlice(
                            new ComposerAvailablePackagesSlice(artifactIndex, name),
                            basicAuth,
                            tokenAuth,
                            new OperationControl(
                                policy, new AdapterBasicPermission(name, Action.Standard.READ)
                            )
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath("^/packages/list\\.json$"),
                        new RtRule.Any(MethodRule.GET, MethodRule.HEAD)
                    ),
                    new HeadAsGetSlice(
                        PhpComposer.createAuthSlice(
                            new ComposerListSlice(artifactIndex, name),
                            basicAuth,
                            tokenAuth,
                            new OperationControl(
                                policy, new AdapterBasicPermission(name, Action.Standard.READ)
                            )
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(Pattern.compile("^/?artifacts/.*\\.(zip|tar\\.gz|tgz)$")),
                        new RtRule.Any(MethodRule.GET, MethodRule.HEAD)
                    ),
                    PhpComposer.archive(
                        repository,
                        downloadPolicy,
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(Pattern.compile("^/.*\\.(zip|tar\\.gz|tgz)$")),
                        new RtRule.Any(MethodRule.GET, MethodRule.HEAD)
                    ),
                    PhpComposer.archive(
                        repository,
                        downloadPolicy,
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.READ)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(AddSlice.PATH_PATTERN),
                        MethodRule.PUT
                    ),
                    PhpComposer.createAuthSlice(
                        new AddSlice(repository, immutable),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                        )
                    )
                ),
                new RtRulePath(
                    new RtRule.All(
                        new RtRule.ByPath(".*\\.(zip|tar\\.gz|tgz)$"),
                        MethodRule.PUT
                    ),
                    PhpComposer.createAuthSlice(
                        new AddArchiveSlice(repository, events, name, syncIndex, immutable),
                        basicAuth,
                        tokenAuth,
                        new OperationControl(
                            policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                        )
                    )
                )
            )
        );
    }

    /**
     * Download of a dist archive. A {@code GET} follows the repository's
     * download policy. A {@code HEAD} is answered by the repository itself,
     * like the {@code GET} without its body and whatever the policy: a
     * presigned URL is signed for {@code GET}, so a client that followed a
     * redirect with {@code HEAD} would be refused by the object store.
     *
     * @param repository Repository
     * @param downloads Download policy of the repository
     * @param basicAuth Basic authentication
     * @param tokenAuth Token authentication
     * @param control Operation control
     * @return Slice serving the archive on GET and HEAD
     */
    private static Slice archive(
        final Repository repository, final DownloadPolicy downloads,
        final Authentication basicAuth, final TokenAuthentication tokenAuth,
        final OperationControl control
    ) {
        final Slice get = PhpComposer.createAuthSlice(
            new DownloadArchiveSlice(repository, downloads), basicAuth, tokenAuth, control
        );
        final Slice head = new HeadAsGetSlice(
            PhpComposer.createAuthSlice(
                new DownloadArchiveSlice(repository), basicAuth, tokenAuth, control
            )
        );
        return (line, headers, body) -> {
            final Slice target;
            if (line.method() == RqMethod.HEAD) {
                target = head;
            } else {
                target = get;
            }
            return target.response(line, headers, body);
        };
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
            return new CombinedAuthzSliceWrap(origin, basicAuth, tokenAuth, control);
        }
        return new BasicAuthzSlice(origin, basicAuth, control);
    }
}
