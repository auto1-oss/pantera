import type { Client, FormatSnippets, SnippetCtx, Step } from './types'

/**
 * Conda channels. conda fetches through `requests`, which reads ~/.netrc
 * for any URL without userinfo, so credentials stay out of ~/.condarc and
 * work for usernames containing `@`. A conda repository is a hosted
 * channel; packages are published with a multipart POST to
 * `<channel>/<subdir>/<file>` using the `token` authorization scheme.
 */

/** Built package as `conda build --output` prints it: `<conda-bld>/<subdir>/<file>`. */
const PKG_PATH = 'conda-bld/noarch/my-package-1.0.0-0.tar.bz2'

function netrcStep(ctx: SnippetCtx): Step {
  return {
    title: 'Store credentials in ~/.netrc',
    description:
      `conda sends these credentials to every request for <code>${ctx.hostNoPort}</code>, so the channel `
      + `URL in <code>~/.condarc</code> carries none. On Windows use <code>%USERPROFILE%\\_netrc</code>.`,
    file: '~/.netrc',
    code:
      `cat >> ~/.netrc <<'EOF'\n`
      + `machine ${ctx.hostNoPort} login ${ctx.user} password ${ctx.token}\n`
      + `EOF\n`
      + `chmod 600 ~/.netrc`,
  }
}

function channelStep(ctx: SnippetCtx): Step {
  return {
    title: 'Add the channel',
    description:
      `Puts the channel first in <code>~/.condarc</code>, so it takes priority over the channels `
      + `already configured.`,
    file: '~/.condarc',
    code: `conda config --prepend channels ${ctx.repoUrl}`,
  }
}

function publishSteps(ctx: SnippetCtx): Step[] {
  if (!ctx.pubUrl) return []
  return [
    {
      title: 'Upload a package',
      description:
        `Set <code>PKG</code> to the built package (<code>.tar.bz2</code> or <code>.conda</code>), as `
        + `<code>conda build --output RECIPE</code> prints it. Its parent directory is the subdir the `
        + `package is published under (<code>noarch</code>, <code>linux-64</code>, `
        + `<code>osx-arm64</code>, ...). The server answers <code>201</code> and adds the package to that `
        + `subdir's <code>repodata.json</code>.`,
      code:
        `PKG=${PKG_PATH}\n`
        + `SUBDIR=$(basename "$(dirname "$PKG")")\n`
        + `curl -fsS -H 'Authorization: token ${ctx.token}' \\\n`
        + `  -F "file=@$PKG" \\\n`
        + `  "${ctx.pubUrl}/$SUBDIR/$(basename "$PKG")"`,
    },
  ]
}

function condaClient(ctx: SnippetCtx): Client {
  return {
    id: 'conda',
    label: 'conda',
    configure: [netrcStep(ctx), channelStep(ctx)],
    resolve: [
      {
        title: 'Install a package',
        description:
          `Resolves from the channels in <code>~/.condarc</code>. For a one-off install without `
          + `changing the configuration use <code>conda install -c ${ctx.repoUrl} PACKAGE</code>.`,
        code: 'conda install PACKAGE',
      },
    ],
    publish: publishSteps(ctx),
    verify: [
      {
        title: 'Search the channel',
        description:
          `Lists the channel's packages. On an empty channel conda reports `
          + `<code>PackagesNotFoundInChannelsError</code>, which still proves the channel was read; `
          + `<code>CondaHTTPError: HTTP 401</code> means the <code>~/.netrc</code> entry is missing or wrong.`,
        code: `conda search --override-channels -c ${ctx.repoUrl} '*'`,
      },
    ],
  }
}

export const condaSnippets: FormatSnippets = ctx => [condaClient(ctx)]
