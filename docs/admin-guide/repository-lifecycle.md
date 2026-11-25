# Repository Rename and Delete

> **Guide:** Admin Guide | **Section:** Operations

Renaming or deleting a repository takes the same short time whether it holds ten files or hundreds of millions, on a filesystem or on S3. This page explains what happens, what to watch, and what to do when something fails. It applies to deployments with a database; see [Without a database](#without-a-database) for the others.

---

## Repository key

A repository has a **name**, which clients use in URLs and which a rename changes, and an immutable **key**. The key is:

- the directory of the repository's data at the top level of its storage (`<storage root>/<key>/...`, or the S3 prefix `<key>/`);
- the value of `artifacts.repo_name` in the search index.

The key of a repository is its name at creation. It never changes afterwards, so after a rename the directory in the storage keeps the old name. The answer of a rename (`repo_key`) and the database column `repositories.repo_key` tell the key.

A new repository gets the key `<name>~<n>` instead of `<name>` when that directory is not free: it belongs to a deleted repository that is not purged yet, to a renamed repository, or it holds files left by a delete made before 2.2.14. `~` is not allowed in repository names.

When you look into a bucket or a data directory, map directories to repositories with:

```sql
SELECT name, repo_key FROM repositories WHERE name <> repo_key;
```

---

## Rename

`PUT /api/v1/repositories/:name/move`, or **Rename…** on the repository's edit page.

One database transaction changes the name and everything that refers to the repository by name: group member lists, role grants that name it exactly, its repository-scoped storage aliases, cooldown exemptions and live cooldown blocks. No file is copied and no index row is rewritten. Requests in flight complete normally.

- Clients must switch to the new name. With **Keep the old name working for N days** (`keep_old_name_days`) the old name keeps serving the repository for that time, and the name cannot be given to another repository until then.
- Role grants written as patterns (`*`, `maven-*`) are not rewritten. Check that the new name still matches the patterns your roles rely on.
- Other nodes learn of the rename through the cluster bus at once, and in any case within 30 seconds: every node reloads the repository list on a timer and drops what it kept under the old name (cached handlers, negative cache entries, circuit-breaker and bulkhead state, proxy metadata jobs).

---

## Delete

`DELETE /api/v1/repositories/:name`, or **Delete repository…** on the edit page.

1. **At once**, in one transaction: the repository is removed from the repository list, its name is free, it is no longer served or returned by search on any node. Its configuration is kept as a *deleted repository*.
2. **After the restore window** (24 hours by default, `PANTERA_REPO_DELETE_RETENTION_HOURS`, or `retention_hours` on the request): a purge job removes its files, then its search-index rows and cooldown history, then the deleted-repository record.

Until the purge starts, **Restore** brings the repository back with its data, configuration, grants, aliases, exemptions and group memberships.

A repository that is a member of a group is not deleted unless you confirm (`force=true`): it is then removed from those groups.

### What the purge does on each storage

| Storage | How files are removed | Notes |
|---|---|---|
| Filesystem (`fs`, `vertx-file`) | Files are deleted in batches of 1000 in a sorted walk of the directory; directories left empty are removed, including the repository's own. | Memory use does not grow with the number of files. |
| S3 (`s3`) | `ListObjectsV2` pages of 1000 keys and one `DeleteObjects` request per page; unfinished multipart uploads under the prefix are aborted. | S3 allows about 3,500 deletes per second per prefix: 100 million objects take in the order of 8 hours. When S3 answers `SlowDown` the job backs off and continues. |
| S3 with a versioned bucket | As above. | Deleting writes delete markers; the versions stay and are billed until a bucket lifecycle rule expires noncurrent versions. |
| S3 Express (`s3-express`) | Not supported. | Directory buckets cannot be listed from a start key. The job fails with a message saying so: empty the prefix with a lifecycle rule, then press **Retry**. |

Objects created outside Pantera that are not valid keys (console "folder" placeholders such as `repo/dir/`, keys with an empty segment) are not listed and stay.

### Safety against late writes

A request that was uploading or caching a file at the moment of the delete may write after the purge passed that name. The job therefore lists the directory again before it finishes, and for a deleted repository it does so only once the repository has been gone for 30 minutes. Anything found is removed by another pass. If files keep appearing after five passes the attempt fails with *"something still writes there"*.

---

## Repository jobs

**Repository Jobs** in the UI, or `GET /api/v1/repository-jobs`. Every node runs jobs. A job belongs to the node that holds its lease (two minutes, renewed while it works). When that node stops, another node takes the job when the lease runs out and continues after the last file the first node removed.

| State | Meaning | What to do |
|---|---|---|
| `pending` | Waiting for the restore window, for the delay after a failed attempt, or for a node. | Nothing. **Purge now** ends the restore window. |
| `running` | A node works on it; `objects_done` and `rows_done` grow. | Nothing. |
| `failed` | Eight attempts failed; `last_error` says why. | Fix the cause (storage unreachable, permissions, S3 Express), then **Retry**. The job continues where it stopped. |
| `done` / `cancelled` | Finished, or the delete was restored. | Kept for 30 days. |

Each state change is logged by logger `com.auto1.pantera.repojob` (`event.action` `repo_job_start`, `repo_job_progress`, `repo_job_wait`, `repo_job_attempt`, `repo_job_throttled`, `repo_job_lease_lost`, `repo_job_done`) with `repository.name` and the `trace.id` of the delete request.

Metrics:

| Metric | Meaning |
|---|---|
| `pantera_repo_jobs_objects_purged_total` | Storage objects removed by purge jobs on this node. |
| `pantera_repo_jobs_events_total{event,type}` | Jobs `done`, jobs parked as `failed`, and `throttled` storage answers. A growing `failed` count needs an operator. |

A purge deletes at the rate the storage allows and shares the storage with client traffic. It runs one job at a time per node.

---

## Unowned storage directories

Deletes made before 2.2.14 removed files one by one inside the request. A removal that failed or was interrupted, or files written while it ran, left files behind with no repository to own them; an interrupted rename left a partial copy.

**Repository Jobs → Unowned storage directories → Scan storages** (`GET /api/v1/repository-orphans`) lists the top-level directories of every storage in use that hold files and belong to no repository, no deleted repository and no running purge. Directories whose name starts with `.` or `_` are never listed.

Pantera cannot know whether a storage also holds data it does not own. Nothing is removed until you purge a specific directory, and that purge has no restore window. Check the directory first.

---

## Search index after a rename

Index rows carry the repository key, so search, the dashboard and group lookups show the renamed repository under its new name at once. `repo:<name>` filters and the repository facet use the new name. A free-text search for the repository's own name matches the key, that is the name it was created with, not the new name.

---

## Without a database

Rename and delete keep their previous behaviour: files are copied or deleted one by one inside the request, there is no restore window, no job, and no protection against writes made meanwhile. Do not use these deployments for repositories large enough for that to matter.
