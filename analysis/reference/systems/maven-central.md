# Maven Central infrastructure — reference architecture study

## TL;DR

Maven Central is the canonical Java/JVM artifact repository operated by Sonatype.
Today (May 2026) its production read path looks like:

`mvn`/`gradle` client → DNS `repo.maven.apache.org` (CNAME `repo1.maven.org`)
→ **Cloudflare anycast CDN** (HTTP/2, TLS 1.3, IPs `104.18.18.12`/`104.18.19.12`,
cert by Google Trust Services) → **AWS S3 origin** (bucket served behind the CDN,
visible in 404 bodies as `x-amz-error-code: NoSuchKey` /
`x-amz-error-detail-key: maven2/...`).

This is a **substantial change from the picture in older publicly-cited material**.
The Fastly case study and the Sonatype 2014 changelog entry that introduced HTTPS
both name Fastly as the CDN, but live measurements in May 2026 show
`server: cloudflare`, Cloudflare `cf-ray`/`cf-cache-status` headers and Cloudflare
anycast IPs. **Sonatype has not publicly announced a CDN migration**, so the
public-facing posts about "our CDN partner Fastly" are now stale; the
infrastructure has been migrated to Cloudflare without a blog post, and observable
HTTP responses are the only public artefact of that change.

Adjacent services run on different stacks:
- `search.maven.org` → AWS CloudFront → S3 (`x-amz-cf-pop: FRA60-P12`, `server: AmazonS3`).
- `central.sonatype.org` (docs site) → AWS CloudFront → S3 (`server: AmazonS3`, `x-amz-cf-pop: FRA60-P10`).
- `central.sonatype.com` (Publisher Portal) → Next.js app, `cache-control: private, no-cache, no-store`.

Critical for a proxy product:
- Cache HIT latency from a European client is **60-90 ms** for a POM/metadata
  and **130-230 ms** for a 3 MB JAR (measured, Cloudflare HIT). That is the cost
  Pantera must match — there is no slow origin to outrun.
- Artifacts are **immutable by contract** but Maven Central **does not send any
  `Cache-Control` header**. Only `ETag` + `Last-Modified` + Cloudflare-internal
  `age` are exposed. Downstream proxies that want immutability semantics must
  encode them locally; the upstream will not tell you.
- `maven-metadata.xml` is the same: no `Cache-Control`, no explicit TTL. Mutable
  in practice; clients must treat as fresh-on-fetch.
- Rate limiting (HTTP 429) has been **production-enforced since 2024** and was
  significantly tightened in late 2025 / early 2026. The current published policy
  is intentionally vague on numbers ("top few percent of traffic patterns") and
  explicitly warns that infrastructure providers / CI tenants / proxies will be
  rate-limited at the platform level, not just per-IP. Initial blocks are short
  (minutes), escalating to a **maximum 24-hour block** after repeated
  overconsumption.

## Sources [11] URL — retrieved 2026-05-14

1. `https://central.sonatype.org/faq/429-error/` — current rate-limit policy doc
   (the canonical reference). Confirms 24h max block, escalating block durations,
   non-punitive intent, and that "extreme over-limit activity, attempts to evade
   limits, or clear abuse may result in longer or permanent blocks without the
   same gradual ramp."
2. `https://central.sonatype.org/faq/429-no-repo-manager/` —
   "if you do not have a repository manager" path. Sonatype's repository-manager
   recommendation.
3. `https://central.sonatype.org/faq/429-infrastructure-provider/` —
   infrastructure-provider / CI / cloud / scanner path. Tells platforms that
   rate limits apply at the network edge, not per-tenant.
4. `https://central.sonatype.org/faq/429-shared-egress/` — confirms that
   `repo.maven.apache.org` is a CNAME for `repo1.maven.org` and that both
   resolve to the same service.
5. `https://central.sonatype.org/faq/429-contact-support/` — what Sonatype
   support requires (egress IP first), what they cannot look up (org name,
   account name).
6. `https://central.sonatype.org/changelog/` — official Central changelog.
   Confirms Fastly as the historical CDN partner (HTTPS launched 2014-08-03).
7. `https://central.sonatype.org/news/archive/` — news archive 2014-2025.
8. `https://central.sonatype.org/central-status/` — Sonatype's own
   troubleshooting / "blocked due to past violation" page. Mentions blocking
   IPs that consume "hundreds of GBs or TBs of data every month."
9. `https://central.sonatype.org/publish/requirements/immutability/` — the
   immutability contract for published artifacts.
