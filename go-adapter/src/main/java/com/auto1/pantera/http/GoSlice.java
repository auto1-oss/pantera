/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.BasicAuthzSlice;
import com.auto1.pantera.http.auth.CombinedAuthzSliceWrap;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rt.MethodRule;
import com.auto1.pantera.http.rt.RtRule;
import com.auto1.pantera.http.rt.RtRulePath;
import com.auto1.pantera.http.rt.SliceRoute;
import com.auto1.pantera.http.slice.LoggingSlice;
import com.auto1.pantera.http.slice.SliceDownload;
import com.auto1.pantera.http.slice.StorageArtifactSlice;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.http.slice.SliceWithHeaders;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Slice implementation that provides HTTP API (Go module proxy protocol) for Golang repository.
 */
public final class GoSlice implements Slice {

    private final Slice origin;

    /**
     * @param storage Storage
     * @param policy Security policy
     * @param users Users
     * @param name Repository name
     */
    public GoSlice(final Storage storage, final Policy<?> policy, final Authentication users,
        final String name) {
        this(storage, policy, users, null, name, Optional.empty());
    }

    /**
     * @param storage Storage
     * @param policy Security policy
     * @param users Users
     * @param name Repository name
     * @param events Artifact events
     */
    public GoSlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication users,
        final String name,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this(storage, policy, users, null, name, events);
    }

    /**
     * Ctor with combined authentication support.
     * @param storage Storage
     * @param policy Security policy
     * @param basicAuth Basic authentication
     * @param tokenAuth Token authentication
     * @param name Repository name
     */
    public GoSlice(final Storage storage, final Policy<?> policy, final Authentication basicAuth,
        final TokenAuthentication tokenAuth, final String name) {
        this(storage, policy, basicAuth, tokenAuth, name, Optional.empty());
    }

    /**
     * Ctor with combined authentication support.
     * @param storage Storage
     * @param policy Security policy
     * @param basicAuth Basic authentication
     * @param tokenAuth Token authentication
     * @param name Repository name
     * @param events Artifact events queue
     */
    public GoSlice(
        final Storage storage,
        final Policy<?> policy,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final String name,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this.origin = new SliceRoute(
            GoSlice.pathGet(
                ".+/@v/v.*\\.info",
                GoSlice.createSlice(storage, ContentType.json(), policy, basicAuth, tokenAuth, name)
            ),
            GoSlice.pathGet(
                ".+/@v/v.*\\.mod",
                GoSlice.createSlice(storage, ContentType.text(), policy, basicAuth, tokenAuth, name)
            ),
            GoSlice.pathGet(
                ".+/@v/v.*\\.zip",
                GoSlice.createSlice(storage, ContentType.mime("application/zip"), policy, basicAuth, tokenAuth, name)
            ),
            GoSlice.pathGet(
                ".+/@v/list", GoSlice.createSlice(storage, ContentType.text(), policy, basicAuth, tokenAuth, name)
            ),
            GoSlice.pathGet(
                ".+/@latest",
                GoSlice.createAuthSlice(
                    new LatestSlice(storage),
                    basicAuth,
                    tokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.READ)
                    )
                )
            ),
            new RtRulePath(
                MethodRule.PUT,
                GoSlice.createAuthSlice(
                    new GoUploadSlice(storage, name, events),
                    basicAuth,
                    tokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.WRITE)
                    )
                )
            ),
            new RtRulePath(
                RtRule.FALLBACK,
                GoSlice.createAuthSlice(
                    new SliceSimple(ResponseBuilder.notFound().build()),
                    basicAuth,
                    tokenAuth,
                    new OperationControl(
                        policy, new AdapterBasicPermission(name, Action.Standard.READ)
                    )
                )
            )
        );
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers,
        final Content body) {
        return this.origin.response(line, headers, body);
    }

    /**
     * Creates slice instance.
     * @param storage Storage
     * @param contentType Content-type
     * @param policy Security policy
     * @param basicAuth Basic authentication
     * @param tokenAuth Token authentication
     * @param name Repository name
     * @return Slice
     */
    private static Slice createSlice(
        Storage storage,
        Header contentType,
        Policy<?> policy,
        Authentication basicAuth,
        TokenAuthentication tokenAuth,
        String name
    ) {
        return GoSlice.createAuthSlice(
            new SliceWithHeaders(new StorageArtifactSlice(storage), Headers.from(contentType)),
            basicAuth,
            tokenAuth,
            new OperationControl(policy, new AdapterBasicPermission(name, Action.Standard.READ))
        );
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

    /**
     * This method simply encapsulates all the RtRule instantiations.
     * @param pattern Route pattern
     * @param slice Slice implementation
     * @return Path route slice
     */
    private static RtRulePath pathGet(final String pattern, final Slice slice) {
        return new RtRulePath(
            new RtRule.All(
                new RtRule.ByPath(Pattern.compile(pattern)),
                MethodRule.GET
            ),
            new LoggingSlice(slice)
        );
    }
}
