# Failed Logins: Login Delay and Account Lock

> **Guide:** Admin Guide | **Section:** Failed Logins

Since 2.2.15 Pantera counts failed logins per user and answers them in two stages, as JFrog Artifactory does: a short delay that grows with every failure, then an account lock. During a delay or a lock Pantera checks nothing and asks no identity provider, so a tool that repeats a wrong password cannot pile failed logins onto the user's directory account.

Every value is an admin setting: **System Settings → Failed logins**, or `GET`/`PUT /api/v1/admin/failed-logins-settings`. A change applies to the next login, on every node, without a restart.

---

## The Policy

| Setting | Key | Values | Default |
|---------|-----|--------|---------|
| Delay logins from failed login number | `failed_logins_delay_from_failure` | 0 (no delay) to 100 | `2` |
| First delay | `failed_logins_first_delay_seconds` | 1 to 60 seconds | `1` |
| Longest delay | `failed_logins_max_delay_seconds` | 1 to 3600 seconds | `60` |
| Lock after failed logins | `failed_logins_lock_after` | 0 (no lock) to 100 | `3` |
| Unlock automatically after | `failed_logins_unlock_after_minutes` | 0 (only an admin unlocks) to 1440 minutes | `0` |
| Forget failures after | `failed_logins_forget_after_minutes` | 0 (never) to 43200 minutes | `0` |
| Count rejected API keys | `failed_logins_count_rejected_keys` | `true` / `false` | `false` |
| A lock blocks every credential | `failed_logins_lock_all_credentials` | `true` / `false` | `true` |

Each key also has an environment variable, `PANTERA_<KEY IN UPPER CASE>`, that is used when the database has no row for it.

**The delay.** From the configured failure on, logins for that user are refused for `first delay × failures²` seconds, up to the longest delay. With the defaults that is 4 seconds after the second failure. With the lock switched off it goes on: 9 seconds after the third failure, 60 from the eighth on.

**The lock.** The failure that reaches the limit locks the user. The lock ends when an admin unlocks the user, when the automatic unlock time has passed (if one is set), or when the lock is switched off: setting the limit to 0 unlocks every locked user within 15 seconds.

**Forget failures after.** With 0, failures count until a successful password login or an unlock, however far apart they are. Three mistyped passwords spread over months then lock a user who normally signs in with an identity provider's button and never resets the count. Set a time, for example 30 minutes, to count only failures that follow each other: a failure that comes later than that after the previous one starts a new count. The time ends no lock.

---

## What Counts

- A **failed password login**: from the sign-in form, the token API, `npm login`, or a repository client that sends a password. One count per user name serves the delay and the lock.
- The name is counted in lower case and without surrounding spaces. `Alice@example.com` and `alice@example.com` are one account at a directory, and one count here.
- The **client address is not part of the count**. A limit per user and address would give an attacker a new allowance from every address.
- A name **without a Pantera user** is counted too: a login that is checked with an identity provider is a failed login of the directory account behind it.
- A **rejected API key** counts only when *Count rejected API keys* is on. The [Sign-in Failures Report](sign-in-failures.md) lists the rejected keys with their owners whether they count or not. Before you turn it on, clear the stale keys your users' tools still send: with the lock at 3, an IDE that holds an old key locks its owner with its first three requests, and again after every unlock.
- A successful password login forgets the failures. A request with a valid API key does not: a running build would otherwise keep clearing the count of an attack on its user.
- While a name has failures on record, its logins are not answered from the credential cache: each is checked, so that the right password does forget the failures. A node learns of a failure counted on another node within 15 seconds.

**Not counted:**

- a login refused during a delay or a lock;
- a login the identity provider refused for a reason of its own: a second factor was asked for (`mfa_required`), the directory account is locked or its password expired;
- a login that could not be checked because the identity provider cannot be reached or limits the rate. Such a login is answered `503`, not `401`.

Logins of one user are checked one at a time on a node. Guesses sent at once therefore reach the identity provider no more often than the lock limit allows. Requests that wait behind one with the same password take its answer when it was accepted, so a build that opens many connections with one right password asks the provider once.

---

## What a Delay and a Lock Do

