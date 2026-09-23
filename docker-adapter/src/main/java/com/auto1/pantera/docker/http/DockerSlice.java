/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.http;

import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.http.blobs.GetBlobsSlice;
import com.auto1.pantera.docker.http.blobs.HeadBlobsSlice;
import com.auto1.pantera.docker.http.manifest.GetManifestSlice;
import com.auto1.pantera.docker.http.manifest.HeadManifestSlice;
import com.auto1.pantera.docker.http.manifest.PushManifestSlice;
import com.auto1.pantera.docker.http.upload.DeleteUploadSlice;
import com.auto1.pantera.docker.http.upload.GetUploadSlice;
import com.auto1.pantera.docker.http.upload.PatchUploadSlice;
import com.auto1.pantera.docker.http.upload.PostUploadSlice;
import com.auto1.pantera.docker.http.upload.PutUploadSlice;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthScheme;
import com.auto1.pantera.http.rt.MethodRule;
import com.auto1.pantera.http.rt.RtPath;
import com.auto1.pantera.http.rt.RtRule;
import com.auto1.pantera.http.rt.RtRulePath;
import com.auto1.pantera.http.rt.SliceRoute;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.policy.Policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

/**
 * Slice implementing Docker Registry HTTP API.
 * See <a href="https://docs.docker.com/registry/spec/api/">Docker Registry HTTP API V2</a>.
 */
public final class DockerSlice extends Slice.Wrap {

    /**
     * @param docker Docker repository.
     */
    public DockerSlice(final Docker docker) {
        this(docker, Policy.FREE, AuthScheme.NONE, Optional.empty());
    }

    /**
     * @param docker Docker repository.
     * @param events Artifact events
     */
    public DockerSlice(final Docker docker, final Queue<ArtifactEvent> events) {
        this(docker, Policy.FREE, AuthScheme.NONE, Optional.of(events));
    }

