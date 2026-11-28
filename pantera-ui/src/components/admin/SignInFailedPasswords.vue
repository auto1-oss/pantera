<script setup lang="ts">
import { computed, ref } from 'vue'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import { listFailedPasswordLogins, type FailedPasswordLogin } from '@/api/signInFailures'
import { unlockUser } from '@/api/failedLogins'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import { useUserPages } from '@/composables/useUserPages'
import { apiErrorMessage } from '@/utils/apiError'
import { localDateTime } from '@/utils/relativeTime'
import { passwordFailureReason, userOrNoName } from '@/utils/signInFailures'
import SignInFailureList from './SignInFailureList.vue'

// Failed password logins of every user, for the Sign-in failures page:
// one line per user and client address, with what applies to the user
// now. A name need not belong to a Pantera user; one that does links to
// the page of that user.
const auth = useAuthStore()
const notify = useNotificationStore()
const { userOf, lookUp } = useUserPages()

const list = ref<{ reload: () => Promise<void> } | null>(null)

/** Unlocking needs the same permission as enabling or disabling a user. */
const canUnlock = computed(() => auth.hasAction('api_user_permissions', 'enable'))

/** A user was unlocked: other lists of the page show the lock too. */
const emit = defineEmits<{ unlocked: [] }>()

/** The user whose unlock waits for the confirmation; null for none. */
const unlockTarget = ref<string | null>(null)
const unlocking = ref(false)
const unlockVisible = computed({
  get: () => unlockTarget.value !== null,
  set: (open) => { if (!open) unlockTarget.value = null },
})

function onLoaded(items: unknown[]) {
  void lookUp((items as FailedPasswordLogin[]).map(line => line.user))
}

async function confirmUnlock() {
  const name = unlockTarget.value
  if (name === null || unlocking.value) return
  unlocking.value = true
  try {
    const wasLocked = await unlockUser(name)
    if (wasLocked) {
      notify.success('User unlocked', name)
    } else {
      notify.info('User was not locked', `${name}: failed logins cleared`)
    }
    unlockTarget.value = null
    emit('unlocked')
    // The state of the user is part of every line: read the lines again.
    await list.value?.reload()
  } catch (err) {
    // The dialog stays open under the error toast.
    notify.error('Failed to unlock user', apiErrorMessage(err, name))
  } finally {
    unlocking.value = false
  }
}

/** Reads the lines again: a user was unlocked elsewhere on the page. */
async function reload() {
  await list.value?.reload()
}

defineExpose({ reload })
</script>

<template>
  <SignInFailureList
    ref="list"
    :fetch="listFailedPasswordLogins"
    empty-text="No failed password login"
    @loaded="onLoaded"
  >
    <template #intro>
      Failed logins were checked and count towards the lock. Refused logins came during a delay
      or a lock and were not checked.
    </template>
    <template #default="{ items, loading }">
      <DataTable :value="items" :loading="loading" size="small" striped-rows class="text-sm">
        <Column header="User">
          <template #body="{ data }">
            <router-link
              v-if="userOf(data.user)"
              :to="{ name: 'admin-user-detail', params: { name: userOf(data.user) } }"
              class="font-mono break-all text-blue-500 hover:underline"
              data-testid="line-user"
            >
              {{ data.user }}
            </router-link>
            <span v-else class="font-mono break-all" data-testid="line-user">{{ userOrNoName(data.user) }}</span>
          </template>
        </Column>
        <Column>
          <template #header>
            <span
              class="font-semibold"
              title="Logins that were checked and failed. They count towards the lock."
              data-testid="pw-failed-head"
            >Failed</span>
          </template>
          <template #body="{ data }">
            <span data-testid="pw-failed">{{ data.failed }}</span>
          </template>
        </Column>
        <Column>
          <template #header>
            <span
              class="font-semibold"
              title="Logins refused without a check, during a delay or a lock."
              data-testid="pw-refused-head"
            >Refused</span>
          </template>
          <template #body="{ data }">
            <span data-testid="pw-refused">{{ data.refused }}</span>
          </template>
        </Column>
        <Column header="Last reason">
          <template #body="{ data }">
            <span :title="data.last_reason" data-testid="pw-reason">{{ passwordFailureReason(data.last_reason) }}</span>
          </template>
        </Column>
        <Column header="First failure">
          <template #body="{ data }">
            <span :title="data.first_failure_at ?? ''">{{ localDateTime(data.first_failure_at) }}</span>
          </template>
        </Column>
        <Column header="Last failure">
          <template #body="{ data }">
            <span :title="data.last_failure_at ?? ''">{{ localDateTime(data.last_failure_at) }}</span>
          </template>
        </Column>
        <Column header="Client address">
          <template #body="{ data }">{{ data.client_ip || '—' }}</template>
        </Column>
        <Column header="Client program">
          <template #body="{ data }">
            <div class="max-w-[14rem] truncate" :title="data.user_agent">{{ data.user_agent || '—' }}</div>
          </template>
        </Column>
        <Column header="Path">
          <template #body="{ data }">{{ data.path || '—' }}</template>
        </Column>
        <Column header="User now">
          <template #body="{ data }">
            <div class="space-y-0.5" data-testid="pw-state">
              <div data-testid="pw-on-record">
                {{ data.state.failures }} failed {{ data.state.failures === 1 ? 'login' : 'logins' }} on record
              </div>
              <div
                v-if="data.state.locked"
                class="text-red-700 dark:text-red-300"
                :title="data.state.locked_at ?? ''"
                data-testid="pw-locked"
              >
                <i class="pi pi-lock text-xs mr-1" />Locked since {{ localDateTime(data.state.locked_at) }}
              </div>
              <div
                v-else-if="data.state.delayed_seconds > 0"
                class="text-amber-700 dark:text-amber-300"
                data-testid="pw-delayed"
              >
                Delayed for {{ data.state.delayed_seconds }} s
              </div>
              <div
                v-if="data.state.unlocked_at"
                class="text-xs text-gray-400"
                :title="data.state.unlocked_at"
                data-testid="pw-unlocked"
              >
                Unlocked by {{ data.state.unlocked_by || 'an administrator' }} on {{ localDateTime(data.state.unlocked_at) }}
              </div>
            </div>
          </template>
        </Column>
        <Column v-if="canUnlock" header="Actions">
          <template #body="{ data }">
            <Button
              v-if="data.state.locked && data.user"
              label="Unlock"
              icon="pi pi-lock-open"
              size="small"
              text
              severity="warn"
              data-testid="pw-unlock"
              @click="unlockTarget = data.user"
            />
          </template>
        </Column>
      </DataTable>
    </template>
  </SignInFailureList>

  <Dialog
    v-model:visible="unlockVisible"
    header="Unlock user"
    modal
    :closable="!unlocking"
    :style="{ width: '30rem' }"
  >
    <div class="space-y-2 text-sm" data-testid="unlock-dialog">
      <p>Unlock <strong class="break-all">{{ unlockTarget }}</strong>?</p>
      <p class="text-gray-500">
        The lock and the failed-login count are removed. The user can sign in again at once.
      </p>
    </div>
    <template #footer>
      <Button
        label="Cancel"
        text
        severity="secondary"
        :disabled="unlocking"
        data-testid="unlock-cancel"
        @click="unlockTarget = null"
      />
      <Button
        label="Unlock"
        icon="pi pi-lock-open"
        severity="warn"
        :loading="unlocking"
        data-testid="unlock-confirm"
        @click="confirmUnlock"
      />
    </template>
  </Dialog>
</template>
