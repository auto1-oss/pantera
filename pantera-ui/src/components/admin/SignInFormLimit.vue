<script setup lang="ts">
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import { listFormLimitHits } from '@/api/signInFailures'
import { localDateTime } from '@/utils/relativeTime'
import { userOrNoName } from '@/utils/signInFailures'
import SignInFailureList from './SignInFailureList.vue'

// Logins refused by the sign-in form's own limit, for the Sign-in
// failures page. That limit counts per user and client address and is
// set on the Login Throttling card of System Settings; it is not the
// delay and the lock of the Failed logins card.
</script>

<template>
  <SignInFailureList :fetch="listFormLimitHits" empty-text="The sign-in form limit refused no login">
    <template #intro>
      Logins that the sign-in form refused by its own limit, per user and client address. The limit
      is set in System Settings → Login Throttling.
    </template>
    <template #default="{ items, loading }">
      <DataTable :value="items" :loading="loading" size="small" striped-rows class="text-sm">
        <Column header="User">
          <template #body="{ data }">
            <span class="font-mono break-all" data-testid="line-user">{{ userOrNoName(data.user) }}</span>
          </template>
        </Column>
        <Column header="Refused logins">
          <template #body="{ data }">
            <span data-testid="limit-hits">{{ data.hits }}</span>
          </template>
        </Column>
        <Column>
          <template #header>
            <span class="font-semibold" title="Failures counted in the window when the limit last refused a login.">
              Failures in the window
            </span>
          </template>
          <template #body="{ data }">
            <span data-testid="limit-failures">{{ data.failures }}</span>
          </template>
        </Column>
        <Column header="Block">
          <template #body="{ data }">
            <div data-testid="limit-block">
              <div
                v-if="data.blocked"
                class="text-red-700 dark:text-red-300"
                :title="data.blocked_until ?? ''"
              >
                Blocked until {{ localDateTime(data.blocked_until) }}
              </div>
              <template v-else>
                <div>Block ended</div>
                <div class="text-xs text-gray-400" :title="data.blocked_until ?? ''">
                  {{ localDateTime(data.blocked_until) }}
                </div>
              </template>
            </div>
          </template>
        </Column>
        <Column header="First refused">
          <template #body="{ data }">
            <span :title="data.first_hit_at ?? ''">{{ localDateTime(data.first_hit_at) }}</span>
          </template>
        </Column>
        <Column header="Last refused">
          <template #body="{ data }">
            <span :title="data.last_hit_at ?? ''">{{ localDateTime(data.last_hit_at) }}</span>
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
