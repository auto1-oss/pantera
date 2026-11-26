<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Paginator from 'primevue/paginator'
import { apiErrorMessage } from '@/utils/apiError'
import type { SignInFailuresQuery } from '@/api/signInFailures'
import type { PaginatedResponse } from '@/types'

// One list of the sign-in failures report: the filters for user and
// client address, the paging and the states around the table. The three
// tabs of the page give it their endpoint and draw their own table in
// the default slot, from the lines of the page that is loaded.
const props = defineProps<{
  /** Reads one page of the list. */
  fetch: (query: SignInFailuresQuery, signal: AbortSignal) => Promise<PaginatedResponse<unknown>>
  /** What the list says when it has no line at all. */
  emptyText: string
}>()
const emit = defineEmits<{
  /** The lines of a page that was loaded. */
  loaded: [items: unknown[]]
}>()

/** Typing in a filter sends one request, this long after the last key. */
const FILTER_DELAY_MS = 300
/** The page size of the server. */
const DEFAULT_SIZE = 50

const items = ref<unknown[]>([])
const page = ref(0)
const size = ref(DEFAULT_SIZE)
const total = ref(0)
const loading = ref(false)
/** False until the first answer, so an empty list is not reported too early. */
const answered = ref(false)
/** Why the last load failed; null after one that worked or was refused. */
const error = ref<string | null>(null)
/** The server refused the list: the account may not read users. */
const forbidden = ref(false)
const user = ref('')
const clientIp = ref('')
/** Whether the lines on screen were asked for with a filter. */
const filtered = ref(false)

const userInput = useId()
const clientIpInput = useId()

let timer: ReturnType<typeof setTimeout> | null = null
let ctrl: AbortController | null = null

function stopTimer() {
  if (timer) clearTimeout(timer)
  timer = null
}

/**
 * Loads the page that is open, with the filters as they are now. Each
 * load cancels the one before it, so a slow answer to an older filter
 * never replaces a newer one.
 */
async function load(): Promise<void> {
  stopTimer()
  if (ctrl) ctrl.abort()
  ctrl = new AbortController()
  const mine = ctrl
  const query = {
    page: page.value, size: size.value, user: user.value, client_ip: clientIp.value,
  }
  loading.value = true
  try {
    const resp = await props.fetch(query, mine.signal)
    if (mine.signal.aborted) return
    const lines = resp.items ?? []
    const all = resp.total ?? lines.length
    const lastPage = Math.max(0, Math.ceil(all / query.size) - 1)
    if (lines.length === 0 && lastPage < query.page) {
      // Lines were removed meanwhile and this page is past the end.
      page.value = lastPage
      void load()
      return
    }
    items.value = lines
    total.value = all
    filtered.value = query.user.trim() !== '' || query.client_ip.trim() !== ''
    error.value = null
    forbidden.value = false
    emit('loaded', lines)
  } catch (err) {
    if (mine.signal.aborted) return
    // The refusal or the error is shown in place of the lines of before.
    forbidden.value = (err as { response?: { status?: number } })?.response?.status === 403
    error.value = forbidden.value ? null : apiErrorMessage(err, 'Request failed')
  } finally {
    if (!mine.signal.aborted) {
      loading.value = false
      answered.value = true
    }
  }
}

// A changed filter is a new list: it starts at its first page.
watch([user, clientIp], () => {
  page.value = 0
  stopTimer()
  timer = setTimeout(() => { void load() }, FILTER_DELAY_MS)
})

function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  size.value = event.rows
  void load()
}

onMounted(() => { void load() })
onBeforeUnmount(() => {
  stopTimer()
  if (ctrl) ctrl.abort()
})

defineExpose({ reload: load })
</script>

<template>
  <div class="space-y-3">
    <p v-if="$slots.intro" class="text-sm text-gray-500" data-testid="list-intro">
      <slot name="intro" />
    </p>

    <div class="flex flex-wrap items-end gap-3">
      <div class="flex flex-col gap-1">
        <label class="text-sm text-gray-500" :for="userInput">User</label>
        <InputText
          :id="userInput"
          v-model="user"
          placeholder="Part of a user name"
          class="w-64"
          autocomplete="off"
          data-testid="list-filter-user"
          @keyup.enter="load"
        />
      </div>
      <div class="flex flex-col gap-1">
        <label class="text-sm text-gray-500" :for="clientIpInput">Client address</label>
        <InputText
          :id="clientIpInput"
          v-model="clientIp"
          placeholder="Part of an address"
          class="w-56"
          autocomplete="off"
          data-testid="list-filter-client-ip"
          @keyup.enter="load"
        />
      </div>
      <div class="flex items-center gap-3 ml-auto">
        <span
          v-if="answered && !error && !forbidden"
          class="text-sm text-gray-400"
          data-testid="list-total"
        >{{ total }} {{ total === 1 ? 'line' : 'lines' }}</span>
        <Button
          label="Refresh"
          icon="pi pi-refresh"
          size="small"
          severity="secondary"
          text
          :loading="loading"
          data-testid="list-refresh"
          @click="load"
        />
      </div>
    </div>

    <Message v-if="forbidden" severity="warn" :closable="false" data-testid="list-forbidden">
      You do not have permission to see this list.
    </Message>
    <div
      v-else-if="error"
      class="flex flex-wrap items-center gap-3 text-sm text-red-700 dark:text-red-300"
      data-testid="list-error"
    >
      <span>The list could not be loaded: {{ error }}</span>
      <Button
        label="Retry"
        icon="pi pi-refresh"
        size="small"
        severity="secondary"
        outlined
        :loading="loading"
        data-testid="list-retry"
        @click="load"
      />
    </div>
    <div v-else-if="!answered" class="text-sm text-gray-500" data-testid="list-loading">Loading…</div>
    <div
      v-else-if="items.length === 0"
      class="text-sm text-gray-500 border border-dashed border-gray-300 dark:border-gray-700 rounded p-4 text-center"
      data-testid="list-empty"
    >
      {{ filtered ? 'No line matches the filter' : emptyText }}
    </div>
    <div v-else class="overflow-x-auto">
      <slot :items="items" :loading="loading" />
    </div>

    <Paginator
      v-if="!forbidden && !error && total > size"
      :rows="size"
      :total-records="total"
      :first="page * size"
      :rows-per-page-options="[20, 50, 100, 200]"
      @page="onPage"
    />
  </div>
</template>
