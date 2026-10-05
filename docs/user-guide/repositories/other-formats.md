# Other Formats

> **Guide:** User Guide | **Section:** Repositories / Other Formats

This page provides concise setup instructions for less commonly used package formats supported by Pantera: RubyGems, NuGet, Debian, RPM, Conda, Conan, and Hex.

For all formats, you need a Pantera account and an API token. See [Getting Started](../getting-started.md). The UI's **Set Me Up** page (`/setup/<format>`) generates these commands with your registry URL, repository and token filled in.

---

## RubyGems

### Add the Source

RubyGems only authenticates a source through its URL, so the credentials go into `~/.gemrc` (percent-encode a username containing `@`, e.g. `me%40example.com`):

```bash
gem sources --add http://your-username:your-api-token@pantera-host:8080/my-gem/
```

### Install a Gem

```bash
gem install my-gem
```

### With Bundler (Gemfile)

Store the credentials in `~/.bundle/config` so they stay out of the Gemfile:

```bash
bundle config set --global http://pantera-host:8080/my-gem/ 'your-username:your-api-token'
```

```ruby
source "https://rubygems.org"

source "http://pantera-host:8080/my-gem/" do
  gem "my-gem"
end
```

### Push a Gem

`gem push` sends the stored key verbatim as the `Authorization` header, so store a Basic credential for the push host:

```bash
mkdir -p ~/.gem
echo "http://pantera-host:8080/my-gem: Basic $(printf %s 'your-username:your-api-token' | base64 | tr -d '\n')" >> ~/.gem/credentials
chmod 0600 ~/.gem/credentials
gem build my-gem.gemspec
gem push my-gem-0.1.0.gem --host http://pantera-host:8080/my-gem
```

Or upload with curl (answers `201`):

```bash
curl -f -u 'your-username:your-api-token' --data-binary @my-gem-0.1.0.gem http://pantera-host:8080/my-gem/api/v1/gems
```

A file that cannot be read as a gem (for example a truncated upload) answers `400` with the reason in the body.

Pushing a gem version that is already stored answers `409 Conflict` (`Repushing of gem versions is not allowed: ...`) when the repository's **Immutable artifacts** setting is on, even for an identical file; when it is off, the push replaces the stored gem. Gem repositories created before Pantera 2.2.10 in a database-backed installation keep the old behaviour (replace) until the administrator turns the setting on. See [Overwrite rules](../getting-started.md#overwrite-rules-immutable).

### Delete a Gem

Gems are stored as `gems/<name>-<version>.gem`. With the `delete` permission, delete one with an HTTP `DELETE` of that path (answers `204`, or `404` when nothing is stored there); `specs.4.8`, `latest_specs.4.8` and `prerelease_specs.4.8` are rebuilt without it:

```bash
curl -f -u 'your-username:your-api-token' -X DELETE http://pantera-host:8080/my-gem/gems/my-gem-0.1.0.gem
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

Always use the `/index.json` service index URL: without it dotnet treats the source as a v2 feed and gets `404`.

### Add Package Source

```bash
dotnet nuget add source http://pantera-host:8080/my-nuget/index.json \
  --name pantera \
  --username your-username \
  --password your-api-token \
  --store-password-in-clear-text \
  --allow-insecure-connections
```

`--allow-insecure-connections` is needed for a plain-HTTP registry on .NET SDK 9 and later; omit it for HTTPS or on the .NET 8 SDK.

### Install a Package

`dotnet add package --source` takes a URL, not a source name:

```bash
dotnet add package Newtonsoft.Json --source http://pantera-host:8080/my-nuget/index.json
```

### Push a Package

Pantera authenticates the push with the credentials stored for the source:

```bash
dotnet pack -c Release -o nupkg
dotnet nuget push "nupkg/*.nupkg" --source pantera --skip-duplicate
```

You can also pass your API token as the NuGet API key (sent as the `X-NuGet-ApiKey` header); it is validated like any other Pantera token. The key only authenticates the push itself: dotnet first reads the service index with the credentials stored for the source, and the index requires valid credentials. Push to the source added above, not to a bare URL:

```bash
dotnet nuget push "nupkg/*.nupkg" --source pantera \
  --api-key your-api-token --skip-duplicate
