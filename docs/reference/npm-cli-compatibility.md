# npm CLI Compatibility

> For client configuration, see [User Guide > npm](../user-guide/repositories/npm.md).

## Quick Reference

| Command | Local | Proxy | Group | Notes |
|---------|-------|-------|-------|-------|
| `npm adduser` / `npm login` | Yes | Yes | Yes | Authenticates with Pantera |
| `npm whoami` | Yes | Yes | Yes | Returns authenticated user |
| `npm logout` | Partial | Partial | Partial | Client-side only (deletes token from ~/.npmrc) |
| `npm install <pkg>` | Yes | Yes | Yes | Downloads from repo |
| `npm ci` | Yes | Yes | Yes | Clean install from lock file |
| `npm update` | Yes | Yes | Yes | Updates packages |
| `npm publish` | Yes | No | No | Only to local repos |
| `npm unpublish` | Yes | No | No | Only from local repos |
| `npm deprecate` | Yes | No | No | Only on local repos |
| `npm view <pkg>` | Yes | Yes | Yes | Shows package metadata |
| `npm search <query>` | Yes | Yes | Yes | Searches packages |
| `npm outdated` | Yes | Yes | Yes | Shows outdated packages |
| `npm audit` | Yes | Yes | Yes | Security vulnerability scan |
| `npm audit fix` | Yes | Yes | Yes | Auto-fix vulnerabilities |
| `npm dist-tag add` | Yes | No | No | Only on local repos |
| `npm dist-tag rm` | Yes | No | No | Only on local repos |
| `npm dist-tag ls` | Yes | Yes | Yes | Read-only operation |
| `npm pack` | Yes | Yes | Yes | Downloads and creates tarball |
| `npm ping` | Yes | Yes | Yes | Checks registry connectivity |

## Key Rules

- **Publishing** (`npm publish`, `npm unpublish`, `npm deprecate`, `npm dist-tag add/rm`) only works on **local** repositories. Proxy and group repositories are read-only.
- **Reading** (`npm install`, `npm view`, `npm search`, `npm audit`) works on all repository types.
- **Group repositories** are recommended as the default registry for development teams -- they provide access to both internal and public packages through a single URL.

## Recommended Setup

Use the **group** repository for installing and the **local** repository for publishing:

```ini
# ~/.npmrc -- use group for all installs
registry=http://pantera-host:8080/npm-group
//pantera-host:8080/:_authToken=your-jwt-token
```

```json
// package.json -- publish to local
{
  "publishConfig": {
    "registry": "http://pantera-host:8080/npm-local"
  }
}
```

For detailed setup instructions, see [User Guide > npm](../user-guide/repositories/npm.md).
