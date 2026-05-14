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