    /**
     * @param docker Docker repository.
     * @param policy Access policy.
     * @param auth Authentication scheme.
     * @param events Artifact events queue.
     */
    public DockerSlice(
        Docker docker, Policy<?> policy, AuthScheme auth,
        Optional<Queue<ArtifactEvent>> events
    ) {
        this(docker, policy, auth, events,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with synchronous artifact-index writer.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public DockerSlice(
        Docker docker, Policy<?> policy, AuthScheme auth,
        Optional<Queue<ArtifactEvent>> events,
        com.auto1.pantera.index.SyncArtifactIndexer syncIndex
    ) {
        this(docker, policy, auth, events, syncIndex, true);
    }

    /**
     * Ctor.
     *
     * <p>A read-only slice (a proxy) answers every write route (blob
     * upload POST/PATCH/PUT/GET/DELETE and manifest PUT) with
     * 405 {@code UNSUPPORTED} before reading the repository or authorizing
     * the request, so a refused push never triggers an upstream lookup and
     * its body is always drained.</p>
     *
     * @param docker Docker repository.
     * @param policy Access policy.
     * @param auth Authentication scheme.
     * @param events Artifact events queue.
     * @param syncIndex Synchronous artifact-index writer.
     * @param writable Whether clients may push to this repository.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public DockerSlice(
        Docker docker, Policy<?> policy, AuthScheme auth,
        Optional<Queue<ArtifactEvent>> events,
        com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
        boolean writable
    ) {
        super(
            new ErrorHandlingSlice(
                new SliceRoute(
                    DockerSlice.routes(
                        docker, policy, auth, events, syncIndex, writable
                    )
                )
            )
        );
    }

    /**
     * Registry API routes.
     *
     * @param docker Docker repository.
     * @param policy Access policy.
     * @param auth Authentication scheme.
     * @param events Artifact events queue.
     * @param syncIndex Synchronous artifact-index writer.
     * @param writable Whether clients may push to this repository.
     * @return Routes in match order.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private static List<RtPath> routes(
        final Docker docker, final Policy<?> policy, final AuthScheme auth,
        final Optional<Queue<ArtifactEvent>> events,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
        final boolean writable
    ) {
        final List<RtPath> routes = new ArrayList<>(20);
        if (!writable) {
            // Refuse writes up front: no repository lookup (which on a
            // proxy would fetch and cache the upstream manifest) and the
            // body is drained by UnsupportedSlice.
            routes.add(RtRulePath.route(MethodRule.PUT, PathPatterns.MANIFESTS,
                new UnsupportedSlice()));
            for (final RtRule method : List.of(
                MethodRule.POST, MethodRule.PATCH, MethodRule.PUT,
                MethodRule.GET, MethodRule.DELETE
            )) {
                routes.add(RtRulePath.route(method, PathPatterns.UPLOADS,
                    new UnsupportedSlice()));
            }
        }
        routes.addAll(
            List.of(
                RtRulePath.route(MethodRule.GET, PathPatterns.BASE,
                    auth(new BaseSlice(docker), policy, auth)
                ),
                RtRulePath.route(MethodRule.HEAD, PathPatterns.MANIFESTS,
                    auth(new HeadManifestSlice(docker), policy, auth)
                ),
                RtRulePath.route(MethodRule.GET, PathPatterns.MANIFESTS,
                    auth(new GetManifestSlice(docker), policy, auth)
                ),
                RtRulePath.route(MethodRule.PUT, PathPatterns.MANIFESTS,
                    auth(new PushManifestSlice(docker, events.orElse(null), syncIndex, policy),
                        policy, auth)
                ),
                RtRulePath.route(MethodRule.GET, PathPatterns.TAGS,
                    auth(new TagsSlice(docker), policy, auth)
                ),
                RtRulePath.route(MethodRule.HEAD, PathPatterns.BLOBS,
                    auth(new HeadBlobsSlice(docker), policy, auth)
                ),
                RtRulePath.route(MethodRule.GET, PathPatterns.BLOBS,
                    auth(new GetBlobsSlice(docker), policy, auth)
                ),
                RtRulePath.route(MethodRule.POST, PathPatterns.UPLOADS,
                    auth(new PostUploadSlice(docker), policy, auth)
                ),
                RtRulePath.route(MethodRule.PATCH, PathPatterns.UPLOADS,
                    auth(new PatchUploadSlice(docker), policy, auth)
                ),
                RtRulePath.route(MethodRule.PUT, PathPatterns.UPLOADS,
                    auth(new PutUploadSlice(docker), policy, auth)
                ),
                RtRulePath.route(MethodRule.GET, PathPatterns.UPLOADS,
                    auth(new GetUploadSlice(docker), policy, auth)
                ),
                RtRulePath.route(MethodRule.DELETE, PathPatterns.UPLOADS,
                    auth(new DeleteUploadSlice(docker), policy, auth)
                ),
                RtRulePath.route(MethodRule.GET, PathPatterns.CATALOG,
                    auth(new CatalogSlice(docker), policy, auth)
                ),
                RtRulePath.route(MethodRule.GET, PathPatterns.REFERRERS,
                    auth(new ReferrersSlice(docker), policy, auth)
                ),
                // Deletion through the registry API is not supported;
                // the spec requires 405 (tags are removed via the UI
                // or the REST API).
                RtRulePath.route(MethodRule.DELETE, PathPatterns.MANIFESTS,
                    new UnsupportedSlice()
                ),
                RtRulePath.route(MethodRule.DELETE, PathPatterns.BLOBS,
                    new UnsupportedSlice()
                )
            )
        );
        return routes;
    }

    /**
     * Requires authentication and authorization for slice.
     *
     * @param origin Origin slice.
     * @param policy Access permissions.
     * @param auth Authentication scheme.
     * @return Authorized slice.
     */
    private static Slice auth(DockerActionSlice origin, Policy<?> policy, AuthScheme auth) {
        return new DockerAuthSlice(new AuthScopeSlice(origin, auth, policy));
    }
}
