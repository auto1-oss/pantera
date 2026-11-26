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
import com.auto1.pantera.http.log.AnswerReason;
import com.auto1.pantera.http.log.RequestTrace;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * "Why can't I get this?" — explains a failing or stale repository request.
 *
 * <p>Resolves the URL to its repository and replays the request once,
 * in-process, with the caller's credentials. The replayed request is traced
 * (see {@link RequestTrace}): every repository on its way records what it
 * answered and why, so the report shows what happened to the request, member
 * by member, and a verdict in one sentence. It then checks the layers that
 * can make the answer wrong: the negative-cache keys the request could be
 * shadowed by, the package's cooldown state and metadata visibility, and the
 * breakers in front of the proxies. Every problem carries a concrete fix the
 * UI can run as one API call.</p>
 *
 * <p>The negative cache is read before the replay. Replaying a request for
 * a file the upstream lacks caches that "not found", as any client request
 * would; such an entry was not there when the client's request failed, so
 * it is neither reported nor left behind.</p>
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
        final List<DerivedNegativeKeys.Derived> keys =
            new DerivedNegativeKeys(this.diag.topology()).keys(repo, target.path());
        return this.cached(keys)
            .thenCompose(
                before -> this.diag.fetch()
                    .get(repo.name(), target.rawPath(), authorization, null, KEEP_BODY)
                    .handle((resp, err) -> {
                        this.forget(keys, before);
                        final List<RequestTrace.Step> steps = Troubleshooter.steps(repo, resp);
                        report.put("request", Troubleshooter.requestJson(resp, err, steps));
                        report.put("trace", this.traceJson(steps));
                        report.put("verdict", Troubleshooter.verdict(repo, resp, err, steps));
                        checks.add(
                            Troubleshooter.requestCheck(
                                resp, err,
                                steps.isEmpty() ? "" : ": " + repo.name() + ": "
                                    + this.explanation(steps.size() - 1, steps)
                            )
                        );
                        Troubleshooter.groupCheck(repo, steps, checks);
                        Troubleshooter.negativeCheck(keys, before, checks);
                        return resp;
                    })
            )
            .thenCompose(ignored -> this.cooldownChecks(repo, req, authorization, checks))
            .thenApply(ignored -> {
                this.upstreamChecks(repo, checks);
                return report.put("checks", checks);
            });
    }

    /**
     * The steps of the replayed request. A fetch that recorded none (a
     * fetch that is not the serving path) yields one step for the repository
     * itself, from the status and the reason its answer carries.
     *
     * @param repo Repository
     * @param resp Response, null on failure
     * @return Steps, the addressed repository last
     */
    private static List<RequestTrace.Step> steps(
        final RepoTopology.RepoInfo repo, final RepoFetch.Fetched resp
    ) {
        final List<RequestTrace.Step> steps;
        if (resp == null) {
            steps = List.of();
        } else if (resp.trace().isEmpty()) {
            steps = List.of(
                new RequestTrace.Step(
                    repo.name(), repo.type(), resp.status(),
                    resp.status() < 400 ? null : resp.header(AnswerReason.HEADER), 0L
                )
            );
        } else {
            steps = resp.trace();
        }
        return steps;
    }

    /**
     * The trace as JSON: one entry per repository, in the order they
     * answered, each with what it answered and why.
     *
     * @param steps Steps
     * @return JSON array
     */
    private JsonArray traceJson(final List<RequestTrace.Step> steps) {
        final JsonArray trace = new JsonArray();
        for (final RequestTrace.Step step : steps) {
            final Optional<RepoTopology.RepoInfo> info = this.diag.topology().repo(step.repository());
            final JsonObject entry = new JsonObject()
                .put("repository", step.repository())
                .put(
                    "type",
                    Optional.ofNullable(step.type()).filter(type -> !type.isEmpty())
                        .or(() -> info.map(RepoTopology.RepoInfo::type)).orElse("")
                )
                .put("mode", info.map(RepoTopology.RepoInfo::mode).orElse(null))
                .put("status", step.status())
                .put("reason", step.reason())
                .put("explanation", this.explanation(trace.size(), steps))
                .put("durationMs", step.millis());
            info.map(RepoTopology.RepoInfo::remotes).filter(remotes -> !remotes.isEmpty())
                .ifPresent(remotes -> entry.put("upstreams", new JsonArray(remotes)));
            trace.add(entry);
        }
        return trace;
    }

    /**
     * What happened in the repository of one step, in words. A group has no
     * upstream and no storage: its step is explained by the members that
     * answered before it, unless the group decided by itself (its own
     * negative cache).
     *
     * @param idx Index of the step
     * @param steps Steps of the replayed request
     * @return Explanation
     */
    private String explanation(final int idx, final List<RequestTrace.Step> steps) {
        final RequestTrace.Step step = steps.get(idx);
        final ReasonText own = new ReasonText(step.reason(), step.status());
        final List<RequestTrace.Step> members = steps.subList(0, idx);
        final boolean group = this.diag.topology().repo(step.repository())
            .map(RepoTopology.RepoInfo::group).orElse(false);
        final String text;
        if (!group || members.isEmpty()) {
            text = own.explanation();
        } else if (step.status() >= 200 && step.status() < 400) {
            text = members.stream()
                .filter(member -> member.status() >= 200 && member.status() < 300)
                .findFirst().map(member -> "served by member " + member.repository())
                .orElseGet(own::explanation);
        } else if (own.plainNotFound()) {
            text = "no member has this file";
        } else {
            text = members.stream()
                .filter(member -> java.util.Objects.equals(member.reason(), step.reason()))
                .findFirst()
                .map(member -> "member " + member.repository() + ": "
                    + new ReasonText(member.reason(), member.status()).explanation())
                .orElseGet(own::explanation);
        }
        return text;
    }

    /**
     * What the members of a group answered, from the trace of the replayed
     * request: the members the group asked, in the order it asked them.
     *
     * @param repo Repository
     * @param steps Steps of the replayed request
     * @param checks Checks to append to
     */
    private static void groupCheck(
        final RepoTopology.RepoInfo repo, final List<RequestTrace.Step> steps,
        final JsonArray checks
    ) {
        if (!repo.group() || steps.isEmpty()) {
            return;
        }
        final RequestTrace.Step top = steps.get(steps.size() - 1);
        final JsonArray members = new JsonArray();
        final StringBuilder walk = new StringBuilder();
        String served = null;
        for (final RequestTrace.Step step : steps.subList(0, steps.size() - 1)) {
            final String why = new ReasonText(step.reason(), step.status()).explanation();
            members.add(new JsonObject()
                .put("name", step.repository())
                .put("status", step.status())
                .put("reason", step.reason())
                .put("explanation", why));
            if (walk.length() > 0) {
                walk.append(" → ");
            }
            walk.append(step.repository()).append(": ").append(step.status())
                .append(" (").append(why).append(')');
            if (served == null && step.status() >= 200 && step.status() < 300) {
                served = step.repository();
            }
        }
        final boolean fine = top.status() >= 200 && top.status() < 400;
        final String message;
        if (members.isEmpty()) {
            message = "No member was asked: " + repo.name() + " "
                + new ReasonText(top.reason(), top.status()).explanation();
        } else if (served != null) {
            message = "Members asked: " + walk + " — served by " + served;
        } else {
            message = "Members asked: " + walk + " — no member served this path";
        }
        checks.add(Troubleshooter.check(
            "group-walk", "group", fine ? OK : PROBLEM, message, null
        ).put("members", members));
    }

    /**
     * Which of the negative-cache keys the request maps to hold a cached 404
     * right now.
     *
     * @param keys Keys the request maps to
     * @return Future of flat key to tiers ({@code L1}, {@code L2}, {@code L1+L2})
     */
    private CompletableFuture<Map<String, String>> cached(
        final List<DerivedNegativeKeys.Derived> keys
    ) {
        return this.negative.l2TtlMillis(
            keys.stream().map(der -> der.key().flat()).toList()
        ).thenApply(ttls -> {
            final Map<String, String> present = new HashMap<>();
            for (final DerivedNegativeKeys.Derived der : keys) {
                final boolean l1 = this.negative.inL1(der.key());
                final boolean l2 = ttls.getOrDefault(der.key().flat(), -2L) != -2L;
                if (l1 || l2) {
                    present.put(
                        der.key().flat(),
                        (l1 ? "L1" : "") + (l1 && l2 ? "+" : "") + (l2 ? "L2" : "")
                    );
                }
            }
            return present;
        });
    }

    /**
     * Drop the cached 404s the replayed request created: every key the
     * request maps to that held none before. The check must not change what
     * the next client request gets.
     *
     * @param keys Keys the request maps to
     * @param before Keys that held a cached 404 before the replay
     */
    private void forget(
        final List<DerivedNegativeKeys.Derived> keys, final Map<String, String> before
    ) {
        for (final DerivedNegativeKeys.Derived der : keys) {
            if (!before.containsKey(der.key().flat())) {
                this.negative.invalidate(der.key());
            }
        }
    }

    /**
     * Negative-cache keys that shadowed the request before the check.
     *
     * @param keys Keys the request maps to
     * @param before Keys that held a cached 404 before the replay
     * @param checks Checks to append to
     */
    private static void negativeCheck(
        final List<DerivedNegativeKeys.Derived> keys, final Map<String, String> before,
        final JsonArray checks
    ) {
        boolean any = false;
        for (final DerivedNegativeKeys.Derived der : keys) {
            final String tiers = before.get(der.key().flat());
            if (tiers != null) {
                any = true;
                checks.add(Troubleshooter.check(
                    "negative-cache:" + der.key().scope(), "negative-cache", PROBLEM,
                    "Cached 404 in " + der.key().scope() + " for "
                        + der.key().artifactName()
                        + (der.key().artifactVersion().isEmpty() ? ""
                            : " " + der.key().artifactVersion())
                        + " (" + tiers
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
                "No cached 404 shadowed this request (" + keys.size()
                    + " key(s) checked before the replay; what the replay itself cached "
                    + "was removed again)",
                null
            ));
        }
    }

    /**
     * The conclusion: what the client gets, why, and what to do.
     *
     * @param repo Repository
     * @param resp Response, null on failure
     * @param err Failure
     * @param steps Steps of the replayed request
     * @return Verdict JSON: status, summary, advice
     */
    private static JsonObject verdict(
        final RepoTopology.RepoInfo repo, final RepoFetch.Fetched resp, final Throwable err,
        final List<RequestTrace.Step> steps
    ) {
        final JsonObject verdict = new JsonObject();
        if (resp == null || steps.isEmpty()) {
            verdict.put("status", PROBLEM)
                .put("summary", "The request could not be replayed: "
                    + PackageInspector.cause(err).getMessage())
                .put("advice", "");
        } else if (resp.status() >= 200 && resp.status() < 400) {
            final Optional<String> served = steps.subList(0, steps.size() - 1).stream()
                .filter(step -> step.status() >= 200 && step.status() < 300)
                .map(RequestTrace.Step::repository).findFirst();
            verdict.put("status", OK)
                .put("summary", "The request is served (HTTP " + resp.status() + ")"
                    + served.map(name -> ", served by " + name).orElse(""))
                .put("advice", "");
        } else {
            final RequestTrace.Step top = steps.get(steps.size() - 1);
            final RequestTrace.Step culprit = steps.stream()
                .filter(step -> java.util.Objects.equals(step.reason(), top.reason()))
                .findFirst().orElse(top);
            final ReasonText text = new ReasonText(top.reason(), top.status());
            verdict.put("status", Troubleshooter.expected(top.reason()) ? INFO : PROBLEM)
                .put("summary", Troubleshooter.summary(repo, top, culprit, text))
                .put("advice", text.advice());
        }
        return verdict;
    }

    /**
     * Whether a reason is Pantera doing what it was told: cooldown and
     * access control.
     *
     * @param reason Reason token
     * @return True for an answer that is not a fault
     */
    private static boolean expected(final String reason) {
        return "cooldown_active".equals(reason) || "authentication_required".equals(reason)
            || "forbidden".equals(reason);
    }

    /**
     * One sentence on a request that is not served.
     *
     * @param repo Repository addressed
     * @param top Its step
     * @param culprit The step that explains the answer
     * @param text The reason in words
     * @return Summary
     */
    private static String summary(
        final RepoTopology.RepoInfo repo, final RequestTrace.Step top,
        final RequestTrace.Step culprit, final ReasonText text
    ) {
        final String summary;
        if (repo.group() && text.plainNotFound()) {
            summary = "HTTP " + top.status() + ": none of the repositories asked has this "
                + "file. Nothing is cached wrongly and nothing failed.";
        } else if ("negative_cache".equals(top.reason())) {
            summary = "HTTP " + top.status() + " from the negative cache of "
                + culprit.repository() + ": an earlier \"not found\" is still remembered "
                + "there, and the upstream was not asked.";
        } else {
            summary = "HTTP " + top.status() + ". " + culprit.repository() + ": "
                + new ReasonText(culprit.reason(), culprit.status()).explanation() + ".";
        }
        return summary;
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
     * @param steps Steps of the replayed request
     * @return JSON
     */
    private static JsonObject requestJson(
        final RepoFetch.Fetched resp, final Throwable err, final List<RequestTrace.Step> steps
    ) {
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
            .put("reason", steps.isEmpty() ? null : steps.get(steps.size() - 1).reason())
            .put("headers", headers)
            .put("bodySnippet", Troubleshooter.snippet(resp))
            .put("bodyBytes", resp.bytes());
    }

    /**
     * Check derived from the replayed request.
     *
     * @param resp Response, null on failure
     * @param err Failure
     * @param why What happened in the addressed repository, as a suffix
     *  starting with a colon; empty when unknown
     * @return Check
     */
    private static JsonObject requestCheck(
        final RepoFetch.Fetched resp, final Throwable err, final String why
    ) {
        final String status;
        final String message;
        if (resp == null) {
            status = PROBLEM;
            message = "In-process request failed: " + PackageInspector.cause(err).getMessage();
        } else if (resp.status() >= 200 && resp.status() < 400) {
            status = OK;
            message = "Request answers " + resp.status();
        } else if (resp.status() == 403 && Troubleshooter.cooldownForbidden(resp)) {
            status = INFO;
            message = "Request answers 403 because the version is in cooldown — see the cooldown check";
        } else if (resp.status() == 401 || resp.status() == 403) {
            status = INFO;
            message = "Request answers " + resp.status()
                + " for your account — clients without read permission see the same";
        } else if (resp.status() == 503 && resp.header("Retry-After") != null) {
            status = PROBLEM;
            message = "Request answers 503" + why + "; retry in "
                + resp.header("Retry-After") + "s";
        } else {
            status = PROBLEM;
            message = "Request answers " + resp.status() + why;
        }
        return Troubleshooter.check("request", "repository", status, message, null);
    }

    /**
     * Whether a 403 comes from cooldown rather than authorization: the
     * cooldown header, or the cooldown JSON body some adapters answer with.
     *
     * @param resp Response
     * @return True for a cooldown block
     */
    private static boolean cooldownForbidden(final RepoFetch.Fetched resp) {
        return resp.header("X-Pantera-Cooldown") != null
            || Troubleshooter.snippet(resp).contains("\"blocked_until\"");
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
