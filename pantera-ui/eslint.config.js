import js from '@eslint/js'
import tseslint from 'typescript-eslint'
import pluginVue from 'eslint-plugin-vue'
import vueParser from 'vue-eslint-parser'
import globals from 'globals'

export default [
  { ignores: ['dist/', 'node_modules/'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.vue'],
    languageOptions: {
      parser: vueParser,
      parserOptions: {
        parser: tseslint.parser,
        ecmaVersion: 'latest',
        sourceType: 'module',
      },
    },
  },
  {
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.node,
        __APP_VERSION__: 'readonly',
      },
    },
    rules: {
      'vue/multi-word-component-names': 'off',
      'vue/html-self-closing': 'off',
      'vue/max-attributes-per-line': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      // vue-tsc 3.3 takes `//` in an attribute of a component for the start of
      // a comment: the code it generates for the rest of the template is
      // broken, and the type check reports errors far from the cause or none.
      'vue/no-restricted-syntax': ['error', {
        selector: 'VElement[rawName=/^[A-Z]|-/] > VStartTag > VAttribute '
          + ':matches(VLiteral[value=/\\x2f\\x2f/], Literal[value=/\\x2f\\x2f/], TemplateElement[value.raw=/\\x2f\\x2f/])',
        message: 'vue-tsc breaks on `//` in an attribute of a component. Bind the attribute to a constant of the script.',
      }],
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-unused-vars': ['error', {
        argsIgnorePattern: '^_',
        varsIgnorePattern: '^_',
        caughtErrorsIgnorePattern: '^_?(e|err|error)$',
      }],
    },
  },
]
