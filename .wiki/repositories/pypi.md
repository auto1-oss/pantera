## PyPI

PyPI is a [Python Index Repository](https://pypi.org/), it allows to store and distribute python packages. 
Artipie supports this repository type:
```yaml
repo:
  type: pypi
  storage:
    type: fs
    path: /var/artipie/data
```
Check [storage](./Configuration-Storage) documentations to learn more about storage settings.

To publish the packages with [twine](https://packaging.python.org/tutorials/packaging-projects/#uploading-the-distribution-archives)
specify Artipie repository url with `--repository-url` option
```bash
$ twine upload --repository-url http://{host}:{port}/{repository-name} -u {username} -p {password} my-project/dist/*
```

To install the package with `pip install` specify Artipie repository url with `--index-url` option:

```bash
$ pip install --index-url http://{username}:{password}@{host}:{port}/{repository-name} my-project
```

In the examples above `{host}` and `{port}` are Artipie service host and port, `{repository-name}`
is the name of the repository (and repository name is the name of the repo config yaml file),
`username` and `password` are credentials of Artipie user.

## PyPI Group Repository

A PyPI group aggregates multiple Python repositories into a single virtual index.
Artipie merges `/simple/` HTML index pages, deduplicating package links by filename.

```yaml
repo:
  type: pypi-group
  settings:
    repositories:
      - pypi-local        # local repository
      - pypi-proxy        # proxy to pypi.org
    group:
      index:
        remote_exists_ttl: 15m
        remote_not_exists_ttl: 5m
      metadata:
        ttl: 5m
```

Members are listed in priority order - if the same package file exists in multiple members,
the first member's link is used. Install from the group:

```bash
pip install --index-url http://{username}:{password}@{host}:{port}/pypi-group my-package
```

See [Group Cache Configuration](../Configuration-Group-Cache) for detailed cache settings.