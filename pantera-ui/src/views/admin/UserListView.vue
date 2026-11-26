<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { listUsers, deleteUser, enableUser, disableUser, putUser } from '@/api/users'
import { listRoles } from '@/api/roles'
import { useNotificationStore } from '@/stores/notifications'
import { useConfirmDelete } from '@/composables/useConfirmDelete'
import { useLockedUsers } from '@/composables/useLockedUsers'
import { useAuthStore } from '@/stores/auth'
import AppLayout from '@/components/layout/AppLayout.vue'
import UnlockAllDialog from '@/components/admin/UnlockAllDialog.vue'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import InputText from 'primevue/inputtext'
import Checkbox from 'primevue/checkbox'
import Dialog from 'primevue/dialog'
import Paginator from 'primevue/paginator'
import MultiSelect from 'primevue/multiselect'
import Message from 'primevue/message'
import PasswordComplexityForm from '@/components/auth/PasswordComplexityForm.vue'
import { apiErrorMessage } from '@/utils/apiError'
import type { User, Role } from '@/types'

const notify = useNotificationStore()
const auth = useAuthStore()
const { visible: delVisible, targetName, confirm: confirmDel, accept: acceptDel, reject: rejectDel } = useConfirmDelete()

const users = ref<User[]>([])
const page = ref(0)
const size = ref(20)
const total = ref(0)
const loading = ref(false)
const search = ref('')

// Create dialog
const createVisible = ref(false)
const newUsername = ref('')
const newPassword = ref('')
const newEmail = ref('')
const newRoles = ref<string[]>([])
const creating = ref(false)
// The server enforces the password policy (PasswordPolicy.java) and the
// role ceiling; the form mirrors the policy for live feedback and the
// server's rejection message is shown inline so the admin can fix it.
const newPasswordValid = ref(false)
const createError = ref<string | null>(null)
// Required prop of PasswordComplexityForm; ignored with hide-old-password.
const unusedOldPassword = ref('')
const availableRoles = ref<Role[]>([])

// Users locked after failed logins (2.2.15). A locked name is in lower
// case and need not be a Pantera user; this page shows the Pantera users
// among them, System Settings → Failed logins shows every name.
const {
  locked, loadError: lockedLoadError, unlocking, unlockingAll, canUnlock,
  isLocked, load: loadLocked, unlock, unlockAll,
} = useLockedUsers()
const lockedCount = computed(() => locked.value.length)
const lockedOnly = ref(false)
const unlockAllVisible = ref(false)
// The API has no filter for locked users, so "Locked only" reads the user
// list page by page, at the API's largest page size, and keeps the locked
// ones. The scan stops after LOCKED_SCAN_PAGES pages and says so.
const LOCKED_SCAN_SIZE = 100
const LOCKED_SCAN_PAGES = 20
const lockedScanCut = ref(false)
// Each load supersedes the one before it: a scan that is still running
// when the filter or the search changes must not fill the table.
let loadSeq = 0

/** Rows of the table: with "Locked only", the loaded users that are still locked. */
const rows = computed(() => lockedOnly.value
  ? users.value.filter(user => isLocked(user.name))
  : users.value)

