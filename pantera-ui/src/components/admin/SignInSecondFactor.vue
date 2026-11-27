<script setup lang="ts">
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Tag from 'primevue/tag'
import { listSecondFactorLines } from '@/api/signInFailures'
import { localDateTime } from '@/utils/relativeTime'
import { secondFactorReason, userOrNoName } from '@/utils/signInFailures'
import SignInFailureList from './SignInFailureList.vue'

// Sign-ins whose password was right but whose second factor was not
// confirmed, for the Sign-in failures page: one line per user and client
// address. They count towards the pause of password sign-in set on the
// Failed logins card of System Settings, never towards the lock.
</script>

<template>
  <SignInFailureList :fetch="listSecondFactorLines" empty-text="No sign-in with an unconfirmed second factor">
    <template #intro>
      Sign-ins whose password was right but whose second factor was not confirmed: a push denied
      or not answered, or a wrong code. They count towards a pause of password sign-in, never
      towards the lock. Refused sign-ins came during a pause and were not checked. The pause is set
      in System Settings → Failed logins.
    </template>
    <template #default="{ items, loading }">
      <DataTable :value="items" :loading="loading" size="small" striped-rows class="text-sm">
        <Column header="User">
          <template #body="{ data }">
            <span class="font-mono break-all" data-testid="line-user">{{ userOrNoName(data.user) }}</span>
          </template>
        </Column>
        <Column>
          <template #header>
            <span
              class="font-semibold"
              title="Sign-ins whose password was right and whose second factor was not confirmed."
              data-testid="sf-unconfirmed-head"
            >Unconfirmed</span>
          </template>
          <template #body="{ data }">
            <span data-testid="sf-unconfirmed">{{ data.unconfirmed }}</span>
          </template>
        </Column>
        <Column>
          <template #header>
            <span
              class="font-semibold"
              title="Sign-ins refused without a check, during a pause."
              data-testid="sf-refused-head"
            >Refused</span>
          </template>
          <template #body="{ data }">
            <span data-testid="sf-refused">{{ data.refused }}</span>
          </template>
        </Column>
        <Column header="Last reason">
          <template #body="{ data }">
            <span :title="data.last_reason" data-testid="sf-reason">{{ secondFactorReason(data.last_reason) }}</span>
          </template>
        </Column>
        <Column>
          <template #header>
            <span
              class="font-semibold"
              title="Unconfirmed sign-ins that counted towards the pause at the last event. A successful sign-in ends the count."
              data-testid="sf-counted-head"
            >Counted</span>
          </template>
          <template #body="{ data }">
            <span data-testid="sf-counted">{{ data.counted }}</span>
          </template>
        </Column>
        <Column header="Paused until">
          <template #body="{ data }">
            <div data-testid="sf-pause">
              <template v-if="data.paused_until">
                <div :title="data.paused_until">{{ localDateTime(data.paused_until) }}</div>
                <Tag
                  v-if="data.paused"
                  value="Still paused"
                  severity="danger"
                  class="mt-0.5"
                  data-testid="sf-paused"
                />
                <div v-else class="text-xs text-gray-400" data-testid="sf-pause-ended">Pause ended</div>
              </template>
              <span v-else class="text-gray-400" title="No pause was reached.">—</span>
            </div>
          </template>
        </Column>
        <Column header="First seen">
          <template #body="{ data }">
            <span :title="data.first_seen_at ?? ''">{{ localDateTime(data.first_seen_at) }}</span>
          </template>
        </Column>
        <Column header="Last seen">
          <template #body="{ data }">
            <span :title="data.last_seen_at ?? ''">{{ localDateTime(data.last_seen_at) }}</span>
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
      </DataTable>
    </template>
  </SignInFailureList>
</template>
