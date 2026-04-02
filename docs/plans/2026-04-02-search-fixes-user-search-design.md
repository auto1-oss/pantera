# Search Backend Fixes + User/Role Server-Side Search Design

**Date:** 2026-04-02
**Status:** Approved
**Author:** Ayd Asraf + Claude

---

## Problem Statement

Three problems in Pantera's search and listing infrastructure:

1. **PR #22 scalability issues** — 7 high-confidence issues found during code review: SQL injection fragility in `buildOrderBy`, unbounded aggregation queries causing connection exhaustion, broken pagination for restricted users, missing timeouts on FTS path, and index-hostile GROUP BY.

2. **User/role search is client-side** — `UserDao.list()` and `RoleDao.list()` return ALL rows to Java. `UserHandler` and `RoleHandler` do in-memory slicing. The UI sends a `q` param but the backend ignores it. Sorting is PrimeVue client-side default. Doesn't scale beyond a few hundred users.

3. **No structured search syntax** — power users can't filter search results by field (name, version, repo, type). Only free-text FTS is available.

---

## Part 1: Search Backend Fixes (PR #22)

### Fix 1: `buildOrderBy` type safety

**Problem:** `buildOrderBy(String sortBy, boolean sortAsc)` accepts a raw String. Any future caller bypassing the handler's allowlist check could inject SQL via the `sortBy` parameter.

**Fix:** Create `SortField` enum in `DbArtifactIndex.java`:
```java
enum SortField { NAME, VERSION, DATE, RELEVANCE }
```

`SearchHandler` maps the validated string to the enum before calling. `buildOrderBy` accepts only the enum. The `default` case in the switch becomes unreachable but is kept as a safety net.

**Files:** `DbArtifactIndex.java` (enum + `buildOrderBy` signature), `SearchHandler.java` (map string → enum)

### Fix 2: Facet queries only on page 0

**Problem:** Every search request fires 3 DB queries — the main search plus 2 unbounded GROUP BY aggregations (type counts, repo counts). On broad queries matching 500K+ rows, the aggregations take seconds and hold DB connections.

**Fix:** Add `boolean includeFacets` parameter to `searchFiltered*` methods. `SearchHandler` passes `offset == 0`. When false, `queryTypeCounts` and `queryRepoCounts` are skipped — return empty maps. The client caches facet counts from the first page.

**Files:** `DbArtifactIndex.java` (method signatures), `SearchHandler.java` (pass `offset == 0`)

### Fix 3: `totalHits` on empty offset pages

**Problem:** When filter + offset exceeds the result set, `COUNT(*) OVER()` returns nothing (no rows). `totalHits` stays 0. Client sees `total: 0` instead of the real filtered count.

**Fix:** When result set is empty AND offset > 0, run a fallback `SELECT COUNT(*) FROM artifacts WHERE <same predicates>` to get the real total. Return that total with an empty items list and `hasMore: false`.

**Files:** `DbArtifactIndex.java` (`searchFilteredPrefixFts`, `searchFilteredFts`)

### Fix 4: Cap effective offset

**Problem:** `MAX_PAGE=500 * MAX_SIZE=100 = 50,000` effective offset. PostgreSQL OFFSET is O(n) — must materialize and discard all skipped rows. OFFSET 50,000 on a 1M row table takes seconds.

**Fix:** Replace `MAX_PAGE` with `MAX_OFFSET = 10_000`. Reject with 400 if `page * size > MAX_OFFSET` with message: "Offset too large. Refine your search query." Remove `MAX_PAGE` constant.

**Files:** `SearchHandler.java`

### Fix 5: Permission-aware SQL filter

**Problem:** The overfetch hack (`size * OVERFETCH_MULTIPLIER`) fetches 10x rows and filters permissions in Java. On page > 0, overlapping windows cause duplicates/skips across pages for restricted users.

**Fix:** Push the permission filter into SQL. `SearchHandler` resolves the user's allowed repo names from the policy, passes them as `List<String> allowedRepos` to the index. SQL adds `AND repo_name = ANY(?)` when the list is non-null (null = unrestricted admin). Remove `OVERFETCH_MULTIPLIER`.

**Files:** `SearchHandler.java` (resolve allowed repos), `DbArtifactIndex.java` (add `allowedRepos` param to search methods, add SQL predicate)