| State | Answer | Reason on the access record |
|-------|--------|-----------------------------|
| Logins delayed | `403` with `Retry-After` and "Too many failed logins. Try again in N seconds." | `login_delayed` |
| User locked | `403` with "Account locked" | `account_locked` |

Both answers are `403`, as in JFrog Artifactory. A client tells them apart by the `Retry-After` header, which only a delay carries, and by the text; the sign-in form and the management API also carry the codes `LOGIN_DELAYED` and `ACCOUNT_LOCKED`.

With *A lock blocks every credential* on, a locked user can do nothing in Pantera: password logins, API keys (as a password or as a Bearer token), open UI sessions and a sign-in through an identity provider's button are all refused. The sign-in form and the management API answer `403` with the code `ACCOUNT_LOCKED`.

With it off, only the user's password sign-in is locked; API keys, sessions and the provider's button keep working.

A user who is signed in to the UI when the lock comes is signed out with the next request, and the sign-in page says that the account is locked.

**Think about service accounts.** Anyone who can reach Pantera and knows a user name can lock it with wrong passwords, and with every credential blocked that also stops the builds that use the account's API key. Choose an automatic unlock time, or limit the lock to passwords, if that is not acceptable for your installation.

---

## Unlocking

- **UI:** *Users* shows a **Locked** tag and an **Unlock** action per user, a **Locked only** filter and **Unlock all**. A user's page has a **Failed logins** panel with the count, the lock and where the last failed login came from, and **Unlock** (or **Clear failed logins** for a user who is not locked). The *Failed logins* card in System Settings lists every locked name, also those that have no Pantera user. The **Locked only** filter reads the user list in pages and covers the first 2,000 users; the card's list has no such limit.
- **API:** `POST /api/v1/users/:name/unlock` and `POST /api/v1/users/unlock-all`. `GET /api/v1/locked-users` lists the locked names with the client address, client program and sign-in path of their last failed login. Unlocking needs the permission to enable or disable a user.
- **Without a login,** for the case that every administrator is locked: remove the row in the database. Every node picks the change up within 15 seconds; no restart is needed.

```sql
-- one user (the name in lower case)
DELETE FROM login_failures WHERE username = 'alice@example.com';

-- every locked user
DELETE FROM login_failures WHERE locked_at IS NOT NULL;
```

An unlock also sets the user's count back to zero.

---

## Several Nodes

The counts and the locks are in the database (`login_failures`), so all nodes count the same failures and a restart forgets none. Password logins read the database on every attempt. The check that stops a locked user's API key or session answers from memory; a node learns of a lock or an unlock made elsewhere within 15 seconds.

Without a database the counts are kept in memory, per node, and do not survive a restart.

---

## The Sign-in Form's Own Limit

The sign-in form keeps its older limit beside this policy (**System Settings → Login Throttling**): 5 failures per user and client address in 15 minutes, answered `429`. A failed login on the form counts for both. With the lock at 3 a user is locked before that limit is reached; it matters when you raise the lock limit above it or switch the lock off.

A locked user is answered `403` "Account locked" on the form however often they try: the lock is checked before this limit, and those tries are not counted against it.

---

## Monitoring

| Metric | Labels | Meaning |
|--------|--------|---------|
| `pantera_auth_login_refused_total` | `reason` (`login_delayed`, `account_locked`, `sign_in_busy`) | Logins refused without a check |
| `pantera_auth_account_locks_total` | -- | Users locked after failed logins |
| `pantera_auth_account_unlocks_total` | `cause` (`admin`, `automatic`, `lock_off`) | Locks that ended |
| `pantera_auth_provider_checks_total` | `provider`, `outcome` | Passwords sent to an identity provider; see [Authentication](authentication.md#which-credentials-reach-an-identity-provider) |

The **Pantera - Sign-in** dashboard charts them. Every lock and unlock is also a log record (`event.action` `account_lock` / `account_unlock`, with the user and, for an unlock, who did it).

---

## Related Pages

- [Sign-in Failures Report](sign-in-failures.md) -- who fails to sign in, with what, and from where.
- [Authentication](authentication.md)
- [Logging](logging.md)
- [Monitoring](monitoring.md)
- [Configuration Reference](../configuration-reference.md)
- [REST API Reference](../rest-api-reference.md)
