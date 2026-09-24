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
package com.auto1.pantera.api.v1.admin;

import com.auto1.pantera.http.cache.NegativeCache;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * "Why can't I get this?" — explains a failing or stale repository request.
 *
 * <p>Resolves the URL to its repository, replays the request in-process with
 * the caller's credentials, then checks each layer that can make the answer
 * wrong: the group member walk, the negative-cache keys the request could be
 * shadowed by, the package's cooldown state and metadata visibility, and the
 * breakers in front of the proxies. Every problem carries a concrete fix the
 * UI can run as one API call.</p>
 *
 * @since 2.2.9
 */
public final class Troubleshooter {

    /**
     * Body bytes kept from the replayed request.
     */
    private static final int KEEP_BODY = 8192;

    /**
     * Snippet length.
     */
    private static final int SNIPPET = 500;

    /**
     * Response headers reported.
     */
    private static final Set<String> HEADERS = Set.of(
        "content-type", "content-length", "etag", "last-modified", "cache-control",
        "location", "retry-after", "www-authenticate", "x-pantera-circuit-open",
        "x-pantera-negative-cache-skip", "x-pantera-cache", "vary", "age"
    );

    /**
     * Check status: ok.
     */
    private static final String OK = "ok";

    /**
     * Check status: problem.
     */
    private static final String PROBLEM = "problem";

    /**
     * Check status: info.
     */
    private static final String INFO = "info";

    /**
     * Serving-side access.
     */
    private final AdminDiagnostics diag;

    /**
     * Negative cache.
     */
    private final NegativeCache negative;

    /**
     * Package inspector.
     */
    private final PackageInspector inspector;

    /**
     * Ctor.
     *
     * @param diag Serving-side access
     * @param negative Negative cache
     * @param inspector Package inspector
     */
    public Troubleshooter(
        final AdminDiagnostics diag, final NegativeCache negative,
        final PackageInspector inspector
    ) {
        this.diag = diag;
        this.negative = negative;
        this.inspector = inspector;
    }

    /**
     * Explain a URL.
     *
     * @param url Full client URL or {@code /<repo>/<path>}
     * @param authorization Caller's Authorization header
     * @return Future of the report
     * @throws IllegalArgumentException When the URL is malformed
     */
    public CompletableFuture<JsonObject> explain(final String url, final String authorization) {
        final Optional<RequestTarget> parsed = new RequestTarget.Parser(this.diag.topology())
            .parse(url);
        final JsonObject report = new JsonObject()
            .put("url", url)
            .put("node", this.diag.node());
        if (parsed.isEmpty()) {
            return CompletableFuture.completedFuture(report
                .put("repo", (Object) null)
                .put("parsed", (Object) null)
                .put("request", (Object) null)
                .put("checks", new JsonArray().add(Troubleshooter.check(
                    "repository", "repository", PROBLEM,
                    "No configured repository is addressed by this URL. Expected "
                        + "/<repo>/<path>, optionally behind a host, a global prefix and api/.",
                    null
                ))));
        }
        final RequestTarget target = parsed.get();
        final RepoTopology.RepoInfo repo = target.repo();
        final ParsedRequest req = ParsedRequest.of(repo.type(), target.path());
        final JsonObject repoJson = new JsonObject()
            .put("name", repo.name()).put("type", repo.type()).put("mode", repo.mode());
        if (repo.group()) {
            repoJson.put("members", new JsonArray(repo.members()));
        }
        report.put("repo", repoJson)
            .put("parsed", new JsonObject()
                .put("package", req.pkg())
                .put("version", req.version())
                .put("kind", req.kind())
                .put("path", target.path()));
        final JsonArray checks = new JsonArray().add(Troubleshooter.check(
            "repository", "repository", OK,
            "Repository " + repo.name() + " exists (" + repo.type() + ", " + repo.mode() + ")",
            null
        ));
        return this.diag.fetch().get(repo.name(), target.rawPath(), authorization, null, KEEP_BODY)
            .handle((resp, err) -> {
                report.put("request", Troubleshooter.requestJson(resp, err));
                checks.add(Troubleshooter.requestCheck(resp, err));
                return resp;
            })
            .thenCompose(resp -> this.groupCheck(repo, target, authorization, checks))
            .thenCompose(ignored -> this.negativeCheck(repo, target, checks))
            .thenCompose(ignored -> this.cooldownChecks(repo, req, authorization, checks))
            .thenApply(ignored -> {
                this.upstreamChecks(repo, checks);
                return report.put("checks", checks);
            });
    }

    /**
     * Group member walk: which member answers the path, in walk order.
     *
     * @param repo Repository
     * @param target Target
     * @param authorization Credentials
     * @param checks Checks to append to
     * @return Future completing when done
     */
    private CompletableFuture<Void> groupCheck(
        final RepoTopology.RepoInfo repo, final RequestTarget target,
        final String authorization, final JsonArray checks
    ) {
        if (!repo.group()) {
            return CompletableFuture.completedFuture(null);
        }
        final JsonArray walk = new JsonArray();
        return this.walk(repo.members(), 0, target, authorization, walk).thenAccept(served -> {
            final String message;
            if (served.isPresent()) {
                message = "Member walk " + Troubleshooter.walkText(walk) + " — served by "
                    + served.get();
            } else {
                message = "Member walk " + Troubleshooter.walkText(walk)
                    + " — no member serves this path";
            }
            checks.add(Troubleshooter.check(
                "group-walk", "group", served.isPresent() ? OK : PROBLEM, message, null
            ).put("members", walk));
        });
    }

