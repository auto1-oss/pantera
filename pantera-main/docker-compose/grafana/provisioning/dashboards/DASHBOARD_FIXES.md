# Grafana Dashboard Fixes Summary

## Fixed Dashboards
- `pantera-main-overview.json` - Main Overview Dashboard
- `pantera-cache-storage.json` - Cache & Storage Metrics Dashboard

---

## ⚠️ LATEST: PromQL Syntax Errors Fixed (2024-12-01)

### Critical PromQL Fixes Applied

#### 1. ✅ Active Repositories - Fixed Malformed Query
**Issue:** Invalid PromQL syntax with malformed count and by clauses
**Error:** `1:28: parse error: unexpected <by> in aggregation`
**Old Query (BROKEN):**
```promql
count(count{job="pantera"} by{job="pantera"} (repo_name{job="pantera"}) (pantera_http_requests_total{job="pantera"}))
```
**New Query (FIXED):**
```promql
count(count by (repo_name) (pantera_proxy_requests_total{job="pantera"}))
```
**Explanation:**
- Uses `pantera_proxy_requests_total` which has `repo_name` label
- Proper PromQL syntax: `count by (label)` not `by{filter}`
- Counts distinct repository names from proxy requests

#### 2. ✅ Request Latency by Method - Fixed by Clause Syntax
**Issue:** Incorrect `by` clause syntax
**Error:** `1:103: parse error: unexpected "{" in grouping opts, expected "("`
**Old Query (BROKEN):**
```promql
histogram_quantile(0.95, sum(rate(pantera_http_request_duration_seconds_bucket{job="pantera"}[5m])) by{job="pantera"} (le{job="pantera"}))
```
**New Query (FIXED):**
```promql
histogram_quantile(0.95, sum(rate(pantera_http_request_duration_seconds_bucket{job="pantera"}[5m])) by (method, le))
```
**Explanation:**
- Fixed `by` clause: `by (method, le)` instead of `by{job="pantera"} (le{job="pantera"})`
- Groups by both `method` (GET, POST, etc.) and `le` (histogram bucket)
- Shows p95 latency per HTTP method

#### 3. ✅ Request Latency Percentiles - Fixed All Three Queries
**Issue:** Same `by` clause syntax error in p50, p95, p99 queries
**Error:** `1:103: parse error: unexpected "{" in grouping opts, expected "("`
**Old Queries (BROKEN):**
```promql
histogram_quantile(0.50, sum(rate(pantera_http_request_duration_seconds_bucket{job="pantera"}[5m])) by{job="pantera"} (le{job="pantera"}))
histogram_quantile(0.95, sum(rate(pantera_http_request_duration_seconds_bucket{job="pantera"}[5m])) by{job="pantera"} (le{job="pantera"}))
histogram_quantile(0.99, sum(rate(pantera_http_request_duration_seconds_bucket{job="pantera"}[5m])) by{job="pantera"} (le{job="pantera"}))
```
**New Queries (FIXED):**
```promql
histogram_quantile(0.50, sum(rate(pantera_http_request_duration_seconds_bucket{job="pantera"}[5m])) by (le))
histogram_quantile(0.95, sum(rate(pantera_http_request_duration_seconds_bucket{job="pantera"}[5m])) by (le))
histogram_quantile(0.99, sum(rate(pantera_http_request_duration_seconds_bucket{job="pantera"}[5m])) by (le))
```
**Explanation:**
- Fixed `by` clause: `by (le)` instead of `by{job="pantera"} (le{job="pantera"})`
- Shows overall p50, p95, p99 latencies across all requests

#### 4. ✅ Request Rate by Repository - Now Shows Actual Repo Names
**Issue:** Query grouped by `method` instead of repository name
**Old Query (INCORRECT):**
```promql
sum(rate(pantera_http_requests_total{job="pantera"}[5m])) by (method)
```
**Legend:** `{{method}}` (showed GET, POST, etc.)

**New Query (CORRECT):**
```promql
sum(rate(pantera_proxy_requests_total{job="pantera"}[5m])) by (repo_name)
```
**Legend:** `{{repo_name}}` (shows npm_proxy, etc.)

**Explanation:**
- Changed metric from `pantera_http_requests_total` to `pantera_proxy_requests_total`
- `pantera_http_requests_total` does NOT have `repo_name` label (only has `method`, `status_code`)
- `pantera_proxy_requests_total` DOES have `repo_name` label
- Now correctly shows request rate per repository
- Added panel description: "Request rate per repository (proxy repositories only)"

### Available Metrics with repo_name Label
```
pantera_proxy_requests_total{job, repo_name, result, upstream}
pantera_proxy_request_duration_seconds_bucket{job, repo_name, result, upstream, le}
pantera_upstream_errors_total{job, repo_name, upstream, error_type}
```

### Metrics WITHOUT repo_name Label
```
pantera_http_requests_total{job, method, status_code}
pantera_http_request_duration_seconds_bucket{job, method, status_code, le}
```

---

## Pantera - Main Overview Dashboard Fixes (Previous)

