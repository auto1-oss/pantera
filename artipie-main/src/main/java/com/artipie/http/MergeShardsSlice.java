/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http;

import com.artipie.RepositorySlices;
import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.composer.ComposerImportMerge;
import com.artipie.http.headers.ContentType;
import com.artipie.http.rq.RequestLine;
import com.artipie.settings.repo.RepoConfig;
import com.artipie.maven.metadata.MavenMetadata;
import com.artipie.helm.metadata.IndexYaml;
import com.artipie.helm.metadata.IndexYamlMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.artipie.helm.misc.DateTimeNow;
import org.yaml.snakeyaml.Yaml;

import javax.json.Json;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import java.nio.charset.StandardCharsets;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.xembly.Directives;
import org.xembly.Xembler;
import java.time.Instant;
import java.security.MessageDigest;

/**
 * Slice to handle import merge requests across repository types.
 * 
 * <p>Handles POST requests to {@code /.merge/{repo}} and performs a
 * repository-type specific metadata merge:</p>
 * <ul>
 *  <li>composer/php: delegates to ComposerImportMerge (p2 per-package files)</li>
 *  <li>maven/gradle: merges shard files under .meta/maven/shards into maven-metadata.xml</li>
 *  <li>helm: merges shard files under .meta/helm/shards into index.yaml</li>
 * </ul>
 * 
 * <p>Example:</p>
 * <pre>
 * POST /.merge/php-api
 * 
 * Response:
 * {
 *   "mergedPackages": 50,
 *   "mergedVersions": 1842,
 *   "failedPackages": 0
 * }
 * </pre>
 * 
 * @since 1.18.14
 */
public final class MergeShardsSlice implements Slice {

    /**
     * Logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(MergeShardsSlice.class);

    /**
     * Pattern to extract repository name from path.
     */
    private static final Pattern PATH_PATTERN = Pattern.compile("^/\\.merge/([^/]+)$");

    /**
     * Repository slices to get storage per repository.
     */
    private final RepositorySlices slices;

    /**
     * Ctor.
     * 
     * @param slices Repository slices
     */
    public MergeShardsSlice(final RepositorySlices slices) {
        this.slices = slices;
    }

    /**
     * Merge PyPI shards into static HTML indices under .pypi.
     */
    private CompletionStage<PypiSummary> mergePypiShards(final Storage storage) {
        final Key prefix = new Key.From(".meta", "pypi", "shards");
        return storage.list(prefix).thenCompose(keys -> {
            final Map<String, List<Key>> byPackage = new HashMap<>();
            final String pfx = prefix.string() + "/";
            for (final Key key : keys) {
                final String p = key.string();
                if (!p.startsWith(pfx) || !p.endsWith(".json")) {
                    continue;
                }
                final String rest = p.substring(pfx.length());
                final String[] segs = rest.split("/");
                if (segs.length < 3) {
                    continue;
                }
                final String pkg = segs[0];
                byPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(key);
            }

            final AtomicInteger files = new AtomicInteger(0);
            final AtomicInteger packages = new AtomicInteger(byPackage.size());
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (final Map.Entry<String, List<Key>> ent : byPackage.entrySet()) {
                final String pkg = ent.getKey();
                final List<Key> shardKeys = ent.getValue();
                final Key out = new Key.From(".pypi", pkg, pkg + ".html");
                chain = chain.thenCompose(nothing -> storage.exclusively(out, st -> {
                    final List<CompletableFuture<String>> lines = new ArrayList<>();
                    for (final Key k : shardKeys) {
                        lines.add(st.value(k).thenCompose(Content::asStringFuture).thenApply(json -> {
                            try (JsonReader rdr = Json.createReader(new StringReader(json))) {
                                final var obj = rdr.readObject();
                                final String version = obj.getString("version", null);
                                final String filename = obj.getString("filename", null);
                                final String sha256 = obj.getString("sha256", null);
                                if (version != null && filename != null) {
                                    files.incrementAndGet();
                                    final String href = String.format("%s/%s", version, filename);
                                    if (sha256 != null && !sha256.isBlank()) {
                                        return String.format("<a href=\"%s#sha256=%s\">%s</a><br/>", href, sha256, filename);
                                    } else {
                                        return String.format("<a href=\"%s\">%s</a><br/>", href, filename);
                                    }
                                }
                                return "";
                            }
                        }).toCompletableFuture());
                    }
                    return CompletableFuture.allOf(lines.toArray(CompletableFuture[]::new))
                        .thenApply(v -> {
                            final StringBuilder sb = new StringBuilder();
                            lines.forEach(fut -> sb.append(fut.join()));
                            return String.format("<!DOCTYPE html>\n<html>\n  <body>\n%s\n</body>\n</html>", sb.toString());
                        })
                        .thenCompose(html -> st.save(out, new Content.From(html.getBytes(StandardCharsets.UTF_8))));
                }));
            }

            // Write repo-level simple.html
            final Key simple = new Key.From(".pypi", "simple.html");
            chain = chain.thenCompose(nothing -> storage.exclusively(simple, st -> {
                final String body = byPackage.keySet().stream()
                    .sorted()
                    .map(name -> String.format("<a href=\"%s/\">%s</a><br/>", name, name))
                    .reduce(new StringBuilder(), StringBuilder::append, StringBuilder::append)
                    .toString();
                final String html = String.format("<!DOCTYPE html>\n<html>\n  <body>\n%s\n</body>\n</html>", body);
                return st.save(simple, new Content.From(html.getBytes(StandardCharsets.UTF_8)));
            }));

            return chain.thenApply(n -> new PypiSummary(packages.get(), files.get()));
        });
    }

