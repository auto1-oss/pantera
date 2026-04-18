# UI Repo Type Completeness + Group Member UX — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the UI repo-creation type list (add Go local/group, Gradle variants, fix hex→hexpm) and replace the free-text group member input with a searchable AutoComplete dropdown + inline create-member modal.

**Architecture:** Two changes to the `pantera-ui` Vue 3 frontend: (1) extend `repoTypes.ts` with missing types, (2) replace the `InputText` group-member list in `RepoConfigForm.vue` with PrimeVue `AutoComplete` filtered by compatible type + a "Create new" modal that opens a stripped-down `RepoConfigForm`.

**Tech Stack:** Vue 3 (Composition API), PrimeVue 4.5.5 (`AutoComplete`, `Dialog`, `Select`, `Button`, `Tag`), TypeScript, Vite.

---

### Task 1: Add missing repo types to `repoTypes.ts`

**Files:**
- Modify: `pantera-ui/src/utils/repoTypes.ts`
- Test: manual — verify all types render in the create-repo dropdown

- [ ] **Step 1: Add Go and Gradle to TECH_MAP**

In `pantera-ui/src/utils/repoTypes.ts`, verify `TECH_MAP` already has entries for `go` and `gradle`. If `gradle` is missing, add it:

```typescript
gradle: { label: 'Gradle', icon: 'pi pi-box', color: '#02303A', bgClass: 'bg-emerald-100', textClass: 'text-emerald-800' },
```

- [ ] **Step 2: Add missing types to REPO_TYPE_CREATE_OPTIONS**

Add to the `REPO_TYPE_CREATE_OPTIONS` array (maintain alphabetical grouping by tech):

```typescript
// Go section — add local + group (go-proxy already exists)
{ label: 'Go (Local)', value: 'go' },
{ label: 'Go (Group)', value: 'go-group' },

// Gradle section — all three variants (new)
{ label: 'Gradle (Local)', value: 'gradle' },
{ label: 'Gradle (Proxy)', value: 'gradle-proxy' },
{ label: 'Gradle (Group)', value: 'gradle-group' },

// PHP section — add group if missing
{ label: 'PHP (Group)', value: 'php-group' },
```

- [ ] **Step 3: Fix Hex value**

Find the existing Hex entry and change:
```typescript
// Before:
{ label: 'Hex (Local)', value: 'hex' },
// After:
{ label: 'Hex (Local)', value: 'hexpm' },
```

- [ ] **Step 4: Add Gradle to REPO_TYPE_FILTERS if missing**

Check the `REPO_TYPE_FILTERS` array — add a Gradle filter entry if not present:
```typescript
{ label: 'Gradle', value: 'gradle' },
```

- [ ] **Step 5: Verify type-check passes**

Run: `cd pantera-ui && npx vue-tsc --noEmit`
Expected: no type errors.

- [ ] **Step 6: Verify build**

Run: `cd pantera-ui && npm run build`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add pantera-ui/src/utils/repoTypes.ts
git commit -m "fix(ui): add missing repo types — Go local/group, Gradle all, fix hex→hexpm"
```

---

### Task 2: Replace group member InputText with AutoComplete dropdown

**Files:**
- Modify: `pantera-ui/src/components/admin/RepoConfigForm.vue`
- Test: manual — create a maven-group, verify dropdown shows only maven + maven-proxy repos

- [ ] **Step 1: Add imports and state for the AutoComplete**

At the top of `<script setup>` in `RepoConfigForm.vue`, add:

```typescript
import AutoComplete from 'primevue/autocomplete'
import Tag from 'primevue/tag'
import { getApiClient } from '@/api/client'

// State for compatible repos dropdown
const compatibleRepos = ref<Array<{ name: string; type: string }>>([])
const filteredRepos = ref<Array<{ name: string; type: string }>>([])
```

- [ ] **Step 2: Add the compatibility-filter function**

```typescript
/**
 * Given a group type like "maven-group", return the compatible member types.
 * Rule: strip "-group" → base; compatible = [base, base + "-proxy"]
 */
function compatibleTypes(groupType: string): string[] {
  const base = groupType.replace(/-group$/, '')
  return [base, `${base}-proxy`]
}

/**
 * Fetch repos compatible with the current group type from the API.
 */
async function fetchCompatibleRepos() {
  if (!repoType.value?.endsWith('-group')) return
  const types = compatibleTypes(repoType.value)
  try {
    const client = getApiClient()
    const resp = await client.get('/api/v1/repositories', { params: { size: 500 } })
    const all: Array<{ name: string; type: string }> = resp.data.items || []
    compatibleRepos.value = all.filter(r => types.includes(r.type))
  } catch (e) {
    console.error('Failed to fetch compatible repos', e)
    compatibleRepos.value = []
  }
}

/**
 * PrimeVue AutoComplete completeMethod — filters the pre-fetched list client-side.
 */
