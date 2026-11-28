# Sign-in Failures Report

> **Guide:** Admin Guide | **Section:** Sign-in Failures Report | **Since:** 2.2.15

The report answers one question: **who fails to sign in, with what, and from where?** It lists rejected API keys with their owner, failed password logins with the user's delay and lock, and sign-ins whose password was right but whose second factor was not confirmed. Use it to find the tool that still sends an old key or password before that tool locks its user or reaches the directory behind an identity provider.

- **UI:** *Administration → Sign-in failures*. At the top, the users locked now, with where their last failed login came from, **Unlock** per name and **Unlock all** (see [Failed Logins](failed-logins.md#unlocking)); below them three tabs. Each tab can be filtered by user and by client address. An unlock in either place updates the other.
- **API:** `GET /api/v1/sign-in-failures/api-keys`, `/passwords` and `/second-factor`. See the [REST API reference](../rest-api-reference.md#get-apiv1sign-in-failuresapi-keys).
- **Every user** sees their own rejected keys on their profile page, and with `GET /api/v1/auth/rejected-keys`.

The lists of all users need the permission to read users.

---

## Rejected API Keys

An API key is signed by Pantera and carries its owner and its own id. Pantera checks the signature apart from the expiry date, so a key that is expired or revoked still proves whose key it was.

| Reason | Meaning | What to do |
|--------|---------|------------|
| `api_key_expired` | The key is past its expiry date. | The owner creates a new key and puts it into the tool that sent this one. |
| `api_key_revoked` | The key was revoked or removed. | The same. |
| `api_key_refused` | The key is valid but was not accepted: its owner is disabled, or it was sent with another user name. | Enable the user, or correct the user name in the tool. |
| `api_key_unknown` | This installation did not sign it: a key of another installation (a qa key sent to prod), or not a key at all. | Replace it with a key of this installation. |

A line shows the owner, the name of the key and its dates, the reason, how often it was rejected and when first and last, and the client address, client program and path (`repository` or `api`) of the last request.

An `api_key_unknown` line is marked **Not verified**: the key proves nothing, so the user on that line is only the name the client sent, and anyone can type a name.

The client is told the reason too: the `401` says "API key expired", "API key revoked", "API key not accepted" or "API key not valid here", and the access record carries the reason as `event.reason`.

**Before you let rejected keys count towards the lock** (*Count rejected API keys* on the Failed logins card, off by default): clear this list first. With the lock at 3, a tool that holds an old key locks its owner with its first three requests, and again after every unlock.

---

## Failed Password Logins

One line per user and client address: failed logins (these are the ones that count towards the lock), logins refused without a check during a delay or a lock, the reason of the last one (`wrong_credentials`, `login_delayed`, `account_locked`), when first and last, the client program and the sign-in path (`ui` for the sign-in form, `api`, `repository`).

Each line also shows the user's state now: the failed logins on record, a running delay, a lock and since when, and the last unlock by an administrator with the administrator's name. A locked user can be unlocked from the list.

A name need not be a Pantera user: a login that is checked with an identity provider is a failed login of the directory account behind it, whether or not Pantera knows the user.

See [Failed Logins](failed-logins.md) for the delay and the lock themselves.

---

## Second Factor

One line per user and client address for sign-ins whose password was right but whose second factor was not confirmed: an Okta push denied or not answered, or a wrong code. These never count as failed logins; after the limit they pause password sign-in of the user (see [Failed Logins](failed-logins.md#the-pause)).

A line shows how many sign-ins were not confirmed, how many were refused without a check during a pause, the reason of the last one (`second_factor_failed` or `sign_in_paused`), the count toward the pause at the last one, when the pause ends and whether it still runs, when first and last, the client program and the sign-in path.

A user with such lines approved no push that someone started with the right password. If that was not the user, someone else knows the password: have the user change it.

---

## How Current the Lists Are

A sign-in failure is noted in memory and nothing else happens on the request path. Once a minute a node writes what it noted, as one line per user, client address and key, however many requests that stands for: a tool that repeats a failing request every second costs one row a minute.

- The node that answers a list writes its own notes first, so its own events are in the list at once.
- An event noted by another node appears within a minute.
- A node keeps at most 5,000 lines in memory between two writes. Beyond that, events of further users and addresses are not noted, and a `WARN` record `sign_in_report_overflow` says how many. That takes more than 5,000 different users and addresses failing within one minute.
- Without a database the lines are kept in memory, per node, at most 20,000, and do not survive a restart.

---

## Settings

**System Settings → Sign-in report**, or `PUT /api/v1/admin/sign-in-report-settings`. Both apply on every node without a restart.

| Setting | Key | Default | Meaning |
|---------|-----|---------|---------|
| Record sign-in failures | `sign_in_report_enabled` | `true` | Off: nothing is noted or stored. Lines already stored stay until their retention ends. |
| Keep lines for (days) | `sign_in_report_retention_days` | `90` | 1 to 3650. A line is removed this many days after its last event. |

Environment fallbacks for an installation without a database: `PANTERA_SIGN_IN_REPORT_ENABLED`, `PANTERA_SIGN_IN_REPORT_RETENTION_DAYS`.

**What is stored:** user names, client addresses and client programs of failed sign-ins, in the table `sign_in_failures`. No password and no key is stored, only the id of a key. Choose the retention, or switch the report off, to match your rules for such data.

---

## Monitoring

| Metric | Labels | Meaning |
|--------|--------|---------|
| `pantera_auth_api_key_rejected_total` | `reason` | API keys rejected, by reason. Counted also while the report is off. |
| `pantera_auth_second_factor_failures_total` | -- | Sign-ins whose second factor was not confirmed. |
| `pantera_auth_sign_in_pauses_total` | -- | Pauses of password sign-in after unconfirmed second factors. |

All are on the **Pantera - Sign-in** dashboard. The counters say how much; the report says who.

---

## Related Pages

- [Failed Logins](failed-logins.md) -- the login delay, the account lock and the second-factor pause.
- [Authentication](authentication.md) -- providers, API keys, and where a provider takes passwords.
- [Monitoring](monitoring.md) -- sign-in metrics.
