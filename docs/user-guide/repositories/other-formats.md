# Other Formats

> **Guide:** User Guide | **Section:** Repositories / Other Formats

This page provides concise setup instructions for less commonly used package formats supported by Pantera: RubyGems, NuGet, Debian, RPM, Conda, Conan, and Hex.

For all formats, you need a Pantera account and JWT token. See [Getting Started](../getting-started.md).

---

## RubyGems

### Client Configuration (~/.gemrc)

```yaml
---
:sources:
  - http://pantera-host:8080/my-gem
```

### Install a Gem

```bash
gem install rails --source http://pantera-host:8080/my-gem
```

### With Bundler (Gemfile)

```ruby
source "http://pantera-host:8080/my-gem"

gem "rails", "~> 7.1"
```

<details>
<summary>Server-Side Repository Configuration</summary>

```yaml
# my-gem.yaml
repo:
  type: gem
  storage:
    type: fs
    path: /var/pantera/data
```

</details>

---

## NuGet

### Add Package Source

```bash
dotnet nuget add source http://pantera-host:8080/my-nuget \
  -n pantera \
  -u your-username \
  -p your-jwt-token \
  --store-password-in-clear-text
```

### Install a Package

```bash
dotnet add package Newtonsoft.Json --source pantera
```

### nuget.config

```xml
<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <packageSources>
    <add key="pantera" value="http://pantera-host:8080/my-nuget" />
  </packageSources>
  <packageSourceCredentials>
    <pantera>
      <add key="Username" value="your-username" />
      <add key="ClearTextPassword" value="your-jwt-token" />
    </pantera>
  </packageSourceCredentials>
</configuration>
```

<details>
<summary>Server-Side Repository Configuration</summary>

```yaml
# my-nuget.yaml
repo:
  type: nuget
  url: http://pantera-host:8080/my-nuget
  storage:
    type: fs
    path: /var/pantera/data
```

</details>

---

## Debian

### Configure APT Source

Add the Pantera repository to your APT sources. The `[trusted=yes]` parameter can be omitted if GPG signing is enabled on the server:

```bash
echo "deb [trusted=yes] http://your-username:your-jwt-token@pantera-host:8080/my-debian my-debian main" | \
  sudo tee /etc/apt/sources.list.d/pantera.list
```

If authentication is required, configure it in `/etc/apt/auth.conf`:

```
machine pantera-host
  login your-username
  password your-jwt-token
```

### Install a Package

```bash
sudo apt update
sudo apt install my-package
```

### Upload a .deb Package

```bash
curl http://your-username:your-jwt-token@pantera-host:8080/my-debian/main \
  --upload-file /path/to/my-package_1.0.0_amd64.deb
```

<details>
<summary>Server-Side Repository Configuration</summary>

```yaml
# my-debian.yaml
repo:
  type: deb
  storage:
    type: fs
    path: /var/pantera/data
  settings:
    Components: main
    Architectures: amd64
    gpg_password: ${GPG_PASSPHRASE}
    gpg_secret_key: secret-keys/my-key.gpg
```

The GPG signing fields are optional but recommended for production:

| Field | Description |
|-------|-------------|
| `gpg_password` | Passphrase for the GPG secret key |
| `gpg_secret_key` | Path to the secret key file, relative to Pantera config storage |

When GPG signing is enabled, clients can verify package signatures and do not need the `[trusted=yes]` parameter in their sources.list entry.

</details>

---

## RPM

### Configure Yum/DNF Repository

Create `/etc/yum.repos.d/pantera.repo`:

```ini
[pantera]
name=Pantera RPM Repository
baseurl=http://pantera-host:8080/my-rpm
enabled=1
gpgcheck=0
```

### Install a Package

```bash
sudo yum install my-package
# or with dnf
sudo dnf install my-package
```

### Upload an .rpm Package

```bash
curl -X PUT \
  -H "Authorization: Basic $(echo -n your-username:your-jwt-token | base64)" \
  --data-binary @my-package-1.0.0-1.x86_64.rpm \
  http://pantera-host:8080/my-rpm/my-package-1.0.0-1.x86_64.rpm
```

Upload supports optional query parameters:

| Parameter | Description |
|-----------|-------------|
| `override=true` | Overwrite an existing package with the same name |
| `skip_update=true` | Upload the package without regenerating repository metadata |

Example with query parameters:

```bash
curl -X PUT \
  -H "Authorization: Basic $(echo -n your-username:your-jwt-token | base64)" \
  --data-binary @my-package-1.0.0-1.x86_64.rpm \
  "http://pantera-host:8080/my-rpm/my-package-1.0.0-1.x86_64.rpm?override=true&skip_update=true"
```

<details>
<summary>Server-Side Repository Configuration</summary>

```yaml
# my-rpm.yaml
repo:
  type: rpm
  storage:
    type: fs
    path: /var/pantera/data
  settings:
    digest: sha256
    naming-policy: sha256
    filelists: true
    update:
      on: upload
```

RPM-specific settings:

| Field | Values | Default | Description |
|-------|--------|---------|-------------|
| `digest` | `sha256`, `sha1` | `sha256` | Checksum algorithm for package metadata |
| `naming-policy` | `plain`, `sha1`, `sha256` | `sha256` | How packages are named in the repository |
| `filelists` | `true`, `false` | `true` | Whether to generate `filelists.xml` metadata |
| `update.on` | `upload` or `cron: "<expression>"` | -- | When to regenerate repository metadata |

The `update.on` field controls when RPM repository metadata is regenerated:

- `upload` -- regenerate metadata after every package upload
- `cron: "0 2 * * *"` -- regenerate metadata on a cron schedule (e.g., daily at 2 AM)

</details>

---

## Conda

### Configure Conda Channel

```bash
conda config --add channels http://pantera-host:8080/my-conda
```

Or in `~/.condarc`:

```yaml
channels:
  - http://pantera-host:8080/my-conda
  - defaults
```

### Install a Package

```bash
conda install my-package
```

<details>
<summary>Server-Side Repository Configuration</summary>

```yaml
# my-conda.yaml
repo:
  type: conda
  url: http://pantera-host:8080/my-conda
  storage:
    type: fs
    path: /var/pantera/data
```

</details>

---

## Conan

### Add Remote

```bash
conan remote add pantera http://pantera-host:9300/my-conan
conan remote login pantera your-username -p your-jwt-token
```

### Install a Package

```bash
conan install . --remote pantera
```

Note: Conan repositories in Pantera use a dedicated port (typically 9300), not the standard 8080 port.

<details>
<summary>Server-Side Repository Configuration</summary>

```yaml
# my-conan.yaml
repo:
  type: conan
  url: http://pantera-host:9300/my-conan
  port: 9300
  storage:
    type: fs
    path: /var/pantera/data
```

</details>

---

## Hex (Elixir/Erlang)

### Configure Mix

In your `mix.exs`, configure the Hex repository:

```elixir
defp deps do
  [
    {:my_dep, "~> 1.0", repo: "pantera"}
  ]
end
```

Register the repository:

```bash
mix hex.repo add pantera http://pantera-host:8080/my-hex \
  --auth-key your-jwt-token
```

### Fetch Dependencies

```bash
mix deps.get
```

<details>
<summary>Server-Side Repository Configuration</summary>

```yaml
# my-hex.yaml
repo:
  type: hexpm
  storage:
    type: fs
    path: /var/pantera/data
```

</details>

---

## Related Pages

- [Getting Started](../getting-started.md) -- Obtaining JWT tokens
- [Troubleshooting](../troubleshooting.md) -- Common error resolution
- [REST API Reference](../../rest-api-reference.md) -- Repository management endpoints
