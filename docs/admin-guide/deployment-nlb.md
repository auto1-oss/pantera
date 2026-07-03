# Deployment behind an NLB

> **Guide:** Admin Guide | **Section:** Deployment behind an NLB

When Pantera sits behind a Layer-4 load balancer (AWS NLB, HAProxy in TCP mode, any other PROXY-protocol-v2 forwarder), the listener sees the LB's IP on every connection -- not the real client IP. The standard fix is PROXY protocol, which the LB prepends as a small prelude on every new connection.

---

## HTTP/1 and HTTP/2

The Vert.x-served HTTP/1 + HTTP/2 listeners already honor the `meta.http_server.use-proxy-protocol` YAML flag. See [Configuration Reference](../configuration-reference.md) for the setting.

---

## HTTP/3

The HTTP/3 listener is served via Jetty's `QuicheServerConnector`. As of v2.2.0, PROXY-protocol support is opt-in via environment variable:

```
PANTERA_HTTP3_PROXY_PROTOCOL=true
```

When set, Pantera prepends Jetty's `ProxyConnectionFactory` to the connector's factory chain. An INFO startup log is emitted with `event.action=http3_proxy_protocol_enabled` and the listener port so operators can confirm activation.

Default is `false` -- enabling PROXY protocol when the LB is not configured to send it will cause every connection to fail at the protocol-prelude parse. Make sure the LB side is sending PROXY v2 before flipping the flag.

The equivalent YAML path (`meta.http3.proxyProtocol`) is not yet wired because `Http3Server`'s constructor does not currently accept a `Settings` object. Use the env var.

---

## Checklist for NLB deployments

1. NLB target-group protocol is TCP (Layer 4). Termination of TLS happens inside Pantera, not the LB.
2. NLB is configured to send PROXY v2 on every target connection (target-group attribute `proxy_protocol_v2.enabled=true` on AWS NLB).
3. For HTTP/1 + HTTP/2: set `meta.http_server.use-proxy-protocol: true` in `pantera.yml`.
4. For HTTP/3: set `PANTERA_HTTP3_PROXY_PROTOCOL=true` in the environment.
5. Verify access logs now show real client IPs (`client.ip` in the ECS JSON line), not the LB's IP.

---

## Related Pages

- [High Availability](high-availability.md) -- Multi-node cluster layout.
- [Environment Variables](environment-variables.md#http-3) -- `PANTERA_HTTP3_*` reference.
