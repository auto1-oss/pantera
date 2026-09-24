<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  troubleshootUrl,
  runTroubleshootFix,
  type TroubleshootCheck,
  type TroubleshootFix,
  type TroubleshootResponse,
} from '@/api/troubleshoot'
import { INSPECT_REPO_TYPES } from '@/api/cooldown'
import { useNotificationStore } from '@/stores/notifications'
import { repoTypeBase } from '@/utils/repoTypes'
import AppLayout from '@/components/layout/AppLayout.vue'
import RepoTypeBadge from '@/components/common/RepoTypeBadge.vue'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Tag from 'primevue/tag'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import SelectButton from 'primevue/selectbutton'

type Mode = 'url' | 'package'

const route = useRoute()
const router = useRouter()
const notify = useNotificationStore()

const mode = ref<Mode>('url')
const url = ref('')
const pkgRepoType = ref<string>('npm')
const pkgName = ref('')
const loading = ref(false)
const result = ref<TroubleshootResponse | null>(null)
const error = ref('')
const runningFix = ref<string | null>(null)

const problems = computed(() => result.value?.checks.filter(c => c.status === 'problem') ?? [])

function queryString(v: unknown): string {
  return typeof v === 'string' ? v : ''
}

async function run(target: string) {
  loading.value = true
  error.value = ''
  try {
    result.value = await troubleshootUrl(target)
  } catch (err: unknown) {
    result.value = null
    const resp = (err as { response?: { status?: number; data?: { message?: string } } })?.response
    error.value = resp?.data?.message
      ?? (resp?.status ? `Troubleshoot failed (HTTP ${resp.status})` : 'Troubleshoot failed')
  } finally {
    loading.value = false
  }
}

function submit() {
  const target = url.value.trim()
  if (!target) return
  if (queryString(route.query.url) !== target) {
    // The route watcher below triggers the run.
    router.replace({ query: { url: target } })
  } else {
    run(target)
  }
}

watch(() => route.query.url, (v) => {
  const target = queryString(v)
  if (target) {
    url.value = target
    mode.value = 'url'
    run(target)
  }
})

function openInspector() {
  const name = pkgName.value.trim()
  if (!name) return
  router.push({ path: '/cooldown', query: { tab: 'inspect', repoType: pkgRepoType.value, package: name } })
}

const inspectorLink = computed(() => {
  const r = result.value
  if (!r?.repo || !r.parsed?.package) return null
  return {
    path: '/cooldown',
    query: {
      tab: 'inspect',
      repoType: repoTypeBase(r.repo.type),
      package: r.parsed.package,
      repo: r.repo.name,
    },
  }
})

function fixLabel(fix: TroubleshootFix): string {
  const words = fix.action.replace(/[-_]+/g, ' ').trim()
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : 'Fix'
}

async function applyFix(check: TroubleshootCheck) {
  if (!check.fix) return
  runningFix.value = check.id
  try {
    await runTroubleshootFix(check.fix)
    notify.success('Fix applied', fixLabel(check.fix))
  } catch {
    notify.error('Fix failed', fixLabel(check.fix))
  } finally {
    runningFix.value = null
  }
  const target = result.value?.url || url.value.trim()
  if (target) await run(target)
}

function statusIcon(status: string): string {
  if (status === 'ok') return 'pi pi-check-circle text-green-500'
  if (status === 'problem') return 'pi pi-times-circle text-red-500'
  return 'pi pi-info-circle text-blue-400'
}

function httpSeverity(status: number): string {
  if (status >= 200 && status < 300) return 'success'
  if (status >= 300 && status < 400) return 'info'
  if (status >= 400 && status < 500) return 'warn'
  return 'danger'
}

onMounted(() => {
  const target = queryString(route.query.url)
  if (target) {
    url.value = target
    run(target)
  }
})
</script>

