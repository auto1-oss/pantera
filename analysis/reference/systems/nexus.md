# Sonatype Nexus 3 — reference architecture study

## TL;DR

Nexus Repository Manager 3 is a JVM (Java 8/11/17, depending on version) artifact registry that has evolved from an OSGi/Karaf-based runtime to a Spring Framework runtime as of 3.78.0. For Maven proxy semantics the architecture has four load-bearing primitives, all visible in the OSS source at `github.com/sonatype/nexus-public`:

1. A `ProxyFacetSupport` template-method pipeline (`get` → `maybeGetCachedContent` → `isStale` → `proxyCooperation.on(...)` → `doGet` → `fetch` → `store`) — exactly the same shape as `pantera-core` `BaseCachedProxySlice`, but with one extra trick described below.
2. A `Cooperation2` single-flight primitive (`ConcurrentMap<String, CooperatingFuture<?>>`) that collapses concurrent requests by path key with a configurable `threadsPerKey=100`, `majorTimeout=0s` (wait indefinitely), `minorTimeout=30s` defaults. Documented system properties: `nexus.proxy.cooperation.{enabled,majorTimeout,minorTimeout,threadsPerKey}`.
3. A pluggable `NegativeCacheFacetImpl` backed by JCache (`javax.cache.Cache<NegativeCacheKey, Status>`) with a 1440-minute default `timeToLive`, scoped per-path, only caching 404s from GET/HEAD, automatically invalidated on a subsequent 200, and explicitly skipped while the upstream is auto-blocked.
4. A `BlockingHttpClient` wrapper around Apache HttpClient that, on any 5xx / 401 / 407 / I/O error, replaces the live HttpClient with a fast-failing wrapper for `Fib(40s) = 40s, 80s, 120s, 200s, 320s, 520s …` and schedules a background HEAD request at the boundary to probe recovery. Default `AutoBlockConfiguration.shouldBlock(code) = code == 401 || code == 407 || code >= 500`.

Crucially, when the lead thread serves a request through `proxyCooperation.on(...).cooperate(key)`, every follower is served the **in-memory `TempContent`** (a `BytesPayload` made from a fully-read `byte[]`) — i.e. Nexus does NOT stream the upstream body to concurrent followers; it buffers it. This is the cooperation/streaming tradeoff Pantera has to make explicit.

Conditional requests are sent on every miss when `Content-ETag` or `Content-Last-Modified` attributes exist on the stale asset, using stored `If-None-Match` / `If-Modified-Since` headers. On 304 the asset's `lastVerified` is refreshed without rewriting the blob.

The known weakness: on a stale-but-cached asset, Nexus historically does NOT serve stale-on-error — when max-age has expired AND the remote is unreachable Nexus returns 502 (JIRA NEXUS-12527, a regression from Nexus 2 fixed in 3.3.0; the patch made the auto-blocked path skip the negative cache and surface stale content during the block window — see `NegativeCacheHandler.isRemoteBlocked()`).

## Sources

[1] Sonatype OSS source mirror — `github.com/sonatype/nexus-public`, branch `main`, paths under `public/common/components/nexus-repository-services/` and `nexus-repository-view/`. Retrieved 2026-05-14.
[2] `help.sonatype.com/en/configurable-repository-fields.html` — defaults for Not-Found Cache TTL, Maximum Component Age, Maximum Metadata Age. Retrieved 2026-05-14.
[3] `help.sonatype.com/en/repository-types.html` — group-repository "first match wins" semantics. Retrieved 2026-05-14.
[4] `help.sonatype.com/en/http-request-and-proxy-settings.html` — default Connection Timeout 30s, Retries 2, Request Timeout 20s, Keep-Alive 30s, Buffer 8k. Retrieved 2026-05-14.
[5] `www.sonatype.com/blog/2010/04/nexus-1-6-introduces-auto-blocking-unreachable-remote-repositories` — original auto-block design (Fibonacci backoff, HEAD probe). Retrieved 2026-05-14.
[6] `support.sonatype.com/hc/en-us/articles/115015441707-How-to-Debug-Outbound-HTTP-Requests-in-Repository-3` — confirms Apache HttpClient is the outbound HTTP library. Retrieved 2026-05-14.
[7] `help.sonatype.com/en/database-options.html` — OrientDB → H2 / PostgreSQL transition; 3.71 retirement of OrientDB. Retrieved 2026-05-14.
[8] `help.sonatype.com/en/blob-stores.html` — file vs S3 / Azure / GCS blob store; soft-delete via `.properties` files. Retrieved 2026-05-14.
[9] `help.sonatype.com/en/scaling-with-proxy-nodes.html` — proxy-node scaling guidance, recommended `Maximum Metadata Age = 0` for very fresh deployments, 16-core / 32 GB sizing. Retrieved 2026-05-14.
[10] `help.sonatype.com/en/prometheus.html` — `/service/metrics/prometheus` endpoint (pre-3.81), `/service/rest/metrics/prometheus` (3.81+); `nx-metrics-all` privilege required. Retrieved 2026-05-14.
[11] Sonatype JIRA NEXUS-12527 — "Nexus will not deliver files from the on-disk cache of a proxy repository if their metadata/artifact max age has expired and the remote is not reachable", Blocker, fixed in 3.3.0. Retrieved 2026-05-14.
[12] `javadoc.io/static/org.sonatype.nexus/nexus-repository/3.19.1-01/org/sonatype/nexus/repository/proxy/ProxyFacetSupport.html` — published Javadoc for `configureCooperation`, `nexus.proxy.cooperation.*` properties. Retrieved 2026-05-14.
[13] `help.sonatype.com/en/sonatype-nexus-repository-3-78-0-release-notes.html` referenced via release-notes search — migration off Karaf/OSGi to Spring Framework "at least double the performance on the same infrastructure". Retrieved 2026-05-14.
[14] `help.sonatype.com/en/maven-repositories.html` — version-policy values (Release / Snapshot / Mixed), layout policy (Strict / Permissive), Maven metadata semantics. Retrieved 2026-05-14.