```

Pushing a package version that is already stored answers `409 Conflict` when the repository's **Immutable artifacts** setting is on (the default), even for an identical package; `--skip-duplicate` turns that into a skipped push. When the setting is off, the push replaces the stored version. See [Overwrite rules](../getting-started.md#overwrite-rules-immutable).

### Delete a Package Version

With the `delete` permission, `dotnet nuget delete` removes a version (a hard delete, not an unlist). It authenticates like a push, with the credentials stored for the source or with `--api-key`:

```bash
dotnet nuget delete My.Package 1.2.3 --source pantera --non-interactive
```

It sends `DELETE /my-nuget/package/<id>/<version>` and answers `204`, or `404` when that version is not stored. The version's `.nupkg`, `.nuspec` and hash are removed, it is dropped from `<id>/index.json`, and search stops returning it.

Packages are stored as `<id>/<version>/<id>.<version>.nupkg`, with the id and version in lower case, so an HTTP `DELETE` of a version's storage directory works too (answers `204`, or `404` when nothing is stored there):

```bash
curl -f -u 'your-username:your-api-token' -X DELETE \
  http://pantera-host:8080/my-nuget/newtonsoft.json/13.0.3
```

### nuget.config

```xml
<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <packageSources>
    <add key="pantera" value="http://pantera-host:8080/my-nuget/index.json" protocolVersion="3" allowInsecureConnections="true" />
  </packageSources>
  <packageSourceCredentials>
    <pantera>
      <add key="Username" value="your-username" />
      <add key="ClearTextPassword" value="your-api-token" />
    </pantera>
  </packageSourceCredentials>
</configuration>
```

Keep this file out of version control: it holds your token.

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

### Store Credentials

Keep credentials out of the source list (which any user can read) and put them in `/etc/apt/auth.conf.d/`:

```bash
sudo tee /etc/apt/auth.conf.d/pantera.conf > /dev/null <<'EOF'
machine http://pantera-host:8080/my-debian
login your-username
password your-api-token
EOF
sudo chmod 600 /etc/apt/auth.conf.d/pantera.conf
```

On a plain-HTTP registry the `machine` entry **must** include the `http://` scheme, otherwise apt does not send the credentials. For HTTPS, use `machine pantera-host/my-debian`.

### Configure APT Source

The distribution is the repository name and the component is `main`. `[trusted=yes]` is needed unless GPG signing is configured on the repository; for a signed repository use `[signed-by=/etc/apt/keyrings/pantera.gpg]` instead:

```bash
echo "deb [trusted=yes] http://pantera-host:8080/my-debian my-debian main" | \
  sudo tee /etc/apt/sources.list.d/pantera.list
```

### Install a Package

```bash
sudo apt-get update
apt-cache policy my-package
sudo apt-get install my-package
```

### Upload a .deb Package

Upload into `pool/main/`. The trailing `/` makes curl append the file name, so each package is stored under its own name:

```bash
curl -f -u 'your-username:your-api-token' \
  -T my-package_1.0.0_amd64.deb \
  http://pantera-host:8080/my-debian/pool/main/
```

The package's `Architecture` must be one of the repository's `Architectures`, otherwise the server answers `400` and names both. An architecture-independent package (`Architecture: all`) needs `all` in the repository's `Architectures`. A file that is not a Debian package answers `400` with the reason. The upload path must end with the `.deb` file name (and must not be under `dists/`); a bare directory such as `/main` answers `400`. A refused upload leaves the package already stored at that path in place.

Uploading to a path that already holds a package answers `409 Conflict` (`Package <path> already exists and the repository is immutable`) when the repository's **Immutable artifacts** setting is on, even for an identical file; when it is off, the upload replaces the package and the indexes. The check is by path: the same package version uploaded under a different file name is not refused. Debian repositories created before Pantera 2.2.10 in a database-backed installation keep replacing until the administrator turns the setting on. See [Overwrite rules](../getting-started.md#overwrite-rules-immutable).

### Delete a .deb Package

With the `delete` permission, delete a package with an HTTP `DELETE` of its path. The package is removed from the `Packages` indexes and from search; the answer is `200`, or `404` when no package is stored at that path:

```bash
curl -f -u 'your-username:your-api-token' -X DELETE \
  http://pantera-host:8080/my-debian/pool/main/my-package_1.0.0_amd64.deb
```

