import type { FormatSnippets } from './types'
import { mavenSnippets } from './maven'
import { gradleSnippets } from './gradle'
import { npmSnippets } from './npm'
import { dockerSnippets } from './docker'
import { pypiSnippets } from './pypi'
import { phpSnippets } from './php'
import { helmSnippets } from './helm'
import { goSnippets } from './go'
import { nugetSnippets } from './nuget'
import { debSnippets } from './deb'
import { rpmSnippets } from './rpm'
import { condaSnippets } from './conda'
import { gemSnippets } from './gem'
import { conanSnippets } from './conan'
import { hexpmSnippets } from './hexpm'
import { fileSnippets } from './file'

export * from './types'
export * from './context'

/** Snippet builders keyed by the Set Me Up technology key (see SETUP_TECHS). */
export const FORMAT_SNIPPETS: Record<string, FormatSnippets> = {
  maven: mavenSnippets,
  gradle: gradleSnippets,
  npm: npmSnippets,
  docker: dockerSnippets,
  pypi: pypiSnippets,
  php: phpSnippets,
  helm: helmSnippets,
  go: goSnippets,
  nuget: nugetSnippets,
  deb: debSnippets,
  rpm: rpmSnippets,
  conda: condaSnippets,
  gem: gemSnippets,
  conan: conanSnippets,
  hexpm: hexpmSnippets,
  file: fileSnippets,
}
