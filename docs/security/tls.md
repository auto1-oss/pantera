# TLS Configuration

Pantera enforces TLS 1.2+ on every endpoint that terminates TLS and on every
outbound HTTPS request to upstream registries. The baseline matches Mozilla
SSL Configuration Generator's **"intermediate"** profile (April 2024 revision).

This document is the authoritative reference; the implementation lives in:

- `pantera-main/src/main/java/com/auto1/pantera/api/ssl/TlsHardening.java`
  (inbound Vert.x server)
- `http-client/src/main/java/com/auto1/pantera/http/client/jetty/JettyClientSlices.java`
  (outbound Jetty client)

## Protocols

| Protocol     | Inbound | Outbound | Rationale                                                                       |
|--------------|---------|----------|---------------------------------------------------------------------------------|
| `SSLv2`      | reject  | reject   | RFC 6176 prohibits SSLv2; broken since 1995.                                    |
| `SSLv2Hello` | reject  | reject   | Backwards-compat handshake header for SSLv2; the protocol itself is forbidden.  |
| `SSLv3`      | reject  | reject   | POODLE (CVE-2014-3566) makes SSLv3 unsafe.                                      |
| `TLSv1`      | reject  | reject   | RFC 8996 deprecates TLS 1.0; PCI DSS 4.0 prohibits its use after 2023-06-30.    |
| `TLSv1.1`    | reject  | reject   | Same RFC 8996 / PCI DSS 4.0 prohibition.                                        |
| `TLSv1.2`    | accept  | accept   | Industry baseline. Vert.x / Jetty / OpenSSL all support out of the box.         |
| `TLSv1.3`    | accept  | accept   | Mandatory AEAD ciphers + 1-RTT handshake. Required for ≥ SOC2-Type-2 audits.    |

The enforcement is strict on both surfaces: a client that offers only
`TLSv1.1` to Pantera is rejected at the handshake stage; an upstream that
only speaks `TLSv1.1` to Pantera is rejected at the same stage and never
sees an HTTP request.

## Cipher suites (TLS 1.2)

The suite list is identical between inbound and outbound. Order is by
client preference: ECDHE forward-secrecy first, AES-256 before AES-128.

```
TLS_AES_256_GCM_SHA384
TLS_CHACHA20_POLY1305_SHA256
TLS_AES_128_GCM_SHA256
TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256
TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
TLS_DHE_RSA_WITH_AES_256_GCM_SHA384
TLS_DHE_RSA_WITH_AES_128_GCM_SHA256
```

Explicitly excluded (must NEVER be added back without a security review):

- `RC4_*` — broken since 2013 (CVE-2013-2566).
- `3DES_*` — Sweet32 (CVE-2016-2183) makes 64-bit block ciphers unsafe.
- `*_NULL_*` — no encryption.
- `*_EXPORT_*` — 40-bit grade ciphers, FREAK (CVE-2015-0204).
- `*_anon_*` — disables peer authentication.

## Cipher suites (TLS 1.3)

TLS 1.3 defines its own AEAD-only cipher suite set (RFC 8446 §B.4); the JVM
and Jetty select from that set automatically. There is no operator knob.

## Hostname verification (outbound)

Pantera's outbound Jetty client sets
`endpointIdentificationAlgorithm = "HTTPS"`, which is the default in Jetty 12
but is set explicitly to prevent a future code change from silently disabling
hostname verification when `trustAll=true` is configured for a dev
environment.

There is no Pantera setting to disable hostname verification. A misconfigured
upstream certificate (CN/SAN mismatch) is a hard error.

## Verifying the configuration

Once a TLS listener is live (e.g. via the operator-guide `ssl.yml` keystore
configuration):

```bash
# Should succeed
openssl s_client -connect localhost:443 -tls1_2 -servername pantera.example
openssl s_client -connect localhost:443 -tls1_3 -servername pantera.example

# Should fail (handshake aborted)
openssl s_client -connect localhost:443 -tls1
openssl s_client -connect localhost:443 -tls1_1

# Enumerate supported suites
nmap --script ssl-enum-ciphers -p 443 localhost
```

No weak ciphers should appear in the `nmap` output; `tls1`/`tls1_1` handshakes
must terminate with `tlsv1 alert protocol version` or equivalent.

## Operational notes

- Cloudflare, Maven Central, Docker Hub, npm registry, PyPI, and every other
  default Pantera upstream support TLS 1.3. No upstream regressions are
  expected from the TLS 1.0/1.1 lockout on outbound calls.
- For air-gapped on-prem upstreams running ancient appliances: open an
  exception ticket and document the upstream in the deployment runbook before
  relaxing the protocol set locally. **Do not patch the trust list silently.**