10. `https://www.sonatype.com/blog/maven-central-and-the-tragedy-of-the-commons` —
    Brian Fox, June 2024: introduces the "1% of IPs = 83% of bandwidth" and
    "75% of traffic from hyperscale cloud customers" figures.
11. `https://www.sonatype.com/blog/beyond-ips-addressing-organizational-overconsumption-in-maven-central`
    — follow-up: shift from per-IP to per-organization enforcement.
12. `https://www.sonatype.com/blog/open-is-not-costless-reclaiming-sustainable-infrastructure`
    — 2026 framing: rate limit as emergency brake, not strategy.
13. `https://blog.gradle.org/maven-central-mirror` — Gradle's August 2025
    "Good Neighbors" guide on reducing Maven Central traffic.
14. `https://github.com/apache/maven-resolver/issues/1071` (MRESOLVER-396) —
    native transport retry-on-429 with Retry-After.
15. `https://github.com/DependencyTrack/dependency-track/issues/3986` —
    DependencyTrack handling of Maven Central 429.
16. `https://github.com/renovatebot/renovate/discussions/43146` — example of
    Renovate hitting the limit; reports the actual server-side message:
    *"Your IP has hit the rate limit with Maven Central. Too many requests."*
17. `https://www2.fastly.com/customers/sonatype` — Fastly case study (historical
    snapshot; "request traffic by 4x" growth metric).
18. Empirical HTTP-header probes against `repo.maven.apache.org` /
    `repo1.maven.org` / `search.maven.org` / `central.sonatype.com` /
    `central.sonatype.org` performed on 2026-05-14 from a European client.
    Used to confirm current CDN (Cloudflare for repo, CloudFront+S3 for
    everything else), absence of `Cache-Control`, presence of `ETag` and
    `Last-Modified`, S3 NoSuchKey on 404s, and HIT latency.

## 1. Public-facing architecture (CDN, origin, TLS)

### 1.1 The artifact read path (`repo.maven.apache.org` / `repo1.maven.org`)

DNS evidence:

```
repo.maven.apache.org. CNAME repo.apache.maven.org.
repo.apache.maven.org. CNAME repo.apache.maven.org.cdn.cloudflare.net.
repo1.maven.org.       CNAME repo1.maven.org.cdn.cloudflare.net.
                       A     104.18.18.12 / 104.18.19.12   (Cloudflare anycast)
```

TLS: Cloudflare-terminated, certificate issued by `C=US, O=Google Trust Services,
CN=WE1`, TLS 1.3 with `X25519MLKEM768` key exchange — i.e. Google's hybrid
post-quantum key agreement, available to Cloudflare customers since late 2024.
ALPN advertises HTTP/2 (`h2`); HTTP/3 advertised via `alt-svc: h3=":443"; ma=86400`.

HTTP response headers on a representative artifact
(`com.google.guava:guava:32.1.3-jre` JAR, 3 MB):

```
HTTP/2 200
content-type: application/java-archive
content-length: 632267
cf-ray: 9fba5ffc6d4511c6-TXL
cf-cache-status: HIT
accept-ranges: bytes
age: 2351265
etag: "3435b913691a5c1b173485a49850b1a8"
last-modified: Sun, 23 Jul 2023 19:44:57 GMT
x-checksum-md5: 3435b913691a5c1b173485a49850b1a8
x-checksum-sha1: b7263237aa89c1f99b327197c41d0669707a462e
server: cloudflare
alt-svc: h3=":443"; ma=86400
```

Things to notice:

- `server: cloudflare`, `cf-ray`, `cf-cache-status` — this is **Cloudflare**, not
  Fastly. The 2014 HTTPS-launch changelog and the published Fastly case study
  refer to a now-superseded architecture; the migration was made silently.
- `age: 2351265` is ~27 days. The Cloudflare edge node has held this artifact
  in cache for almost a month and revalidation has not been triggered. Consistent
  with the **immutable-by-contract** nature of release artifacts.
- The origin is AWS S3. We can prove this from a 404 response, where the
  CloudFront/Cloudflare frontend pass-through reveals the upstream
  S3 error structure:

  ```
  HTTP/2 404
  cf-cache-status: EXPIRED
  x-amz-error-code: NoSuchKey
  x-amz-error-detail-key: maven2/com/example/nonexistent/1.0.0/nonexistent-1.0.0.jar
  x-amz-error-message: The specified key does not exist.
  server: cloudflare
  ```

  The `x-amz-*` keys are S3-native, and `cf-cache-status: EXPIRED` means the 404
  was previously cached at the edge — i.e. Cloudflare **does cache 404s**.