function searchRepos(event: { query: string }) {
  const q = event.query.toLowerCase()
  filteredRepos.value = compatibleRepos.value.filter(
    r => !groupMembers.value.includes(r.name) && r.name.toLowerCase().includes(q)
  )
}
```

- [ ] **Step 3: Watch repoType to re-fetch compatible repos**

```typescript
watch(repoType, () => {
  fetchCompatibleRepos()
})
onMounted(() => {
  if (repoType.value?.endsWith('-group')) fetchCompatibleRepos()
})
```

- [ ] **Step 4: Replace the InputText in the group member list with AutoComplete**

Replace the `<InputText v-model="groupMembers[idx]" placeholder="repository-name" class="flex-1" />` (around line 479) with:

```vue
<AutoComplete
  v-model="groupMembers[idx]"
  :suggestions="filteredRepos"
  optionLabel="name"
  field="name"
  @complete="searchRepos"
  @item-select="(e: any) => { groupMembers[idx] = e.value.name }"
  placeholder="Search repos..."
  class="flex-1"
  :dropdown="true"
  forceSelection
>
  <template #option="{ option }">
    <div class="flex items-center gap-2">
      <span>{{ option.name }}</span>
      <Tag :value="option.type" severity="info" class="text-xs" />
    </div>
  </template>
</AutoComplete>
```

- [ ] **Step 5: Verify type-check + build**

Run: `cd pantera-ui && npx vue-tsc --noEmit && npm run build`
Expected: success.

- [ ] **Step 6: Commit**

```bash
git add pantera-ui/src/components/admin/RepoConfigForm.vue
git commit -m "feat(ui): searchable AutoComplete dropdown for group member selection"
```

---

### Task 3: Add "Create new member" modal

**Files:**
- Modify: `pantera-ui/src/components/admin/RepoConfigForm.vue`

- [ ] **Step 1: Add Dialog state**

```typescript
import Dialog from 'primevue/dialog'

const showCreateMemberDialog = ref(false)
const newMemberType = ref('')
const newMemberName = ref('')
const newMemberCreating = ref(false)
```

- [ ] **Step 2: Add the create-member function**

```typescript
async function createMemberRepo() {
  if (!newMemberName.value || !newMemberType.value) return
  newMemberCreating.value = true
  try {
    const client = getApiClient()
    await client.put(`/api/v1/repositories/${newMemberName.value}`, {
      type: newMemberType.value,
      storage: { type: 'fs' }  // minimal config for local; proxy needs remote_url
    })
    // Add to members list + refresh dropdown
    groupMembers.value.push(newMemberName.value)
    await fetchCompatibleRepos()
    showCreateMemberDialog.value = false
    newMemberName.value = ''
    newMemberType.value = ''
  } catch (e: any) {
    console.error('Failed to create member repo', e)
  } finally {
    newMemberCreating.value = false
  }
}
```

- [ ] **Step 3: Add the Dialog template**

After the "Add member" button, add:

```vue
<Button
  icon="pi pi-plus-circle"
  label="Create new"
  severity="info"
  outlined
  size="small"
  class="ml-2"
  @click="showCreateMemberDialog = true"
/>

<Dialog
  v-model:visible="showCreateMemberDialog"
  header="Create New Member Repository"
  :modal="true"
  :style="{ width: '500px' }"
>
  <div class="flex flex-col gap-4">
    <div>
      <label class="block text-sm font-medium mb-1">Type</label>
      <Select
        v-model="newMemberType"
        :options="compatibleTypes(repoType).map(t => ({ label: t, value: t }))"
        optionLabel="label"
        optionValue="value"
        placeholder="Select type"
        class="w-full"
      />
    </div>
    <div>
      <label class="block text-sm font-medium mb-1">Name</label>
      <InputText v-model="newMemberName" placeholder="e.g. maven-central" class="w-full" />
    </div>
  </div>
  <template #footer>
    <Button label="Cancel" severity="secondary" @click="showCreateMemberDialog = false" />
    <Button
      label="Create & Add"
      icon="pi pi-check"
      :loading="newMemberCreating"
      :disabled="!newMemberName || !newMemberType"
      @click="createMemberRepo"
    />
  </template>
</Dialog>
```

- [ ] **Step 4: Verify type-check + build**

Run: `cd pantera-ui && npx vue-tsc --noEmit && npm run build`
Expected: success.

- [ ] **Step 5: Commit**

```bash
git add pantera-ui/src/components/admin/RepoConfigForm.vue
git commit -m "feat(ui): inline create-member modal for group repos"
```

---

### Task 4: Update documentation

**Files:**
- Modify: `docs/user-guide/` or equivalent (check what exists)

- [ ] **Step 1: Update repo-creation docs**

Add a note about the new Go and Gradle repo types. Document the group member dropdown + create-member workflow.

- [ ] **Step 2: Commit**

```bash
git add docs/
git commit -m "docs: update repo creation guide for new types + group member UX"
```

---

### Task 5: Final verification

- [ ] **Step 1: Full UI build**

Run: `cd pantera-ui && npm run build`
Expected: success, no warnings.

- [ ] **Step 2: Backend compile**

Run: `mvn -T8 install -DskipTests -Dmaven.docker.plugin.skip=true -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Push + update PR**

```bash
git push origin 2.2.0
gh pr edit 34 --body-file docs/analysis/v2.2.0-pr-description.md
```
