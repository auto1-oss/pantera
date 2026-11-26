<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import { useAuthStore } from '@/stores/auth'
import { useLockedUsers } from '@/composables/useLockedUsers'
import { localDateTime, relativeTime } from '@/utils/relativeTime'
import UnlockAllDialog from './UnlockAllDialog.vue'

// Every locked name with the origin of its last failed login, for the
// Failed logins card of System Settings. Unlike the users list it also
// shows names that have no Pantera user: a login checked with an identity
// provider is counted for the directory account behind it.
const auth = useAuthStore()
const {
  locked, loading, loadError, unlocking, unlockingAll, canUnlock, load, unlock, unlockAll,
} = useLockedUsers()

// Locks stay until somebody unlocks them, and a name need not exist to
// be locked, so the list can grow long: it is paged in the browser.
const PAGE_ROWS = 20

/** The list is served to whoever may read users. */
const canRead = computed(() => auth.hasAction('api_user_permissions', 'read'))
const confirmVisible = ref(false)

async function reload() {
  if (canRead.value) {
    await load()
  }
}

async function confirmUnlockAll() {
  // On a failure the dialog stays open under the error toast.
  if (await unlockAll()) {
    confirmVisible.value = false
  }
}

onMounted(reload)

defineExpose({ reload })
</script>

<template>
  <div class="space-y-3" data-testid="locked-users">
    <div class="flex items-start justify-between gap-3">
      <div>
        <div class="font-medium text-sm">
          Locked users
          <span v-if="locked.length > 0" class="text-gray-400 font-normal" data-testid="locked-users-count">({{ locked.length }})</span>
        </div>
        <div class="text-xs text-gray-500">
          Names locked after failed logins, oldest lock first, with where their
          last failed login came from. A name need not belong to a Pantera user.
        </div>
      </div>
      <div v-if="canRead" class="flex gap-2 shrink-0">
        <Button
          label="Refresh"
          icon="pi pi-refresh"
          size="small"
          severity="secondary"
          text
          :loading="loading"
          data-testid="locked-users-refresh"
          @click="reload"
        />
        <Button
          v-if="canUnlock && locked.length > 0"
          label="Unlock all"
          icon="pi pi-lock-open"
          size="small"
          severity="warn"
          outlined
          data-testid="locked-users-unlock-all"
          @click="confirmVisible = true"
        />
      </div>
    </div>

    <div v-if="!canRead" class="text-sm text-gray-500" data-testid="locked-users-no-permission">
      The locked users are shown to accounts that may read users.
    </div>
    <div
      v-else-if="loadError"
      class="text-sm text-red-700 dark:text-red-300"
      data-testid="locked-users-error"
    >
      Failed to load the locked users: {{ loadError }}
    </div>
    <div v-else-if="locked.length === 0 && loading" class="text-sm text-gray-500">Loading…</div>
    <div
      v-else-if="locked.length === 0"
      class="text-sm text-gray-500 border border-dashed border-gray-300 dark:border-gray-700 rounded p-4 text-center"
      data-testid="locked-users-empty"
    >
      No user is locked
    </div>
    <div v-else class="overflow-x-auto">
      <DataTable
        :value="locked"
        data-key="name"
        size="small"
        striped-rows
        class="text-sm"
        :paginator="locked.length > PAGE_ROWS"
        :rows="PAGE_ROWS"
      >
        <Column field="name" header="Name">
          <template #body="{ data }">
            <span class="font-mono break-all" data-testid="locked-user-name">{{ data.name }}</span>
          </template>
        </Column>
        <Column header="Locked since">
          <template #body="{ data }">
            <div :title="data.locked_at ?? ''">{{ localDateTime(data.locked_at) }}</div>
            <div v-if="data.locked_at" class="text-xs text-gray-400">{{ relativeTime(data.locked_at) }}</div>
          </template>
        </Column>
        <Column field="failures" header="Failed logins" />
        <Column header="Client address">
          <template #body="{ data }">{{ data.client_ip || '—' }}</template>
        </Column>
        <Column header="Client program">
          <template #body="{ data }">
            <div class="max-w-[14rem] truncate" :title="data.user_agent ?? ''">{{ data.user_agent || '—' }}</div>
          </template>
        </Column>
        <Column header="Sign-in path">
          <template #body="{ data }">{{ data.path || '—' }}</template>
        </Column>
        <Column header="Credential">
          <template #body="{ data }">{{ data.credential || '—' }}</template>
        </Column>
        <Column v-if="canUnlock" header="Actions">
          <template #body="{ data }">
            <Button
              label="Unlock"
              icon="pi pi-lock-open"
              size="small"
              text
              severity="warn"
              :loading="unlocking === data.name"
              :disabled="unlockingAll"
              data-testid="locked-user-unlock"
              @click="unlock(data.name)"
            />
          </template>
        </Column>
      </DataTable>
    </div>

    <UnlockAllDialog
      v-model:visible="confirmVisible"
      :count="locked.length"
      :running="unlockingAll"
      @confirm="confirmUnlockAll"
    />
  </div>
</template>