- `x-checksum-md5` / `x-checksum-sha1` are passed through from S3 object metadata.
  Sonatype writes object checksums into S3 user-metadata at publish time so the
  CDN can echo them without a body parse.
- `accept-ranges: bytes` — range requests work, which is what Maven 3.9's
  parallel/resumable artifact transport relies on.

### 1.2 The search path (`search.maven.org`)

A separate stack: AWS CloudFront → S3 (`server: AmazonS3`, `via: 1.1 ...cloudfront.net (CloudFront)`,
`x-amz-cf-pop: FRA60-P12`). Sonatype kept the search frontend on AWS even after
moving the repo frontend to Cloudflare. The community has noted that the
search index lags publishing by **~4 hours** (Wicked Good Fall 2022 panel,
referenced from Sonatype blog material).

### 1.3 The Publisher Portal (`central.sonatype.com`)

Next.js application with `cache-control: private, no-cache, no-store,
max-age=0, must-revalidate`. This is the **upload** side of Maven Central
(OSSRH replacement). It is unrelated to the read path and is not relevant for
a downstream proxy product like Pantera; we mention it only to disambiguate
"`central.sonatype.com`" (portal) from "`central.sonatype.org`" (docs) from
"`repo.maven.apache.org`" / "`repo1.maven.org`" (artifact repo).

### 1.4 The documentation site (`central.sonatype.org`)

AWS CloudFront → S3. Static MkDocs site. Notably this is what serves the
429 FAQ; the 404 fallback when we requested a non-existent doc page returned
`Code: NoSuchKey ... RequestId: ... HostId: ...` — vanilla S3 website hosting.

### 1.5 Geographic edge

Cloudflare anycast routes per-client to the nearest POP. We were served from
`-TXL` (Berlin Tegel) on this run. The Cloudflare DC code is encoded in the
`cf-ray` suffix. Sonatype does not publish a list of POPs and does not need
to — it inherits the full Cloudflare footprint (300+ cities as of 2026).

## 2. Rate-limit policy (numbers, headers, recommended client behavior)

This is the single most important section for Pantera.

### 2.1 What Sonatype has officially published (current as of 2026-05-14)

The 429 FAQ at `central.sonatype.org/faq/429-error/` is **deliberately vague
on numbers**. The published thresholds are:

> "Today, enforcement is focused only on the highest-volume consumers — the
> top few percent of traffic patterns we see across Maven Central. Over time,
> those thresholds will move downward as we continue the transition toward a
> more sustainable consumption model."

