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
package  com.auto1.pantera.conan.http;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.lock.storage.IndexUpdateLock;
import com.auto1.pantera.conan.ItemTokenizer;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.AuthzSlice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;
import com.auto1.pantera.http.rq.RqParams;
import com.auto1.pantera.http.slice.KeyFromPath;
import com.auto1.pantera.http.slice.SliceUpload;
import com.auto1.pantera.scheduling.RepositoryEvents;

import javax.json.Json;
import javax.json.JsonObjectBuilder;
import javax.json.stream.JsonParser;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Slice for Conan package data uploading support.
 */
public final class ConanUpload {

    /**
     * Pattern for /v1/conans/{path}/upload_urls.
     */
    public static final PathWrap UPLOAD_SRC_PATH = new PathWrap.UploadSrc();

    /**
     * HTTP Content-type header name.
     */
    private static final String CONTENT_TYPE = "Content-Type";

    /**
     * HTTP json application type string.
     */
    private static final String JSON_TYPE = "application/json";

    /**
     * Path part of the request URI.
     */
    private static final String URI_PATH = "path";

    /**
     * Host name http header.
     */
    private static final String HOST = "Host";

    /**
     * Subdir for package recipe (sources).
     */
    private static final String PKG_SRC_DIR = "/0/export/";

    /**
     * Subdir for package binary.
     */
    private static final String PKG_BIN_DIR = "/0/";

    /**
     * Ctor is hidden.
     */
    private ConanUpload() { }

    /**
     * Match pattern for the request.
     *
     * @param line Request line.
     * @return Corresponding matcher for the request.
     */
    private static Matcher matchRequest(final RequestLine line) {
        final Matcher matcher = ConanUpload.UPLOAD_SRC_PATH.getPattern().matcher(
            line.uri().getPath()
        );
        if (!matcher.matches()) {
            throw new PanteraException("Request parameters doesn't match: " + line);
        }
        return matcher;
    }

    /**
     * Conan /v1/conans/{path}/upload_urls REST APIs.
     */
    public static final class UploadUrls implements Slice {

        /**
         * Current Pantera storage instance.
         */
        private final Storage storage;

        /**
         * Tokenizer for repository items.
         */
        private final ItemTokenizer tokenizer;

        /**
         * Name of the repository the upload URLs are issued for.
         */
        private final String repository;

        /**
         * Whether a stored file may never be replaced by an upload.
         */
        private final boolean immutable;

        /**
         * Ctor; URLs are issued for stored files too (they are overwritten).
         * @param storage Current Pantera storage instance.
         * @param tokenizer Tokenizer for repository items.
         * @param repository Name of the repository the upload URLs are issued for.
         */
        public UploadUrls(final Storage storage, final ItemTokenizer tokenizer,
            final String repository) {
            this(storage, tokenizer, repository, false);
        }

        /**
         * Ctor.
         * @param storage Current Pantera storage instance.
         * @param tokenizer Tokenizer for repository items.
         * @param repository Name of the repository the upload URLs are issued for.
         * @param immutable When {@code true} a request naming a file that is
         *  already stored is refused with 404 (the status this endpoint has
         *  always refused with, which the conan client reports as an error);
         *  when {@code false} URLs are issued for stored files too
         */
        public UploadUrls(final Storage storage, final ItemTokenizer tokenizer,
            final String repository, final boolean immutable) {
            this.storage = storage;
            this.tokenizer = tokenizer;
            this.repository = repository;
            this.immutable = immutable;
        }

        @Override
        public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
            final Matcher matcher = matchRequest(line);
            final String path = matcher.group(ConanUpload.URI_PATH);
            final Signer signer = new Signer(
                new RqHeaders.Single(headers, ConanUpload.HOST).asString(),
                ConanUpload.verifiedUser(headers)
            );
            return new Content.From(body).asStringFuture().thenCompose(
                str -> {
                    final Map<String, String> files = ConanUpload.files(path, str);
                    return this.stored(path, files.values()).thenApply(
                        taken -> taken.map(ConanUpload::refusal).orElseGet(
                            () -> this.urls(files, signer, new RepoFileUrl(headers))
                        )
                    );
                }
            );
        }