<template>
  <AppLayout>
    <div class="space-y-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Troubleshoot</h1>
        <p class="text-sm text-gray-500 mt-1">
          Explain why a client cannot get an artifact or sees stale metadata: repository, group walk,
          negative cache, cooldown, metadata visibility and upstream breaker, with one-click fixes.
        </p>
      </div>

      <Card class="shadow-sm">
        <template #content>
          <SelectButton
            v-model="mode"
            :options="[
              { label: 'URL', value: 'url' },
              { label: 'Package', value: 'package' },
            ]"
            option-label="label"
            option-value="value"
            :allow-empty="false"
            class="mb-4"
          />
          <div v-if="mode === 'url'" class="flex gap-3 items-end">
            <div class="flex-1">
              <label class="block text-sm text-gray-500 mb-1" for="troubleshoot-url">
                Client URL or <code>/&lt;repo&gt;/&lt;path&gt;</code>
              </label>
              <InputText
                id="troubleshoot-url"
                v-model="url"
                placeholder="https://registry.example.com/npm-group/lodash/-/lodash-4.17.21.tgz"
                class="w-full font-mono"
                @keyup.enter="submit"
              />
            </div>
            <Button label="Troubleshoot" icon="pi pi-search" :loading="loading" data-testid="troubleshoot-run" @click="submit" />
          </div>
          <div v-else class="flex flex-wrap gap-3 items-end">
            <div class="flex flex-col gap-1">
              <label class="text-sm text-gray-500" for="troubleshoot-pkg-type">Repo type</label>
              <Select
                id="troubleshoot-pkg-type"
                v-model="pkgRepoType"
                :options="[...INSPECT_REPO_TYPES]"
                option-label="label"
                option-value="value"
                class="w-44"
              />
            </div>
            <div class="flex flex-col gap-1 flex-1 min-w-[14rem]">
              <label class="text-sm text-gray-500" for="troubleshoot-pkg-name">Package</label>
              <InputText
                id="troubleshoot-pkg-name"
                v-model="pkgName"
                placeholder="lodash, requests, com.example:foo..."
                class="w-full"
                @keyup.enter="openInspector"
              />
            </div>
            <Button label="Open in cooldown inspector" icon="pi pi-arrow-right" @click="openInspector" />
          </div>
        </template>
      </Card>

      <div
        v-if="error"
        class="p-3 rounded-lg border border-red-300 bg-red-50 text-red-700 dark:border-red-800 dark:bg-red-950/40 dark:text-red-300"
      >
        {{ error }}
      </div>

      <template v-if="result">
        <div class="grid grid-cols-1 lg:grid-cols-3 gap-4">
          <Card class="shadow-sm">
            <template #title>Repository</template>
            <template #content>
              <div v-if="result.repo" class="space-y-2 text-sm" data-testid="troubleshoot-repo">
                <div class="flex items-center gap-2 flex-wrap">
                  <span class="font-semibold text-gray-900 dark:text-white">{{ result.repo.name }}</span>
                  <RepoTypeBadge :type="result.repo.type" />
                  <Tag :value="result.repo.mode" severity="secondary" />
                </div>
                <div v-if="result.repo.members?.length" class="text-gray-500">
                  Members (walk order):
                  <ol class="list-decimal list-inside font-mono text-xs mt-1">
                    <li v-for="m in result.repo.members" :key="m">{{ m }}</li>
                  </ol>
                </div>
              </div>
              <div v-else class="text-sm text-gray-400">No repository resolved</div>
            </template>
          </Card>
          <Card class="shadow-sm">
            <template #title>Parsed request</template>
            <template #content>
              <dl v-if="result.parsed" class="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
                <dt class="text-gray-500">Package</dt>
                <dd class="font-mono break-all">{{ result.parsed.package || '—' }}</dd>
                <dt class="text-gray-500">Version</dt>
                <dd class="font-mono">{{ result.parsed.version || '—' }}</dd>
                <dt class="text-gray-500">Kind</dt>
                <dd>{{ result.parsed.kind || '—' }}</dd>
              </dl>
              <router-link
                v-if="inspectorLink"
                :to="inspectorLink"
                class="inline-block mt-3 text-sm text-amber-600 dark:text-amber-400 underline"
              >
                Inspect package in cooldown
              </router-link>
            </template>
          </Card>
          <Card class="shadow-sm">
            <template #title>Response</template>
            <template #content>
              <div v-if="result.request" class="space-y-2 text-sm" data-testid="troubleshoot-request">
                <Tag :value="`HTTP ${result.request.status}`" :severity="httpSeverity(result.request.status)" />
                <dl
                  v-if="result.request.headers && Object.keys(result.request.headers).length"
                  class="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-xs font-mono"
                >
                  <template v-for="(v, k) in result.request.headers" :key="k">
                    <dt class="text-gray-500">{{ k }}</dt>
                    <dd class="break-all">{{ v }}</dd>
                  </template>
                </dl>
                <pre
                  v-if="result.request.bodySnippet"
                  class="text-xs whitespace-pre-wrap break-all p-2 rounded bg-gray-50 dark:bg-gray-800 max-h-40 overflow-auto"
                >{{ result.request.bodySnippet }}</pre>
              </div>
              <div v-else class="text-sm text-gray-400">No in-process request was made</div>
            </template>
          </Card>
        </div>

        <Card class="shadow-sm">
          <template #title>
            <div class="flex items-center justify-between">
              <span>Checks</span>
              <span class="text-sm font-normal" :class="problems.length ? 'text-red-500' : 'text-green-600'">
                {{ problems.length
                  ? `${problems.length} problem${problems.length === 1 ? '' : 's'} found`
                  : 'No problems found' }}
              </span>
            </div>
          </template>
          <template #content>
            <ul class="divide-y divide-gray-200 dark:divide-gray-800" data-testid="troubleshoot-checks">
              <li
                v-for="check in result.checks"
                :key="check.id"
                class="flex flex-wrap items-center gap-3 py-2"
                :data-status="check.status"
                data-testid="troubleshoot-check"
              >
                <i :class="statusIcon(check.status)" class="text-lg" :title="check.status" />
                <Tag :value="check.layer" severity="secondary" class="shrink-0" />
                <span class="flex-1 min-w-[12rem] text-sm text-gray-800 dark:text-gray-200">{{ check.message }}</span>
                <Button
                  v-if="check.fix"
                  :label="fixLabel(check.fix)"
                  icon="pi pi-wrench"
                  size="small"
                  severity="warn"
                  :loading="runningFix === check.id"
                  :disabled="runningFix !== null || loading"
                  data-testid="troubleshoot-fix"
                  @click="applyFix(check)"
                />
              </li>
            </ul>
            <p class="text-xs text-gray-400 mt-3">
              Answered by node <span class="font-mono">{{ result.node }}</span>
            </p>
          </template>
        </Card>
      </template>
    </div>
  </AppLayout>
</template>