A figure on the same page ("Real Maven Central traffic data. Dashed lines
indicate current thresholds.") shows where the cutoff sits but does not put
a number on the y-axis.

The numbers that **are** in print:

- "**83% of the total bandwidth of Maven Central is being consumed by just
  1% of the IP addresses**" — Brian Fox, June 2024
  (`sonatype.com/blog/maven-central-and-the-tragedy-of-the-commons`).
- "**75% of the total traffic to Central originates from hyperscale cloud
  customers**" — same post.
- "organizations downloading the same components **over half a million times
  per month**, not for a few isolated libraries, but for thousands of artifacts"
  — `sonatype.com/blog/beyond-ips-addressing-organizational-overconsumption-in-maven-central`.
- Sonatype's own troubleshooting page: blocking applies to consumers running
  "**hundreds of GBs or TBs of data every month**"
  (`central.sonatype.org/central-status/`).

### 2.2 Block durations

From the 429 FAQ, quoted verbatim:

> "Initial blocks are short and are meant to provide an early signal that
> something needs to change. If the pattern continues, new blocks may be
> triggered repeatedly, including multiple times in the same day. Over time,
> block durations increase. **For most overconsumption patterns, the maximum
> single block duration is 24 hours.** The 24-hour maximum is reached only
> after repeated overconsumption over many days."

> "Extreme over-limit activity, attempts to evade limits, or clear abuse may
> result in longer or permanent blocks without the same gradual ramp."

This is the closest Sonatype has come to a published SLA-shaped statement.
Note that **permanent blocks** are explicitly on the table for actors
considered abusive.

### 2.3 Response shape

Maven Central returns:
- HTTP status **429** ("Too Many Requests").
- A human-readable body. The text observed in the wild (Renovate discussion
  #43146): *"Your IP has hit the rate limit with Maven Central. Too many
  requests. Find out how to address this at
  https://www.sonatype.com/blog/maven-central-and-the-tragedy-of-the-commons"*

The presence of a `Retry-After` header is **inconsistent**:
- The Sonatype FAQ never confirms one will be sent.
- The Renovate discussion explicitly notes "*The logs do not mention a
  `Retry-After` header value, suggesting Maven Central may not have provided
  one in this response.*"
- Apache Maven Resolver issue MRESOLVER-396 / `apache/maven-resolver#1071`
  adds optional `Retry-After` handling on the assumption that it *might* be
  present — i.e. the upstream contract is "if present, respect it; otherwise
  back off conservatively."

For Pantera: **do not depend on `Retry-After`**. Implement local back-off with
jitter regardless of whether the header is set.

### 2.4 Recommended client behaviour

Cribbed from the FAQ and from the upstream resolver issue:

1. **Do not retry hard.** Quote: "In most cases, the right answer is not to
   retry harder. More retries usually make the problem worse."
2. Honour `Retry-After` if present.
3. Cache. The repeated theme across every Sonatype source is that a caching
   repository manager is the only durable solution. Quote (Gradle's "Good
   Neighbors" post citing Brian Fox): "we will start to work with our
   providers to implement throttling mechanisms aimed at the extremely heavy
   consumers, which are effectively abusing a community resource."
4. Do not bypass the cache. Sonatype calls out by name "scanners", "CI fleets
   that hit the same artifacts over and over", and "security tooling that
   behaves like bandwidth is free." Any "always check Maven Central" code path
   in a proxy is part of the problem.

### 2.5 Per-IP vs per-organization

The September-2024 follow-up post (`...overconsumption-in-maven-central`)
documents the move from per-IP to per-org enforcement: *"This marks a
strategic shift from isolated IP enforcement to a more comprehensive approach
that accounts for the real-world structure of how enterprises build and
deploy software today."*

This matters because corporate NAT and CI shared egress create false negatives
on per-IP throttling. Sonatype now maps the same activity across "hundreds or
thousands of IPs within a single organization" and throttles at that level.
**A proxy product whose customers all share an egress IP range (e.g. a cloud
service) will be evaluated at the platform level**, and the dedicated FAQ path
for "I am an infrastructure provider, CI provider, cloud provider, security
vendor, or large platform" explicitly tells those operators to contact
Sonatype directly, because "the right next step is to contact Sonatype so we
can understand the traffic pattern and discuss possible solutions together."

## 3. Cache headers served (Cache-Control, ETag, Last-Modified)

### 3.1 What is served on a release artifact

```
etag: "3435b913691a5c1b173485a49850b1a8"
last-modified: Sun, 23 Jul 2023 19:44:57 GMT
age: 2351265
x-checksum-md5: 3435b913691a5c1b173485a49850b1a8
x-checksum-sha1: b7263237aa89c1f99b327197c41d0669707a462e
```

What is **not** served:
- `Cache-Control` — absent.
- `Expires` — absent.
- `immutable` directive — absent.

The `ETag` is the MD5 of the artifact body (matches `x-checksum-md5`) — that is
the S3 default. So a conditional `If-None-Match`/`If-Modified-Since` revalidation
loop works correctly against the CDN. There is no upstream signal that a release
artifact is immutable; clients have to know this from the Maven Central contract.

The `age` header is Cloudflare-internal (seconds since the edge fetched from
origin), not a client-facing cache directive. It is informative but not
authoritative.

### 3.2 What is served on `maven-metadata.xml`

```
etag: "cc1d9ee31ff9ed6e77a43ca652c19375"
last-modified: Sun, 16 Nov 2025 12:55:35 GMT
age: 168
```

Same shape: no `Cache-Control`, no explicit TTL. The `last-modified` here is
recent (the file gets rewritten on every release publish), and `age` is small
(~3 minutes on this fetch). The Cloudflare edge is clearly caching it for short
windows but Sonatype gives no client directive about how long is safe.

### 3.3 What is served on a 404

```
HTTP/2 404
cf-cache-status: EXPIRED
last-modified: Sat, 11 Oct 2025 00:20:51 GMT
x-amz-error-code: NoSuchKey
```

So 404s **are** cached at the edge (some edges report HIT, some MISS, some
EXPIRED), and they pass S3 error metadata through. A proxy that does its own
negative cache can use these as positive-cacheability signals.

### 3.4 The immutability contract

From `central.sonatype.org/publish/requirements/immutability/`:

> "In order to provide reliable access to open source components **we do not
> remove or modify components once they are publicly available**. When a
> project includes a specific version of a component as a dependency, there
> is an inherent expectation that end-users will be able to build that
> project in a repeatable, reliable manner. A part of that expectation is
> that Maven Central will be able to provide every dependency exactly as
> they were originally published."

So the immutability is **a publishing-side contract enforced by Sonatype on
the publisher**, not an HTTP-side signal to the downstream consumer. The
Maven Central read path will respond identically to a "this artifact is
immutable" request and a "this artifact is mutable" request. SNAPSHOT
artifacts are published to a different repository (`oss.sonatype.org` /
`s01.oss.sonatype.org` historically, the Central Portal SNAPSHOTs endpoint
from January 2025) and are mutable.

## 4. Migration history and lessons learned

Timeline from the official changelog and news archive:

- Origin at Apache Software Foundation / Ibiblio.
- Contegix-hosted single machine, decommissioned **2011**.
- **2010-08**: European mirror provisioned.
- **2014-08-03**: HTTPS launched, "in coordination with our excellent CDN
  provider Fastly." This is the canonical public reference to Fastly.
- **2014-11**: Repository-side validation tightened (Ivy version ranges
  rejected).
- **2018-07**: HTTP/2 support added on Central.
- **2019-04 / 2019-11**: Non-canonical URLs deprecated and redirected.
- **2020-01-15**: Plain-HTTP access disabled; HTTPS only.
- **2021-02-25**: New OSSRH server `s01.oss.sonatype.org` for new projects.
- **2022-03-02**: "Maven Central Roadmap: A First Look" (next-gen Central
  Portal announced).
- **2022 (Jan)**: 6.2 PB / 51 B requests in a single month (Wicked Good Fall
  2022 panel material).
- **2023**: "estimated one trillion requests in 2023", 500k+ projects,
  12M+ versions (`sonatype.com/blog/the-history-of-maven-central-...`).
- **2024-06**: Brian Fox's "Tragedy of the Commons" post — formal
  announcement that throttling is being rolled out.
- **2024-09 (approx)**: per-org enforcement extension.
- **2025-01-14**: SNAPSHOT publishing via Central Portal (replacement for
  OSSRH).
- **2025-06-30**: OSSRH end-of-life. All Java publishing moves to
  `central.sonatype.com`.
- **2025-2026 (silent)**: artifact CDN migrated from Fastly to Cloudflare.
  No public Sonatype announcement; only discoverable from HTTP headers.
- **2026 (current)**: 429 FAQ rewritten; OpenSSF "Open Infrastructure Is
  Not Free, Part II" co-authored with PyPI, Rust Foundation, OpenJS.

What we cannot determine from public sources:
- The specific date of the Fastly → Cloudflare migration.
- The exact origin storage: S3 is provable from headers, but the bucket name,
  region, and replication scheme are not published.
- Whether the European/US/Asia segregation that existed in the Contegix era
  still exists or has been collapsed into Cloudflare anycast.

The "lessons learned" theme that runs through Sonatype's recent posts is
**not about reliability incidents** — Maven Central's uptime story is
extremely good, and the status-page incident history is short. The lesson
Sonatype is publishing instead is operational/economic: that a free,
unauthenticated, unlimited public registry is being **load-shedded by 1% of
its users**, that this is unsustainable, and that the answer is rate-limiting
plus a push to repository-manager-mediated consumption.

## 5. Why direct access is fast — latency budget

Empirical measurements from a European client (Berlin, residential network)
on 2026-05-14:

| Resource | Size | TTFB | Total | Speed |
|---|---|---|---|---|
| Guava 32.1.3 JAR (cache HIT) | 3.04 MB | 56 ms | 230 ms | 21 MB/s |
| Guava 32.1.3 POM | 12.8 kB | 85 ms | 85 ms | n/a |
| Guava `maven-metadata.xml` | 5.8 kB | 62 ms | 62 ms | n/a |
| Guava JAR `.sha1` | 40 B | 78 ms | 79 ms | n/a |
| Repeat fetch (same JAR) | 3.04 MB | 60 ms | 133 ms | 22 MB/s |

The structural reasons direct access is fast:

1. **Anycast termination, 1 RTT to a nearby Cloudflare POP.**
   Connect time is ~18 ms; TLS 1.3 handshake completes in the same flight as
   ALPN negotiation. The Cloudflare TLS terminator is on the same physical
   POP that serves the cached body.
2. **HTTP/2 stream multiplexing.** Maven 3.9 with the native HTTP transport
   pipelines POM lookups across the same connection; `mvn dependency:resolve`
   for a complex tree gets dozens of GETs over one socket.
3. **HTTP/3 advertised** (`alt-svc: h3=":443"`). Clients that speak QUIC
   amortise handshake cost further.
4. **High cache-hit rate.** With `age: 2351265` (27 days) on a randomly chosen
   artifact, Cloudflare's working set on a single POP comfortably covers the
   common dependencies of every JVM build on the planet.
5. **Range requests supported** (`accept-ranges: bytes`), so resumable / parallel
   downloads work without falling back to full GETs.
6. **Tight `content-length`** with no `transfer-encoding: chunked`, so the
   client can size the buffer and parallelism upfront.

A typical full `mvn dependency:resolve` for a non-trivial plugin tree is on
the order of ~150-300 GETs. At ~60-90 ms per metadata/POM and ~150 ms per
JAR, finishing in ~13 s is consistent with serial-with-low-pipelining
clients; Maven 3.9 with parallel artifact download drops that further.

Pantera's "4-5x slower on cold cache miss" means Pantera adds ~150-300 ms
*per request* on a path the CDN does in ~60-150 ms. That overhead is in the
proxy, not in the upstream. The upstream is not slow — Pantera must not
treat upstream as a slow tier to be hidden behind aggressive batching, because
the latency budget it is competing with is already small.

## 6. Recommended proxy/mirror behaviour (per Sonatype guidance)

Compiled from the 429 FAQ family and the Gradle "Good Neighbors" post:

1. **Cache everything that can be cached.** Release artifacts are immutable
   by contract — once you have them, never re-fetch.
2. **Deduplicate concurrent requests** for the same key. The single largest
   bandwidth-amplifying pattern Sonatype calls out is parallel CI builds
   re-fetching the same artifact (post: "redundant downloads, bypassed
   caches, CI fleets that hit the same artifacts over and over").
3. **Negative-cache 404s.** Maven Central itself caches 404s at the edge;
   re-asking for `com/example/nonexistent/...` repeatedly is a documented
   antipattern.
4. **Respect rate-limit responses.** Honour `Retry-After` if present;
   otherwise back off with jitter. Do not retry on 429.
5. **Surface the 429 to operators.** Both Renovate (#37629) and
   DependencyTrack (#3986) opened explicit issues to upgrade their logging
   from DEBUG to WARN for upstream 429s. A proxy that silently swallows
   429 hides the problem until the block escalates from minutes to 24 hours.
6. **Do not pre-fetch / pre-warm against Maven Central.** This is implicit
   in the FAQ ("avoiding repeated downloads from clean or ephemeral
   environments when the same artifacts have already been retrieved") and
   explicit in the "Open is Not Costless" post ("security tooling that
   behaves like bandwidth is free").
7. **One HEAD per artifact, not many.** A proxy that does a HEAD before
   every GET to detect modifications on immutable content is doubling its
   request rate against the upstream for zero useful information.
8. **Talk to Sonatype if you operate a platform-class proxy.** The
   infrastructure-provider FAQ path is explicit: *"the right next step is
   to contact Sonatype so we can understand the traffic pattern and discuss
   possible solutions together."* `mavencentral@sonatype.com`.
9. **Have a stable egress IP and document it.** The 429 contact-support
   page makes the egress IP a required field; without it Sonatype cannot
   look up the activity.

Gradle's "Good Neighbors" post adds two specific recommendations for
build-tool authors:

- Make the caching proxy easy to wire in. Gradle currently lacks Maven's
  `<mirrorOf>` semantics; the post documents an `init.gradle.kts` that
  forces all repository declarations to be rewritten to the local mirror.
  For Pantera this is a reminder that **clients will misconfigure**, and the
  proxy must work correctly when the client wires it up wrongly (e.g. lists
  both Pantera and Central, so the upstream gets DOS'd for every artifact
  not in Pantera yet).
- Use Build Scan / equivalent telemetry to diagnose where traffic is going.
  A proxy product should expose first-party telemetry of "upstream requests
  per minute" because operators have no other way to see whether they are
  about to be blocked.

## 7. What Sonatype does about abusive proxies

The escalation ladder from the published documents:

1. **Soft throttling.** Reduced download speed, no error. From the
   "Tragedy of the Commons" post: "in some circumstances it may lead to 429
   error codes" — i.e. throttle first, error second.
2. **Short HTTP 429.** Minutes. Initial blocks, "deliberately short, so
   organizations have a chance to notice the pattern, investigate, and
   adjust before the impact becomes severe."
3. **Repeated HTTP 429.** Same day, escalating. "If the pattern continues,
   new blocks may be triggered repeatedly, including multiple times in the
   same day. Over time, block durations increase."
4. **24-hour single block.** Maximum for normal-pattern overconsumption.
5. **Longer or permanent block.** Reserved for "extreme over-limit activity,
   attempts to evade limits, or clear abuse." No public ramp; this is at
   Sonatype's discretion.
6. **Direct outreach.** Sonatype contacts large consumers (the
   "Beyond IPs" post is explicit about mapping org-level activity).
7. **Commercial conversation.** The repeated theme — and the Sonatype
   business model — is that organizations with high consumption needs
   should buy Nexus or have a commercial arrangement.

A proxy product that triggers (5) is genuinely a problem for its users, not
just for Sonatype.

Things Sonatype does **not** publicly do:

- Publish numbered thresholds.
- Provide an API or status endpoint to query "am I rate-limited right now."
- Provide tenant identification headers (no `X-Sonatype-Tenant-Id`,
  no API key, no signed token).

Everything is anonymous-by-IP, which is why the "Beyond IPs" follow-up moved
to per-organization aggregation. A platform-class proxy is effectively a
single high-volume IP from the upstream's perspective and will be evaluated
as such.

## 8. Mermaid sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant Client as mvn / gradle
    participant Resolver as Maven Resolver
    participant DNS as DNS
    participant CF as Cloudflare POP (anycast)
    participant Edge as Cloudflare cache shard
    participant S3 as AWS S3 (origin)

    Note over Client,S3: cold cache miss on the CLIENT only (Cloudflare almost always HIT)

    Client->>Resolver: dependency:resolve
    Resolver->>DNS: A repo.maven.apache.org
    DNS-->>Resolver: CNAME repo.apache.maven.org.cdn.cloudflare.net -> 104.18.18.12
    Resolver->>CF: TCP+TLS1.3 (X25519MLKEM768, ALPN=h2)
    CF-->>Resolver: TLS established, h2 negotiated

    par metadata
        Resolver->>CF: GET /maven2/.../maven-metadata.xml
        CF->>Edge: lookup
        alt HIT
            Edge-->>CF: 200 + ETag + Last-Modified (no Cache-Control)
        else MISS
            CF->>S3: GET (signed)
            S3-->>CF: 200 + x-checksum-md5 + x-checksum-sha1
            Edge->>Edge: store
            Edge-->>CF: 200
        end
        CF-->>Resolver: 200 (~60-90 ms TTFB)
    and POMs
        Resolver->>CF: GET /maven2/.../foo-1.2.3.pom (multiplexed h2)
        CF-->>Resolver: 200
    and SHA1s
        Resolver->>CF: GET /maven2/.../foo-1.2.3.jar.sha1
        CF-->>Resolver: 200 (40 bytes, ~78 ms total)
    end

    Note over Resolver,CF: many GETs over one h2 connection

    Resolver->>CF: GET /maven2/.../foo-1.2.3.jar
    CF->>Edge: lookup
    Edge-->>CF: HIT (age=days, cf-cache-status: HIT)
    CF-->>Resolver: 200 + body (~150 ms total for 3 MB)

    Note over CF,S3: in steady state, almost every request is a HIT;<br/>S3 is only touched on cold-edge or new artifacts

    Note over Client,CF: if Resolver fires too fast / too many IPs of same org:<br/>CF returns 429 with human-readable body<br/>(Retry-After may or may not be set)
```

## Implications for a proxy product

A proxy product like Pantera that sits between developers/CI and Maven Central
must internalise the following:

1. **Upstream latency is the floor, not the ceiling.** A cache HIT at
   Cloudflare's edge is 60-90 ms for metadata, 130-230 ms for a 3 MB JAR.
   Pantera's first-fetch path has to do at least this well *plus* its own
   storage write — a few extra hundred ms in the worst case. Beyond that and
   users will route around the proxy.

2. **The upstream HAS NO `Cache-Control`.** A proxy that mechanically
   forwards upstream cache directives will end up caching nothing because
   nothing is sent. The proxy must apply local policy: release artifacts
   immutable forever (from the immutability contract); `maven-metadata.xml`
   short TTL with revalidation; 404s negative-cached aggressively.

3. **`ETag` and `Last-Modified` ARE sent.** A proxy that re-fetches an
   immutable artifact wastes upstream budget; a proxy that revalidates with
   `If-None-Match`/`If-Modified-Since` against Cloudflare gets 304 and
   actually saves the upstream nothing because Cloudflare already had it
   cached. The right pattern is: do not revalidate immutable releases at all.

4. **Deduplicate aggressively.** The single highest-leverage thing a proxy
   can do is collapse concurrent requests for the same key into one upstream
   GET. Pantera's `RequestDeduplicator` is exactly this; the upstream
   guidance explicitly calls out "redundant downloads, bypassed caches, CI
   fleets that hit the same artifacts over and over" as the abusive pattern.

5. **Do not pre-fetch.** Pantera's removal of speculative prefetch (M2 in the
   recent perf-gate work) is directly aligned with the upstream's
   anti-amplification stance.

6. **Honour 429, surface it.** Local back-off with jitter on 429; don't
   retry-amplify; log it loud enough that an operator can fix the underlying
   build pattern. Quiet 429 handling is a documented antipattern in both
   DependencyTrack #3986 and Renovate #37629.

7. **Negative-cache 404s.** The upstream already does. A proxy that doesn't
   is amplifying.

8. **Single egress, declared, known to Sonatype.** If Pantera ever ships as
   a managed service, "talk to Sonatype" via `mavencentral@sonatype.com`
   before the platform-scale traffic shows up at the edge. The
   infrastructure-provider FAQ path makes this an explicit expectation.

9. **The 429 body is a website, not JSON.** Parse the status, not the body.
   The body content includes a link to the tragedy-of-the-commons blog;
   it can change. Status + (optional) `Retry-After` is the only stable
   contract.

10. **Cache `maven-metadata.xml` for **seconds**, not minutes.** Cloudflare
    is doing this for you upstream (`age` is small on those responses).
    A proxy that holds metadata for minutes will look broken to users who
    just published a new version.

## Non-obvious findings

1. **Maven Central's artifact CDN is now Cloudflare, not Fastly.** This is
   not announced anywhere on `central.sonatype.org`. The Fastly case study,
   the 2014 HTTPS-launch entry on the official Sonatype changelog, and
   every blog post that mentions "our CDN partner Fastly" are now stale.
   The migration is only observable from HTTP headers and DNS. For
   Pantera this is important because written guidance about
   "Maven Central is on Fastly" is no longer factually correct, and any
   internal documentation that quotes that should be corrected.

2. **The TLS certificate is issued by Google Trust Services, not Let's
   Encrypt or Cloudflare Origin CA.** Cloudflare offers Google-issued certs
   as a customer-tier option; Sonatype is on that tier. This matters only
   for TLS pinning code (do not pin) but is notable as a small piece of
   "the operator chose this configuration" evidence.

3. **Sonatype publishes no `Cache-Control` header at all on artifacts**,
   despite the published immutability contract on the publishing side.
   `Cache-Control: public, max-age=31536000, immutable` would let every
   downstream cache (Cloudflare, Pantera, the user's browser if it ever
   asked) make better decisions; the absence is conspicuous and probably
   a holdover from S3 default behaviour.

4. **404 responses leak the S3 backend identity.** `x-amz-error-code`,
   `x-amz-error-detail-key`, and the `Code: NoSuchKey` body shape are not
   stripped at the edge. A determined attacker who didn't already know the
   origin is on S3 would learn it from any missing artifact lookup.

5. **The 429 enforcement is not actually new.** The 2014 status page already
   documented "Blocked due to Past Violation" for "constantly consuming
   hundreds of GBs or TBs of data every month" — a manually-curated IP
   blocklist that pre-dates the 2024 automated throttling by a decade.
   The 2024 change is the automation, not the policy.

## What I could not determine

- The exact Fastly-to-Cloudflare migration date.
- The specific rate-limit thresholds, in either requests/sec or bytes/sec
  or per-time-window terms. Sonatype has chosen not to publish numbers and
  is explicit about why ("the thresholds will move downward over time").
- Whether the Cloudflare configuration includes Argo Smart Routing, Tiered
  Cache, R2-as-origin, or any other Cloudflare-side enhancement.
- The S3 bucket name, region, replication policy, or whether origin is a
  single bucket or a multi-region setup.
- The exact `Retry-After` semantics. The MRESOLVER-396 issue implies it is
  sent at least sometimes, but the Renovate report shows it absent. The
  FAQ is silent. Empirically we did not trigger a 429 to inspect.
- The relationship between the artifact CDN (Cloudflare) and the search/docs
  stack (CloudFront+S3). Whether this is a deliberate dual-vendor strategy
  or an artefact of partial migration is not documented.
- Internal observability — whether Sonatype publishes per-org dashboards to
  the affected organizations after contact (the "Beyond IPs" post implies
  they do, but the FAQ does not describe a self-service flow).
- Whether SNAPSHOT publishing (`s01.oss.sonatype.org` → Portal SNAPSHOTs)
  uses the same Cloudflare frontend as releases. We did not probe it for
  this study.
- Geographic latency outside Europe. All numbers in section 5 are from one
  client in Berlin; US/APAC numbers are not measured here, though the
  Cloudflare anycast footprint suggests similar single-digit-to-low-double
  digit RTTs everywhere with a Cloudflare POP.