A Debian repository deletes single packages only, not directories.

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

Create `/etc/yum.repos.d/pantera.repo`; dnf and yum send `username`/`password` as HTTP Basic auth. The file holds your token, so make it readable by root only:

```bash
sudo tee /etc/yum.repos.d/pantera.repo > /dev/null <<'EOF'
[pantera]
name=Pantera my-rpm
baseurl=http://pantera-host:8080/my-rpm
username=your-username
password=your-api-token
enabled=1
gpgcheck=0
repo_gpgcheck=0
skip_if_unavailable=0
EOF
sudo chmod 600 /etc/yum.repos.d/pantera.repo
```

`skip_if_unavailable=0` makes dnf fail loudly instead of silently skipping the repository when the credentials are wrong. Set `gpgcheck=1` with `gpgkey=` if your packages are signed.

### Install a Package

```bash
sudo dnf --repo pantera --refresh makecache   # verify: prints "Metadata cache created."
sudo dnf install my-package
```

### Upload an .rpm Package

`-T` sends an HTTP PUT; the trailing `/` makes curl append the file name. The repository metadata is regenerated after the upload:

```bash
curl -f -u 'your-username:your-api-token' \
  -T my-package-1.0.0-1.x86_64.rpm \
  http://pantera-host:8080/my-rpm/
```

An existing file name answers `409`. Upload supports optional query parameters:

| Parameter | Description |
|-----------|-------------|
| `override=true` | Overwrite an existing package with the same name. Honoured only when the repository's **Immutable artifacts** setting is off; on an immutable repository (the default for new repositories) the upload still answers `409` |
| `skip_update=true` | Upload the package without regenerating repository metadata |