    /**
     * Sequential member walk, stopping at the first 2xx like the resolver.
     *
     * @param members Members in walk order
     * @param idx Current index
     * @param target Target
     * @param authorization Credentials
     * @param walk Walk record to append to
     * @return Future of the serving member
     */
    private CompletableFuture<Optional<String>> walk(
        final List<String> members, final int idx, final RequestTarget target,
        final String authorization, final JsonArray walk
    ) {
        if (idx >= members.size()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        final String member = members.get(idx);
        return this.diag.fetch().get(member, target.rawPath(), authorization, null, 0)
            .handle((resp, err) -> {
                walk.add(new JsonObject()
                    .put("name", member)
                    .put("status", resp == null ? 0 : resp.status())
                    .put("error", err == null ? null
                        : String.valueOf(PackageInspector.cause(err).getMessage())));
                return resp != null && resp.status() >= 200 && resp.status() < 300;
            })
            .thenCompose(ok -> ok
                ? CompletableFuture.completedFuture(Optional.of(member))
                : this.walk(members, idx + 1, target, authorization, walk));
    }

    /**
     * Negative-cache keys the request could be shadowed by.
     *
     * @param repo Repository
     * @param target Target
     * @param checks Checks to append to
     * @return Future completing when done
     */
    private CompletableFuture<Void> negativeCheck(
        final RepoTopology.RepoInfo repo, final RequestTarget target, final JsonArray checks
    ) {
        final List<DerivedNegativeKeys.Derived> keys =
            new DerivedNegativeKeys(this.diag.topology()).keys(repo, target.path());
        return this.negative.l2TtlMillis(
            keys.stream().map(der -> der.key().flat()).toList()
        ).thenAccept(ttls -> {
            boolean any = false;
            for (final DerivedNegativeKeys.Derived der : keys) {
                final boolean l1 = this.negative.inL1(der.key());
                final boolean l2 = ttls.getOrDefault(der.key().flat(), -2L) != -2L;
                if (l1 || l2) {
                    any = true;
                    checks.add(Troubleshooter.check(
                        "negative-cache:" + der.key().scope(), "negative-cache", PROBLEM,
                        "Cached 404 in " + der.key().scope() + " for "
                            + der.key().artifactName()
                            + (der.key().artifactVersion().isEmpty() ? ""
                                : " " + der.key().artifactVersion())
                            + " (" + (l1 ? "L1" : "") + (l1 && l2 ? "+" : "") + (l2 ? "L2" : "")
                            + ") — requests are answered 404 without asking the upstream",
                        new JsonObject()
                            .put("action", "invalidate")
                            .put("endpoint", "/api/v1/admin/neg-cache/invalidate")
                            .put("body", new JsonObject()
                                .put("scope", der.key().scope())
                                .put("repoType", der.key().repoType())
                                .put("artifactName", der.key().artifactName())
                                .put("version", der.key().artifactVersion()))
                    ));
                }
            }
            if (!any) {
                checks.add(Troubleshooter.check(
                    "negative-cache", "negative-cache", OK,
                    "No cached 404 for any of the " + keys.size()
                        + " key(s) this request maps to", null
                ));
            }
        });
    }

    /**
     * Cooldown state and metadata visibility of the requested package.
     *
     * @param repo Repository
     * @param req Parsed request
     * @param authorization Credentials
     * @param checks Checks to append to
     * @return Future completing when done
     */
    private CompletableFuture<Void> cooldownChecks(
        final RepoTopology.RepoInfo repo, final ParsedRequest req,
        final String authorization, final JsonArray checks
    ) {
        if (req.pkg() == null) {
            checks.add(Troubleshooter.check(
                "cooldown", "cooldown", INFO,
                "The path names no package; cooldown and metadata checks skipped", null
            ));
            return CompletableFuture.completedFuture(null);
        }
        return this.inspector.inspect(repo.family(), req.pkg(), repo.name(), authorization)
            .thenAccept(doc -> new CooldownFindings(repo, req, doc).addTo(checks))
            .exceptionally(err -> {
                checks.add(Troubleshooter.check(
                    "cooldown", "cooldown", INFO,
                    "Cooldown inspection failed: " + PackageInspector.cause(err).getMessage(), null
                ));
                return null;
            });
    }

    /**
     * Breakers in front of the proxies this request reaches.
     *
     * @param repo Repository
     * @param checks Checks to append to
     */
    private void upstreamChecks(final RepoTopology.RepoInfo repo, final JsonArray checks) {
        boolean any = false;
        for (final RepoTopology.RepoInfo leaf : this.diag.topology().leaves(repo.name())) {
            if (!"proxy".equals(leaf.mode())) {
                continue;
            }
            any = true;
            final String member = this.diag.breakers().memberStatus(leaf.name());
            if ("blocked".equals(member) || "probing".equals(member)) {
                checks.add(Troubleshooter.check(
                    "member-breaker:" + leaf.name(), "upstream", PROBLEM,
                    "Group-member circuit breaker for " + leaf.name() + " is " + member
                        + " — groups skip it and serve only what it has cached",
                    null
                ));
            }
            for (final BreakerProbe.Upstream up : this.diag.breakers().upstreams(leaf.name())) {
                checks.add(Troubleshooter.check(
                    "upstream-breaker:" + leaf.name() + ":" + up.key(), "upstream",
                    up.open() ? PROBLEM : OK,
                    up.open()
                        ? "Upstream breaker " + up.key() + " is OPEN (retry in "
                            + up.retryAfterSeconds() + "s) — requests fast-fail with 502"
                        : "Upstream breaker " + up.key() + " is closed",
                    null
                ));
            }
        }
        if (!any) {
            checks.add(Troubleshooter.check(
                "upstream", "upstream", INFO, "No proxy repository on this path", null
            ));
        }
    }

    /**
     * The replayed request as JSON.
     *
     * @param resp Response, null on failure
     * @param err Failure
     * @return JSON
     */
    private static JsonObject requestJson(final RepoFetch.Fetched resp, final Throwable err) {
        if (resp == null) {
            return new JsonObject()
                .put("status", 0)
                .put("error", String.valueOf(PackageInspector.cause(err).getMessage()));
        }
        final JsonObject headers = new JsonObject();
        resp.headers().forEach(hdr -> {
            final String name = hdr.getKey().toLowerCase(Locale.ROOT);
            if (HEADERS.contains(name) && !headers.containsKey(name)) {
                headers.put(name, hdr.getValue());
            }
        });
        return new JsonObject()
            .put("status", resp.status())
            .put("headers", headers)
            .put("bodySnippet", Troubleshooter.snippet(resp))
            .put("bodyBytes", resp.bytes());
    }

    /**
     * Check derived from the replayed request.
     *
     * @param resp Response, null on failure
     * @param err Failure
     * @return Check
     */
    private static JsonObject requestCheck(final RepoFetch.Fetched resp, final Throwable err) {
        final String status;
        final String message;
        if (resp == null) {
            status = PROBLEM;
            message = "In-process request failed: " + PackageInspector.cause(err).getMessage();
        } else if (resp.status() >= 200 && resp.status() < 400) {
            status = OK;
            message = "Request answers " + resp.status();
        } else if (resp.status() == 401 || resp.status() == 403) {
            status = INFO;
            message = "Request answers " + resp.status()
                + " for your account — clients without read permission see the same";
        } else if (resp.status() == 503 && resp.header("Retry-After") != null) {
            status = PROBLEM;
            message = "Request answers 503 — every member is unavailable (breakers open); retry in "
                + resp.header("Retry-After") + "s";
        } else {
            status = PROBLEM;
            message = "Request answers " + resp.status();
        }
        return Troubleshooter.check("request", "repository", status, message, null);
    }

    /**
     * Printable body snippet.
     *
     * @param resp Response
     * @return Snippet (≤500 chars) or a size note for binary bodies
     */
    private static String snippet(final RepoFetch.Fetched resp) {
        final String type = String.valueOf(resp.header("Content-Type")).toLowerCase(Locale.ROOT);
        final boolean text = type.contains("json") || type.contains("text")
            || type.contains("xml") || type.contains("html") || "null".equals(type);
        final byte[] body = resp.body();
        final String res;
        if (body.length == 0) {
            res = "";
        } else if (!text) {
            res = "<" + resp.bytes() + " bytes, " + type + ">";
        } else {
            final String str = new String(body, StandardCharsets.UTF_8);
            res = str.length() > SNIPPET ? str.substring(0, SNIPPET) : str;
        }
        return res;
    }

    /**
     * Walk as text.
     *
     * @param walk Walk record
     * @return e.g. {@code a:404 → b:200}
     */
    private static String walkText(final JsonArray walk) {
        final StringBuilder out = new StringBuilder();
        for (int idx = 0; idx < walk.size(); idx = idx + 1) {
            if (idx > 0) {
                out.append(" → ");
            }
            final JsonObject step = walk.getJsonObject(idx);
            out.append(step.getString("name")).append(':').append(step.getInteger("status"));
        }
        return out.toString();
    }

    /**
     * Build a check.
     *
     * @param id Check id
     * @param layer Layer
     * @param status Status
     * @param message Message
     * @param fix Fix, may be null
     * @return Check JSON
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    static JsonObject check(
        final String id, final String layer, final String status,
        final String message, final JsonObject fix
    ) {
        final JsonObject check = new JsonObject()
            .put("id", id).put("layer", layer).put("status", status).put("message", message);
        if (fix != null) {
            check.put("fix", fix);
        }
        return check;
    }
}