## 1. Request lifecycle on cache miss

The handler chain on a proxy repository, traced from `nexus-repository-services` source:

```
ViewServlet → SecurityHandler → ConditionsHandler →
PartialFetchHandler → ContentHeadersHandler →
LastDownloadedHandler → ProxyHandler (calls ProxyFacet.get(context))
                            └→ NegativeCacheHandler (wraps the above)
```

`NegativeCacheHandler.handle(context)` runs first within the proxy block. It:

1. Skips if the action is not GET/HEAD (`NFC_CACHEABLE_ACTIONS = ImmutableSet.of(HttpMethods.GET, HttpMethods.HEAD)`).
2. Computes `key = new PathNegativeCacheKey(request.getPath())`.
3. Looks up `Status` in the negative cache. If present, **returns a synthetic 404 response** without invoking the proxy logic.
4. Otherwise calls `context.proceed()` (which is `ProxyFacetSupport.get(context)`).
5. On the way out: if the response is 404 AND `!isRemoteBlocked` AND no `MISSING_BLOB_SKIP_NEGATIVE_CACHE` marker, inserts into the negative cache.
6. On a 2xx, calls `negativeCache.invalidate(key)` to remove any prior 404.

`ProxyFacetSupport.get(context)` (line 345 in `ProxyFacetSupport.java`):

```java
Content content = maybeGetCachedContent(context);          // 1. local-cache lookup
if (!isStale(context, content)) {                          // 2. stale check
  return content;                                          //    HIT: serve cached blob
}
return get(context, content);                              // 3. cooperate + fetch
```

`get(context, staleContent)` is the cooperation gate:

```java
return proxyCooperation.on(() -> doGet(context, staleContent))
    .checkFunction(() -> {
      Content latestContent = maybeGetCachedContent(context);
      if (!isStale(context, latestContent)) {
        return Optional.of(latestContent);                 // follower short-circuit
      }
      return Optional.empty();
    })
    .cooperate(getRequestKey(context));                    // key = path + '?' + params
```

`doGet` → `fetch(context, stale)` builds an `HttpGet` with conditional headers if a stale `CacheInfo` exists, executes via the auto-blocking `HttpClient`, branches on status code, and on 200 OK returns an `HttpEntityPayload` wrapping the live entity (streaming). The lead thread then writes that stream into the asset store via `store(context, remote)`. If `remote.equals(content)` after store (i.e. the store did not consume / rebuffer it — rare path) the lead thread wraps it in `TempContent`, which reads the full body into a `byte[]` so cooperating followers get a re-readable copy.

### Sequence diagram (cold miss with two concurrent clients)

```mermaid
sequenceDiagram
  autonumber
  participant C1 as Client A
  participant C2 as Client B
  participant J as Jetty (Nexus front-end)
  participant V as ViewServlet + handler chain
  participant NCH as NegativeCacheHandler
  participant PF as ProxyFacetSupport
  participant CO as Cooperation2 / CooperatingFuture
  participant HC as BlockingHttpClient (Apache)
  participant DB as Component DB (H2/Postgres)
  participant BS as Blob store (file or S3)
  participant U as Upstream (Maven Central)

  C1->>J: GET /maven-central/com/foo/bar/1/bar-1.jar
  C2->>J: GET /maven-central/com/foo/bar/1/bar-1.jar
  J->>V: dispatch
  V->>NCH: handle()
  NCH->>NCH: negativeCache.get(path) -> null (no entry)
  NCH->>PF: context.proceed()
  PF->>DB: find asset by path
  DB-->>PF: null (miss)
  PF->>CO: cooperate(key=path+'?')
  Note over CO: C1 wins putIfAbsent, becomes "lead"; C2 attaches to existing CooperatingFuture
  PF->>HC: fetch(URL) (lead thread only)
  HC->>U: HTTP GET (conditional headers absent on cold miss)
  U-->>HC: 200 OK, body stream
  HC-->>PF: HttpEntityPayload
  PF->>BS: store blob (stream + digests SHA1/SHA256/MD5)
  BS-->>PF: BlobRef
  PF->>DB: insert asset row + mark cache info (lastVerified=now)
  PF->>CO: complete(future) with Content (or TempContent if needed)
  CO-->>C2: same Content reference
  PF->>NCH: return 200
  NCH-->>V: 200
  V-->>J: 200 + stream
  J-->>C1: 200 OK
  J-->>C2: 200 OK
```

The two notable mechanical details:

- **`Cooperation2` key includes query parameters** (`path + '?' + parameters`) — same-path-different-query are different keys.
- **Negative cache lives in JCache outside the cooperation gate**: a 404 from one thread is visible to all subsequent threads without going through cooperation.

## 2. Cache hierarchy

Nexus has four logical caches per proxy repo, each with distinct semantics:

| Cache | Storage | Default TTL | Scope | Trigger |
|-------|---------|-------------|-------|---------|
| Negative Cache (Not Found Cache) | JCache (Caffeine impl) `<NegativeCacheKey, Status>` per repo, name `{repo}#negative-cache` | **1440 min (24 h)** | Path | 404 on GET/HEAD |
| Component Cache (the blob itself) | Blob store (file / S3 / Azure / GCS) | governed by `Maximum Component Age` | Asset | Successful fetch |
| Metadata Cache (`maven-metadata.xml`, `index.json`, equivalents) | Same blob store but separate `CacheController` | governed by `Maximum Metadata Age` | Metadata path | Successful fetch |
| Cache Token (group-level invalidation epoch) | DB column / Caffeine | manual | Whole repo | `invalidateProxyCaches()` |

The defaults straight from `help.sonatype.com/en/configurable-repository-fields.html` and the OSS source:

- `negativeCache.timeToLive` = `Time.hours(24).toMinutesI()` → **1440 minutes**.
- `ProxyConfig.contentMaxAge` = `(int) Duration.ofHours(24).toMinutes()` → **1440 minutes** (release-policy repos default to **-1** via the UI / API converter, which disables remote check entirely for already-cached assets).
- `ProxyConfig.metadataMaxAge` = **1440 minutes**.

The `CacheController` (held in a `CacheControllerHolder` with two slots, `CONTENT` and `METADATA`) carries:
- `maxAgeSeconds` (computed from `contentMaxAge` / `metadataMaxAge`)
- a `cacheToken` (UUID, bumped on `invalidateCache()` — that's how a group repo or an admin REST call invalidates a whole proxy logically without rewriting blobs)
- `isStale(CacheInfo info)` returns true iff `info.isInvalidated()` OR `info.getCacheToken() != current().getCacheToken()` OR `info.getLastVerified().isBefore(now() - maxAgeSeconds)`.

Auto Blocking is orthogonal to the four caches — it lives inside `BlockingHttpClient` and decides whether to even attempt an upstream fetch; covered in §6.

### `Maximum Component Age` semantics (quoted)

> "Maximum component age is only applicable for proxy repositories. When the server receives a request for a component, the remote repository is not checked for a modified version of the component until the cached component is older than the number of minutes configured in this field."
> — `help.sonatype.com/en/configurable-repository-fields.html`

> "A value of -1 will prevent the proxy from ever checking for new versions, which in most cases is not desirable. A value of 0 will always check for new versions."

The release-policy repo default is **-1** because release coordinates are immutable; snapshot-policy default is **1440**.

### `Maximum Metadata Age` semantics

> "When a client requests a version of a component not already cached and the metadata max age has expired, Nexus Repository will ask the remote what versions are available for the component."

The non-obvious detail (also from the same doc page):

> "For component metadata, Nexus Repository honors whichever value between *Maximum component age* and *Maximum metadata age* is greater before rechecking."

So setting `metadataMaxAge = 0` while leaving `contentMaxAge = -1` does NOT actually re-fetch metadata. Both must be aligned.

### Negative Cache TTL semantics

> "Nexus Repository will cache this result before attempting another request for the missing component."

Important nuances from the source:

- The negative cache stores the full `Status` (not just the code). A subsequent request returns a `Response.Builder().status(status).build()` — body is empty.
- `invalidate(key)` happens on **any successful response**, which means a single successful upload to a hosted repo joined to the same path key invalidates the proxy's negative entry.
- `invalidateSubset(parentKey)` exists for hierarchical keys — npm uses this to invalidate everything under a tarball-name prefix.
- The negative cache is **skipped while the upstream is `AUTO_BLOCKED_UNAVAILABLE` or `BLOCKED`**. Without this skip, the first failure during an outage would poison the NFC for 24 hours.

## 3. Request collapsing (single-flight)

The mechanism is `Cooperation2` (current generation; older `Cooperation` deprecated in 3.41 per the package comment). The implementation in `ScopedCooperation2Support.java`:

```java
private final ConcurrentMap<String, CooperatingFuture<?>> localFutures = new ConcurrentHashMap<>();

public R cooperate(final String action, final String... nestedScope) throws IOException {
  CooperationKey cooperationKey = CooperationKey.create(scope, action, nestedScope);
  CooperatingFuture<R> myFuture = new CooperatingFuture<>(cooperationKey, config);
  String scopedKey = cooperationKey.getHashedKey();

  CooperatingFuture<R> theirFuture = localFutures.putIfAbsent(scopedKey, myFuture);
  if (theirFuture == null) {
    try {
      return myFuture.call(this::perform);              // we are the lead thread
    } finally {
      localFutures.remove(scopedKey, myFuture);
    }
  } else {
    return theirFuture.cooperate(this::perform);        // wait for lead
  }
}
```

Configuration (from `ProxyFacetSupport.configureCooperation`, line 222):

```java
@Value("${nexus.proxy.cooperation.enabled:true}") boolean cooperationEnabled,
@Value("${nexus.proxy.cooperation.majorTimeout:0s}") Duration majorTimeout,
@Value("${nexus.proxy.cooperation.minorTimeout:30s}") Duration minorTimeout,
@Value("${nexus.proxy.cooperation.threadsPerKey:100}") int threadsPerKey
```

- **Key structure** (`ProxyFacetSupport.getRequestKey`): `path + '?' + parameters` — same as the cache lookup key. The cooperation key is then namespaced by `repository.getName() + ":proxy"`, so two repos coincidentally requesting the same upstream URL do NOT cooperate (each repo has its own `Cooperation2` instance built in `buildCooperation(repository)`).
- **`threadsPerKey=100`**: if more than 100 threads try to attach to the same `CooperatingFuture`, request 101 gets an immediate `CooperationException` rather than queueing. This is the back-pressure knob.
- **`majorTimeout=0s`** (wait indefinitely) is for the lead thread — i.e. the lead never times out waiting for itself.
- **`minorTimeout=30s`** is for followers — after 30 s a follower un-attaches and re-checks the cache (`checkFunction` runs again, and if still stale the follower becomes a NEW lead and re-enters cooperation). This is a graceful failover for the case where the lead's request is slower than typical.

OSGi / Karaf concurrency: pre-3.78 each bundle had its own classloader but the `ConcurrentMap` is just a Java field on a per-repo singleton bean injected via Sisu / Spring, so the OSGi container does not get in the way of single-flight semantics. Post-3.78 it's plain Spring DI.

The `CooperatingFuture` is the `CompletableFuture` instance that holds the result. Lead-thread completion publishes via `CompletableFuture.complete(...)`, followers `get()` it. Crucially, **the value handed to followers is what the lead returned from `perform()`** — i.e. a `Content` object. If that `Content`'s payload is a single-use `InputStream`, followers cannot replay it. Hence the `TempContent` wrapper described above.

There is a distinct "datastore-based" path `DefaultCooperation2Factory` that picks between `LocalCooperation2` (single-instance, local in-memory) and a clustered implementation in Pro. Nexus 3 OSS uses `LocalCooperation2`. Nexus Pro HA uses a cluster-aware variant that stores intent in PostgreSQL so two front-ends don't both hit the upstream — this is not in OSS.

## 4. Negative caching

Source: `NegativeCacheFacetImpl.java` + `NegativeCacheHandler.java`.

**Scope.** Per-repo, per-path. Key implementation:

```java
public NegativeCacheKey getCacheKey(final Context context) {
  return new PathNegativeCacheKey(context.getRequest().getPath());
}
```

`PathNegativeCacheKey` overrides `equals/hashCode` on the path string and provides `isParentOf(other)` for hierarchical invalidation (used by `invalidateSubset`). Query parameters are NOT part of the key by default — formats that need parameter-aware NFC override `getCacheKey`.

**Storage.** JCache. Implementation in Nexus is Caffeine-backed via the `CacheHelper` (cache name `{repo}#negative-cache`, `CreatedExpiryPolicy.factoryOf(Duration(MINUTES, timeToLive))`). The entry is just a `<NegativeCacheKey, Status>` — the Status carries the HTTP code (always 404 in practice — see below) and reason phrase.

**Status codes that get cached.** Only 404. From `NegativeCacheHandler.isNotFound(response)`:

```java
private boolean isNotFound(final Response response) {
  return HttpStatus.NOT_FOUND == response.getStatus().getCode();
}
```

5xx, 401, 403, 429 are **not** put in the NFC. The intent: NFC is for "definitively missing" not "currently broken". The auto-block mechanism handles "currently broken" separately.

**Invalidation triggers.**

1. **Automatic**: any subsequent 2xx response on the same path → `negativeCache.invalidate(key)`.
2. **Replication writes (Pro)**: on a successful pull-replication, NFC entry is invalidated explicitly.
3. **Manual**: REST `POST /service/rest/v1/repositories/{repo}/invalidate-cache` calls `invalidateProxyCaches()` which calls `NegativeCacheFacet.invalidate()` (clears all entries) and bumps the cache-token (which renders all content cache info stale).
4. **TTL expiration**: 1440 minutes default.

**Skip conditions** (NFC is NOT populated when):

- The repo's `HttpClientFacet` reports `AUTO_BLOCKED_UNAVAILABLE` or `BLOCKED`.
- The request is a pull-replication request (Pro).
- A `MISSING_BLOB_SKIP_NEGATIVE_CACHE` attribute is set — this happens when the DB row says the asset exists but the blob is missing on disk (typically post-restore corruption); Nexus chooses to re-fetch rather than mask the discrepancy with a 404.

## 5. Conditional requests

Confirmed by direct source inspection (`ProxyFacetSupport.buildFetchHttpRequest`, line ~668):

```java
protected HttpRequestBase buildFetchHttpRequest(final URI uri, final Context context, final Content stale) {
  HttpRequestBase request = buildFetchHttpRequest(uri, context);
  if (stale != null) {
    final DateTime lastModified = stale.getAttributes().get(Content.CONTENT_LAST_MODIFIED, DateTime.class);
    if (lastModified != null) {
      request.addHeader(HttpHeaders.IF_MODIFIED_SINCE, DateUtils.formatDate(lastModified.toDate()));
    }
    final String etag = stale.getAttributes().get(Content.CONTENT_ETAG, String.class);
    if (etag != null) {
      request.addHeader(HttpHeaders.IF_NONE_MATCH, ETagHeaderUtils.quote(etag));
    }
  }
  ...
}
```

- **When sent**: every time `fetch` is called with a non-null `staleContent`. That happens precisely when the asset exists in the cache but `isStale` returned true — i.e. the max-age window elapsed.
- **What's stored as the validator**: two asset attributes, `Content.CONTENT_LAST_MODIFIED` (parsed as `DateTime` from the `Last-Modified` response header) and `Content.CONTENT_ETAG` (extracted via `ETagHeaderUtils.extract`, which strips the surrounding quotes and any weak-validator prefix). Both are persisted alongside the asset in the component DB.
- **304 handling** (`build3xxResponseContent`):

  ```java
  if (isUnmodified) {
    checkState(stale != null, "Received 304 without conditional GET (bad server?) from %s", uri);
    indicateVerified(context, stale, cacheInfo);
  }
  ```

  `indicateVerified` (in `ContentProxyFacetSupport`) updates the asset's `lastVerified` to "now" via `assets().with(asset).markAsCached(cacheInfo)`. No blob rewrite, no DB row replacement — just a timestamp bump. This is exactly the conditional-GET fast path Pantera's plan calls out for `maven-metadata.xml`.
- **For maven-metadata.xml specifically**: the same mechanism applies. The `MavenProxyFacet` does not override `buildFetchHttpRequest`, so metadata fetches send the same conditional headers when the cached metadata has an `ETag` / `Last-Modified` attribute. Maven Central reliably returns `Last-Modified` on `maven-metadata.xml`, so the 304 path is hot in practice.
- **A known bug**: NEXUS-19404 (referenced in search) — for yum `repodata/repomd.xml`, Nexus 2 returned 304 even when content had changed. Nexus 3 handles this correctly; the bug history shows Sonatype is aware conditional GET correctness matters.

## 6. Upstream HTTP client

**Library.** Apache HttpClient (the 4.x series — `org.apache.http.client.HttpClient`, `org.apache.http.impl.client.CloseableHttpClient`). Confirmed by source imports and by Sonatype's debug-logging article: "All outbound HTTP requests Nexus Repository 3 makes use Apache httpclient libraries" [6].

**HTTP version.** HTTP/1.1 only. Apache HttpClient 4.x does not implement HTTP/2 in the synchronous client; Nexus does not pull in `httpclient5` or `async`. No documentation reference to HTTP/2 anywhere.

**Pool sizing.** Global HttpClientManager has a `maxConnections` and `maxConnectionsPerRoute` knob exposed in System → HTTP settings. The defaults are not documented as constants; the HTTP-config docs only quote the request-side knobs.

**Default knobs from `help.sonatype.com/en/http-request-and-proxy-settings.html`** (quoted):

- Connection/Socket Timeout: **30 seconds**
- Connection/Socket Retry Attempts: **2** (HttpClient `DefaultHttpRequestRetryHandler` with retry=2)
- Request Timeout: **20 seconds**
- Keep Alive Duration: **30 seconds**
- Buffer Size: **8 KiB**

**429 / 503 handling.** Source-level: a 503 is `>= 500` so it triggers `AutoBlockConfiguration.shouldBlock` (returns true). A 429 (Too Many Requests) is < 500 and is NOT in the auto-block set:

```java
public boolean shouldBlock(final int statusCode) {
  return statusCode == SC_UNAUTHORIZED || statusCode == PROXY_AUTHENTICATION_REQUIRED || statusCode >= 500;
}
```

So a 429 from Maven Central propagates as a 429 to the client — Nexus does not auto-block on rate-limit responses out of the box. (Note: some format-specific subclasses do override `AutoBlockConfiguration` — Docker has its own `qualifier="docker"` AutoBlockConfiguration to handle Docker Hub's idiosyncratic 401 flow, see `BearerHttpClientFacet`.)

### Auto Blocking — mechanism

`BlockingHttpClient` (decorator over `CloseableHttpClient`):

```java
public BlockingHttpClient(...) {
  ...
  autoBlockSequence = new FibonacciNumberSequence(Time.seconds(40).toMillis());
}

protected CloseableHttpResponse filter(HttpHost target, Filterable filterable) throws IOException {
  if (blocked) {
    throw new RemoteBlockedIOException("Remote Manually Blocked");
  }
  DateTime blockedUntilCopy = this.blockedUntil;
  if (autoBlock && blockedUntilCopy != null && blockedUntilCopy.isAfterNow()) {
    throw new RemoteBlockedIOException("Remote Auto Blocked until " + blockedUntilCopy);
  }
  try {
    CloseableHttpResponse response = filterable.call();
    int statusCode = response.getStatusLine().getStatusCode();
    if (autoBlockConfiguration.shouldBlock(statusCode)) {
      ...
      updateStatusToUnavailable(getReason(statusCode), statusCode, target);
    } else {
      updateStatusToAvailable();
    }
    return response;
  } catch (IOException e) {
    if (isRemoteUnavailable(e)) {
      updateStatusToUnavailable(getReason(e), null, target);
    }
    throw e;
  }
}
```

- **Block trigger**: first 5xx / 401 / 407 OR first I/O exception that isn't `ConnectionPoolTimeoutException`. There is NO consecutive-failure threshold in OSS — a single qualifying failure blocks (this is documented imprecisely in [5], which says "three attempts" but [5] is the 2010 announcement post; the OSS source as of 3.x is single-failure trip). The HttpClient retry handler still gets its 2 retries first, so in practice it's 3 attempts to the wire then a block.
- **Block duration**: `blockedUntil = DateTime.now().plus(autoBlockSequence.next())`. `FibonacciNumberSequence(40s)` yields the sequence **40 s, 40 s, 80 s, 120 s, 200 s, 320 s, 520 s, 840 s, 1360 s, 2200 s, 3560 s …** (starts with two 40s because the seed is `(start, start)`). There is no cap in the OSS source — sequence grows unbounded until success. The 2010 blog claims a 60-minute cap but the modern code is uncapped; the cap may exist in Pro.
- **HEAD probe**: while blocked, `scheduleCheckStatus(uri, blockedUntil)` spawns a daemon thread `CheckStatus` that `Thread.sleep`s until `blockedUntil` and then executes `new HttpHead(uri)`. The HEAD goes through `filter()` itself, so its result updates the status. If success, `updateStatusToAvailable` clears `blockedUntil` and resets the sequence. If failure, the sequence increments.
- **Effect on in-flight requests**: while blocked, every call to `filter()` throws `RemoteBlockedIOException("Remote Auto Blocked until <ts>")` immediately — no upstream connection is attempted. This is the amplification-prevention property: 1000 concurrent requests during an outage produce 0 upstream calls.
- **Coalescing with Cooperation**: when the block trip happens, the lead thread sees the exception. Followers waiting on `CooperatingFuture` get the exception propagated. They do NOT each individually re-probe — the block is global per-repo.

The negative cache + auto block interaction is the key safety property: `NegativeCacheHandler.isRemoteBlocked(context)` checks the HttpClientFacet status and **skips inserting 404 entries during a block**. Otherwise a single failure during an outage would generate millions of false-404 NFC entries.

## 7. Streaming vs buffering

This is the most surprising design choice. The implementation:

1. **Lead thread, single-reader case**: `fetch()` returns a `Content(HttpEntityPayload(response, response.getEntity()))`. The payload's `openInputStream()` calls `entity.getContent()` which streams from the live HTTP response. `store(context, remote)` consumes that stream into the blob store (chunked write, digests computed on the fly via `DigestInputStream`). The lead's own client response is then served from the just-written blob.

2. **Cooperating followers**: `CooperatingFuture` shares a single `Content` reference. If the lead returned the live `HttpEntityPayload`, only one follower could read it. The path that fixes this is in `doGet`:

   ```java
   remote = fetch(context, content);
   if (remote != null) {
     content = store(context, remote);
     if (remote.equals(content)) {
       // remote wasn't stored; make reusable copy for cooperation
       content = new TempContent(remote);
     }
   }
   ```

   `TempContent`:

   ```java
   class TempContent extends Content {
     public TempContent(final Content remote) throws IOException {
       super(cachePayload(remote), remote.getAttributes());
     }
     private static Payload cachePayload(final Content remote) throws IOException {
       try (InputStream in = remote.openInputStream()) {
         return new BytesPayload(toByteArray(in), remote.getContentType());
       }
     }
   }
   ```

   `toByteArray` is `com.google.common.io.ByteStreams.toByteArray` — buffer-the-whole-body-in-memory. So when cooperation is triggered (>= 2 concurrent clients), the lead reads the full body into a `byte[]` and shares that.

3. **Common case (normal proxy fetch with store)**: `store(context, remote)` returns the stored asset's content (read from the blob store) — `remote.equals(content)` is false — and **NO byte-array buffering happens**. The lead serves from the just-written file; followers will, after `checkFunction` re-runs, find the asset cached and read it from the blob store directly.

So the effective behavior:

- **Lead-thread cooperation common-case path**: stream upstream → write blob → followers fall through to cache lookup → read blob. Each follower gets its own blob-store input stream. No in-memory full-body buffering.
- **Edge case (remote not stored)**: full-body in-memory buffer in `TempContent`. For very large artifacts this is a heap-pressure risk; Nexus mitigates by making the "remote not stored" path rare (only some npm / weird format paths).

There is **no chunked-streaming-while-writing pipe** that lets follower A start reading bytes the moment lead has written them but before lead is done. Followers wait until lead's `CooperatingFuture` completes, then re-check the cache and stream from the blob. The latency cost is: follower-perceived response time ≈ lead's full upstream-fetch-plus-store time.

## 8. Metadata handling

`maven-metadata.xml` semantics in Nexus 3:

1. **Path-level**: metadata is just another asset on a known path. The `MavenContentProxyFacet` (`public/common/components/formats/nexus-repository-maven/src/main/java/org/sonatype/nexus/content/maven/internal/recipe/MavenProxyFacet.java`) overrides `getCacheController(context)` to return the `METADATA` controller for paths matching the maven-metadata regex; everything else uses the `CONTENT` controller.

2. **Refresh policy**: governed by `Maximum Metadata Age` (default 1440 min). On a request whose `lastVerified` is older than `metadataMaxAge`, Nexus issues a conditional GET (`If-Modified-Since` if available, `If-None-Match` if available). On 304, `indicateVerified` bumps `lastVerified` only.

3. **Snapshot vs release**:
   - Release-policy proxy default `contentMaxAge = -1` (never refresh component blobs) but `metadataMaxAge = 1440` (refresh metadata daily).
   - Snapshot-policy proxy default `contentMaxAge = 1440` AND `metadataMaxAge = 1440`. Both refresh daily.
   - **For snapshots**, the actual artifact filename embeds a timestamp (`bar-1.0-SNAPSHOT-20230101.123456-7.jar`) and is itself effectively immutable; the metadata is what changes ("latest snapshot timestamp"). The 1440-min default keeps the metadata in sync with upstream within a day.

4. **Group repos and metadata merging**: `MavenContentGroupFacetImpl.merge(...)` fetches metadata from all members and merges versions (preserving Maven's `RepositoryMetadataMerger` semantics — union of `<version>` entries, `<latest>` and `<release>` are recomputed). Merged metadata is cached at the group repo level under the group's cache token; invalidating the group cache (via `invalidateGroupCaches()`) bumps the group token without rewriting member assets.

5. **Quirk surfaced by docs**: "For component metadata, Nexus Repository honors whichever value between *Maximum component age* and *Maximum metadata age* is greater before rechecking." This means setting `metadataMaxAge=0` (always check) but leaving `contentMaxAge=-1` (never check) results in metadata NEVER being checked. To force daily metadata refresh you must set BOTH or rely on the greater of the two.

6. **The Maven Metadata Rebuild Task** (introduced 3.40 for SQL-based deployments) is for **hosted** repos — it rebuilds local `maven-metadata.xml` from the components in the hosted repo. For proxies this task is not relevant; the metadata is always whatever upstream serves.

## 9. Storage layout

**Blob store implementations**, all behind `BlobStore` interface:

- **File** (default for new installs): each blob written to `<blob-store-root>/content/vol-XX/chap-YY/<uuid>.bytes` plus `.properties` sidecar (atime, ctime, size, sha1, content type, repo name, soft-delete marker `deleted=true`). The `vol-XX/chap-YY` directory sharding caps file count per directory.
- **S3 / Azure / GCS**: same blob-id + properties pattern, but as S3 objects. Reads use HTTP GET; deletes are S3 DELETE.
- **Group blob store**: combines members with fill policies "Write to First" or "Round Robin". Reads check each member in order until found.

**Soft delete.** Cleanup tasks do not physically delete files; they mark `deleted=true` in the `.properties` file. A subsequent "Compact Blob Store" task is what actually frees disk:

> "Cleanup tasks 'soft delete' components by flagging them for removal, and components still consume space but may be recovered when needed."

This separates the cheap user-facing operation (deleted from search and access) from the expensive operation (filesystem unlink / S3 DELETE). Recovery before compaction is possible via the "Repair - Reconcile component database from blob store" task, which scans `.properties` and re-creates database rows from blob metadata.

**Component database.** Three eras:

1. **OrientDB** (3.0 through 3.70.x): embedded graph DB. All component metadata (asset rows, blob refs, attributes, search index) lived in OrientDB files alongside the blob store.
2. **H2** (3.31+ optional, default for new OSS installs 3.71+): single-file embedded RDBMS in the same process. Limits: "maximum 200,000 requests per day and 100,000 components" per Sonatype guidance.
3. **PostgreSQL** (3.31+ for Pro, required for HA): external. All component metadata, user/role tables, repository configs, search indexes (`tsvector`-style FTS on names), Quartz JDBC store.

OrientDB was deprecated August 2024; 3.71+ does not support it; users who can't migrate stay on 3.70.x.

The blob is referenced from the asset row via `(blobStoreName, blobId)`. The same blob is theoretically shareable across assets but in practice each asset writes its own blob (no content-addressed dedup at the blob layer).

## 10. Group repository resolution

**Order: strict serial, first match wins.** From `help.sonatype.com/en/repository-types.html`:

> "The order of the repositories within a Group repository matters. When requests come in, Sonatype Nexus Repository will search for the component in the first repository on the list before continuing sequentially to the second, third, etc."

From `GroupHandler.getFirst(context, members, dispatched)`:

```java
for (Repository member : members) {
  // skip offline repositories
  if (member.getConfiguration() != null && !member.getConfiguration().isOnline()) {
    continue;
  }
  if (dispatched.contains(member)) {
    continue;  // prevent circular dispatch for nested groups
  }
  dispatched.add(member);
  final ViewFacet view = member.facet(ViewFacet.class);
  final Response response = view.dispatch(request, context);
  if (isValidResponse(response)) {
    return response;
  }
}
return notFoundResponse(context);
```

Properties:

- **Sequential** (single for-loop, no fan-out).
- **Offline members skipped** but `AUTO_BLOCKED_UNAVAILABLE` is NOT skipped at the group level — the dispatch happens, the proxy member throws `RemoteBlockedIOException`, the response is non-2xx, the loop continues to the next member. This means auto-blocked members add latency proportional to their position in the group.
- **Circular dispatch protection** via the per-request `dispatched` set.
- **Cooperation runs PER MEMBER**: each proxy member has its own `Cooperation2`, so cooperation is not group-wide.

For metadata-format paths (Maven `maven-metadata.xml`, npm `package.json`), `MergingGroupHandlerSupport` overrides `getFirst` and fetches from ALL members in parallel-ish (actually still sequential in OSS), then merges via format-specific mergers. The merged result is cached at the group repo with the group's cache token.

The first-match-wins design is the source of a documented footgun: a community post titled "NPM Group Repository Fetching Metadata from Second Proxy Despite Artifact Found in First Proxy" — for tarball assets the first-match works; for metadata (which is merge-not-first-match), the second proxy is queried even after the first succeeded. The two paths have different group dispatch logic.

## 11. Observability minimum

**Metrics endpoints**:

- `/service/metrics/data` — Dropwizard Metrics JSON (gauges, meters, timers, histograms).
- `/service/metrics/healthcheck` — Dropwizard health checks.
- `/service/metrics/ping` — liveness.
- `/service/metrics/threads` — thread dump.
- `/service/metrics/prometheus` (pre-3.81) / `/service/rest/metrics/prometheus` (3.81+) — Prometheus exposition format.

All require `nx-metrics-all` privilege (basic auth or token).

**JMX**: standard Dropwizard JMX reporter exposes all `metrics` registry entries under `metrics:name=...`. JVM defaults (`java.lang:type=Memory` etc.) are visible. Karaf-era installs added Karaf MBeans; Spring-era (3.78+) drops those.

**Healthy day signals**:
- `org.eclipse.jetty.server.HttpConnectionFactory` connection counts steady, no QueuedThreadPool starvation.
- `BlobStore.totalSize` growing slowly.
- `org.sonatype.nexus.repository.cache.NegativeCacheFacetImpl.<repo>#negative-cache` size in the low thousands per repo.
- `RemoteConnectionStatus` per proxy in `AVAILABLE` state.
- Cooperation thread count per key stays low (typically 1–3 for hot artifacts).

**Incident signals**:
- `RemoteConnectionStatus` flipping to `AUTO_BLOCKED_UNAVAILABLE` with `blockedUntil` advancing through Fibonacci values.
- `BlockingHttpClient` log line `Repository status for {name} continued as AUTO_BLOCKED_UNAVAILABLE until {ts}` — search this in `nexus.log`.
- `Cooperation` thread count > 100 on a single key → backpressure exception, request rejected.
- DB connection pool saturation (`HikariCP` if Pro/Postgres).
- Negative cache size spike (signals upstream catalog mismatch or scanning bots).

Sonatype's own guidance for proxy-node-scaling [9] recommends:
- 16 cores / 32 GB RAM for read-heavy.
- JVM `-Xms6G -Xmx6G -XX:MaxDirectMemorySize=15530M`.
- Jetty thread pool = 400.

## 12. Throttling resilience

The combined defense:

1. **HttpClient retry**: 2 retries (default) for transient I/O failures via `DefaultHttpRequestRetryHandler`.
2. **AutoBlock trip**: after retries exhausted with 5xx / 401 / 407 / I/O exception, the proxy is auto-blocked for `Fib(40s)`.
3. **Block window**: every subsequent request to that proxy fast-fails with `RemoteBlockedIOException`, no wire call. Followers in cooperation get the same exception.
4. **Recovery probe**: a single daemon HEAD request at the block-expiry instant. On success, sequence resets. On failure, sequence advances.
5. **NFC pause**: negative cache writes are skipped while blocked.

The math: a 60-second Maven Central outage with a 100 r/s steady load:
- Without auto-block: 100 r/s × 60 s = 6 000 upstream failures.
- With auto-block (default `Fib(40s)`): first request fails → block 40 s → 4 000 client requests served `RemoteBlockedIOException` from in-memory → 1 HEAD at 40 s mark; if upstream still down, block another 40 s → 1 more HEAD; if up, full recovery. Upstream contact: **at most 3 requests** in the 60-second window (initial + 2 HEAD probes).

This is the core amplification-suppression property and the reason Nexus's proxy is resilient. Pantera's `RateLimitedClientSlice` is an approximation; the real architectural difference is that Nexus **stops attempting to call upstream entirely** rather than throttling. Stopping is qualitatively different from throttling.

**Coalescing**: auto-block + cooperation together coalesce a thundering herd during an outage. Cooperation alone would mean N=1 upstream call per key per outage window; auto-block + cooperation means N=1 upstream call per repo per Fibonacci block duration, across all keys.

## Non-obvious design decisions

**1. Negative cache uses JCache, not a custom datastructure.**
Nexus delegates NFC storage to `javax.cache.Cache` via a `CacheHelper` indirection. This is the same `CacheHelper` that backs other internal caches. The choice means NFC entries:
- Have proper TTL eviction (no manual sweeper thread).
- Can be cluster-coordinated by swapping the JCache provider (Pro HA uses a Hazelcast-backed provider; OSS uses Caffeine).
- Get a per-repo `Cache` instance (cache name `{repo}#negative-cache`), enabling drop-the-whole-repo invalidation via `cacheHelper.maybeDestroyCache(name)` — used on every config update.

This is more architecturally clean than a `ConcurrentHashMap` with a sweeper, and the cost is one more interface boundary. For Pantera: matters if you ever want multi-region NFC sharing.

**2. The lead thread always wins; followers re-validate the cache after waking.**
Followers in `CooperatingFuture.cooperate(perform)` don't blindly take the lead's result. The `checkFunction` re-runs `maybeGetCachedContent` + `isStale` after lead completion. This handles the case where the lead's `store()` succeeded but the result is staler than what's now in the cache (e.g. another non-cooperating writer beat lead during the in-flight). The cost: an extra DB lookup per follower.

This is also how `minorTimeout=30s` graceful failover works: when a follower's wait times out, `checkFunction` runs, and if the cache STILL has stale content, the follower abandons its position in the cooperation and becomes a new lead (going around again). Lead never times out (`majorTimeout=0s`).

**3. Auto-block sequence is uncapped in OSS code; the 2010 blog post saying "60-minute cap" is out of date.**
`FibonacciNumberSequence(Time.seconds(40).toMillis())` has no upper bound and no cap is applied in `updateStatusToUnavailable`. A multi-day outage will push `blockedUntil` arbitrarily far into the future. Manual unblock is via REST `PUT /service/rest/v1/repositories/{repo}/run-health-check` or `DELETE /service/rest/v1/repositories/{repo}/proxy/health-check`. For Pantera: a cap with manual override + alerting is reasonable; uncapped is OK if your unblock path is reliable.

**4. `TempContent` materializes upstream body into a `byte[]` — only on the cooperation edge case.**
In the common case (`store()` returns a different `Content` than the upstream), there is no full-body buffer; followers read from the blob store. The `TempContent` path is only triggered when `store()` returns the upstream content unchanged, which is a format-specific corner case. This is a beautiful design: optimistic streaming with a buffered fallback only when needed, controlled by the `equals()` contract on `Content`.

**5. Conditional GET is "lazy" — `If-Modified-Since` / `If-None-Match` are sent only on stale path, never on cold-miss.**
This is correct (no validator exists on cold miss) but means the first hit of a new asset always does a full transfer. There is no upstream `HEAD` probe before `GET` — Nexus removed the pre-fetch HEAD that older proxy designs use (cf. Pantera's M5 cooldown-HEAD elimination, which arrived at the same conclusion independently). For Pantera: aligning with Nexus on this is the right call.

**6. Negative cache 404-only, not 5xx.**
The decision to NOT cache 5xx in NFC is the difference between caching "definitely missing" and caching "transient broken". The latter would be a correctness disaster; Nexus avoids it deliberately. Auto-block handles transient broken at a different layer. The clean separation is worth copying.

**7. The auto-block check is INSIDE the HttpClient decorator, not at the proxy facet boundary.**
`BlockingHttpClient.filter()` is called for every upstream request, including the format-specific ones (e.g. `MavenContentProxyIndexFacet` fetches index files via the same path). This means the block applies to ALL upstream traffic for that repo, not just artifact fetches. For Pantera: rate-limiting at the HTTP-client slice (as currently done) achieves the same effect; the design pattern is equivalent.

## What I could not determine

- **Exact `maxConnections` / `maxConnectionsPerRoute` defaults**. The Sonatype docs describe the System → HTTP settings UI but do not quote the integer defaults. The HttpClient `PoolingHttpClientConnectionManager` defaults (20 / 2 per route) are likely used unless `HttpClientConfiguration` overrides them — I could not locate the override in the OSS source within reasonable search depth.
- **Cluster-aware Cooperation2 implementation details (Nexus Pro HA).** The `Cooperation2Factory` interface has an OSS `LocalCooperation2` and is described as having a clustered alternative; the clustered impl is not in `nexus-public` and I have no authoritative source.
- **Exact pg_cron-equivalent or scheduling primitive in 3.78+ Spring era.** Older Quartz-based scheduling is documented; the migration off OSGi may or may not have moved the scheduler. The blob-compaction task definitely uses Quartz JDBC.
- **Whether AutoBlock cap exists in Nexus Pro.** OSS has no cap; the 2010 blog mentions 60 min; Pro behavior unverified.
- **Streaming behavior of Vertx-vs-Jetty differences.** Nexus runs on Jetty (Karaf bundle until 3.78, embedded Jetty post-3.78). I did not find an authoritative Jetty version reference in 3.78+; release notes mention "Spring Framework" but not the Jetty version.
- **Default Apache HttpClient retry handler behavior for 429.** Source level `DefaultHttpRequestRetryHandler` retries on `IOException` only, not on 4xx — 429 propagates. Nexus does not register a retry handler for 429 by default; some format facets may override.
- **`Cooperation2` `threadsPerKey=100` enforcement: is it eager-reject or queue-with-timeout?** Source-level it's `putIfAbsent` + counter on the `CooperatingFuture`; I could not verify whether thread 101 throws synchronously or queues with `minorTimeout`. Test sources (`CooperatingFutureTest`) would clarify but I did not fetch them.
