# Cooldown

> **Guide:** User Guide | **Section:** Cooldown

This page explains the cooldown system from a user perspective: what it means when artifacts are blocked, how to check if an artifact is affected, and what to do when cooldown impacts your builds.

---

## What Gets Blocked and Why

Pantera's cooldown system is a supply chain security feature. When enabled on a proxy repository, it temporarily blocks artifacts that were recently published to the upstream registry. This gives your security team time to review new versions before they enter your build pipeline.

For example, if cooldown is set to 7 days on the npm proxy, a package version published on npmjs.org today will not be available through Pantera until 7 days have passed.

**What is affected:**

- Only **proxy** repositories can have cooldown enabled (local and group repositories are not affected).
- Only **new versions** are blocked -- existing cached versions remain available.
- Cooldown is typically enabled per repository type (e.g., all npm proxies, all Maven proxies).

**What is NOT affected:**

- Artifacts already cached in Pantera before cooldown was enabled.
- Artifacts in local repositories (your own published packages).
- Artifacts that have been manually unblocked by an administrator.

---

## Checking if an Artifact is Blocked

### Via the Management UI

1. Navigate to **Cooldown** in the sidebar.
2. The **Blocked Artifacts** table shows all currently blocked packages.
3. Use the search bar to filter by package name, version, or repository.
4. Each entry shows:
   - Package name and version
   - Repository and type
   - Reason (e.g., `TOO_YOUNG`)
   - Remaining time until the block expires

### Via the API

```bash
curl "http://pantera-host:8086/api/v1/cooldown/blocked?search=lodash" \
  -H "Authorization: Bearer $TOKEN"
```

Response:

```json
{
  "items": [
    {
      "package_name": "lodash",
      "version": "4.18.0",
      "repo": "npm-proxy",
      "repo_type": "npm-proxy",
      "reason": "TOO_YOUNG",
      "blocked_date": "2026-03-20T08:00:00Z",
      "blocked_until": "2026-03-27T08:00:00Z",
      "remaining_hours": 120
    }
  ],
  "page": 0,
  "size": 50,
  "total": 1,
  "hasMore": false
}
```

---

## Requesting an Unblock from Admin

If a blocked artifact is needed urgently (e.g., a critical security patch), contact your Pantera administrator and provide:

1. The **package name** and **version** (e.g., `lodash 4.18.0`)
2. The **repository** it is blocked in (e.g., `npm-proxy`)
3. The **business justification** for unblocking early

Administrators can unblock individual artifacts or all artifacts in a repository through the UI Cooldown panel or via the API. See the [REST API Reference](../rest-api-reference.md) for the unblock endpoints.

---

## What to Do When Builds Fail Due to Cooldown

### Symptom

Your build fails with a message like:

- npm: `ERR! 404 Not Found` or `ETARGET no matching version`
- Maven: `Could not find artifact` or `Could not resolve dependencies`
- pip: `No matching distribution found`

And the package version exists on the public registry but is not available through Pantera.

### Steps to Resolve

1. **Check the cooldown panel.** Open the Management UI and go to **Cooldown**. Search for the package name. If it appears in the blocked list, cooldown is the cause.

2. **Pin to an older version.** If the blocked version is a minor update, pin your dependency to the previous version that is already cached:
   ```
   # package.json
   "lodash": "4.17.21"   # Use the already-cached version
   ```

3. **Wait for the cooldown to expire.** The blocked artifacts table shows the remaining time. Once expired, the artifact becomes available automatically.

4. **Request an emergency unblock.** If waiting is not an option, ask your administrator to unblock the specific artifact.

### Preventing Cooldown Surprises

- **Pin your dependencies** to specific versions rather than using ranges like `^4.0.0` or `~2.1`.
- **Run `npm ci` or `pip install --no-deps`** with a lockfile to ensure reproducible builds.
- **Check cooldown status before upgrading** critical dependencies in your CI pipeline.

---

## Related Pages

- [User Guide Index](index.md)
- [Search and Browse](search-and-browse.md) -- Finding artifacts
- [Troubleshooting](troubleshooting.md) -- Build failure resolution
- [REST API Reference](../rest-api-reference.md) -- Cooldown API endpoints
