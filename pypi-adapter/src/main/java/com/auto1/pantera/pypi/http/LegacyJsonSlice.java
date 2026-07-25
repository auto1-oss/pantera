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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.ext.Digests;
import com.auto1.pantera.asto.ext.KeyLastPart;
import com.auto1.pantera.asto.rx.RxFuture;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.pypi.NormalizedProjectName;
import com.auto1.pantera.pypi.cooldown.Pep440VersionComparator;
import com.auto1.pantera.pypi.meta.PypiSidecar;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;
import org.slf4j.MDC;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves the legacy PyPI JSON API ({@code GET /pypi/<pkg>/json}) for hosted
 * packages, synthesized from the persisted per-version files and their
 * {@link PypiSidecar} metadata — the same data {@link SliceIndex} projects
 * into the PEP 503/691 Simple index. poetry and pip-tools resolve
 * {@code <package>} (no version pin) through this legacy endpoint; without
 * it a local repository 404s tools that still rely on it.
 *
 * <p>Scoped to the package level only. The version-specific legacy form
 * ({@code /pypi/<pkg>/<version>/json}) is not served locally — there is no
 * cooldown-leak risk for a hosted repository (no upstream to leak from),
 * so the correctness gap this spec closes (WS4-pypi.9) is proxy-only; see
 * {@link com.auto1.pantera.pypi.cooldown.PypiJsonHandler#handleVersion}.</p>
 *
 * <p>URLs in the rendered document are repository-relative
 * ({@code <version>/<filename>}), matching the convention the PEP 691 JSON
 * renderer already uses (relative URLs are RECOMMENDED by PEP 691 and avoid
 * needing to know this request's externally-visible base URL, which
 * Pantera's routing layer strips before slices ever see it).</p>
 *
 * @since 2.3.0
 */
final class LegacyJsonSlice implements Slice {

    /**
     * Matches {@code .../pypi/<pkg>/json} with an optional trailing slash.
     * Group 1 = raw package name.
     */
    private static final Pattern PATH_PATTERN = Pattern.compile(
        "(?:^|/)pypi/([^/]+)/json/?$", Pattern.CASE_INSENSITIVE
    );

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Repository name (audit correlation).
     */
    private final String rname;

    /**
     * @param storage Storage
     * @param rname Repository name
     */
    LegacyJsonSlice(final Storage storage, final String rname) {
        this.storage = storage;
        this.rname = rname;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        RequestContextHeaders.bindToMdc(headers);
        final AuditContext ctx = new AuditContext(
            MDC.get(EcsMdc.TRACE_ID), MDC.get(EcsMdc.CLIENT_IP)
        );
        final String user = new Login(headers).getValue();
        final Matcher matcher = PATH_PATTERN.matcher(line.uri().getPath());
        return body.asBytesFuture().thenCompose(ignored -> {
            if (!matcher.find()) {
                return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
            }
            final String rawName = matcher.group(1);
            final String packageName = new NormalizedProjectName.Simple(rawName).value();
            return this.buildResponse(packageName, ctx, user);
        });
    }

    /**
     * List the package directory, collect every version's files, and
     * render the legacy JSON blob — or 404 when the package has no files.
     */
    private CompletableFuture<Response> buildResponse(
        final String packageName, final AuditContext ctx, final String user
    ) {
        final Key listKey = new Key.From(packageName);
        return RxFuture.single(this.storage.list(listKey))
            .flatMapPublisher(Flowable::fromIterable)
            // Exclude PEP 658 .metadata sidecars (WS4-pypi.6) — they live
            // alongside the distribution file in storage but are not
            // themselves a release file; storage.list() is flat/recursive
            // so they would otherwise be enumerated as bogus extra files.
            .filter(key -> !IndexGenerator.isPep658MetadataFile(key))
            .concatMapSingle(key -> RxFuture.single(this.filesForKey(key)))
            .flatMapIterable(chunk -> chunk)
            .toList()
            .to(SingleInterop.get())
            .toCompletableFuture()
            .thenApply(files -> {
                if (files.isEmpty()) {
                    AuditLogger.resolution(
                        ctx, "pypi", this.rname, packageName, user, List.of()
                    );
                    return ResponseBuilder.notFound().build();
                }
                AuditLogger.resolution(ctx, "pypi", this.rname, packageName, user, List.of());
                final String json = render(packageName, files);
                return ResponseBuilder.ok()
                    .header("Content-Type", "application/json")
                    .body(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            });
    }

    /**
     * Resolve one entry from {@code storage.list(packageKey)} into zero or
     * more {@link VersionFile}s. Mirrors the exact two-level list-then-list
     * pattern {@link IndexGenerator#generate()} and {@link SliceIndex} use:
     * {@code storage.list(key)} is self-inclusive (a leaf file's own key
     * "lists" itself), so re-listing {@code key} yields the file(s) to
     * build entries from, with the version derived from each file's
     * PARENT segment — never from {@code key} itself, which (for a
     * flat/recursive {@code list()} implementation like
     * {@code InMemoryStorage}) is already the full file path, not a
     * version-folder key.
     *
     * <p>An empty re-list (no storage backend actually returns this for a
     * real key, but kept defensively) means there is no version folder to
     * group under — skipped, matching the legacy no-version-folder
     * edge case the Simple-index renderers also skip.</p>
     */
    private CompletableFuture<List<VersionFile>> filesForKey(final Key key) {
        return this.storage.list(key).thenCompose(rawSubKeys -> {
            // storage.list() is a raw string-prefix match: re-listing a real
            // file's own key also returns its ".metadata" sibling (WS4-pypi.6),
            // since that sibling's key literally has the file's key as a
            // string prefix. Filter it out here too, not just in response().
            final List<Key> subKeys = rawSubKeys.stream()
                .filter(k -> !IndexGenerator.isPep658MetadataFile(k))
                .toList();
            if (subKeys.isEmpty()) {
                return CompletableFuture.completedFuture(List.<VersionFile>of());
            }
            final List<CompletableFuture<VersionFile>> futures = new ArrayList<>(subKeys.size());
            for (final Key subKey : subKeys) {
                final String version = new KeyLastPart(
                    new Key.From(subKey.parent().get())
                ).get();
                futures.add(this.buildVersionFile(subKey, version));
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(nothing -> futures.stream().map(CompletableFuture::join).toList());
        });
    }

    private CompletableFuture<VersionFile> buildVersionFile(final Key file, final String version) {
        return this.storage.value(file).thenCompose(
            value -> new ContentDigest(value, Digests.SHA256).hex()
        ).thenCompose(
            hex -> this.storage.metadata(file).thenCompose(
                meta -> {
                    final long size = meta.read(com.auto1.pantera.asto.Meta.OP_SIZE)
                        .map(Long.class::cast).orElse(0L);
                    return PypiSidecar.read(this.storage, file).thenApply(
                        optMeta -> new VersionFile(
                            new KeyLastPart(file).get(),
                            version,
                            hex,
                            size,
                            optMeta.map(PypiSidecar.Meta::requiresPython).orElse(null),
                            optMeta.map(PypiSidecar.Meta::uploadTime).map(Object::toString).orElse(null),
                            optMeta.map(PypiSidecar.Meta::yanked).orElse(false),
                            optMeta.flatMap(PypiSidecar.Meta::yankedReason)
                        )
                    );
                }
            )
        ).toCompletableFuture();
    }

    /**
     * Render the legacy PyPI JSON API document: {@code info} (about the
     * latest version, PEP 440 ordered), {@code releases} (every version),
     * and {@code urls} (the latest version's files — a duplicate view of
     * {@code releases[info.version]}, matching upstream PyPI's shape).
     */
    private static String render(final String packageName, final List<VersionFile> files) {
        final List<String> versions = files.stream().map(VersionFile::version).distinct().toList();
        final String latest = versions.stream().max(new Pep440VersionComparator()).orElseThrow();
        final List<VersionFile> latestFiles = files.stream()
            .filter(file -> file.version().equals(latest)).toList();
        return Json.createObjectBuilder()
            .add("info", renderInfo(packageName, latest, latestFiles))
            .add("releases", renderReleases(files, versions))
            .add("urls", renderFiles(latestFiles))
            .build()
            .toString();
    }

    /**
     * Build the {@code releases} object: every version, each mapping to
     * its array of file entries.
     */
    private static JsonObjectBuilder renderReleases(
        final List<VersionFile> files, final List<String> versions
    ) {
        final JsonObjectBuilder releases = Json.createObjectBuilder();
        for (final String version : versions) {
            final List<VersionFile> versionFiles = files.stream()
                .filter(file -> file.version().equals(version)).toList();
            releases.add(version, renderFiles(versionFiles));
        }
        return releases;
    }

    /**
     * Render a list of files as a JSON array of file objects.
     */
    private static JsonArrayBuilder renderFiles(final List<VersionFile> files) {
        final JsonArrayBuilder array = Json.createArrayBuilder();
        for (final VersionFile file : files) {
            array.add(renderFile(file));
        }
        return array;
    }

    /**
     * Build the {@code info} object describing the latest version: its
     * {@code requires_python} (first non-empty value among its files) and
     * yank status (yanked only when EVERY file of that version is yanked).
     */
    private static JsonObjectBuilder renderInfo(
        final String packageName, final String latest, final List<VersionFile> latestFiles
    ) {
        final String requiresPython = latestFiles.stream()
            .map(VersionFile::requiresPython)
            .filter(value -> value != null && !value.isEmpty())
            .findFirst().orElse(null);
        final boolean yanked = !latestFiles.isEmpty()
            && latestFiles.stream().allMatch(VersionFile::yanked);
        final JsonObjectBuilder info = Json.createObjectBuilder()
            .add("name", packageName)
            .add("version", latest)
            .add("yanked", yanked);
        if (requiresPython == null) {
            info.addNull("requires_python");
        } else {
            info.add("requires_python", requiresPython);
        }
        if (yanked) {
            final String reason = latestFiles.stream()
                .map(VersionFile::yankedReason)
                .flatMap(Optional::stream)
                .findFirst().orElse("");
            info.add("yanked_reason", reason);
        } else {
            info.addNull("yanked_reason");
        }
        return info;
    }

    private static JsonObjectBuilder renderFile(final VersionFile file) {
        final JsonObjectBuilder obj = Json.createObjectBuilder()
            .add("filename", file.filename())
            .add("size", file.size())
            .add("digests", Json.createObjectBuilder().add("sha256", file.sha256()))
            .add("url", file.version() + "/" + file.filename());
        if (file.requiresPython() != null && !file.requiresPython().isEmpty()) {
            obj.add("requires_python", file.requiresPython());
        } else {
            obj.addNull("requires_python");
        }
        if (file.uploadTime() != null) {
            obj.add("upload_time_iso_8601", file.uploadTime());
        }
        if (file.yanked()) {
            obj.add("yanked", true);
            obj.add("yanked_reason", file.yankedReason().orElse(""));
        } else {
            obj.add("yanked", false);
        }
        return obj;
    }

    /**
     * A single distribution file grouped under its version, carrying
     * everything the legacy JSON schema needs per-file.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private record VersionFile(
        String filename,
        String version,
        String sha256,
        long size,
        String requiresPython,
        String uploadTime,
        boolean yanked,
        Optional<String> yankedReason
    ) {
    }
}