### 1. ✅ CPU Usage Formula Fixed
**Issue:** Formula showed CPU idle percentage instead of actual usage  
**Old Query:** `100 * (1 - avg(rate(process_cpu_seconds_total[5m])))`  
**New Query:** `100 * process_cpu_usage{job="pantera"}`  
**Result:** Now shows actual CPU usage percentage (0-100%)

### 2. ✅ JVM Heap Usage Fixed
**Issue:** Showed 3 confusing values due to repeat configuration  
**Fix:** Removed repeat, shows single total heap usage percentage  
**New Query:**
```promql
100 * (
  sum(jvm_memory_used_bytes{job="pantera",area="heap"}) 
  / 
  sum(jvm_memory_max_bytes{job="pantera",area="heap"})
)
```
**Result:** Single gauge showing total heap usage %

### 3. ✅ Added 4xx Error Rate Panel
**Issue:** Only had 5xx error rate, missing 4xx  
**New Panel:** "Error Rate (4xx)"  
**Query:** `sum(rate(pantera_http_requests_total{job="pantera",status_code=~"4.."}[5m]))`  
**Result:** Now shows both 4xx and 5xx error rates side by side

### 4. ✅ Removed Quick Navigation Rows
**Issue:** Unnecessary navigation panels cluttering dashboard  
**Fix:** Removed all "Quick Navigation" row panels  
**Result:** Cleaner dashboard layout

### 5. ✅ Fixed Request Rate by Repository
**Issue:** Showed "Value" instead of actual repo/method names  
**Fix:** Updated query to group by method  
**New Query:** `sum(rate(pantera_http_requests_total{job="pantera"}[5m])) by (method)`  
**Legend:** `{{method}}`  
**Result:** Shows request rate per HTTP method

### 6. ✅ Added Request Latency by Method
**New Panel:** "Request Latency by Method"  
**Query:** `histogram_quantile(0.95, sum(rate(pantera_http_request_duration_seconds_bucket{job="pantera"}[5m])) by (method, le))`  
**Legend:** `{{method}} - p95`  
**Result:** Shows p95 latency breakdown by HTTP method

---

## Pantera - Cache & Storage Metrics Dashboard Fixes

### 1. ✅ Cache Hit Rate Fixed
**Issue:** Showed no data despite having metrics  
**Old Query:** Had incorrect metric name or filter  
**New Query:**
```promql
100 * (
  sum(rate(pantera_cache_requests_total{job="pantera",result="hit"}[5m])) by (cache_type)
  /
  sum(rate(pantera_cache_requests_total{job="pantera"}[5m])) by (cache_type)
)
```
**Legend:** `{{cache_type}}`  
**Result:** Shows hit rate % per cache type (auth, negative, cooldown)

### 2. ✅ Cache Type Variable Fixed
**Issue:** Dropdown only showed "All", no actual cache names  
**Old Query:** `label_values(pantera_cache_requests_total, cache_type)`  
**New Query:** `label_values(pantera_cache_requests_total{job="pantera"}, cache_type)`  
**Result:** Dropdown now shows: auth, negative, cooldown

### 3. ✅ Cache Size Fixed
**Issue:** Showed three zeros due to repeat configuration  
**Fix:** Removed repeat, shows specific cache types  
**New Queries:**
- `pantera_cache_size_entries{job="pantera",cache_type="negative"}`
- `pantera_cache_size_entries{job="pantera",cache_type="auth"}`
- `pantera_cache_size_entries{job="pantera",cache_type="cooldown"}`  
**Result:** Shows size for each cache type clearly labeled

### 4. ✅ JVM Threads Panel Fixed
**Issue:** Showed "No Data"  
**Fix:** Updated query to use correct metric  
**New Query:** `jvm_threads_live_threads{job="pantera"}`  
**Result:** Shows live thread count

### 5. ✅ Added JVM Thread States Panel
**New Panel:** "JVM Thread States"  
**Type:** Time series  
**Query:** `jvm_threads_states_threads{job="pantera"}`  
**Legend:** `{{state}}`  
**Result:** Shows breakdown of thread states (runnable, blocked, waiting, etc.)

### 6. ✅ Added CPU Usage Time Series
**New Panel:** "CPU Usage Over Time" (added at top of dashboard)  
**Type:** Time series  
**Query:** `100 * process_cpu_usage{job="pantera"}`  
**Unit:** percent  
**Range:** 0-100%  
**Result:** Shows CPU usage trend over time

---

## How to Apply Changes

The dashboards have been automatically updated. Grafana has been restarted to load the changes.

**Access dashboards:**
- Main Overview: http://localhost:3000/d/pantera-main-overview
- Cache & Storage: http://localhost:3000/d/pantera-cache-storage

**Default credentials:**
- Username: admin
- Password: admin

---

## Time Range

All dashboards are configured for **Last 24 hours** time range by default.

---

## Notes

- All queries now include `job="pantera"` filter to isolate Pantera metrics
- Removed confusing repeat configurations that caused multiple identical panels
- Added proper legend formats for better metric identification
- Fixed metric names to match actual Prometheus exports