async function load() {
  const seq = ++loadSeq
  loading.value = true
  try {
    if (lockedOnly.value) {
      await loadLockedOnly(seq)
      return
    }
    const resp = await listUsers({ page: page.value, size: size.value, q: search.value || undefined })
    if (seq !== loadSeq) return
    users.value = resp.items
    total.value = resp.total
    lockedScanCut.value = false
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

async function loadLockedOnly(seq: number) {
  const found: User[] = []
  let cut = false
  // Nothing to look for when no name is locked. Otherwise every page is
  // read: a locked name can match several users that differ in case.
  for (let scanned = 0; lockedCount.value > 0; scanned++) {
    if (scanned === LOCKED_SCAN_PAGES) {
      cut = true
      break
    }
    const resp = await listUsers({
      page: scanned, size: LOCKED_SCAN_SIZE, q: search.value || undefined,
    })
    if (seq !== loadSeq) return
    found.push(...resp.items.filter(user => isLocked(user.name)))
    if (resp.items.length === 0 || (scanned + 1) * LOCKED_SCAN_SIZE >= resp.total) break
  }
  users.value = found
  total.value = found.length
  lockedScanCut.value = cut
}

// Switching the filter on reads the locked names first, so the scan
// looks for the locks of this moment; switching it off shows page one.
watch(lockedOnly, async (on) => {
  page.value = 0
  if (on) {
    await loadLocked()
  }
  await load()
})

async function confirmUnlockAll() {
  // On a failure the dialog stays open under the error toast.
  if (await unlockAll()) {
    unlockAllVisible.value = false
  }
}

async function handleDelete(name: string) {
  if (await confirmDel(name)) {
    try {
      await deleteUser(name)
      notify.success('User deleted', name)
      load()
    } catch (err) { notify.error('Failed to delete user', apiErrorMessage(err, name)) }
  }
}

async function toggleUser(user: User) {
  try {
    if (user.enabled) {
      await disableUser(user.name)
      notify.info('User disabled', user.name)
    } else {
      await enableUser(user.name)
      notify.info('User enabled', user.name)
    }
    load()
  } catch (err) { notify.error('Failed to toggle user', apiErrorMessage(err, user.name)) }
}

function resetCreateForm() {
  newUsername.value = ''
  newPassword.value = ''
  newEmail.value = ''
  newRoles.value = []
  createError.value = null
}

watch(createVisible, visible => { if (!visible) resetCreateForm() })

async function handleCreate() {
  creating.value = true
  createError.value = null
  try {
    const body: Record<string, unknown> = {
      password: newPassword.value,
      email: newEmail.value || undefined,
    }
    if (newRoles.value.length > 0) {
      body.roles = newRoles.value
    }
    await putUser(newUsername.value, body)
    notify.success('User created', newUsername.value)
    createVisible.value = false
    load()
  } catch (err) {
    createError.value = apiErrorMessage(err, 'Failed to create user')
    notify.error('Failed to create user', createError.value)
  } finally { creating.value = false }
}

async function loadRoles() {
  try {
    const resp = await listRoles({ size: 100 })
    availableRoles.value = resp.items
  } catch { /* ignore */ }
}

onMounted(() => { load(); loadRoles(); loadLocked() })
</script>

<template>
  <AppLayout>
    <div class="space-y-5">
      <div class="flex items-center justify-between">
        <h1 class="text-2xl font-bold text-gray-900 dark:text-white">User Management</h1>
        <Button v-if="auth.hasAction('api_user_permissions', 'create')" label="Create User" icon="pi pi-plus" @click="createVisible = true" />
      </div>

      <div class="flex flex-wrap items-center gap-4">
        <InputText v-model="search" placeholder="Search users..." class="w-64" @keyup.enter="load" />
        <label class="flex items-center gap-2 text-sm" for="locked-only">
          <Checkbox v-model="lockedOnly" binary input-id="locked-only" data-testid="locked-only" />
          Locked only
        </label>
        <Tag
          v-if="lockedCount > 0" :value="`${lockedCount} locked`" severity="warn" icon="pi pi-lock"
          data-testid="locked-count"
        />
        <Button
          v-if="canUnlock && lockedCount > 0" label="Unlock all" icon="pi pi-lock-open"
          size="small" severity="warn" outlined data-testid="unlock-all" @click="unlockAllVisible = true"
        />
      </div>

      <Message v-if="lockedLoadError" severity="warn" :closable="false" data-testid="locked-load-error">
        Locked users could not be loaded: {{ lockedLoadError }}
      </Message>
      <Message v-if="lockedOnly && lockedScanCut" severity="warn" :closable="false" data-testid="locked-scan-cut">
        Only the first {{ LOCKED_SCAN_SIZE * LOCKED_SCAN_PAGES }} users were checked. Narrow the list
        with the search, or see System Settings → Failed logins for every locked name.
      </Message>

      <DataTable :value="rows" :loading="loading" striped-rows class="shadow-sm">
        <template v-if="lockedOnly" #empty>
          <span data-testid="locked-only-empty">
            <template v-if="lockedLoadError">Locked users could not be loaded</template>
            <template v-else-if="lockedCount === 0">No user is locked</template>
            <template v-else>
              No Pantera user among the {{ lockedCount }} locked {{ lockedCount === 1 ? 'name' : 'names' }}{{ search ? ' matches the search' : '' }}.
              System Settings → Failed logins lists every locked name.
            </template>
          </span>
        </template>
        <Column field="name" header="Username" sortable />
        <Column field="email" header="Email" />
        <Column field="enabled" header="Status">
          <template #body="{ data }">
            <div class="flex flex-wrap gap-1">
              <Tag :value="data.enabled !== false ? 'Active' : 'Disabled'" :severity="data.enabled !== false ? 'success' : 'danger'" />
              <Tag v-if="isLocked(data.name)" value="Locked" severity="warn" icon="pi pi-lock" data-testid="locked-tag" />
            </div>
          </template>
        </Column>
        <Column header="Actions" class="w-48">
          <template #body="{ data }">
            <div class="flex gap-1">
              <Button v-if="auth.hasAction('api_user_permissions', 'update')" icon="pi pi-pencil" text size="small" @click="$router.push(`/admin/users/${data.name}`)" />
              <Button
                v-if="auth.hasAction('api_user_permissions', 'enable')" :icon="data.enabled !== false ? 'pi pi-ban' : 'pi pi-check-circle'" text size="small"
                :severity="data.enabled !== false ? 'warn' : 'success'" @click="toggleUser(data)"
              />
              <Button
                v-if="canUnlock && isLocked(data.name)" icon="pi pi-lock-open" text size="small" severity="warn"
                title="Unlock" aria-label="Unlock" :loading="unlocking === data.name" :disabled="unlockingAll"
                data-testid="unlock-user" @click="unlock(data.name)"
              />
              <Button v-if="auth.hasAction('api_user_permissions', 'delete')" icon="pi pi-trash" text size="small" severity="danger" @click="handleDelete(data.name)" />
            </div>
          </template>
        </Column>
      </DataTable>

      <Paginator
        v-if="!lockedOnly && total > size" :rows="size" :total-records="total" :first="page * size"
        :rows-per-page-options="[10, 20, 50]" @page="(e: any) => { page = e.page; size = e.rows; load() }"
      />

      <UnlockAllDialog
        v-model:visible="unlockAllVisible" :count="lockedCount" :running="unlockingAll"
        @confirm="confirmUnlockAll"
      />

      <!-- Delete Dialog -->
      <Dialog v-model:visible="delVisible" header="Confirm Delete" modal class="w-96">
        <p>Delete user <strong>{{ targetName }}</strong>?</p>
        <template #footer>
          <Button label="Cancel" severity="secondary" text @click="rejectDel" />
          <Button label="Delete" severity="danger" @click="acceptDel" />
        </template>
      </Dialog>

      <!-- Create Dialog -->
      <Dialog v-model:visible="createVisible" header="Create User" modal class="w-[450px]">
        <div class="space-y-3">
          <InputText v-model="newUsername" placeholder="Username" class="w-full" autocomplete="off" />
          <PasswordComplexityForm
            v-model:old-password="unusedOldPassword"
            v-model:password="newPassword"
            :username="newUsername"
            :disabled="creating"
            hide-old-password
            @valid="(v: boolean) => newPasswordValid = v"
          />
          <InputText v-model="newEmail" placeholder="Email (optional)" class="w-full" />
          <div>
            <label class="block text-sm font-medium mb-1">Roles (optional)</label>
            <MultiSelect
              v-model="newRoles"
              :options="availableRoles.map(r => r.name).filter(Boolean)"
              placeholder="Select roles"
              class="w-full"
              display="chip"
            />
          </div>
          <Message v-if="createError" severity="error" :closable="false" data-testid="create-user-error">
            {{ createError }}
          </Message>
        </div>
        <template #footer>
          <Button label="Cancel" severity="secondary" text @click="createVisible = false" />
          <Button label="Create" :loading="creating" :disabled="!newUsername || !newPasswordValid" @click="handleCreate" />
        </template>
      </Dialog>
    </div>
  </AppLayout>
</template>
