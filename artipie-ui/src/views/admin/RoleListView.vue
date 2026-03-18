<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listRoles, getRole, deleteRole, enableRole, disableRole, putRole } from '@/api/roles'
import { listRepos } from '@/api/repos'
import { useNotificationStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import { useConfirmDelete } from '@/composables/useConfirmDelete'
import AppLayout from '@/components/layout/AppLayout.vue'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import InputText from 'primevue/inputtext'
import AutoComplete from 'primevue/autocomplete'
import Dialog from 'primevue/dialog'
import Textarea from 'primevue/textarea'
import Checkbox from 'primevue/checkbox'
import Paginator from 'primevue/paginator'
import type { Role } from '@/types'

const notify = useNotificationStore()
const auth = useAuthStore()
const { visible: delVisible, targetName, confirm: confirmDel, accept: acceptDel, reject: rejectDel } = useConfirmDelete()

const roles = ref<Role[]>([])
const page = ref(0)
const size = ref(20)
const total = ref(0)
const loading = ref(false)

const dialogVisible = ref(false)
const editMode = ref(false)
const newRoleName = ref('')
const newPermissions = ref('{}')
const saving = ref(false)
const advancedMode = ref(false)

// ── Repo name suggestions for autocomplete ──
const repoSuggestions = ref<string[]>([])
const allRepoNames = ref<string[]>([])
async function loadRepoNames() {
  try {
    const resp = await listRepos({ size: 500 })
    allRepoNames.value = ['*', ...resp.items.map(r => typeof r === 'string' ? r : r.name)]
  } catch {
    allRepoNames.value = ['*']
  }
}
function searchRepos(event: { query: string }) {
  const q = (event.query ?? '').toLowerCase()
  if (!q) {
    repoSuggestions.value = [...allRepoNames.value]
  } else {
    repoSuggestions.value = allRepoNames.value.filter(r => r.toLowerCase().includes(q))
  }
}

// ── All-permission toggle ──
const allPermission = ref(false)

// ── API management permissions (flat arrays) ──
const apiRepoActions = ref<string[]>([])
const apiUserActions = ref<string[]>([])
const apiRoleActions = ref<string[]>([])
const apiStorageActions = ref<string[]>([])
const apiSearchActions = ref<string[]>([])
const apiCooldownActions = ref<string[]>([])

// ── Adapter basic (repo-scoped) ──
interface RepoEntry { name: string; actions: string[] }
const repoEntries = ref<RepoEntry[]>([])
function addRepoEntry() { repoEntries.value.push({ name: '', actions: [] }) }
function removeRepoEntry(i: number) { repoEntries.value.splice(i, 1) }

// ── Docker repository permissions (repo + image scoped) ──
interface DockerRepoEntry { repo: string; image: string; actions: string[] }
const dockerRepoEntries = ref<DockerRepoEntry[]>([])
function addDockerRepoEntry() { dockerRepoEntries.value.push({ repo: '', image: '*', actions: [] }) }
function removeDockerRepoEntry(i: number) { dockerRepoEntries.value.splice(i, 1) }

// ── Docker registry permissions (repo-scoped) ──
interface DockerRegEntry { repo: string; actions: string[] }
const dockerRegEntries = ref<DockerRegEntry[]>([])
function addDockerRegEntry() { dockerRegEntries.value.push({ repo: '', actions: [] }) }
function removeDockerRegEntry(i: number) { dockerRegEntries.value.splice(i, 1) }

function buildPermissions(): Record<string, unknown> {
  if (advancedMode.value) return JSON.parse(newPermissions.value)

  const perms: Record<string, unknown> = {}

  if (allPermission.value) {
    perms['all_permission'] = {}
    return perms
  }

  // API permissions (flat arrays — collapse to * when all selected)
  if (apiRepoActions.value.length) perms['api_repository_permissions'] = collapseWildcard(apiRepoActions.value, 'api_repository_permissions')
  if (apiUserActions.value.length) perms['api_user_permissions'] = collapseWildcard(apiUserActions.value, 'api_user_permissions')
  if (apiRoleActions.value.length) perms['api_role_permissions'] = collapseWildcard(apiRoleActions.value, 'api_role_permissions')
  if (apiStorageActions.value.length) perms['api_storage_alias_permissions'] = collapseWildcard(apiStorageActions.value, 'api_storage_alias_permissions')
  if (apiSearchActions.value.length) perms['api_search_permissions'] = collapseWildcard(apiSearchActions.value, 'api_search_permissions')
  if (apiCooldownActions.value.length) perms['api_cooldown_permissions'] = collapseWildcard(apiCooldownActions.value, 'api_cooldown_permissions')

  // Adapter basic permissions (repo-scoped)
  const adapter: Record<string, string[]> = {}
  for (const e of repoEntries.value) {
    if (e.name && e.actions.length) adapter[e.name] = collapseWildcard(e.actions, 'adapter_basic_permissions')
  }
  if (Object.keys(adapter).length) perms['adapter_basic_permissions'] = adapter

  // Docker repository permissions (repo + image scoped)
  const dockerRepo: Record<string, Record<string, string[]>> = {}
  for (const e of dockerRepoEntries.value) {
    if (e.repo && e.actions.length) {
      if (!dockerRepo[e.repo]) dockerRepo[e.repo] = {}
      dockerRepo[e.repo][e.image || '*'] = collapseWildcard(e.actions, 'docker_repository_permissions')
    }
  }
  if (Object.keys(dockerRepo).length) perms['docker_repository_permissions'] = dockerRepo

  // Docker registry permissions (repo-scoped)
  const dockerReg: Record<string, string[]> = {}
  for (const e of dockerRegEntries.value) {
    if (e.repo && e.actions.length) dockerReg[e.repo] = collapseWildcard(e.actions, 'docker_registry_permissions')
  }
  if (Object.keys(dockerReg).length) perms['docker_registry_permissions'] = dockerReg

  return perms
}

function resetPermissions() {
  allPermission.value = false
  apiRepoActions.value = []
  apiUserActions.value = []
  apiRoleActions.value = []
  apiStorageActions.value = []
  apiSearchActions.value = []
  apiCooldownActions.value = []
  repoEntries.value = []
  dockerRepoEntries.value = []
  dockerRegEntries.value = []
}

function resetForm() {
  newRoleName.value = ''
  newPermissions.value = '{}'
  advancedMode.value = false
  editMode.value = false
  resetPermissions()
}

const allActionsMap: Record<string, string[]> = {
  api_repository_permissions: ['read', 'create', 'update', 'delete', 'move'],
  api_user_permissions: ['read', 'create', 'update', 'delete', 'enable', 'change_password'],
  api_role_permissions: ['read', 'create', 'update', 'delete', 'enable'],
  api_storage_alias_permissions: ['read', 'create', 'delete'],
  api_search_permissions: ['read', 'write'],
  api_cooldown_permissions: ['read', 'write'],
  adapter_basic_permissions: ['read', 'write', 'delete'],
  docker_repository_permissions: ['pull', 'push', 'overwrite'],
  docker_registry_permissions: ['base', 'catalog'],
}

function toStringArray(val: unknown): string[] {
  if (Array.isArray(val)) return val.map(String)
  return []
}

function expandWildcard(actions: string[], permType: string): string[] {
  if (actions.includes('*')) return allActionsMap[permType] ?? actions
  return actions
}

function collapseWildcard(actions: string[], permType: string): string[] {
  const all = allActionsMap[permType]
  if (all && all.every(a => actions.includes(a))) return ['*']
  return actions
}

/**
 * Unwrap permissions from API response.
 * The DB stores the full PUT body {"permissions": {...}} in the permissions column,
 * and roleFromRow wraps it again, so role.permissions may be double-nested.
 */
function unwrapPermissions(raw: Record<string, unknown>): Record<string, unknown> {
  if (raw['permissions'] && typeof raw['permissions'] === 'object' && !Array.isArray(raw['permissions'])) {
    return raw['permissions'] as Record<string, unknown>
  }
  return raw
}

function loadPermissionsIntoForm(rawPerms: Record<string, unknown>) {
  resetPermissions()
  if (!rawPerms || typeof rawPerms !== 'object') return

  const perms = unwrapPermissions(rawPerms)

  if ('all_permission' in perms) {
    allPermission.value = true
    return
  }

  // API flat-array permissions
  apiRepoActions.value = expandWildcard(toStringArray(perms['api_repository_permissions']), 'api_repository_permissions')
  apiUserActions.value = expandWildcard(toStringArray(perms['api_user_permissions']), 'api_user_permissions')
  apiRoleActions.value = expandWildcard(toStringArray(perms['api_role_permissions']), 'api_role_permissions')
  apiStorageActions.value = expandWildcard(toStringArray(perms['api_storage_alias_permissions']), 'api_storage_alias_permissions')
  apiSearchActions.value = expandWildcard(toStringArray(perms['api_search_permissions']), 'api_search_permissions')
  apiCooldownActions.value = expandWildcard(toStringArray(perms['api_cooldown_permissions']), 'api_cooldown_permissions')

  // Adapter basic permissions: { "repo": ["read", ...] }
  const adapter = perms['adapter_basic_permissions']
  if (adapter && typeof adapter === 'object') {
    for (const [repoName, actions] of Object.entries(adapter as Record<string, unknown>)) {
      repoEntries.value.push({ name: repoName, actions: expandWildcard(toStringArray(actions), 'adapter_basic_permissions') })
    }
  }

  // Docker repository permissions: { "repo": { "image": ["pull", ...] } }
  const dockerRepo = perms['docker_repository_permissions']
  if (dockerRepo && typeof dockerRepo === 'object') {
    for (const [repo, images] of Object.entries(dockerRepo as Record<string, unknown>)) {
      if (images && typeof images === 'object') {
        for (const [image, actions] of Object.entries(images as Record<string, unknown>)) {
          dockerRepoEntries.value.push({ repo, image, actions: expandWildcard(toStringArray(actions), 'docker_repository_permissions') })
        }
      }
    }
  }

  // Docker registry permissions: { "repo": ["base", ...] }
  const dockerReg = perms['docker_registry_permissions']
  if (dockerReg && typeof dockerReg === 'object') {
    for (const [repo, actions] of Object.entries(dockerReg as Record<string, unknown>)) {
      dockerRegEntries.value.push({ repo, actions: expandWildcard(toStringArray(actions), 'docker_registry_permissions') })
    }
  }
}

async function openEditRole(roleName: string) {
  resetForm()
  editMode.value = true
  newRoleName.value = roleName
  loadRepoNames()
  dialogVisible.value = true
  try {
    const role = await getRole(roleName)
    loadPermissionsIntoForm(role.permissions)
    const inner = unwrapPermissions(role.permissions)
    newPermissions.value = JSON.stringify(inner, null, 2)
  } catch {
    notify.error('Failed to load role', roleName)
  }
}

function openCreateRole() {
  resetForm()
  loadRepoNames()
  dialogVisible.value = true
}

async function load() {
  loading.value = true
  try {
    const resp = await listRoles({ page: page.value, size: size.value })
    roles.value = resp.items
    total.value = resp.total
  } finally { loading.value = false }
}

async function handleDelete(name: string) {
  if (await confirmDel(name)) {
    try { await deleteRole(name); notify.success('Role deleted', name); load() }
    catch { notify.error('Failed to delete role') }
  }
}

async function toggleRole(role: Role) {
  try {
    if (role.enabled !== false) { await disableRole(role.name); notify.info('Role disabled') }
    else { await enableRole(role.name); notify.info('Role enabled') }
    load()
  } catch { notify.error('Failed to toggle role') }
}

async function handleSave() {
  saving.value = true
  try {
    await putRole(newRoleName.value, { permissions: buildPermissions() })
    notify.success(editMode.value ? 'Role updated' : 'Role created', newRoleName.value)
    dialogVisible.value = false
    resetForm()
    load()
  } catch { notify.error(editMode.value ? 'Failed to update role' : 'Failed to create role') }
  finally { saving.value = false }
}

onMounted(load)
</script>

<template>
  <AppLayout>
    <div class="space-y-5">
      <div class="flex items-center justify-between">
        <h1 class="text-2xl font-bold text-gray-900 dark:text-white">Roles & Permissions</h1>
        <Button v-if="auth.hasAction('api_role_permissions', 'create')" label="Create Role" icon="pi pi-plus" @click="openCreateRole" />
      </div>

      <DataTable :value="roles" :loading="loading" stripedRows class="shadow-sm">
        <Column field="name" header="Name" sortable />
        <Column field="enabled" header="Status">
          <template #body="{ data }">
            <Tag :value="data.enabled !== false ? 'Active' : 'Disabled'" :severity="data.enabled !== false ? 'success' : 'danger'" />
          </template>
        </Column>
        <Column header="Actions" class="w-48">
          <template #body="{ data }">
            <div class="flex gap-1">
              <Button v-if="auth.hasAction('api_role_permissions', 'update')" icon="pi pi-pencil" text size="small" @click="openEditRole(data.name)" />
              <Button v-if="auth.hasAction('api_role_permissions', 'enable')" :icon="data.enabled !== false ? 'pi pi-ban' : 'pi pi-check-circle'" text size="small"
                :severity="data.enabled !== false ? 'warn' : 'success'" @click="toggleRole(data)" />
              <Button v-if="auth.hasAction('api_role_permissions', 'delete')" icon="pi pi-trash" text size="small" severity="danger" @click="handleDelete(data.name)" />
            </div>
          </template>
        </Column>
      </DataTable>

      <Paginator v-if="total > size" :rows="size" :totalRecords="total" :first="page * size"
        @page="(e: any) => { page = e.page; size = e.rows; load() }" :rowsPerPageOptions="[10, 20, 50]" />

      <Dialog v-model:visible="delVisible" header="Confirm Delete" modal class="w-96">
        <p>Delete role <strong>{{ targetName }}</strong>?</p>
        <template #footer>
          <Button label="Cancel" severity="secondary" text @click="rejectDel" />
          <Button label="Delete" severity="danger" @click="acceptDel" />
        </template>
      </Dialog>

      <!-- Create / Edit Role Dialog -->
      <Dialog v-model:visible="dialogVisible" :header="editMode ? `Edit Role: ${newRoleName}` : 'Create Role'" modal class="w-[700px]" :style="{ maxHeight: '90vh' }">
        <div class="space-y-4 overflow-y-auto" style="max-height: 70vh">
          <InputText v-model="newRoleName" placeholder="Role name" class="w-full" :disabled="editMode" />

          <div class="flex items-center gap-2">
            <Checkbox v-model="advancedMode" :binary="true" inputId="advRoleMode" />
            <label for="advRoleMode" class="text-sm text-gray-500 cursor-pointer">Advanced mode (raw JSON)</label>
          </div>

          <!-- Advanced: raw JSON -->
          <div v-if="advancedMode">
            <label class="block text-sm font-medium mb-1">Permissions (JSON)</label>
            <Textarea v-model="newPermissions" rows="10" class="w-full font-mono text-sm" />
          </div>

          <!-- Structured permissions -->
          <template v-else>
            <!-- All Permission -->
            <div class="flex items-center gap-2 p-3 bg-yellow-50 dark:bg-yellow-900/20 rounded-lg border border-yellow-200 dark:border-yellow-800">
              <Checkbox v-model="allPermission" :binary="true" inputId="allPerm" />
              <label for="allPerm" class="text-sm font-semibold cursor-pointer">Grant All Permissions (admin)</label>
            </div>

            <template v-if="!allPermission">
              <!-- API Management Permissions -->
              <div class="border rounded-lg p-3 space-y-3">
                <h4 class="text-sm font-bold text-gray-700 dark:text-gray-300">API Management</h4>

                <div>
                  <span class="text-xs font-semibold text-gray-500 uppercase">Repository Management</span>
                  <div class="flex flex-wrap gap-x-4 gap-y-1 mt-1">
                    <div v-for="a in ['read', 'create', 'update', 'delete', 'move']" :key="'apirepo_' + a" class="flex items-center gap-1">
                      <Checkbox v-model="apiRepoActions" :value="a" :inputId="'apirepo_' + a" />
                      <label :for="'apirepo_' + a" class="text-sm cursor-pointer">{{ a }}</label>
                    </div>
                  </div>
                </div>

                <div>
                  <span class="text-xs font-semibold text-gray-500 uppercase">User Management</span>
                  <div class="flex flex-wrap gap-x-4 gap-y-1 mt-1">
                    <div v-for="a in ['read', 'create', 'update', 'delete', 'enable', 'change_password']" :key="'apiuser_' + a" class="flex items-center gap-1">
                      <Checkbox v-model="apiUserActions" :value="a" :inputId="'apiuser_' + a" />
                      <label :for="'apiuser_' + a" class="text-sm cursor-pointer">{{ a }}</label>
                    </div>
                  </div>
                </div>

                <div>
                  <span class="text-xs font-semibold text-gray-500 uppercase">Role Management</span>
                  <div class="flex flex-wrap gap-x-4 gap-y-1 mt-1">
                    <div v-for="a in ['read', 'create', 'update', 'delete', 'enable']" :key="'apirole_' + a" class="flex items-center gap-1">
                      <Checkbox v-model="apiRoleActions" :value="a" :inputId="'apirole_' + a" />
                      <label :for="'apirole_' + a" class="text-sm cursor-pointer">{{ a }}</label>
                    </div>
                  </div>
                </div>

                <div>
                  <span class="text-xs font-semibold text-gray-500 uppercase">Storage Aliases</span>
                  <div class="flex flex-wrap gap-x-4 gap-y-1 mt-1">
                    <div v-for="a in ['read', 'create', 'delete']" :key="'apisto_' + a" class="flex items-center gap-1">
                      <Checkbox v-model="apiStorageActions" :value="a" :inputId="'apisto_' + a" />
                      <label :for="'apisto_' + a" class="text-sm cursor-pointer">{{ a }}</label>
                    </div>
                  </div>
                </div>

                <div>
                  <span class="text-xs font-semibold text-gray-500 uppercase">Search</span>
                  <div class="flex flex-wrap gap-x-4 gap-y-1 mt-1">
                    <div v-for="a in ['read', 'write']" :key="'apisrch_' + a" class="flex items-center gap-1">
                      <Checkbox v-model="apiSearchActions" :value="a" :inputId="'apisrch_' + a" />
                      <label :for="'apisrch_' + a" class="text-sm cursor-pointer">{{ a }}</label>
                    </div>
                  </div>
                </div>

                <div>
                  <span class="text-xs font-semibold text-gray-500 uppercase">Cooldown</span>
                  <div class="flex flex-wrap gap-x-4 gap-y-1 mt-1">
                    <div v-for="a in ['read', 'write']" :key="'apicd_' + a" class="flex items-center gap-1">
                      <Checkbox v-model="apiCooldownActions" :value="a" :inputId="'apicd_' + a" />
                      <label :for="'apicd_' + a" class="text-sm cursor-pointer">{{ a }}</label>
                    </div>
                  </div>
                </div>
              </div>

              <!-- Adapter Basic Permissions (repo-scoped) -->
              <div class="border rounded-lg p-3 space-y-3">
                <div class="flex items-center justify-between">
                  <h4 class="text-sm font-bold text-gray-700 dark:text-gray-300">Repository Access</h4>
                  <Button label="Add Repo" icon="pi pi-plus" text size="small" @click="addRepoEntry" />
                </div>
                <p v-if="repoEntries.length === 0" class="text-xs text-gray-400">No repository access rules. Click "Add Repo" to grant read/write/delete on specific repositories.</p>
                <div v-for="(entry, i) in repoEntries" :key="'repo_' + i" class="flex items-start gap-2 p-2 bg-gray-50 dark:bg-gray-800 rounded">
                  <div class="flex-1 space-y-1">
                    <AutoComplete v-model="entry.name" :suggestions="repoSuggestions" @complete="searchRepos" :completeOnFocus="true" placeholder="Select repo or * for all" class="w-full" inputClass="w-full" size="small" />
                    <div class="flex gap-x-4">
                      <div v-for="a in ['read', 'write', 'delete']" :key="'re_' + i + a" class="flex items-center gap-1">
                        <Checkbox v-model="entry.actions" :value="a" :inputId="'re_' + i + a" />
                        <label :for="'re_' + i + a" class="text-xs cursor-pointer">{{ a }}</label>
                      </div>
                      <div class="flex items-center gap-1">
                        <Checkbox v-model="entry.actions" value="*" :inputId="'re_' + i + 'all'" />
                        <label :for="'re_' + i + 'all'" class="text-xs cursor-pointer">all</label>
                      </div>
                    </div>
                  </div>
                  <Button icon="pi pi-times" text size="small" severity="danger" @click="removeRepoEntry(i)" />
                </div>
              </div>

              <!-- Docker Permissions -->
              <div class="border rounded-lg p-3 space-y-4">
                <h4 class="text-sm font-bold text-gray-700 dark:text-gray-300">Docker Permissions</h4>
                <p class="text-xs text-gray-500">Docker has two permission levels: <strong>Image Access</strong> controls pull/push on specific images, <strong>Registry Access</strong> controls whether users can connect to the registry at all and list images.</p>

                <!-- Docker Image Access (docker_repository_permissions) -->
                <div class="border-l-2 border-blue-400 pl-3 space-y-2">
                  <div class="flex items-center justify-between">
                    <div>
                      <span class="text-xs font-semibold text-gray-600 dark:text-gray-400 uppercase">Image Access</span>
                      <span class="text-xs text-gray-400 ml-1">— pull, push, overwrite specific images</span>
                    </div>
                    <Button label="Add" icon="pi pi-plus" text size="small" @click="addDockerRepoEntry" />
                  </div>
                  <p v-if="dockerRepoEntries.length === 0" class="text-xs text-gray-400">No image access rules. Add one to grant pull/push on Docker images.</p>
                  <div v-for="(entry, i) in dockerRepoEntries" :key="'drepo_' + i" class="flex items-start gap-2 p-2 bg-gray-50 dark:bg-gray-800 rounded">
                    <div class="flex-1 space-y-1">
                      <div class="flex gap-2">
                        <div class="flex-1">
                          <label class="text-xs text-gray-500 mb-0.5 block">Repository</label>
                          <AutoComplete v-model="entry.repo" :suggestions="repoSuggestions" @complete="searchRepos" :completeOnFocus="true" placeholder="Docker repo or *" class="w-full" inputClass="w-full" size="small" />
                        </div>
                        <div class="flex-1">
                          <label class="text-xs text-gray-500 mb-0.5 block">Image</label>
                          <InputText v-model="entry.image" placeholder="image name or *" class="w-full" size="small" />
                        </div>
                      </div>
                      <div class="flex gap-x-4">
                        <div v-for="a in ['pull', 'push', 'overwrite']" :key="'dr_' + i + a" class="flex items-center gap-1">
                          <Checkbox v-model="entry.actions" :value="a" :inputId="'dr_' + i + a" />
                          <label :for="'dr_' + i + a" class="text-xs cursor-pointer">{{ a }}</label>
                        </div>
                        <div class="flex items-center gap-1">
                          <Checkbox v-model="entry.actions" value="*" :inputId="'dr_' + i + 'all'" />
                          <label :for="'dr_' + i + 'all'" class="text-xs cursor-pointer">all</label>
                        </div>
                      </div>
                    </div>
                    <Button icon="pi pi-times" text size="small" severity="danger" @click="removeDockerRepoEntry(i)" />
                  </div>
                </div>

                <!-- Docker Registry Access (docker_registry_permissions) -->
                <div class="border-l-2 border-purple-400 pl-3 space-y-2">
                  <div class="flex items-center justify-between">
                    <div>
                      <span class="text-xs font-semibold text-gray-600 dark:text-gray-400 uppercase">Registry Access</span>
                      <span class="text-xs text-gray-400 ml-1">— connect to /v2/ and list images in catalog</span>
                    </div>
                    <Button label="Add" icon="pi pi-plus" text size="small" @click="addDockerRegEntry" />
                  </div>
                  <p v-if="dockerRegEntries.length === 0" class="text-xs text-gray-400">No registry access rules. Users need <strong>base</strong> to connect and <strong>catalog</strong> to list images.</p>
                  <div v-for="(entry, i) in dockerRegEntries" :key="'dreg_' + i" class="flex items-start gap-2 p-2 bg-gray-50 dark:bg-gray-800 rounded">
                    <div class="flex-1 space-y-1">
                      <AutoComplete v-model="entry.repo" :suggestions="repoSuggestions" @complete="searchRepos" :completeOnFocus="true" placeholder="Docker repo or *" class="w-full" inputClass="w-full" size="small" />
                      <div class="flex gap-x-4">
                        <div v-for="a in ['base', 'catalog']" :key="'rg_' + i + a" class="flex items-center gap-1">
                          <Checkbox v-model="entry.actions" :value="a" :inputId="'rg_' + i + a" />
                          <label :for="'rg_' + i + a" class="text-xs cursor-pointer">{{ a }} <span class="text-gray-400">{{ a === 'base' ? '(connect to /v2/)' : '(list all images)' }}</span></label>
                        </div>
                        <div class="flex items-center gap-1">
                          <Checkbox v-model="entry.actions" value="*" :inputId="'rg_' + i + 'all'" />
                          <label :for="'rg_' + i + 'all'" class="text-xs cursor-pointer">all</label>
                        </div>
                      </div>
                    </div>
                    <Button icon="pi pi-times" text size="small" severity="danger" @click="removeDockerRegEntry(i)" />
                  </div>
                </div>
              </div>
            </template>
          </template>
        </div>
        <template #footer>
          <Button label="Cancel" severity="secondary" text @click="dialogVisible = false" />
          <Button :label="editMode ? 'Save' : 'Create'" :loading="saving" :disabled="!newRoleName" @click="handleSave" />
        </template>
      </Dialog>
    </div>
  </AppLayout>
</template>
