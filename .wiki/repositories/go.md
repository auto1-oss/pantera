## Go

Go repository is the storage for Go packages, it supports 
[Go Module Proxy protocol](https://golang.org/cmd/go/#hdr-Module_proxy_protocol). 
Here is the configuration example:
```yaml
repo:
  type: go
  storage:
    type: fs
    path: /var/artipie/data

```
Check [storage](./Configuration-Storage) documentations to learn more about storage setting.

In order to use Artipie Go repository, declare the following environment variables:

```bash
export GO111MODULE=on
export GOPROXY="http://{host}:{port}/{repository-name}"
export GOSUMDB=off
# the next property is useful if SSL is not configured
export "GOINSECURE={host}*"
```

Now the package can be installed with the command:

```bash
go get -x golang.org/x/time
```
In the examples above `{host}` and `{port}` are Artipie service host and port, `{repository-name}`
is the name of the repository (and repository name is the name of the repo config yaml file).

There is no way to deploy packages to Artipie Go repository for now.

## Go Group Repository

A Go group aggregates multiple Go module proxies into a single virtual proxy.
Artipie merges `@v/list` version lists, sorting by semantic version.

```yaml
repo:
  type: go-group
  settings:
    repositories:
      - go-local          # local repository
      - goproxy-io        # proxy to proxy.golang.org
    group:
      index:
        remote_exists_ttl: 15m
        remote_not_exists_ttl: 5m
      metadata:
        ttl: 5m
```

Members are listed in priority order. Configure Go client:

```bash
export GOPROXY="http://{host}:{port}/go-group"
go get example.com/mymodule
```

See [Group Cache Configuration](../Configuration-Group-Cache) for detailed cache settings.