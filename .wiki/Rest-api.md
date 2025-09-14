# Artipie management Rest API

Artipie provides Rest API to manage [repositories](./Configuration-Repository), [users](./Configuration-Credentials) 
and [storages aliases](./Configuration-Storage#Storage-Aliases). API is self-documented with [Swagger](https://swagger.io/)
interface, Swagger documentation pages are available on URLs `http://{host}:{api}/api/index.html`.

In Swagger documentation have three definitions - Repositories, Users and Auth Token. You can switch
between the definitions with the help of "Select a definition" listbox.

<img src="https://user-images.githubusercontent.com/14931449/193015387-3e25f937-7f23-4b27-884c-f183ca9dc8a0.png" alt="Swagger documentation" width="400"/>

All Rest API endpoints require JWT authentication token to be passed in `Authorization: Bearer <token>` header. 
The token can be issued with the help of `POST /api/v1/oauth/token` request on the "Auth Token" 
definition page in Swagger. Once token is received, copy it, open another definition, press 
"Authorize" button and paste the token. Swagger will add the token to any request you perform.

Note: The same bearer token can be used to authenticate repository uploads/downloads. All repository
adapters accept bearer tokens in addition to Basic auth.

## Manage repository API

Rest API allows to manage repository settings: read, create, update and remove operations are supported. 
Note, that jsons, accepted by Rest endpoints, are equivalents of the YAML repository settings. Which means, 
that API accepts all the repository specific settings fields which are applicable to the repository. 
Choose repository you are interested in from [this table](./Configuration-Repository#Supported-repository-types) 
to learn all the details.

### Repository JSON payloads by type

All payloads follow this envelope:

```
{
  "repo": {
    "type": "<type>",
    "storage": "default" | { "type": "fs", "path": "/path" },
    "port": 8081,       // optional: run on dedicated port
    "http3": true,      // optional: enable HTTP/3 for port-bound repos
    "settings": { ... },
    "remotes": [ { "url": "https://remote", "username": "u", "password": "p", "priority": 0 } ]
  }
}
```

Below are minimal examples per repository type. Use either a storage alias string or full storage object.

- file:
  ```
  { "repo": { "type": "file", "storage": "default" } }
  ```
- file-proxy (mirror):
  ```
  { "repo": { "type": "file-proxy", "remotes": [ { "url": "https://example.com" } ], "storage": { "type": "fs", "path": "/var/cache/files" } } }
  ```
- maven:
  ```
  { "repo": { "type": "maven", "storage": "default" } }
  ```
- maven-proxy:
  ```
  { "repo": { "type": "maven-proxy", "remotes": [ { "url": "https://repo1.maven.org/maven2" } ], "storage": { "type": "fs", "path": "/var/cache/maven" } } }
  ```
- npm:
  ```
  { "repo": { "type": "npm", "url": "http://host:8080/my-npm", "storage": "default" } }
  ```
- npm-proxy:
  ```
  { "repo": { "type": "npm-proxy", "settings": { "remote": { "url": "https://registry.npmjs.org" } }, "storage": { "type": "fs", "path": "/var/cache/npm" } } }
  ```
- gem:
  ```
  { "repo": { "type": "gem", "storage": "default" } }
  ```
- helm:
  ```
  { "repo": { "type": "helm", "url": "http://host:8080/helm", "storage": "default" } }
  ```
- rpm:
  ```
  { "repo": { "type": "rpm", "storage": "default", "settings": { "Components": "main", "Architectures": "amd64" } } }
  ```
- php (Composer):
  ```
  { "repo": { "type": "php", "url": "http://host:8080/php", "storage": "default" } }
  ```
- php-proxy (Composer proxy):
  ```
  { "repo": { "type": "php-proxy", "remotes": [ { "url": "https://repo.packagist.org" } ], "storage": { "type": "fs", "path": "/var/cache/composer" } } }
  ```
- nuget:
  ```
  { "repo": { "type": "nuget", "url": "http://host:8080/nuget/index.json", "storage": "default" } }
  ```
- pypi:
  ```
  { "repo": { "type": "pypi", "storage": "default" } }
  ```
- pypi-proxy:
  ```
  { "repo": { "type": "pypi-proxy", "remotes": [ { "url": "https://pypi.org/simple" } ], "storage": { "type": "fs", "path": "/var/cache/pypi" } } }
  ```
- docker:
  ```
  { "repo": { "type": "docker", "storage": "default" } }
  ```
  With dedicated port and HTTP/3:
  ```
  { "repo": { "type": "docker", "storage": "default", "port": 5000, "http3": true } }
  ```
- docker-proxy:
  ```
  { "repo": { "type": "docker-proxy", "remotes": [ { "url": "https://registry-1.docker.io" } ], "storage": { "type": "fs", "path": "/var/cache/docker" } } }
  ```
- deb (Debian):
  ```
  { "repo": { "type": "deb", "storage": "default", "settings": { "Components": "main", "Architectures": "amd64" } } }
  ```
- conda (Anaconda):
  ```
  { "repo": { "type": "conda", "url": "http://host:8080/conda", "storage": "default" } }
  ```
- conan:
  ```
  { "repo": { "type": "conan", "storage": "default" } }
  ```
- hexpm:
  ```
  { "repo": { "type": "hexpm", "storage": "default" } }
  ```

Rest API provides method to rename repository `PUT /api/v1/{repo_name}/move` (`{repo_name}` is the 
name of the repository) and move all the data
from repository with the `{repo_name}` to repository with new name (new name is provided in json 
request body, check Swagger docs to learn the format). Response is returned immediately, but data 
manipulation is performed in asynchronous mode, so to make sure data transfer is complete, 
call `HEAD /api/v1/{repo_name}` and verify status `404 NOT FOUND` is returned.

## Storage aliases
[Storage aliases](./Configuration-Storage#Storage-Aliases) can also be managed with Rest API, 
there are methods to read, create, update and remove aliases. Note, that concrete storage settings 
depends on storage type, Rest API accepts all the parameters in json format equivalent to the 
YAML storages setting. 

## Users management API

Use Rest API to obtain list of the users, check user info, add, update, remove or deactivate user. Also, it's
possible to change password by calling `POST /api/v1/{username}/alter/password` method providing
old and new password in json request body.

Users API is available if either `artipie` credentials type or `artipie` policy is used.  

### Roles management API

Rest API endpoint allow to create or update, obtain roles list or single role info details, 
deactivate or remove roles. Roles API endpoints are available if `artipie` policy is used.

Check [policy section](./Configuration-Policy) to learn more about users or roles info format.