### Fix 6: Timeout on FTS aggregation queries

**Problem:** The LIKE fallback path has `SET LOCAL statement_timeout = '3000'` but the FTS path doesn't. Broad FTS queries with unbounded aggregations hold connections for 10+ seconds.

**Fix:** Add `SET LOCAL statement_timeout = '3000'` before the FTS aggregation queries in `searchFilteredPrefixFts` and `searchFilteredFts`. On timeout, catch the exception and return empty facet maps — the main search results still work, sidebar counts are just unavailable.

**Files:** `DbArtifactIndex.java`

### Fix 7: Index-friendly type counts

**Problem:** `GROUP BY REGEXP_REPLACE(repo_type, '-(proxy|group)$', '')` forces PostgreSQL to evaluate a regex on every matching row and prevents index use.

**Fix:** Change SQL to `GROUP BY repo_type`. In Java, after the query returns, merge suffix-stripped counts:
```java
// DB returns: {maven-proxy: 100, maven-group: 20, maven: 50}
// Java merges: {maven: 170}
```

**Files:** `DbArtifactIndex.java` (`queryTypeCounts`, `queryTypeCountsLike`)

### Bonus: `getStats()` uses materialized view

**Problem:** `SELECT COUNT(*) FROM artifacts` is a full table scan. `mv_artifact_totals` already exists and is periodically refreshed.

**Fix:** Change to `SELECT artifact_count FROM mv_artifact_totals`. Fallback to `COUNT(*)` if the view is empty (first run before refresh).

**Files:** `DbArtifactIndex.java` (`getStats`)

---

## Part 2: Structured Search Query Syntax

### Syntax

```
name:pydantic AND (version:2.12 OR version:2.11) AND repo:pypi-proxy
```

### Supported Fields

| Field | Maps to | Match type | Example |
|-------|---------|-----------|---------|
| `name:` | `artifact_name` column | ILIKE (case-insensitive substring) | `name:pydantic` |
| `version:` | `version` column | ILIKE | `version:2.12` |
| `repo:` | `repo_name` column | Exact match | `repo:pypi-proxy` |
| `type:` | `repo_type` column | Prefix match (strips `-proxy`/`-group`) | `type:maven` |
| (no prefix) | `ts_vector` full-text search | FTS `@@` operator | `pydantic` |

### Operators

- `AND` — default when fields are space-separated (`name:foo version:1.0` = `name:foo AND version:1.0`)
- `OR` — explicit, must be written (`version:2.12 OR version:2.11`)
- Parentheses — grouping for OR precedence (`(version:2.12 OR version:2.11)`)
- Bare terms (no prefix) go through FTS as today

### Parser

New class `SearchQueryParser` in `pantera-main/.../index/`:

```java
record SearchQuery(
    String ftsQuery,              // bare text terms for ts_vector
    List<FieldFilter> filters     // structured field filters
)

record FieldFilter(
    String field,                 // "name", "version", "repo", "type"
    List<String> values,          // one or more values (OR within a field)
    MatchType matchType           // ILIKE, EXACT, PREFIX
)
```

The parser tokenizes the input string, extracts `field:value` pairs, collects bare terms for FTS, and handles `AND`/`OR`/parentheses for multi-value fields. Bare terms without a field prefix are concatenated into the FTS query string.

### SQL Generation

`DbArtifactIndex` translates `SearchQuery` into parameterized SQL:

```sql
WHERE ts @@ plainto_tsquery('english', $1)           -- FTS terms
  AND LOWER(artifact_name) LIKE $2                     -- name filter
  AND (LOWER(version) LIKE $3 OR LOWER(version) LIKE $4)  -- version OR
  AND repo_name = $5                                   -- repo filter
```

All values are parameterized — no SQL injection possible.

### Backward Compatibility

A query without any field prefixes (`pydantic 2.12`) works exactly as today (pure FTS). The parser passes the entire string to `ftsQuery` and returns an empty `filters` list.

### UI

- Search input accepts the structured syntax directly
- A small help icon (PrimeVue `Button` with `pi pi-question-circle`, text style) next to the search box
- Clicking it shows a tooltip/popover with syntax examples:

```
Search syntax:
  pydantic              — full-text search
  name:pydantic         — filter by package name
  version:2.12          — filter by version
  repo:pypi-proxy       — filter by repository
  type:maven            — filter by repo type

Combine with AND / OR:
  name:pydantic AND version:2.12
  name:pydantic AND (version:2.12 OR version:2.11)
```

**Files:**
- New: `pantera-main/.../index/SearchQueryParser.java`
- New: `pantera-main/.../index/SearchQueryParserTest.java`
- Modify: `DbArtifactIndex.java` — accept `SearchQuery` instead of raw string
- Modify: `SearchHandler.java` — parse query through `SearchQueryParser` before passing to index
- Modify: `pantera-ui/src/views/search/SearchView.vue` — help icon + popover

---

## Part 3: Server-Side User/Role Search and Sorting

### UserDao Changes

New method replaces `list()`:

```java
PagedResult<UserInfo> list(String query, String sortField, boolean ascending, int limit, int offset)
```

SQL:
```sql
SELECT u.username, u.email, u.enabled, u.auth_provider,
       COALESCE(json_agg(r.name) FILTER (WHERE r.name IS NOT NULL), '[]') AS roles,
       COUNT(*) OVER() AS total_count
FROM users u
LEFT JOIN user_roles ur ON u.id = ur.user_id
LEFT JOIN roles r ON ur.role_id = r.id
WHERE ($1 IS NULL OR LOWER(u.username) LIKE $1 OR LOWER(u.email) LIKE $1)
GROUP BY u.id
ORDER BY <sortField> <sortDir>
LIMIT $2 OFFSET $3
```

Sort field validated against allowlist: `username`, `email`, `enabled`, `auth_provider`.

`PagedResult<T>` record: `record PagedResult<T>(List<T> items, int total)`

Old `list()` kept as backward-compatible overload calling `list(null, "username", true, 1000, 0)`.

### RoleDao Changes

Same pattern:
```java
PagedResult<RoleInfo> list(String query, String sortField, boolean ascending, int limit, int offset)
```

```sql
SELECT name, permissions, enabled, COUNT(*) OVER() AS total_count
FROM roles
WHERE ($1 IS NULL OR LOWER(name) LIKE $1)
ORDER BY <sortField> <sortDir>
LIMIT $2 OFFSET $3
```

Sort field allowlist: `name`, `enabled`.

### UserHandler / RoleHandler Changes

Extract new query params (same pattern as `SearchHandler`):
- `q` — search query, converted to `%query%` for ILIKE
- `sort` — validated against field allowlist
- `sort_dir` — `asc` or `desc`, defaults to `asc`
- `page`, `size` — already extracted, now passed to DB

Response format unchanged — still `{items: [...], page, size, total, hasMore}`.

### UI Changes

**UserListView.vue:**
- Search input: change from `@keyup.enter` to `@update:modelValue` with 300ms debounce (use `watchDebounced` or `setTimeout`)
- Add `sort` and `sort_dir` to the API call triggered by PrimeVue DataTable `@sort` event
- Remove client-side sorting (keep `sortable` attribute for column header arrows)

**RoleListView.vue:**
- Add search input (copy pattern from UserListView)
- Same server-side sort wiring

**Files:**
- Modify: `UserDao.java`, `RoleDao.java` — parameterized queries
- Modify: `UserHandler.java`, `RoleHandler.java` — extract params, pass to DAO
- Modify: `UserListView.vue`, `RoleListView.vue` — debounced search, server-side sort
- New: `PagedResult.java` (shared record for paginated DAO results)

---

## Part 4: GroupSlice Index-Miss Fanout Optimization

### Problem

When `locateByName()` returns an empty result (index miss), `GroupSlice.queryAllMembersInParallel()` fans out to ALL group members — both hosted and proxy. Hosted repos are fully indexed, so if `locateByName` didn't find the artifact there, it's definitively absent. Only proxy repos need the fanout because their content is indexed only after first cache.

A group with 15 hosted repos and 3 proxy repos does 18 parallel requests on an index miss when only 3 are necessary.

### Fix

In `GroupSlice.java`, in the index-miss fallback path (~line 444-454), filter `queryAllMembersInParallel` to include only members where `isProxy == true`.