        /**
         * First requested file already in storage, when the repository is
         * immutable. The reference path itself is checked as well (the
         * check this endpoint always made).
         * @param path Reference path of the request
         * @param files Requested file paths, each starting with a slash
         * @return Stored file, empty when none is or the repository is mutable
         */
        private CompletableFuture<Optional<String>> stored(final String path,
            final Collection<String> files) {
            final CompletableFuture<Optional<String>> res;
            if (this.immutable) {
                final List<String> candidates = new ArrayList<>(files.size() + 1);
                candidates.add(path);
                files.forEach(file -> candidates.add(file.substring(1)));
                final List<CompletableFuture<Boolean>> checks = candidates.stream()
                    .map(file -> this.storage.exists(new Key.From(file)))
                    .collect(Collectors.toList());
                res = CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new))
                    .thenApply(
                        nothing -> IntStream.range(0, candidates.size())
                            .filter(idx -> checks.get(idx).join())
                            .mapToObj(candidates::get)
                            .findFirst()
                    );
            } else {
                res = CompletableFuture.completedFuture(Optional.empty());
            }
            return res;
        }

        /**
         * Signed upload URLs of the requested files.
         * @param files Requested file name to its path
         * @param signer Host and user the upload URL's signature is bound to.
         * @param urls Client-facing URLs of repository files.
         * @return Response with the URLs
         */
        private Response urls(final Map<String, String> files, final Signer signer,
            final RepoFileUrl urls) {
            final JsonObjectBuilder result = Json.createObjectBuilder();
            files.forEach(
                (name, filepath) -> result.add(
                    name,
                    String.join(
                        "", urls.of(filepath), "?signature=",
                        this.tokenizer.generateToken(
                            filepath, signer.hostname, this.repository, signer.user
                        )
                    )
                )
            );
            return ResponseBuilder.ok()
                .header(ConanUpload.CONTENT_TYPE, ConanUpload.JSON_TYPE)
                .jsonBody(result.build())
                .build();
        }
    }

    /**
     * Repository paths of the files an upload_urls request names.
     * @param path Reference path of the request
     * @param body Request body: a JSON object keyed by file name
     * @return File name to its path, starting with a slash, in request order
     */
    private static Map<String, String> files(final String path, final String body) {
        final String pkgnew = "/_/_/packages/";
        final String fpath;
        final String pkgdir;
        if (path.indexOf(pkgnew) > 0) {
            fpath = path.replace(pkgnew, "/_/_/0/package/");
            pkgdir = ConanUpload.PKG_BIN_DIR;
        } else {
            fpath = path;
            pkgdir = ConanUpload.PKG_SRC_DIR;
        }
        final Map<String, String> res = new LinkedHashMap<>();
        try (JsonParser parser = Json.createParser(new StringReader(body))) {
            parser.next();
            for (final String key : parser.getObject().keySet()) {
                res.put(key, String.join("", "/", fpath, pkgdir, key));
            }
        }
        return res;
    }

    /**
     * Refusal of an upload_urls request naming a stored file on an immutable
     * repository.
     * @param file Stored file
     * @return 404 response
     */
    private static Response refusal(final String file) {
        return ResponseBuilder.notFound()
            .textBody(
                String.format(
                    "%s already exists and the repository is immutable; it cannot be overwritten",
                    file
                )
            ).build();
    }

    /**
     * User the authorization layer verified for this request: only the
     * login header {@code AuthzSlice} sets (it drops any client-sent one).
     * @param headers Request headers
     * @return User name, empty when the request was not authenticated
     */
    private static String verifiedUser(final Headers headers) {
        return headers.find(AuthzSlice.LOGIN_HDR).stream()
            .findFirst()
            .map(Header::getValue)
            .filter(val -> !val.isBlank() && !AuthUser.ANONYMOUS.name().equals(val))
            .orElse("");
    }

    /**
     * Host and authenticated user an upload URL is signed for.
     * @since 2.2.9
     */
    private static final class Signer {

        /**
         * Host name.
         */
        private final String hostname;

        /**
         * Authenticated user.
         */
        private final String user;

        /**
         * Ctor.
         * @param hostname Host name
         * @param user Authenticated user
         */
        Signer(final String hostname, final String user) {
            this.hostname = hostname;
            this.user = user;
        }
    }

    /**
     * Conan HTTP PUT /{path/to/file}?signature={signature} REST API.
     *
     * <p>The signature is only redeemable against the repository that issued
     * it, for the exact path and host it was issued for, and until it
     * expires. It is a second factor, not the authorisation: {@link ConanSlice}
     * also requires an authenticated user with WRITE on this repository.</p>
     */
    public static final class PutFile implements Slice {

        /**
         * Current Pantera storage instance.
         */
        private final Storage storage;

        /**
         * Tokenizer for repository items.
         */
        private final ItemTokenizer tokenizer;

        /**
         * Optional repository events sink. When present, successful uploads
         * emit an {@code ArtifactEvent} so the artifacts DB index stays in
         * sync with storage — otherwise Conan uploads would be invisible
         * to the tree browser and search.
         */
        private final Optional<RepositoryEvents> events;

        /**
         * Name of this repository; a signature issued for another one is refused.
         */
        private final String repository;

        /**
         * Whether a stored file may never be replaced by an upload.
         */
        private final boolean immutable;

        /**
         * Legacy ctor retained for callers that cannot supply an events
         * queue (tests, tools). Uploads are not indexed in this mode.
         * @param storage Current Pantera storage instance.
         * @param tokenizer Tokenize repository items via JWT tokens.
         * @param repository Name of this repository.
         */
        public PutFile(final Storage storage, final ItemTokenizer tokenizer,
            final String repository) {
            this(storage, tokenizer, repository, Optional.empty());
        }

        /**
         * Ctor; a PUT of a stored file overwrites it.
         * @param storage Current Pantera storage instance.
         * @param tokenizer Tokenize repository items via JWT tokens.
         * @param repository Name of this repository.
         * @param events Optional repository events sink for DB indexing.
         */
        public PutFile(final Storage storage, final ItemTokenizer tokenizer,
            final String repository, final Optional<RepositoryEvents> events) {
            this(storage, tokenizer, repository, events, false);
        }

        /**
         * Ctor.
         * @param storage Current Pantera storage instance.
         * @param tokenizer Tokenize repository items via JWT tokens.
         * @param repository Name of this repository.
         * @param events Optional repository events sink for DB indexing.
         * @param immutable When {@code true} a PUT of a stored file answers
         *  409 Conflict and writes nothing; when {@code false} it overwrites
         * @checkstyle ParameterNumberCheck (5 lines)
         */
        public PutFile(final Storage storage, final ItemTokenizer tokenizer,
            final String repository, final Optional<RepositoryEvents> events,
            final boolean immutable) {
            this.storage = storage;
            this.tokenizer = tokenizer;
            this.repository = repository;
            this.events = events;
            this.immutable = immutable;
        }

        @Override
        public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
            final String path = line.uri().getPath();
            final String hostname = new RqHeaders.Single(headers, ConanUpload.HOST).asString();
            final Optional<String> token = new RqParams(line.uri()).value("signature");
            if (token.isPresent()) {
                return this.tokenizer.authenticateToken(token.get())
                    .toCompletableFuture()
                    .thenApply(
                        item -> {
                            if (item.isPresent()
                                && item.get().getRepository().equals(this.repository)
                                && item.get().getHostname().equals(hostname)
                                && item.get().getPath().equals(path)) {
                                return this.store(line, headers, body);
                            }
                            return body.discard().thenApply(
                                nothing -> ResponseBuilder.unauthorized().build()
                            );
                        }
                    ).thenCompose(Function.identity());
            }
            return body.discard().thenApply(
                nothing -> ResponseBuilder.unauthorized().build()
            );
        }

        /**
         * Store the uploaded file. On an immutable repository the existence
         * check and the save run under a lock on the file key, so two
         * concurrent uploads of the same file cannot both land; a stored
         * file is refused with 409 before anything is written.
         * @param line Request line
         * @param headers Request headers
         * @param body Request body
         * @return Response
         */
        private CompletableFuture<Response> store(final RequestLine line, final Headers headers,
            final Content body) {
            final Slice upload = new SliceUpload(
                this.storage, KeyFromPath::new, this.events
            );
            final CompletableFuture<Response> res;
            if (this.immutable) {
                final Key key = new KeyFromPath(line.uri().getPath());
                res = new IndexUpdateLock(this.storage, key).run(
                    locked -> locked.exists(key).thenCompose(
                        present -> {
                            final CompletableFuture<Response> rsp;
                            if (present) {
                                rsp = body.discard().thenApply(
                                    nothing -> ResponseBuilder.from(RsStatus.CONFLICT)
                                        .textBody(
                                            String.format(
                                                "%s already exists and the repository is immutable",
                                                key.string()
                                            )
                                        ).build()
                                );
                            } else {
                                rsp = upload.response(line, headers, body);
                            }
                            return rsp;
                        }
                    )
                );
            } else {
                res = upload.response(line, headers, body);
            }
            return res;
        }
    }
}