RPM repositories created before Pantera 2.2.10 in a database-backed installation keep honouring `override=true` until the administrator turns the setting on. See [Overwrite rules](../getting-started.md#overwrite-rules-immutable).

Example with query parameters:

```bash
curl -f -u 'your-username:your-api-token' \
  -T my-package-1.0.0-1.x86_64.rpm \
  "http://pantera-host:8080/my-rpm/my-package-1.0.0-1.x86_64.rpm?override=true&skip_update=true"
```

### Delete an .rpm Package

With the `delete` permission, delete a package with an HTTP `DELETE` of its file name:

```bash
curl -f -u 'your-username:your-api-token' -X DELETE \
  http://pantera-host:8080/my-rpm/my-package-1.0.0-1.x86_64.rpm
```

The answer is `202 Accepted`. In the default `update: on: upload` mode (and without `skip_update=true`) the package is removed and the repository metadata rewritten before the response; otherwise the removal is applied by the next metadata update. A file that is not stored answers `404`. Search stops returning the deleted package.

To make sure you delete the file you expect, add its checksum in an `X-Checksum-<algorithm>` header (`SHA-256`, `SHA-1`, `SHA-512` or `MD5`): a checksum that does not match, or an unknown algorithm, answers `400` and nothing is removed. `?force=true` is still accepted but no longer needed:

```bash
curl -f -u 'your-username:your-api-token' -X DELETE \
  -H "X-Checksum-SHA-256: $(sha256sum my-package-1.0.0-1.x86_64.rpm | cut -d' ' -f1)" \
  http://pantera-host:8080/my-rpm/my-package-1.0.0-1.x86_64.rpm
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

### Store Credentials

conda reads `~/.netrc` for any channel URL without credentials (`%USERPROFILE%\_netrc` on Windows), so the channel URL carries none:

```bash
cat >> ~/.netrc <<'EOF'
machine pantera-host login your-username password your-api-token
EOF
chmod 600 ~/.netrc
```

### Configure Conda Channel

```bash
conda config --prepend channels http://pantera-host:8080/my-conda
```

To keep the credential in the channel URL instead, give conda the token hex-encoded: conda keeps only the letters, digits and `-` of a `/t/<token>` channel token, and a Pantera token contains `.` and `_`. Pantera decodes a hex-encoded token:

```bash
conda config --prepend channels \
  "http://pantera-host:8080/my-conda/t/$(printf %s 'your-api-token' | xxd -p | tr -d '\n')"
```

### Install a Package

```bash
conda install my-package
```

### Upload a Package

Upload the built package (`.tar.bz2` or `.conda`) to its subdir (`noarch`, `linux-64`, ...) with the `token` authorization scheme. The server answers `201`:

```bash
PKG=conda-bld/noarch/my-package-1.0.0-0.tar.bz2
SUBDIR=$(basename "$(dirname "$PKG")")
curl -fsS -H 'Authorization: token your-api-token' \
  -F "file=@$PKG" \
  "http://pantera-host:8080/my-conda/$SUBDIR/$(basename "$PKG")"
```

A file that is not a conda package answers `400` with the reason. Uploading a file that is already stored answers `409 Conflict` (`Package <subdir>/<file> already exists and the repository is immutable`) when the repository's **Immutable artifacts** setting is on, even for an identical file; when it is off, the upload replaces the package and its `repodata.json` entry. Conda repositories created before Pantera 2.2.10 in a database-backed installation keep replacing until the administrator turns the setting on. See [Overwrite rules](../getting-started.md#overwrite-rules-immutable).

With the `delete` permission, delete a package with an HTTP `DELETE` of its path, `<subdir>/<file>` (answers `204`, or `404` when nothing is stored there); it is removed from the subdir's `repodata.json`:

```bash
curl -f -u 'your-username:your-api-token' -X DELETE \
  http://pantera-host:8080/my-conda/noarch/my-package-1.0.0-0.tar.bz2
```

`anaconda upload` works too. Point anaconda-client at the repository and log in with your Pantera credentials:

```bash
anaconda config --set url http://pantera-host:8080/my-conda
anaconda login --username your-username --password your-api-token
anaconda upload conda-bld/noarch/my-package-1.0.0-0.tar.bz2
```

`anaconda login` and `anaconda upload` first check the server with a `HEAD` of the repository URL; that request needs no credentials and answers `200`. anaconda-client posts the package file without credentials, so the authenticated stage step hands it a single-use upload URL. That URL is valid for 10 minutes, for that one file only, and needs WRITE permission on the repository. It is built from the repository's configured `url`, so `url` must be the address clients use.

The URL is single-use across the whole cluster when Pantera runs with Valkey. Without Valkey it is single-use per Pantera instance, which is enough for a single-instance deployment. The URL is a credential until it is used or expires: Pantera masks the `/t/<token>/` segment in its own logs, but a reverse proxy in front of Pantera logs it in full unless you configure it not to.

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

Pantera speaks the Conan 1.x protocol; these commands are for Conan 1.60 with revisions disabled (the default). Conan 2 clients are not supported: Conan 2 always uses package revisions, and Pantera only serves numeric revisions.

A Conan repository is served on the main registry address under its name. If the repository has a dedicated `port`, it is served at the root of that port instead. The download and upload URLs Pantera returns to the client point back at the address the remote was added with: on the main port including any path prefix, on a dedicated port `http://` plus the host and port the client connected to.

### Add Remote

```bash
conan remote add pantera http://pantera-host:8080/my-conan
conan user 'your-username' -r pantera -p 'your-api-token'
```

For a repository with a dedicated port, use `http://pantera-host:9300` as the remote URL.

In CI, set `CONAN_LOGIN_USERNAME_PANTERA` and `CONAN_PASSWORD_PANTERA` instead of running `conan user`.

### Install a Package

```bash
conan install my_package/1.0@ -r pantera
```

### Upload a Package

```bash
conan create .
conan upload my_package/1.0@ -r pantera --all --confirm
```

Uploading needs write permission on the repository. Conan does not send your token with the files it uploads to a signed URL, so each upload URL Pantera returns is signed for you, for that one file in that one repository and for the host it was requested through, and expires after one hour. Treat it as a credential until then. The file upload is accepted only while you still have write permission. If a file upload answers 401, check that the remote URL uses the same host and port as the URLs in the `upload_urls` response.

When the repository's **Immutable artifacts** setting is on (the default for new repositories), files that are already stored cannot be uploaded again: the `upload_urls` request answers `404` naming the first stored file (`... already exists and the repository is immutable; it cannot be overwritten`), and an upload to a signed URL of a stored file answers `409`. When the setting is off, an upload replaces the stored files. Conan repositories created before Pantera 2.2.10 in a database-backed installation keep replacing until the administrator turns the setting on. See [Overwrite rules](../getting-started.md#overwrite-rules-immutable).

With the `delete` permission, delete a recipe and its packages with an HTTP `DELETE` of its storage directory, `<name>/<version>` (answers `204`, or `404` when nothing is stored there):

```bash
curl -f -u 'your-username:your-api-token' -X DELETE \
  http://pantera-host:8080/my-conan/my_package/1.0
```

<details>
<summary>Server-Side Repository Configuration</summary>

```yaml
# my-conan.yaml
repo:
  type: conan
  storage:
    type: fs
    path: /var/pantera/data
```

Add `port: 9300` to serve the repository on its own port. On the main port, `url: http://pantera-host:8080/my-conan` pins the address used in the download and upload URLs instead of taking it from the request.

</details>

---

## Hex (Elixir/Erlang)

### Register the Repository

Pantera signs its Hex registry. Download the repository's public key, then register the repository under the **same name as the Pantera repository** (Hex checks that registry records come from a repository of that name). Mix sends `--auth-key` verbatim as the `Authorization` header, so the key is a Basic credential built from your username and token:

```bash
curl -fsS -u 'your-username:your-api-token' -o pantera-hex.pem \
  http://pantera-host:8080/my-hex/public_key
mix hex.repo add my-hex http://pantera-host:8080/my-hex \
  --public-key pantera-hex.pem \
  --auth-key "Basic $(printf %s 'your-username:your-api-token' | base64 | tr -d '\n')"
```

### Configure Mix

In your `mix.exs`:

```elixir
defp deps do
  [
    {:my_dep, "~> 1.0", repo: "my-hex"}
  ]
end
```

### Fetch Dependencies

```bash
mix deps.get
```

### Publish a Package

Build the tarball and upload it with curl (answers `201`; `replace=true` overwrites an existing version on a repository whose **Immutable artifacts** setting is off):

```bash
mix hex.build
curl -fsS -u 'your-username:your-api-token' \
  -H 'Content-Type: application/octet-stream' \
  --data-binary @my_package-0.1.0.tar \
  'http://pantera-host:8080/my-hex/publish?replace=false'
```

`mix hex.publish package` works too; point Hex at the repository with the same Basic credential:

```bash
HEX_API_URL=http://pantera-host:8080/my-hex \
HEX_API_KEY="Basic $(printf %s 'your-username:your-api-token' | base64 | tr -d '\n')" \
  mix hex.publish package --yes
```

It uses the release endpoint `POST /my-hex/packages/<name>/releases`, which accepts the same tarball and answers in the format the client asked for (an Erlang term for Hex, JSON for `Accept: application/json`). Publishing a version that already exists without `replace=true` (`--replace`) answers `422`; a body that is not a Hex tarball answers `400`. When the repository's **Immutable artifacts** setting is on (the default for new repositories), publishing an existing version answers `422` even with `replace=true` (`Version <version> already exists and this repository does not allow replacing releases`). Hex repositories created before Pantera 2.2.10 in a database-backed installation keep honouring `replace=true` until the administrator turns the setting on. See [Overwrite rules](../getting-started.md#overwrite-rules-immutable).

With the `delete` permission, `mix hex.publish --revert` removes a release, using the same `HEX_API_URL` and `HEX_API_KEY` as the publish:

```bash
HEX_API_URL=http://pantera-host:8080/my-hex \
HEX_API_KEY="Basic $(printf %s 'your-username:your-api-token' | base64 | tr -d '\n')" \
  mix hex.publish --revert 0.1.0
```

It sends `DELETE /my-hex/packages/<name>/releases/<version>` (also accepted under `/api` and `/repos/<org>`, like the publish endpoints) and answers `204`, or `404` when that release is not stored. The tarball `tarballs/<name>-<version>.tar` is removed, the release leaves the package's registry record (the record goes with its last release), and search stops returning it. An HTTP `DELETE` of the tarball's storage path does the same:

```bash
curl -fsS -u 'your-username:your-api-token' -X DELETE \
  http://pantera-host:8080/my-hex/tarballs/my_package-0.1.0.tar
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

- [Getting Started](../getting-started.md) -- Obtaining API tokens
- [Troubleshooting](../troubleshooting.md) -- Common error resolution
- [REST API Reference](../../rest-api-reference.md) -- Repository management endpoints
