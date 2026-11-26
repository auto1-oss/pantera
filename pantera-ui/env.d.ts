/// <reference types="vite/client" />

declare const __APP_VERSION__: string

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<object, object, unknown>
  export default component
}

// The package ships no types. The options are the ones its `install` reads
// (node_modules/@elastic/apm-rum-vue/src/index.js).
declare module '@elastic/apm-rum-vue' {
  import type { Plugin } from 'vue'
  import type { Router } from 'vue-router'
  import type { AgentConfigOptions, ApmBase } from '@elastic/apm-rum'

  export interface ApmVuePluginOptions {
    config: AgentConfigOptions
    router?: Router
    apm?: ApmBase
    captureErrors?: boolean
  }

  export const ApmVuePlugin: Plugin<[ApmVuePluginOptions]>
}
