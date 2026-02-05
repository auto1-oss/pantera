## Docker

[Docker registry is server side application that stores and lets you distribute Docker images.](https://docs.docker.com/registry/#what-it-is)
Try the following configuration to use Artipie Docker Repository:

```yaml
repo:
  type: docker
  storage:
    type: fs
    path: /var/artipie/data
```

### Usage example

In order to push Docker image into Artipie repository, let's pull an existing one from Docker Hub:
```bash
docker pull ubuntu
```
Since the Docker registry is going to be located at {host}:{port}/{repository-name}, let's tag
the pulled image respectively:
```bash
docker image tag ubuntu {host}:{port}/{repository-name}/myubuntuimage
```
Then, let's log in into Artipie Docker registry:
```bash
docker login --username {username} --password {password} {host}:{port}
```
And finally, push the pulled image:
```bash
docker push {host}:{port}/{repository-name}/myubuntuimage
```
The image can be pulled as well (first, remove it from local registry as it was downloaded and tagged before):
```bash
docker image rm {host}:{port}/{repository-name}/myubuntuimage
docker pull {host}:{port}/{repository-name}/myubuntuimage
```

### Advanced options

#### Docker on port

Assign a port for the repository to access the image by name without using `{repository-name}` prefix.
To do that we add `port` parameter into repository settings `yaml`:

```yaml
repo:
  port: 5463
  type: docker
  storage:
    type: fs
    path: /var/artipie/data
```

Now we may pull image `{host}:{port}/{repository-name}/myubuntuimage` we pushed before as `{host}:5463/myubuntuimage`:

```bash
docker pull {host}:5463/myubuntuimage
```

In the examples above `{host}` and `{port}` are Artipie service host and port, `{repository-name}`
is the name of Docker repository.

## Docker Group Repository

A Docker group aggregates multiple Docker registries into a single virtual registry.
Artipie merges manifest lists for multi-architecture image support.

```yaml
repo:
  type: docker-group
  port: 5000
  settings:
    repositories:
      - docker-local      # local registry
      - dockerhub-proxy   # proxy to Docker Hub
    group:
      index:
        remote_exists_ttl: 15m
        remote_not_exists_ttl: 5m
      metadata:
        ttl: 5m
```

Members are listed in priority order - if an image exists in multiple registries,
the first member is used. Pull from the group:

```bash
docker pull {host}:5000/myimage:latest
```

See [Group Cache Configuration](../Configuration-Group-Cache) for detailed cache settings.