- The cipher list is JVM-agnostic — Vert.x and Jetty translate the suite
  names through the platform SSL provider. Java 21 maps every name in the
  list above to a real implementation (verified via
  `SSLContext.getInstance("TLS").getSupportedSSLParameters().getCipherSuites()`).

---

# Anonymous Access Controls (v2.2.0+)

Every repo carries two independent flags that decide whether unauthenticated
requests get a `401` challenge or pass through to downstream auth:

| Flag             | Default for proxy repos | Default for hosted repos |
|------------------|------------------------|--------------------------|
| `anonymous_read` | `true`                 | `false`                  |
| `anonymous_write`| `false`                | `false`                  |

The defaults match the Artifactory / Nexus convention — proxy repos stay
curlable for OSS mirrors, hosted repos require credentials on every method.

When a request arrives with **no** `Authorization` header and the matching
flag is `false`, `AnonymousAccessSlice` returns:

```
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Basic realm="pantera"
```

The `WWW-Authenticate` challenge makes every package manager (mvn, npm, pip,
docker login, ...) prompt for credentials instead of silently failing the
request.

YAML overrides per repo:

```yaml
repo:
  anonymous_read: false
  anonymous_write: false
```

The slice is the outermost wrap on every per-repo chain (wired by
`RepositorySlices.wrapIntoCommonSlices`), so the flags apply uniformly across
all 15 adapter types. When the header is present the slice passes through
unconditionally — real credential validation stays the downstream auth
slice's job.

---

# Audit Log (v2.2.0+)

Pantera 2.2.0 introduces a **write-once** audit log for admin operations,
backed by the `audit_log` table. Flyway migration V129 attaches `BEFORE
UPDATE` and `BEFORE DELETE` triggers that raise `feature_not_supported` on
any mutation attempt — a SOC2 / ISO 27001 immutability requirement.

## What is audited

The `AuditEvent` / `AuditService` pipeline is wired into:

- Cooldown unblock (`POST /api/v1/cooldown/unblock`).
- Cooldown unblock-all (`POST /api/v1/cooldown/unblock-all`).
- Repository CRUD (`POST` / `PUT` / `DELETE /api/v1/repositories/...`).
- Negative-cache invalidation.

Each entry records `actor` (username), `action`, `target`, `details` (JSONB),
`success` (boolean), `ip_address`, and `created_at`. Audit entries inherit
the originating HTTP request's `trace.id` via the captured MDC at event
construction.

## Rotation under immutability

`UPDATE` and `DELETE` against `audit_log` will fail. Retention is owned by
either:

1. **Partition rotation (recommended)** — create monthly partitions
   (`audit_log_YYYY_MM`) ahead of time via a `pg_cron` job, then detach and
   drop partitions older than the retention horizon.
2. **`TRUNCATE`** — allowed (bypasses the triggers) but takes an
   `ACCESS EXCLUSIVE` lock. Schedule for a maintenance window. Tests that
   need to reset the table between cases must use `TRUNCATE`, never
   `DELETE`.

---

# PGP Signature Verifier (v2.2.0+, scoped subset)

The Maven adapter ships a PGP detached-signature verifier in 2.2.0. The
**verifier + keyring + DB migration + tests** are in place; the **admin REST
endpoint to upload trusted public keys is deferred** to a follow-up. This is
documented here so operators do not look for an admin UI surface that does
not yet exist.

## Components

- `PgpVerifier` — stateless verifier built on the Bouncy Castle LTS
  distribution already on the classpath. Returns one of five results:
  `VERIFIED`, `TAMPERED`, `UNTRUSTED_KEY`, `MISSING_SIGNATURE`, `MALFORMED`.
- `KeyringStore` — trust-anchor abstraction keyed by 64-bit OpenPGP long
  key id.
- `JdbcKeyringStore` — reads from the `pgp_keyring` table (V131 migration)
  with a 256-entry / 5-minute Caffeine cache to keep verification off the
  HikariCP pool for repeats.
- `InMemoryKeyringStore` — used by tests, no-DB boots, and as an L1 layer.

## Loading trusted keys before the admin endpoint lands

Until the admin REST endpoint ships, populate the `pgp_keyring` table
directly via SQL:

```sql
INSERT INTO pgp_keyring (key_id_hex, fingerprint, public_key_armored,
                         uploaded_by, uploaded_at)
VALUES ('ABCDEF0123456789',
        'ABCDEF0123456789ABCDEF0123456789ABCDEF01',
        '-----BEGIN PGP PUBLIC KEY BLOCK-----\n... -----END PGP PUBLIC KEY BLOCK-----',
        'admin',
        NOW());
```

The `JdbcKeyringStore` cache picks up new keys within 5 minutes (or restart
the instance for immediate effect).
