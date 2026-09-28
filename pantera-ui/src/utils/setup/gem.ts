import type { Client, FormatSnippets, SnippetCtx, Step } from './types'

/** Placeholder gem name used in every step so Resolve, Publish and Verify line up. */
const GEM = 'my-gem'
const GEM_FILE = `${GEM}-0.1.0.gem`

const PUBLISH_NOTE = 'Gems are pushed to a local gem repository. Pick one under "Publish to" to see the push commands.'

/** Resolve URL with the credentials embedded; RubyGems has no other way to authenticate a source. */
function sourceWithAuth(ctx: SnippetCtx): string {
  return `${ctx.scheme}://${ctx.userEnc}:${ctx.token}@${ctx.repoPath}/`
}

/**
 * `gem push` sends the API key verbatim as the Authorization header, so a
 * key of `Basic <base64(user:token)>` authenticates like any Basic client.
 * The credentials file maps the push host to that key.
 */
function pushCredentialsStep(ctx: SnippetCtx): Step {
  return {
    title: 'Store the push credentials',
    description:
      `Adds a key for <code>${ctx.pubRepo}</code> to <code>~/.gem/credentials</code>; <code>gem push --host</code> `
      + `looks it up by the exact host URL. RubyGems refuses the file unless it is private (<code>0600</code>).`,
    code:
      'mkdir -p ~/.gem\n'
      + `echo '${ctx.pubUrl}: Basic ${ctx.b64}' >> ~/.gem/credentials\n`
      + 'chmod 0600 ~/.gem/credentials',
  }
}

function publishSteps(ctx: SnippetCtx): Step[] {
  if (!ctx.pubUrl) return []
  return [
    {
      title: 'Build the gem',
      code: `gem build ${GEM}.gemspec`,
    },
    {
      title: 'Push the gem',
      description:
        `Uses the key stored under Configure. Set <code>homepage</code> and <code>description</code> in the gemspec: `
        + `the repository currently rejects gems without them.`,
      code: `gem push ${GEM_FILE} --host ${ctx.pubUrl}`,
    },
    {
      title: 'Or upload with curl',
      description: 'For CI jobs without RubyGems credentials. The server answers <code>201</code>.',
      code: `curl -f -u '${ctx.user}:${ctx.token}' --data-binary @${GEM_FILE} ${ctx.pubUrl}/api/v1/gems`,
    },
  ]
}

function gemClient(ctx: SnippetCtx): Client {
  const source = sourceWithAuth(ctx)
  const configure: Step[] = [
    {
      title: 'Add the repository as a gem source',
      description:
        `RubyGems only authenticates sources through the URL, so the token is stored in <code>~/.gemrc</code>. `
        + `The username is percent-encoded. <code>https://rubygems.org/</code> stays as a source for public gems.`,
      code: `gem sources --add ${source}`,
    },
  ]
  if (ctx.pubUrl) configure.push(pushCredentialsStep(ctx))
  return {
    id: 'gem',
    label: 'gem',
    configure,
    resolve: [
      {
        title: 'Install a gem',
        code: `gem install ${GEM}`,
      },
    ],
    publish: publishSteps(ctx),
    publishNote: ctx.pubUrl ? undefined : PUBLISH_NOTE,
    verify: [
      {
        title: 'Query the repository index',
        description:
          `Queries only <code>${ctx.repo}</code>: prints <code>200 OK</code> for the index request, then the gems it holds `
          + `(none on an empty repository). <code>401 Unauthorized</code> means the credentials were rejected; `
          + `without <code>--verbose</code>, <code>gem search</code> hides that error.`,
        code: `gem search --verbose --remote --clear-sources --source ${source}`,
      },
    ],
  }
}

function bundlerClient(ctx: SnippetCtx): Client {
  const gemfile =
    'source "https://rubygems.org"\n\n'
    + `source "${ctx.repoUrl}/" do\n`
    + `  gem "${GEM}"\n`
    + 'end\n'
  return {
    id: 'bundler',
    label: 'Bundler',
    configure: [
      {
        title: 'Store the credentials',
        description:
          `Bundler matches the key against the <code>source</code> URL in the Gemfile, so the token stays out of it. `
          + `The username must be percent-encoded. Written to <code>~/.bundle/config</code>.`,
        code: `bundle config set --global ${ctx.repoUrl}/ '${ctx.userEnc}:${ctx.token}'`,
      },
    ],
    resolve: [
      {
        title: 'Add the source to your Gemfile',
        description:
          `Gems in the <code>source</code> block come from <code>${ctx.repo}</code>; everything else from rubygems.org.`,
        code: gemfile,
        lang: 'ruby',
        file: 'Gemfile',
        download: 'Gemfile',
      },
      {
        title: 'Install',
        code: 'bundle install',
      },
    ],
    publish: [],
    publishNote: ctx.pubUrl
      ? 'Bundler does not upload gems. Publish with gem push as shown for the gem client.'
      : PUBLISH_NOTE,
    verify: [
      {
        title: 'Check the installed gem',
        description: `Prints the version and install path of <code>${GEM}</code> resolved from <code>${ctx.repo}</code>.`,
        code: `bundle info ${GEM}`,
      },
    ],
  }
}

export const gemSnippets: FormatSnippets = ctx => [gemClient(ctx), bundlerClient(ctx)]
