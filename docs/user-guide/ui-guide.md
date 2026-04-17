# Management UI

> **Guide:** User Guide | **Section:** Management UI

This page covers the Pantera web-based Management UI, a Vue.js application available at `http://pantera-host:8090/`. The UI provides visual access to repositories, artifacts, search, cooldown monitoring, and administrative functions.

---

## Login

Navigate to `http://pantera-host:8090/login` in your browser.

### Username and Password

1. Enter your Pantera username and password.
2. Click **Sign in**.

### SSO (Okta / Keycloak)

If your organization has configured SSO providers, the login page displays SSO buttons above the manual login form:

1. Click **Continue with okta** (or your configured provider).
2. You are redirected to your identity provider's login page.
3. Complete authentication (including MFA if required).
4. You are redirected back to the Pantera UI with an active session.

SSO providers are configured by your administrator. If no SSO buttons appear, only manual login is available.

### Session

Your session token is stored in the browser's session storage. It persists across page refreshes within the same tab but is cleared when the tab or browser is closed. If your token expires, you are redirected to the login page automatically.

---

## Dashboard

The Dashboard (`/`) is the landing page after login. It provides an at-a-glance overview of your registry:

### Stat Cards

Four summary cards across the top:

| Card | Description |
|------|-------------|
| **Repositories** | Total number of repositories, with the count of distinct package formats |
| **Artifacts** | Total number of indexed artifacts across all repositories |
| **Storage Used** | Aggregate storage consumption (displayed in MB/GB) |
| **Blocked** | Number of artifacts currently held by the cooldown system |

### Top Repositories

Below the stat cards, a ranked table shows the top 5 repositories by artifact count. Each row shows:

- Rank (1-5, with the top 3 highlighted)
- Repository name (clickable link to the detail page)
- Repository type badge
- Usage bar (proportional to artifact count)
- Artifact count
- Storage size

Click **View all** to navigate to the full repository list.

### Grafana Link

If Grafana is configured, a link to the Grafana monitoring dashboard appears in the top-right corner.

---

## Repository Browser

### Repository List (/repositories)

The repository list page shows all repositories you have read access to:

- Filter by repository type using the dropdown.
- Search by repository name using the text field.
- Click a repository name to open its detail page.

### Repository Detail (/repositories/:name)

The detail page varies based on repository type:

**For local and proxy repositories:**

- A breadcrumb-based file browser lets you navigate the directory tree.
- Click folders to drill down, files to view metadata.
- The **Up** button navigates to the parent directory.
- For proxy repositories, a banner notes that only cached artifacts are shown.

**For group repositories:**

- The page displays the list of member repositories.
- Click a member name to navigate to its detail page.
- A banner explains that group repositories are virtual and do not store artifacts directly.

---

## Artifact Details

When you click a file in the repository browser, an **Artifact Detail** dialog opens:

| Field | Description |
|-------|-------------|
| **Path** | Full artifact path within the repository |
| **Size** | File size in human-readable format |
| **Modified** | Last modification timestamp |

### Actions

- **Download** -- Downloads the artifact file to your computer. Uses a short-lived HMAC token for secure, browser-native downloads (no JWT in the URL).
- **Delete** -- Removes the artifact from the repository. Only visible if you have delete permissions.

---

## Search

The Search page (`/search`) provides full-text search across all indexed artifacts:

1. Type your query in the search bar. Results appear automatically after a brief debounce delay (300ms).
2. Results display:
   - Artifact name and full path
   - Repository type badge and repository name
   - Version (if available)
   - File size
3. Use the **Type** filter on the left sidebar to narrow results by package format (Maven, npm, Docker, etc.).
4. The **Repository** section on the left shows which repositories contain matches.
5. Click **Browse** on any result to navigate to that artifact in the repository browser.
6. Use the paginator at the bottom for large result sets.

Search is case-insensitive and tokenizes on dots, dashes, slashes, and underscores. For example, searching `spring-boot` matches `spring.boot`, `spring/boot`, and `spring_boot`.

---

## Cooldown Management Panel

The Cooldown page (`/admin/cooldown`) shows the current state of the cooldown system:

### Cooldown-Enabled Repositories

A list of all repositories with cooldown enabled, showing:

- Repository name and type badge
- Cooldown duration (e.g., `7d`)
- Number of actively blocked artifacts (shown as a red badge)
- **Unblock All** button (visible only with write permissions)

### Blocked Artifacts Table

A paginated, searchable table of all currently blocked artifacts:

| Column | Description |
|--------|-------------|
| Package | Package name |
| Version | Blocked version |
| Repository | Which proxy repository the block applies to |
| Type | Repository type |
| Reason | Block reason (e.g., `TOO_YOUNG`) |
| Remaining | Time until the block expires (displayed as days/hours) |

- Use the search bar to filter by package name, version, or repository.
- Click the unlock button on a row to unblock that specific artifact (requires write permissions).

---

## Profile

The Profile page (`/profile`) shows your user information:

- Username and authentication context
- Email address
- API token management (generate, list, revoke tokens)

---

## Administration Panels

Admin panels appear in the sidebar under **Administration** only if you have the required permissions. These include:

| Panel | Permission Required | Purpose |
|-------|-------------------|---------|
| Repository Management | `api_repository_permissions:write` | Create, edit, delete repositories |
| User Management | `api_user_permissions:write` | Create, edit, enable/disable users |
| Roles & Permissions | `api_role_permissions:write` | Manage RBAC roles |
| Storage Configuration | `api_alias_permissions:write` | Manage storage aliases |
| System Settings | Admin role | Configure server settings, auth providers |

If you do not see the Administration section, you have read-only access. Contact your administrator for elevated permissions.

### Creating Repositories

The **Create Repository** page (`/admin/repositories/create`) allows administrators to create new repositories. The **Type** dropdown lists all supported repository formats:

- **Maven**, **Gradle**, **Docker**, **npm**, **PyPI**, **Go**, **Helm**, **NuGet**, **Debian**, **RPM**, **Conda**, **RubyGems**, **Conan**, **Hex**, **PHP**, **File**, **Binary**

Each format supports Local, Proxy, and/or Group variants where applicable. For example, Go supports Local, Proxy, and Group; Gradle supports all three variants.

### Configuring Group Members

When creating or editing a **Group** repository (e.g., `maven-group`), the **Group Members** section provides:

- **AutoComplete dropdown**: Type to search existing repositories that are compatible with the group type. For a `maven-group`, only `maven` (local) and `maven-proxy` repositories are shown. Each suggestion displays the repository name and type badge.
- **Reordering**: Use the up/down arrow buttons to set resolution priority. The first matching member wins.
- **Create new member**: Click **Create new** to open an inline dialog that creates a new compatible repository and immediately adds it to the member list.

---

## Keyboard and Navigation Tips

- The sidebar can be collapsed by clicking the toggle for more workspace.
- The sidebar shows your current location with a highlighted active link.
- Use the browser's back/forward buttons to navigate between pages -- the UI uses client-side routing.
- The Profile link is always at the bottom of the sidebar.

---

## Related Pages

- [User Guide Index](index.md)
- [Getting Started](getting-started.md) -- Obtaining access credentials
- [Search and Browse](search-and-browse.md) -- API-based search
- [Cooldown](cooldown.md) -- Understanding cooldown from a user perspective
- [Admin Guide: UI Deployment](../admin-guide/ui-deployment.md) -- Deploying and configuring the UI (for administrators)
