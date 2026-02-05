## Composer

Composer repository is a dependency manager and packages sharing tool for [PHP packages](https://getcomposer.org/).
Here is the configuration example:
```yaml
repo:
  type: php
  url: http://{host}:{port}/{repository-name}
  storage:
    type: fs
    path: /var/artipie/data
```
The Composer repository configuration requires `url` field that contains repository full URL,
`{host}` and `{port}` are Artipie service host and port, `{repository-name}`
is the name of the repository (and repository name is the name of the repo config yaml file). Check
[storage](./Configuration-Storage) documentation to learn more about storage setting.

To upload the file into repository, use `PUT` HTTP request:
```bash
curl -X PUT -T 'log-1.1.4.zip' "http://{host}:{port}/{repository-name}/log-1.1.4.zip"
```
To use packages from Artipie repository in PHP project, add requirement and repository to `composer.json`:
```json
{
  "config": { "secure-http": false },
  "repositories": [
    { "type": "composer", "url": "http://{host}:{port}/{repository-name}" },
    { "packagist.org": false }
  ],
  "require": { "log": "1.1.4" }
}
```

## Composer Group Repository

A Composer group aggregates multiple PHP package repositories into a single virtual repository.
Artipie merges `packages.json` metadata from all members.

```yaml
repo:
  type: php-group
  url: http://{host}:{port}/php-group
  settings:
    repositories:
      - php-local         # local repository
      - packagist-proxy   # proxy to packagist.org
    group:
      index:
        remote_exists_ttl: 15m
        remote_not_exists_ttl: 5m
      metadata:
        ttl: 5m
```

Members are listed in priority order - if the same package version exists in multiple members,
the first member's metadata is used. Configure in `composer.json`:

```json
{
  "repositories": [
    { "type": "composer", "url": "http://{host}:{port}/php-group" }
  ]
}
```

See [Group Cache Configuration](../Configuration-Group-Cache) for detailed cache settings.