The `proxyMembers` set already exists (populated during `MemberSlice` construction from repo config). The fix is a one-line filter:

```java
// Before: fans out to ALL members on index miss
return queryAllMembersInParallel(line, headers, body);

// After: fans out to ONLY proxy members on index miss
return queryProxyMembersOnly(line, headers, body);
```

`queryProxyMembersOnly` is a new method that filters the member list to `isProxy == true` before fan-out.

**Edge case:** If a hosted repo has an artifact that was never indexed (DB was rebuilt, backfill incomplete), this optimization would miss it. Mitigation: this is already the design contract — hosted repos MUST be indexed. The backfill CLI covers recovery scenarios.

### Files

- Modify: `pantera-main/src/main/java/com/auto1/pantera/group/GroupSlice.java` (~line 444-454)
- Test: `GroupSliceIndexRoutingTest.java` — verify index miss fans out only to proxy members

---

## Part 5: Documentation Updates

Update all relevant guides to cover the changes in Parts 1-4:

### Admin Guide

- **Search configuration:** Document `MAX_OFFSET` limit (10,000), facet timeout (3s), and behavior when facets are unavailable
- **User/role management:** Server-side search and sort now work — document the query params available
- **Group performance:** Explain index-miss fanout optimization — only proxy members queried on miss

### Developer Guide

- **Search query syntax:** Document `SearchQueryParser` and `SearchQuery` model
- **Adding new search fields:** How to add a new field prefix (add to parser, add SQL predicate, add to UI hint)
- **`SortField` enum:** How to add new sort fields safely
- **`PagedResult<T>`:** Shared pagination model for DAOs
- **GroupSlice index routing:** Explain `locateByName` → index hit/miss → proxy-only fanout

### User Guide

- **Search syntax help:** Same content as the UI popover — field prefixes, AND/OR, parentheses, examples
- **User/role search:** Mention that search now works across all pages, not just the visible page

### REST API Reference

- **`GET /api/v1/search`:** Document structured query syntax in the `q` parameter. Add examples.
- **`GET /api/v1/users`:** Document `q`, `sort`, `sort_dir` query params
- **`GET /api/v1/roles`:** Document `q`, `sort`, `sort_dir` query params
- **Error 400:** Document "Offset too large" response when `page * size > 10,000`

### Changelog (v2.1.0)

Add entries for all four parts under appropriate sections.

---

## Testing Strategy

### Part 1 Tests
- `SortField` enum mapping covers all valid values + unknown → default
- Facet queries skipped when `includeFacets=false` (verify no GROUP BY executed)
- Empty page with offset > 0 returns correct total (not 0)
- `page * size > MAX_OFFSET` returns 400
- Permission filter: user with access to 2 repos sees only their results, consistent across pages
- FTS timeout: broad query with mock slow aggregation → empty facets, results still returned
- Type count merging: `{maven-proxy: 100, maven: 50}` → `{maven: 150}`

### Part 2 Tests
- `SearchQueryParser`: bare terms → ftsQuery only
- Field prefix extraction: `name:foo` → filter with field="name", value="foo"
- OR support: `version:2.12 OR version:2.11` → single filter with 2 values
- Parentheses: `name:foo AND (version:2.12 OR version:2.11)` → correct structure
- Mixed: `pydantic name:foo version:2.12` → ftsQuery="pydantic", 2 filters
- SQL generation: parameterized, no injection

### Part 3 Tests
- `UserDao.list("admin", ...)` returns only users matching "admin" in username or email
- `UserDao.list(null, ...)` returns all users (no filter)
- Sort by email descending works at DB level
- Pagination: `limit=10, offset=20` returns correct slice and total count
- Empty search returns all with correct total
- UI: search triggers API call after debounce, sort triggers re-fetch

### Part 4 Tests
- `GroupSliceIndexRoutingTest`: index miss with mixed group (hosted + proxy) → only proxy members queried
- `GroupSliceIndexRoutingTest`: index hit → only the matching member queried (existing test, verify still passes)
- Edge case: group with only hosted members → index miss returns 404 (no fanout at all)
- Edge case: group with only proxy members → index miss fans out to all (same as before)

### Part 5 Verification
- All docs build/render without broken links
- Search syntax examples in user guide match actual parser behavior
- API reference examples are curl-testable
