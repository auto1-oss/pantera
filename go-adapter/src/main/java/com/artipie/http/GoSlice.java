/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http;

import com.artipie.asto.Content;
import com.artipie.asto.Storage;
import com.artipie.http.auth.Authentication;
import com.artipie.http.auth.BasicAuthzSlice;
import com.artipie.http.auth.CombinedAuthzSliceWrap;
import com.artipie.http.auth.OperationControl;
import com.artipie.http.auth.TokenAuthentication;
import com.artipie.http.headers.ContentType;
import com.artipie.http.headers.Header;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rt.MethodRule;
import com.artipie.http.rt.RtRule;
import com.artipie.http.rt.RtRulePath;
import com.artipie.http.rt.SliceRoute;
import com.artipie.http.slice.LoggingSlice;
import com.artipie.http.slice.SliceDownload;
import com.artipie.http.slice.SliceSimple;
import com.artipie.http.slice.SliceWithHeaders;
import com.artipie.security.perms.Action;
import com.artipie.security.perms.AdapterBasicPermission;
import com.artipie.security.policy.Policy;

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
        this(storage, policy, users, null, name);
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
                RtRule.FALLBACK,
                new SliceSimple(ResponseBuilder.notFound().build())
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
            new SliceWithHeaders(new SliceDownload(storage), Headers.from(contentType)),
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
