# Academic / industry references

Scoped specifically to "what does a Maven proxy at scale need to do?" — caching, request coalescing, content-addressed storage, CDN integration. I have deliberately filtered out generic supply-chain / dependency-graph papers, those that focus on security (SBOMs, signing, malware) without performance insight, and the very large body of mobile/web CDN literature whose conclusions don't transfer.

## Papers

### Vattani, Chierichetti, Lowenstein — "Optimal Probabilistic Cache Stampede Prevention" — VLDB 2015

URL: http://www.vldb.org/pvldb/vol8/p886-vattani.pdf

**Summary.** Formalises the cache stampede problem and proves that a probabilistic early-expiration algorithm called XFetch is asymptotically optimal. The intuition: instead of waiting for a cache entry to expire (which causes every concurrent request to refetch simultaneously), each request computes a probability of voluntarily refetching that increases as the entry approaches its TTL. The probability function is calibrated using the cost of regeneration (`delta`) and a stretch factor (`beta`). With well-tuned parameters, XFetch reduces stampede probability essentially to zero while making redundant refetches negligibly rare.

**Why this matters for Pantera.**
1. The proper response to "a popular tarball is approaching cache expiry" is *not* a TTL countdown. It is XFetch-style stochastic early refresh on the manifest path (tarballs are immutable so this doesn't apply to them; manifests do).
2. The "cost of regeneration" insight: refresh cost is what should drive the stampede prevention parameters, not arbitrary TTL choices. Maven Central is a 100ms+ regional round-trip; a `maven-metadata.xml` refresh is "expensive enough" that XFetch's beta should be tuned generously.

### Atre, Sherry, Wang, Berger — "Caching with Delayed Hits" — SIGCOMM 2020

URL: https://www.pdl.cmu.edu/PDL-FTP/Storage/sigcomm2020-natre.pdf

**Summary.** Identifies a previously-overlooked phenomenon: at high throughput, multiple requests for the same object can pile up *before* an outstanding cache miss resolves. The paper calls these "delayed hits" — requests that *would* hit cache if served sequentially but in practice miss because the cache hasn't been populated yet. Belady's optimal-replacement algorithm is no longer optimal under delayed hits; their "Belatedly" algorithm is. They implement a practical heuristic, Minimum-Aggregate-Delay (MAD), in a CDN caching node and show 12–18% latency reduction depending on backend RTT.

**Why this matters for Pantera.** This is essentially "the single-flight problem, but rigorously analyzed." The Pantera-specific implication:
1. `RequestDeduplicator` (single-flight) is doing the right *kind* of thing — coalescing the requests so they all become a single delayed hit — but its effectiveness depends on the backend RTT. The further away Maven Central is (higher RTT), the more requests pile up during the miss, the bigger the win from coalescing.
2. The paper's framing — "average latency under delayed hits" — is the *right* SLO metric for a Maven proxy, not "p99 latency on a sample of independent requests." If `mvn install` requests 200 dependencies and 5 of them hit slow paths, the build is bottlenecked on those 5. Tail latency, weighted by request popularity, dominates.

### Berger, Beckmann, Harchol-Balter — "Practical Bounds on Optimal Caching with Variable Object Sizes" — SIGMETRICS 2018

URL: https://dl.acm.org/doi/10.1145/3219617.3219629 (paywalled abstract — full PDF available via authors' CMU pages, free)

**Summary.** Real CDNs cache objects of vastly different sizes. Classical optimal-replacement results (Belady's algorithm) assume uniform objects and don't apply. The authors give an LP formulation that produces tight bounds on the achievable hit ratio for a workload, and an offline algorithm that approximates the optimum with provable guarantees. They show that production cache implementations (Varnish, Nginx) commonly leave 5–15% of achievable hit ratio on the table compared to the theoretical optimum.

**Why this matters for Pantera.** Maven jars range from kilobytes (a small library) to hundreds of megabytes (a fat jar). LRU treats them all equally but a 200MB jar evicted to make room for ten 1MB jars may be a bad trade if the big jar gets re-fetched often. The paper's practical advice: size-aware admission control (don't admit a huge object unless its expected hit count justifies the displacement) outperforms naive LRU. Pantera's `DiskCacheStorage` watermark eviction is exactly the kind of heuristic the paper benchmarks against; the gap is "size-aware-LRU is good, size-aware admission is better."

### Zimmermann, Staicu, Tenny, Pradel — "Small World with High Risks: A Study of Security Threats in the npm Ecosystem" — USENIX Security 2019

URL: https://www.usenix.org/system/files/sec19-zimmermann.pdf

**Summary.** Primary focus is security but contains valuable measurement data on the npm dependency graph. Headline statistic: "the average package…impacts about 230 other packages via dependencies."

**Why this matters for Pantera.** Not a performance paper. The single performance-adjacent fact worth keeping: the dependency closure of a typical npm `install` is ~230 packages. For Maven this is similar (mid-size apps pull 200–500 jars). A user-perceived `mvn install` makes hundreds of GETs to the proxy in rapid succession; if any 1% of them are slow, the build feels slow. **The right SLO is the 99th percentile of the slowest-of-N-requests distribution, not p99 of a single request.** This is non-obvious and most monitoring stacks measure the latter.

### Decan, Mens, Constantinou — "On the evolution of technical lag in the npm package dependency network" — MSR 2018 (arXiv 1806.01545)

URL: https://arxiv.org/pdf/1806.01545

**Summary.** Longitudinal study of 120k npm packages over 8 years showing how stale dependencies (technical lag) accumulate.

**Why this matters for Pantera.** One performance-relevant insight only: 90% of `npm install` requests resolve to versions older than the latest release of the same package. This means a Maven proxy's working set is dominated by *not the newest* artifacts. The hot set is "every version anyone has pinned anywhere," which is many millions of artifacts for Maven Central but with extremely Zipfian access — top 1% of versions account for >80% of bytes served. Pantera's cache eviction policy should be tuned on this distribution.

### Wikipedia / industry consolidated reference — "Cache stampede"

URL: https://en.wikipedia.org/wiki/Cache_stampede

**Summary.** Survey-style article consolidating the techniques used in practice: external recomputation (one process refreshes, all others see stale data), locking (one request refreshes while others wait), probabilistic early expiration (XFetch above), and "use stale while revalidating" (`stale-while-revalidate` in HTTP). Each has different tradeoffs around staleness, fairness, and origin load.

**Why this matters for Pantera.** A Maven proxy must implement at least one of these for `maven-metadata.xml` (the mutable index file). Pantera currently uses locking (`RequestDeduplicator`) — fine for tarballs but for indexes, `stale-while-revalidate` is the right primitive because the lag tolerance is high (you can serve a 30-second-old metadata file safely while a background fetch refreshes). The Nginx `proxy_cache_lock` + `proxy_cache_use_stale updating` combination does both at once and is the simplest production-proven recipe.

## Industry / engineering blog posts (semi-academic)

These are not peer-reviewed but contain measurement data and operational reasoning that *is* citeable.

### Donald Stufft — "Powering the Python Package Index" (caremad, 2016)

URL: https://caremad.io/posts/2016/05/powering-pypi/

**Summary.** Insider walkthrough of PyPI's infrastructure circa 2016. Quantifies the Fastly contribution: at the time, donated services to PyPI were estimated at ~$35,000/month, of which Fastly was the largest single contributor. The post identifies the design pattern: every read is a CDN hit; the origin only handles cache-miss tails and write traffic. PyPI itself runs on a small number of machines because the CDN absorbs everything.

**Why this matters for Pantera.** This is the canonical "the CDN is the architecture" writeup. The Pantera-relevant insight is the order-of-magnitude cost ratio: a CDN can cost-effectively serve ~95–99% of traffic for any package registry, and the design effort should be entirely about maximising hit rate, not about making the origin faster. A 4–5× slowdown on the origin is much less important than a 1% reduction in CDN hit rate, because the latter affects 100× more requests.

### Dustin Ingram — "What does it take to power the Python Package Index?" (2021)

URL: https://dustingram.com/articles/2021/04/14/powering-the-python-package-index-in-2021/

**Summary.** Five-year update on Stufft's post. Notable update: by 2021 Warehouse had migrated tarball storage from S3 to Backblaze B2 (egress-free agreement with Fastly), retaining S3 only as the fallback origin. Confirms the Fastly numbers: PyPI's bandwidth grant was estimated at $1.8M/month by 2023.

**Why this matters for Pantera.** Storage choices are driven by *egress economics*, not by performance. PyPI moved to B2 because they couldn't afford S3 egress at their traffic levels. For an internal Pantera the equivalent is: storage choice should follow the deployment's egress model. Inside AWS, S3 with a CloudFront distribution is the obvious move; outside, an S3-compatible store + Cloudflare R2 (egress-free) is the modern PyPI choice.

### Fastly + Python Software Foundation case study

URL: https://www.fastly.com/customers/python-software-foundation

**Summary.** Marketing case study but contains operational numbers Fastly is contractually committed to:
- 99% cache hit ratio on PyPI traffic.
- ~36k requests/second average.
- 1.2 trillion requests over the lifetime of the sponsorship as of 2023.

**Why this matters for Pantera.** These numbers define what "good" looks like. 99% hit ratio for a registry is achievable in practice; if your proxy is below 95%, you are doing something architecturally wrong. The 36k QPS sustained average means peaks of 100–300 kQPS are real; an origin that can do 5k QPS is fine because the CDN absorbs the other 295k.

### Sonatype — "Maven Central: Addressing the Tragedy of the Commons" (2024)

URL: https://www.sonatype.com/blog/maven-central-and-the-tragedy-of-the-commons

**Summary.** Sonatype-published analysis of Maven Central traffic with hard numbers:
- 1% of IPs consume 83% of bandwidth.
- 75% of traffic originates from hyperscale cloud customers.
- 2024: 1.5 trillion Java component requests, ~47,000 downloads per second average.
- 36% YoY growth.

The blog argues for "organizational rate limiting" — throttling by cloud-account or org, not by IP, because the heavy users spread across many IPs in cloud footprints.

**Why this matters for Pantera.** Two takeaways:
1. **Quantifies the scale Pantera is operating against.** Maven Central handles 47k DPS; any proxy in front of it is competing on a single dimension (regional locality / cache rate) because Sonatype has paid for the CDN and global distribution.
2. **Pantera's M3 rate-limiting is on the right side of history.** If we don't dedupe and back-pressure, we look like the 1%; Sonatype will throttle us; user builds slow down. The architectural moves to reduce outbound amplification (M1–M4 in the recent perf gate work) directly map to staying off Sonatype's 1% list.

### Bojie Li / iBug — "How is the USTC Open Source Software Mirror Made?" (2013) + ZFS rebuild post (2024)

URLs: https://01.me/en/2013/09/how-ustc-mirror-works/, https://ibug.io/blog/2024/10/ustc-mirrors-zfs-rebuild/

**Summary.** Operational notes from a major Chinese university mirror serving Linux distros and language ecosystems via rsync. Quantified:
- 2013: ~100 upstreams, >10 TB data, "tens of millions" of HTTP hits/day, >4 TB egress/day.
- 2024: 36 TiB average daily egress, 10.3 TiB from rsync alone.

The 2024 post-mortem details a storage rebuild that explicitly chose spinning disk over SSD because the working set fits in RAM page cache and the SSD price premium wasn't justified.

**Why this matters for Pantera.** Universities and ISP-local mirrors operate on a different cost model from CDN-fronted commercial mirrors. They cache *everything* because they pay for disk once but pay for upstream bandwidth on every miss. Cost-driven decisions like "spinning disk + lots of RAM" beat "SSD" when the working set is small relative to total storage. For Pantera, the implication is: the disk cache layer doesn't need to be fast; it needs to be big and OS-page-cache-friendly. The hot bytes are coming from RAM; the warm bytes from disk; the cold bytes from upstream. SSD only matters for the warm middle.

### Nginx — `proxy_cache_lock` and the thundering herd post

URL: https://blog.nginx.org/blog/mitigating-thundering-herd-problem-pbs-nginx

**Summary.** Nginx's own write-up on how `proxy_cache_lock` + `proxy_cache_use_stale updating` + `proxy_cache_background_update` together implement (a) single-flight via locking, (b) stale-while-revalidate, (c) background refresh. The post is framed around PBS (Public Broadcasting Service) but the configuration is generic.

**Why this matters for Pantera.** This is the simplest production-grade single-flight + stale-while-revalidate stack and it is *built into Nginx*. Anyone considering rolling their own in Java/Vert.x should first justify why they're not just sticking Nginx in front. Pantera does roll its own (`RequestDeduplicator`) but the configuration knobs Nginx exposes — `proxy_cache_lock_timeout`, `proxy_cache_lock_age`, `proxy_cache_background_update` — are the right named primitives for a config surface. The Pantera M3 work added the right primitives but the surface area is less standardised than Nginx's.

### Sonatype — "Beyond IPs: Addressing Organizational Overconsumption in Maven Central"

URL: https://www.sonatype.com/blog/beyond-ips-addressing-organizational-overconsumption-in-maven-central

**Summary.** Direct follow-up to the tragedy-of-commons post. Operational details on how organizational throttling works (presumably some combination of UA inspection, ASN-level grouping, and account correlation across cloud providers). Includes the policy decision tree for when a 429 is returned vs. when traffic is silently slowed.

**Why this matters for Pantera.** The Pantera proxy must (a) cleanly identify itself in `User-Agent` so it can be allowlisted if needed, (b) honor `Retry-After` headers when 429'd, (c) coalesce upstream requests so the org footprint stays small. The recent Pantera fix for nested 503/Retry-After parsing (commit `bef9c39dc`) is on exactly this surface.

### Kent C. Dodds — "unpkg: An open source CDN for npm"

URL: https://kentcdodds.com/blog/unpkg-an-open-source-cdn-for-npm

**Summary.** Architecture summary of unpkg, which serves arbitrary npm package files over Cloudflare Workers. Quantified: 95% of unpkg's traffic is served from Cloudflare cache, never reaching the Fly.io origin. The key insight: unpkg URLs are deterministic and immutable because npm versions are immutable, so the cache key is permanent. Cache-busting is impossible by design — to "update" you publish a new version.

**Why this matters for Pantera.** The "URLs are deterministic and content-addressed" property is what makes 95% cache hit achievable. For Maven, the equivalent is the standard `groupId/artifactId/version/filename.jar` path — also deterministic, also content-addressed in practice. Any Pantera URL scheme that adds query parameters, headers, or per-client variation is destroying cacheability. The PMD ruleset enforces a lot of things; "deterministic URLs" should be a documented architectural invariant too.

### Maven Central + Cloudflare status page

URL: https://status.maven.org/

**Summary.** Status page lists Cloudflare CDN/Cache as a component. Combined with the earlier Sonatype writeups citing Fastly, this is the public confirmation that Maven Central is multi-CDN in 2024–2025.

**Why this matters for Pantera.** Operational note only: Maven Central can degrade in two layers (origin vs CDN). A Pantera proxy that fails over between mirrors (`repo1.maven.org` ↔ `repo.maven.apache.org` ↔ `maven.aliyun.com`) on 5xx is more available than one tied to a single upstream.

## Conference talks

### Donald Stufft — PyCon US talks on PyPI architecture (multiple years, 2015–2018)

URL collection: https://us.pycon.org/ (search archive for "Stufft")

**Summary.** Stufft gave annual updates on Warehouse migration covering the move from the legacy PyPI codebase to Pyramid + Postgres + Fastly. Talks are on YouTube; slides are linked from his GitHub.

**Why this matters for Pantera.** Implementation specifics: Warehouse uses **surrogate keys** to invalidate Fastly cache entries on package publish events. Worker processes (Celery) emit explicit purge requests. The takeaway: invalidation is push-based, not TTL-based, for the mutable parts of the API; TTL is reserved for the immutable parts where it acts as a sanity backstop, not a correctness mechanism.

### Dustin Ingram — "Building a Sustainable Python Package Index" — PyCon AU 2019

URL: https://www.youtube.com/watch?v=gcbNT3tLgUg

**Summary.** Cost-and-scale framing of PyPI as a public good. Quantifies the donor stack and explains how the immutability of releases is what makes the cache architecture work.

**Why this matters for Pantera.** Cite-able quote (paraphrased from the talk): "We never have to invalidate package files because they never change." This is the Maven equivalent of `1.2.3.jar` is immutable; only `maven-metadata.xml` is mutable. Treat them differently or you will lose hit rate on the wrong file.

### Nirav Atre — "Caching with Delayed Hits" — SIGCOMM 2020

URL: https://www.youtube.com/watch?v=0cTfF8ufnV0

**Summary.** Author talk on the SIGCOMM paper above. Worth watching for the visual explanation of how delayed hits emerge as the throughput-to-RTT ratio increases — i.e., when there are more requests arriving per RTT than there are cache hits, every miss generates a queue. The talk explicitly draws the connection to CDN caching, where many object types have low TTLs and any expiry triggers a delayed-hit queue.

**Why this matters for Pantera.** Operational rule of thumb: number of in-flight cache misses per upstream RTT × request rate = expected pileup. If Maven Central is 100ms away and Pantera sees 500 RPS for a single tarball during a miss window, that's 50 simultaneous in-flight requests for the same byte stream. Single-flight cuts that to 1. **The bigger the RTT and the higher the request rate, the more valuable single-flight is.**

### Brian Fox / Sonatype — talks on Maven Central sustainability (various, 2024–2025)

URL: https://openssf.org/podcast/2024/07/16/whats-in-the-soss-podcast-9-sonatypes-brian-fox-and-the-perplexing-phenomenon-of-downloading-known-vulnerabilities/

**Summary.** Podcast and conference talks discussing the operational burden of Maven Central. Same numbers as the blog posts but with additional commentary on heavy-user identification.

**Why this matters for Pantera.** Reinforces the case for treating outbound-request reduction as a first-class architectural concern, not an optimisation. The recent Pantera M-series (M1–M6) work is correctly framed around exactly this.

## Convergent themes

These are the propositions multiple independent sources (papers, talks, mass-scale operators) agree on:

1. **Immutable content + content-addressed URLs are the foundation of cache effectiveness.** Versioned tarball URLs unlock 95%+ CDN hit rates. Any URL scheme that introduces variance (query strings, vary headers beyond Accept/Accept-Encoding) destroys this.
2. **Single-flight is necessary but not sufficient.** Vattani+Chierichetti (XFetch), Atre+Sherry (delayed hits), and Nginx documentation all converge on: coalesce, set a timeout, fall through to background-update + stale-while-revalidate. The Pantera `RequestDeduplicator` does step 1 well; step 2 (`proxy_cache_lock_timeout`-equivalent) and step 3 (background update) are the natural extensions.
3. **Origin should redirect, not stream.** PyPI's `files.pythonhosted.org` pattern and cnpmcore's `nfsAdapter.getDownloadUrl` are the same trick. The origin's job is to look up an object key, generate a signed URL, and return 302. The bytes never enter the application process. **Pantera does not do this and almost certainly should.**
4. **Object storage is the durability boundary.** Local file storage is for development and university mirrors only. Anything serving real load uses S3/OSS/B2/COS/R2 because that's where the CDN peering deals live and that's what supports signed URLs.
5. **Heavy upstream users get throttled at the *org* level.** Sonatype's organizational throttling means a proxy *must* aggressively cache + dedupe to avoid being lumped in with the 1% heavy hitters; once throttled, no amount of local optimisation will hide the slowness.
6. **Working sets are Zipfian.** Decan et al. on npm, USTC's RAM-cache-dominates observation, and the Sonatype 1%/83% number all point at the same thing: the top 1% of requests is most of the traffic, and the top 0.01% is the difference between a fast cache and a slow one. Cache admission policies should weight popularity heavily.
7. **Reads dominate. Bytes do not flow through the application.** Every system separates the read and write paths, and the read path never holds a byte. Anywhere a Java thread is reading a byte from disk and writing it to a response, you have an architectural mistake at scale.

Conjecturally — and this is where the academic literature actually meets the Pantera-specific gap — a 4–5× slowdown vs reference is almost always explained by one or more of:

- **Pantera is on the byte path; reference is not.** (See points 3, 7.) Reference 302-redirects; Pantera streams. The 4–5× is roughly the cost of a JVM round trip per byte.
- **Pantera is making upstream requests reference is not making.** (See point 5.) Reference's CDN absorbs 95%+; Pantera's origin sees 100%.
- **Pantera's cache key space is larger than reference's.** (See point 1.) Reference has deterministic content-addressed URLs; Pantera adds variance.

The remaining 5–15% gap, if any, is usually in the dedup/single-flight + delayed-hit interaction (point 2). Fix the structural issues first; tune the dedup parameters second.