    private static final class PypiSummary {
        final int packages;
        final int files;
        PypiSummary(final int packages, final int files) {
            this.packages = packages;
            this.files = files;
        }
    }

    /**
     * Convert bytes to lowercase hex string.
     */
    private static String hexLower(final byte[] bytes) {
        final char[] HEX = "0123456789abcdef".toCharArray();
        final char[] out = new char[bytes.length * 2];
        for (int i = 0, j = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[j++] = HEX[v >>> 4];
            out[j++] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String path = line.uri().getPath();
        final Matcher matcher = PATH_PATTERN.matcher(path);
        
        if (!matcher.matches()) {
            return ResponseBuilder.notFound()
                .textBody("Invalid merge endpoint path")
                .completedFuture();
        }
        
        final String repoName = matcher.group(1);
        
        if (!"POST".equalsIgnoreCase(line.method().value())) {
            return ResponseBuilder.methodNotAllowed()
                .textBody("Only POST method is supported")
                .completedFuture();
        }
        
        LOG.info("Triggering metadata merge for repository: {}", repoName);
        
        // Get repository configuration
        final Optional<RepoConfig> repoConfigOpt = this.slices.repositories().config(repoName);
        
        if (repoConfigOpt.isEmpty()) {
            return ResponseBuilder.notFound()
                .textBody(String.format("Repository '%s' not found", repoName))
                .completedFuture();
        }
        
        final RepoConfig repoConfig = repoConfigOpt.get();
        
        // Get repository storage and type
        final Storage storage = repoConfig.storage();
        final String type = repoConfig.type().toLowerCase(Locale.ROOT);
        final Optional<String> baseUrl = Optional.of("/" + repoName);

        final CompletableFuture<JsonObjectBuilder> merged;
        if ("php".equals(type) || "composer".equals(type)) {
            // Delegate to existing composer merger
            final ComposerImportMerge merge = new ComposerImportMerge(storage, baseUrl);
            merged = merge.mergeAll().thenApply(result ->
                Json.createObjectBuilder()
                    .add("type", type)
                    .add("composer", Json.createObjectBuilder()
                        .add("mergedPackages", result.mergedPackages)
                        .add("mergedVersions", result.mergedVersions)
                        .add("failedPackages", result.failedPackages))
                    .add("mavenArtifactsUpdated", 0)
                    .add("helmChartsUpdated", 0)
            ).toCompletableFuture();
        } else if ("maven".equals(type) || "gradle".equals(type)) {
            merged = mergeMavenShards(storage)
                .thenApply(sum -> Json.createObjectBuilder()
                    .add("type", type)
                    .add("mavenArtifactsUpdated", sum.artifactsUpdated)
                    .add("mavenBases", sum.bases)
                    .add("composer", Json.createObjectBuilder()
                        .add("mergedPackages", 0)
                        .add("mergedVersions", 0)
                        .add("failedPackages", 0))
                    .add("helmChartsUpdated", 0)
                ).toCompletableFuture();
        } else if ("helm".equals(type)) {
            merged = mergeHelmShards(storage, baseUrl, repoName)
                .thenApply(sum -> Json.createObjectBuilder()
                    .add("type", type)
                    .add("helmChartsUpdated", sum.charts)
                    .add("helmVersions", sum.versions)
                    .add("composer", Json.createObjectBuilder()
                        .add("mergedPackages", 0)
                        .add("mergedVersions", 0)
                        .add("failedPackages", 0))
                    .add("mavenArtifactsUpdated", 0)
                ).toCompletableFuture();
        } else if ("pypi".equals(type) || "python".equals(type)) {
            merged = mergePypiShards(storage)
                .thenApply(sum -> Json.createObjectBuilder()
                    .add("type", type)
                    .add("pypiPackagesUpdated", sum.packages)
                    .add("pypiFilesIndexed", sum.files)
                    .add("composer", Json.createObjectBuilder()
                        .add("mergedPackages", 0)
                        .add("mergedVersions", 0)
                        .add("failedPackages", 0))
                    .add("mavenArtifactsUpdated", 0)
                    .add("helmChartsUpdated", 0)
                ).toCompletableFuture();
        } else {
            merged = CompletableFuture.completedFuture(
                Json.createObjectBuilder().add("type", type).add("message", "No merge action for this repo type")
            );
        }

        return merged.thenCompose(json -> {
            // After merge (successful or not), clean up temporary folders
            return cleanupTempFolders(storage).thenApply(v -> json);
        }).thenApply(json -> {
            final byte[] responseBytes = json.build().toString().getBytes(StandardCharsets.UTF_8);
            return ResponseBuilder.ok()
                .header(ContentType.NAME, "application/json")
                .body(responseBytes)
                .build();
        }).exceptionally(error -> {
            LOG.error("Metadata merge failed for {}: {}", repoName, error.getMessage(), error);
            // Clean up even on failure
            cleanupTempFolders(storage).exceptionally(e -> {
                LOG.warn("Failed to cleanup after merge failure: {}", e.getMessage());
                return null;
            });
            final String errorMsg = String.format(
                "{\"error\": \"%s\"}",
                error.getMessage().replace("\"", "\\\"")
            );
            return ResponseBuilder.internalError()
                .header(ContentType.NAME, "application/json")
                .textBody(errorMsg)
                .build();
        }).toCompletableFuture();
    }

    /**
     * Merge Maven/Gradle shards into maven-metadata.xml per artifact base.
     */
    private CompletionStage<MavenSummary> mergeMavenShards(final Storage storage) {
        final Key prefix = new Key.From(".meta", "maven", "shards");
        return storage.list(prefix).thenCompose(keys -> {
            final Map<String, Set<String>> byBase = new HashMap<>();
            final String pfx = prefix.string() + "/";
            final List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            for (final Key key : keys) {
                final String p = key.string();
                if (!p.startsWith(pfx) || !p.endsWith(".json")) {
                    continue;
                }
                final String rest = p.substring(pfx.length());
                final String[] segs = rest.split("/");
                if (segs.length < 3) { // Need at least artifactId/version/filename.json or groupPath/artifactId/version/filename.json
                    continue;
                }
                final String filenameJson = segs[segs.length - 1];
                final String versionFromPath = segs[segs.length - 2];
                final String artifactId = segs[segs.length - 3];
                // Skip if not a .json file
                if (!filenameJson.endsWith(".json")) {
                    continue;
                }
                // Check if we have a group path (when segs.length > 3)
                final String groupPath;
                if (segs.length > 3) {
                    groupPath = String.join("/", java.util.Arrays.copyOf(segs, segs.length - 3));
                } else {
                    groupPath = ""; // Root-level artifact
                }
                
                // Read the shard content asynchronously to get the actual version
                final CompletableFuture<Void> future = storage.value(new Key.From(p))
                    .thenCompose(Content::asStringFuture)
                    .thenAccept(content -> {
                        // Parse version from JSON
                        try {
                            if (content.contains("\"version\"")) {
                                final int start = content.indexOf("\"version\":\"") + 11;
                                final int end = content.indexOf("\"", start);
                                final String actualVersion = content.substring(start, end);
                                if (groupPath.isEmpty()) {
                                    // Root-level artifact, base is just artifactId
                                    byBase.computeIfAbsent(artifactId, k -> new HashSet<>()).add(actualVersion);
                                } else {
                                    final String base = groupPath + "/" + artifactId;
                                    byBase.computeIfAbsent(base, k -> new HashSet<>()).add(actualVersion);
                                }
                            }
                        } catch (Exception e) {
                            LOG.warn("Failed to parse version from shard {}: {}", p, e.getMessage());
                        }
                    })
                    .exceptionally(e -> {
                        LOG.warn("Failed to read shard {}: {}", p, e.getMessage());
                        return null;
                    });
                futures.add(future);
            }
            
            // Wait for all shard reads to complete
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenCompose(v -> {
                    // Now process the collected versions
                    final AtomicInteger updated = new AtomicInteger(0);
                    final AtomicInteger bases = new AtomicInteger(byBase.size());
                    CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                    for (final Map.Entry<String, Set<String>> ent : byBase.entrySet()) {
                        final String base = ent.getKey();
                        final Set<String> versions = ent.getValue();
                        final String groupId;
                        final String artifactId;
                        if (base.contains("/")) {
                            // Has group path
                            groupId = base.substring(0, base.lastIndexOf('/')).replace('/', '.');
                            artifactId = base.substring(base.lastIndexOf('/') + 1);
                        } else {
                            // Root-level artifact
                            groupId = base; // Use artifactId as groupId for root-level
                            artifactId = base;
                        }
                        final Key mdKey = new Key.From(base, "maven-metadata.xml");
                        // Ensure parent directory path, not file path
                        final Key parentDir = new Key.From(base);
                        chain = chain.thenCompose(nothing -> storage.exclusively(mdKey, st -> {
                            // Build maven-metadata.xml content inline (avoid nested path issues)
                            final Directives d = new Directives()
                                .add("metadata")
                                .add("groupId").set(groupId).up()
                                .add("artifactId").set(artifactId).up()
                                .add("versioning");
                            // latest = max version
                            versions.stream().max((a, b) -> new com.artipie.maven.metadata.Version(a).compareTo(new com.artipie.maven.metadata.Version(b)))
                                .ifPresent(lat -> d.add("latest").set(lat).up());
                            // release = max non-SNAPSHOT
                            versions.stream().filter(ver -> !ver.endsWith("SNAPSHOT"))
                                .max((a, b) -> new com.artipie.maven.metadata.Version(a).compareTo(new com.artipie.maven.metadata.Version(b)))
                                .ifPresent(rel -> d.add("release").set(rel).up());
                            d.add("versions");
                            versions.forEach(ver -> d.add("version").set(ver).up());
                            d.up().add("lastUpdated").set(String.format("%tY%<tm%<td%<tH%<tM", new java.util.Date())).up().up();
                            final String xml;
                            try {
                                xml = new Xembler(d).xml();
                            } catch (final Exception ex) {
                                return CompletableFuture.failedFuture(ex);
                            }
                            return st.save(mdKey, new Content.From(xml.getBytes(StandardCharsets.UTF_8)))
                                .thenCompose(saved -> st.value(mdKey)
                                    .thenCompose(Content::asBytesFuture)
                                    .thenCompose(bytes -> {
                                        try {
                                            final String sha1 = hexLower(MessageDigest.getInstance("SHA-1").digest(bytes));
                                            final String md5 = hexLower(MessageDigest.getInstance("MD5").digest(bytes));
                                            final String sha256 = hexLower(MessageDigest.getInstance("SHA-256").digest(bytes));
                                            final CompletableFuture<Void> s1 = st.save(new Key.From(base, "maven-metadata.xml.sha1"), new Content.From(sha1.getBytes(StandardCharsets.US_ASCII))).toCompletableFuture();
                                            final CompletableFuture<Void> s2 = st.save(new Key.From(base, "maven-metadata.xml.md5"), new Content.From(md5.getBytes(StandardCharsets.US_ASCII))).toCompletableFuture();
                                            final CompletableFuture<Void> s3 = st.save(new Key.From(base, "maven-metadata.xml.sha256"), new Content.From(sha256.getBytes(StandardCharsets.US_ASCII))).toCompletableFuture();
                                            return CompletableFuture.allOf(s1, s2, s3);
                                        } catch (final Exception ex) {
                                            return CompletableFuture.failedFuture(ex);
                                        }
                                    })
                                )
                                .thenApply(n -> {
                                    updated.incrementAndGet();
                                    return null;
                                });
                        }));
                    }
                    return chain.thenApply(n -> new MavenSummary(updated.get(), bases.get()));
                });
        });
    }

    /**
     * Merge Helm shards into a unified index.yaml at repository root.
     */
    private CompletionStage<HelmSummary> mergeHelmShards(final Storage storage, final Optional<String> baseUrl, final String repoName) {
        final Key prefix = new Key.From(".meta", "helm", "shards");
        return storage.list(prefix).thenCompose(keys -> {
            final Map<String, List<Key>> byChart = new HashMap<>();
            final String pfx = prefix.string() + "/";
            for (final Key key : keys) {
                final String p = key.string();
                if (!p.startsWith(pfx) || !p.endsWith(".json")) {
                    continue;
                }
                final String rest = p.substring(pfx.length());
                final String[] segs = rest.split("/");
                if (segs.length != 2) {
                    continue;
                }
                final String chart = segs[0];
                byChart.computeIfAbsent(chart, k -> new ArrayList<>()).add(key);
            }

            final AtomicInteger charts = new AtomicInteger(0);
            final AtomicInteger vers = new AtomicInteger(0);
            final Key idx = IndexYaml.INDEX_YAML;
            return storage.exclusively(idx, st -> {
                // Start with a fresh empty index structure
                final IndexYamlMapping mapping = new IndexYamlMapping();
                
                // Ensure required fields are set
                mapping.entries(); // initializes entries map
                final Map<String, Object> raw = new java.util.HashMap<>();
                raw.put("apiVersion", "v1");
                raw.put("generated", new DateTimeNow().asString());
                raw.put("entries", new java.util.HashMap<>());
                
                final List<CompletableFuture<Void>> reads = new ArrayList<>();
                final Map<String, List<Map<String, Object>>> chartVersions = new HashMap<>();
                
                byChart.forEach((chart, shardKeys) -> {
                    charts.incrementAndGet();
                    final List<Map<String, Object>> versions = new ArrayList<>();
                    final List<CompletableFuture<Void>> chartReads = new ArrayList<>();
                    
                    for (final Key k : shardKeys) {
                        chartReads.add(st.value(k).thenCompose(Content::asStringFuture).thenAccept(json -> {
                            try (JsonReader rdr = Json.createReader(new StringReader(json))) {
                                final var obj = rdr.readObject();
                                final String version = obj.getString("version", null);
                                String url = obj.getString("url", null);
                                final String digest = obj.getString("sha256", null);
                                final String name = obj.getString("name", chart);
                                final String path = obj.getString("path", null);
                                
                                if (version != null && url != null) {
                                    // Use the path field from shard if available, it's more reliable
                                    if (path != null) {
                                        // The path field contains the full storage path
                                        // Remove the repository name prefix (which can be multiple segments)
                                        if (path.startsWith(repoName + "/")) {
                                            url = path.substring(repoName.length() + 1);
                                        } else {
                                            url = path;
                                        }
                                    } else {
                                        // Fallback: try to extract from URL
                                        if (url.startsWith("http://") || url.startsWith("https://")) {
                                            final int slashIndex = url.indexOf('/', url.indexOf("://") + 3);
                                            if (slashIndex > 0) {
                                                String urlPath = url.substring(slashIndex + 1);
                                                // Remove repository prefix using repoName
                                                if (urlPath.startsWith(repoName + "/")) {
                                                    urlPath = urlPath.substring(repoName.length() + 1);
                                                }
                                                url = urlPath;
                                            }
                                        }
                                    }
                                    
                                    final Map<String, Object> entry = new HashMap<>();
                                    // Required fields for Helm index.yaml
                                    entry.put("apiVersion", "v1");
                                    entry.put("name", name);
                                    entry.put("version", version);
                                    entry.put("created", new DateTimeNow().asString());
                                    
                                    // URL should be an array in Helm index.yaml
                                    entry.put("urls", java.util.List.of(url));
                                    
                                    // Use proper field name for digest
                                    if (digest != null && !digest.isBlank()) {
                                        entry.put("digest", digest);
                                    }
                                    
                                    // Add appVersion, description, etc. if available in shard
                                    if (obj.containsKey("appVersion")) {
                                        entry.put("appVersion", obj.getString("appVersion"));
                                    }
                                    if (obj.containsKey("description")) {
                                        entry.put("description", obj.getString("description"));
                                    }
                                    if (obj.containsKey("home")) {
                                        entry.put("home", obj.getString("home"));
                                    }
                                    if (obj.containsKey("icon")) {
                                        entry.put("icon", obj.getString("icon"));
                                    }
                                    
                                    versions.add(entry);
                                    vers.incrementAndGet();
                                }
                            }
                        }));
                    }
                    
                    // Wait for all shard reads for this chart, then add to mapping
                    reads.add(CompletableFuture.allOf(chartReads.toArray(CompletableFuture[]::new))
                        .thenRun(() -> {
                            if (!versions.isEmpty()) {
                                synchronized (chartVersions) {
                                    chartVersions.put(chart, versions);
                                }
                            }
                        }));
                });
                
                return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new))
                    .thenCompose(n -> {
                        // Add all chart versions to the mapping
                        chartVersions.forEach(mapping::addChartVersions);
                        
                        // Generate final YAML with proper structure
                        final Map<String, Object> finalMap = new HashMap<>();
                        finalMap.put("apiVersion", "v1");
                        finalMap.put("generated", new DateTimeNow().asString());
                        finalMap.put("entries", mapping.entries());
                        
                        final Yaml yaml = new org.yaml.snakeyaml.Yaml();
                        final String yamlContent = yaml.dump(finalMap);
                        
                        return st.save(idx, new Content.From(yamlContent.getBytes(StandardCharsets.UTF_8)));
                    })
                    .thenApply(n -> new HelmSummary(charts.get(), vers.get()));
            });
        });
    }

    private static final class MavenSummary {
        final int artifactsUpdated;
        final int bases;
        MavenSummary(final int artifactsUpdated, final int bases) {
            this.artifactsUpdated = artifactsUpdated;
            this.bases = bases;
        }
    }

    private static final class HelmSummary {
        final int charts;
        final int versions;
        HelmSummary(final int charts, final int versions) {
            this.charts = charts;
            this.versions = versions;
        }
    }

    /**
     * Clean up temporary folders after merge.
     * Deletes .import and .meta folders and all their contents.
     */
    private static CompletionStage<Void> cleanupTempFolders(final Storage storage) {
        LOG.info("Starting cleanup of temporary folders after merge");
        final List<CompletionStage<Void>> deletions = new ArrayList<>();
        
        // Delete .import folder completely
        LOG.info("Deleting .import folder");
        deletions.add(storage.delete(new Key.From(".import"))
            .thenRun(() -> LOG.info(".import folder deleted successfully"))
            .exceptionally(e -> {
                LOG.warn("Failed to delete .import folder: {}", e.getMessage());
                return null;
            }));
        
        // Delete .meta folder completely
        LOG.info("Deleting .meta folder");
        deletions.add(storage.delete(new Key.From(".meta"))
            .thenRun(() -> LOG.info(".meta folder deleted successfully"))
            .exceptionally(e -> {
                LOG.warn("Failed to delete .meta folder: {}", e.getMessage());
                return null;
            }));
        
        return CompletableFuture.allOf(deletions.toArray(new CompletableFuture[0]))
            .thenRun(() -> LOG.info("Temporary folders cleanup completed"))
            .exceptionally(e -> {
                LOG.warn("Failed to cleanup temporary folders: {}", e.getMessage());
                return null;
            });
    }
}
