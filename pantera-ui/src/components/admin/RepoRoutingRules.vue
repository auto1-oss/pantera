<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import SelectButton from 'primevue/selectbutton'
import Tag from 'primevue/tag'
import { listRepos } from '@/api/repos'
import { testRouting } from '@/api/routing'
import type { InheritedRoutingRule, RoutingTestResult } from '@/api/routing'
import { apiErrorMessage } from '@/utils/apiError'
import {
  parseRoutingProblems, ruleKind, ruleKindLabel, ruleMember, ruleValue,
} from '@/utils/routingRules'
import type { RoutingProblems, RoutingRuleKind } from '@/utils/routingRules'
import type { RoutingRule } from '@/types/repo'

// Routing rules card of a group repository: the rules of the group
// (`repo.routing`), the rules its nested groups bring along, and a test of
// one path. The form owns the rules and the members; this card edits the
// rules through `v-model:rules` and asks the server about the form's
// current, unsaved state. The server decides what a valid rule is: a
// pattern is a Java regular expression, which JavaScript cannot check.
const props = defineProps<{
  /** Name of the group. Empty while a new group has no usable name: the server is not asked then. */
  repoName: string
  /** Direct members of the form as it is now, in their order, unsaved changes included. */
  members: string[]
  /** The server's message of the last refused save; its rule problems are shown on their rows. */
  saveError?: string
}>()

/** The rules in their order. Never changed in place: every edit sets a new list. */
const rules = defineModel<RoutingRule[]>('rules', { required: true })

const NO_PROBLEMS: RoutingProblems = { routing: false, byRule: {}, general: [] }
/** A member name is typed letter by letter: the server is asked when the typing stops. */
const MEMBERS_DELAY_MS = 300

// ---------------------------------------------------------------------------
// Which member is a group
// ---------------------------------------------------------------------------
/** Group repositories by the repository list. */
const groupRepos = ref<string[]>([])
/** Groups named by the last answer of the routing test (`through`). */
const nestedGroups = ref<string[]>([])

function isGroup(name: string): boolean {
  return groupRepos.value.includes(name) || nestedGroups.value.includes(name)
}

async function loadGroups() {
  try {
    const page = await listRepos({ mode: 'group', size: 100 })
    groupRepos.value = (page.items ?? []).map(r => r.name)
  } catch {
    // Without the list a group is still refused by the server, on its rule.
    groupRepos.value = []
  }
}

/** What a rule can name: the direct members, a group among them not selectable. */
const memberOptions = computed(() =>
  [...new Set(props.members)].map(name => ({ name, group: isGroup(name) })),
)

// ---------------------------------------------------------------------------
// Problems of the rules, as the server reports them
// ---------------------------------------------------------------------------
const problems = ref<RoutingProblems>(NO_PROBLEMS)
const checking = ref(false)
const checkError = ref('')
const inherited = ref<InheritedRoutingRule[]>([])
let generation = 0
let timer: ReturnType<typeof setTimeout> | null = null

function problemsOf(index: number): string[] {
  return problems.value.byRule[index + 1] ?? []
}

/** Problems that belong to no row: no rule number, or the number of a rule that is not listed. */
const generalProblems = computed(() => {
  const out = [...problems.value.general]
  for (const [number, texts] of Object.entries(problems.value.byRule)) {
    if (Number(number) < 1 || Number(number) > rules.value.length) {
      out.push(...texts.map(text => `rule ${number}: ${text}`))
    }
  }
  return out
})

function listedBy(answer: RoutingTestResult) {
  inherited.value = answer.inherited
  nestedGroups.value = [...new Set(answer.members.map(m => m.through).filter(Boolean))]
}

/**
 * Ask the server about the rules and members as they are in the form,
 * without a path: it answers the rules of the nested groups, or 400 with
 * the problems of the rules. Refused rules are left out of a second call,
 * so the rules of the nested groups are known either way.
 */
async function check() {
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
  generation += 1
  const mine = generation
  checkError.value = ''
  if (!props.repoName) {
    checking.value = false
    inherited.value = []
    return
  }
  const members = [...props.members]
  checking.value = true
  try {
    let answer: RoutingTestResult
    try {
      answer = await testRouting(props.repoName, { members, routing: [...rules.value] })
      if (mine !== generation) return
      problems.value = NO_PROBLEMS
    } catch (err: unknown) {
      if (mine !== generation) return
      const found = parseRoutingProblems(apiErrorMessage(err, ''))
      if (!found.routing) throw err
      problems.value = found
      answer = await testRouting(props.repoName, { members, routing: [] })
      if (mine !== generation) return
    }
    listedBy(answer)
  } catch (err: unknown) {
    if (mine !== generation) return
    inherited.value = []
    checkError.value = apiErrorMessage(err, 'Unknown error')
  } finally {
    if (mine === generation) checking.value = false
  }
}

/** The form changed: what the server said about the old state no longer holds. */
function forget() {
  problems.value = NO_PROBLEMS
  clearTest()
}

// Compared as text, so a list that was rebuilt with the same content is no
// change. A changed rule is checked at once; a change of the members alone
// waits for the typing to stop.
watch(
  [() => props.repoName, () => JSON.stringify(rules.value), () => JSON.stringify(props.members)],
  ([name, ruleList], [oldName, oldRuleList]) => {
    forget()
    if (name !== oldName || ruleList !== oldRuleList) {
      check()
    } else {
      if (timer) clearTimeout(timer)
      timer = setTimeout(check, MEMBERS_DELAY_MS)
    }
  },
)
watch(() => props.saveError, (message) => {
  const found = parseRoutingProblems(message ?? '')
  if (found.routing) problems.value = found
})

onMounted(() => {
  loadGroups()
  check()
})
onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
  generation += 1
})

// ---------------------------------------------------------------------------
// Add and edit a rule
// ---------------------------------------------------------------------------
const KINDS: Array<{ label: string; value: RoutingRuleKind }> = [
  { label: 'Prefix', value: 'prefix' },
  { label: 'Pattern', value: 'pattern' },
]
const editorVisible = ref(false)
/** Index of the rule being edited; null while a new rule is added. */
const editIndex = ref<number | null>(null)
const draftMember = ref('')
const draftKind = ref<RoutingRuleKind>('prefix')
const draftValue = ref('')
/** The required fields are marked once the admin tried to submit. */
const submitted = ref(false)

const memberError = computed(() => {
  if (!draftMember.value) return 'Pick a member.'
  if (isGroup(draftMember.value)) {
    return `${draftMember.value} is a group: its repositories get their rules in that group.`
  }
  return ''
})
const valueError = computed(() => {
  if (draftValue.value.trim()) return ''
  return draftKind.value === 'prefix' ? 'Type a prefix.' : 'Type a pattern.'
})
/** Not an error: the server takes it. It just never matches. */
const leadingSlash = computed(() => draftKind.value === 'prefix' && draftValue.value.trim().startsWith('/'))

function openAdd() {
  editIndex.value = null
  draftMember.value = ''
  draftKind.value = 'prefix'
  draftValue.value = ''
  submitted.value = false
  editorVisible.value = true
}

function openEdit(index: number) {
  const rule = rules.value[index]
  const member = ruleMember(rule)
  editIndex.value = index
  // A member that left the group, or is a group, has to be picked anew.
  draftMember.value = memberOptions.value.some(o => o.name === member && !o.group) ? member : ''
  draftKind.value = ruleKind(rule) ?? 'prefix'
  draftValue.value = ruleValue(rule)
  submitted.value = false
  editorVisible.value = true
}

function submitRule() {
  submitted.value = true
  if (memberError.value || valueError.value) return
  const value = draftValue.value.trim()
  const rule: RoutingRule = draftKind.value === 'prefix'
    ? { member: draftMember.value, prefix: value }
    : { member: draftMember.value, pattern: value }
  const next = [...rules.value]
  if (editIndex.value === null) next.push(rule)
  else next[editIndex.value] = rule
  rules.value = next
  editorVisible.value = false
}

function removeRule(index: number) {
  rules.value = rules.value.filter((_, i) => i !== index)
}

/** A stored item that is no rule is shown as it is stored. */
function valueText(rule: unknown): string {
  if (ruleKind(rule)) return ruleValue(rule)
  return rule && typeof rule === 'object' ? '—' : JSON.stringify(rule)
}

// ---------------------------------------------------------------------------
// Test a path
// ---------------------------------------------------------------------------
const testPath = ref('')
const testResult = ref<RoutingTestResult | null>(null)
const testError = ref('')
const testing = ref(false)
let testGeneration = 0

const canTest = computed(() => !!props.repoName && testPath.value.trim().length > 0 && !testing.value)

function clearTest() {
  testGeneration += 1
  testResult.value = null
  testError.value = ''
  testing.value = false
}

async function runTest() {
  if (!canTest.value) return
  const path = testPath.value.trim()
  testGeneration += 1
  const mine = testGeneration
  testing.value = true
  testError.value = ''
  try {
    const answer = await testRouting(props.repoName, {
      path, members: [...props.members], routing: [...rules.value],
    })
    if (mine !== testGeneration) return
    testResult.value = answer
  } catch (err: unknown) {
    if (mine !== testGeneration) return
    testResult.value = null
    const message = apiErrorMessage(err, 'Unknown error')
    const found = parseRoutingProblems(message)
    if (found.routing) {
      problems.value = found
      testError.value = 'Fix the rules marked above, then test the path again.'
    } else {
      testError.value = message
    }
  } finally {
    if (mine === testGeneration) testing.value = false
  }
}
</script>

<template>
  <Card class="shadow-sm" data-testid="routing-card">
    <template #title>
      <div class="flex items-center justify-between">
        <span>Routing rules</span>
        <span class="text-xs font-normal text-gray-500">
          {{ rules.length }} {{ rules.length === 1 ? 'rule' : 'rules' }}
        </span>
      </div>
    </template>
    <template #content>
      <div class="space-y-4">
        <div class="space-y-1 text-xs text-gray-500" data-testid="routing-help">
          <p>
            A member named by a rule is asked only for the paths its rules match; for every other
            path it counts as not found without being asked. Several rules of one member are
            alternatives. A member without rules is asked for every path.
          </p>
          <p>
            The path is the part of the URL after the group's name, without a leading slash
            (<code>com/acme/app/1.0/app-1.0.jar</code>). Prefix: the path starts with the text.
            Pattern: a Java regular expression that must match the whole path.
          </p>
          <p>
            To keep internal names away from a public upstream: member <code>maven-central</code>,
            pattern <code>(?!com/acme/|internal/).*</code>. To ask a narrow or slow upstream only
            for what it hosts: member <code>jboss</code>, prefix <code>org/jboss/</code>.
          </p>
        </div>

        <div v-if="!repoName" class="text-xs text-gray-500" data-testid="routing-no-name">
          Name the repository first: the rules are checked and a path is tested under its name.
        </div>
        <div
          v-if="checkError"
          class="flex flex-wrap items-center gap-2 text-sm text-red-700 dark:text-red-300"
          data-testid="routing-check-error"
        >
          <span>The rules could not be checked: {{ checkError }}</span>
          <Button
            label="Check again"
            icon="pi pi-refresh"
            size="small"
            severity="secondary"
            text
            :loading="checking"
            data-testid="routing-check-retry"
            @click="check"
          />
        </div>
        <ul
          v-if="generalProblems.length"
          class="list-disc space-y-1 pl-5 text-sm text-red-700 dark:text-red-300"
          data-testid="routing-problems"
        >
          <li v-for="(problem, idx) in generalProblems" :key="idx" data-testid="routing-problem">{{ problem }}</li>
        </ul>

        <div v-if="rules.length" class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="text-left text-xs font-medium text-gray-500">
                <th class="py-1.5 pr-3 font-medium">#</th>
                <th class="py-1.5 pr-3 font-medium">Member</th>
                <th class="py-1.5 pr-3 font-medium">Kind</th>
                <th class="py-1.5 pr-3 font-medium">Value</th>
                <th class="py-1.5"><span class="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              <template v-for="(rule, idx) in rules" :key="idx">
                <tr class="border-t border-gray-200 align-top dark:border-gray-700" data-testid="routing-rule-row">
                  <td class="py-2 pr-3 tabular-nums text-gray-400" data-testid="routing-rule-number">{{ idx + 1 }}</td>
                  <td class="py-2 pr-3 font-mono break-all" data-testid="routing-rule-member">{{ ruleMember(rule) || '—' }}</td>
                  <td class="py-2 pr-3 whitespace-nowrap" data-testid="routing-rule-kind">{{ ruleKindLabel(rule) }}</td>
                  <td class="py-2 pr-3 font-mono break-all" data-testid="routing-rule-value">{{ valueText(rule) }}</td>
                  <td class="py-1 text-right whitespace-nowrap">
                    <Button
                      icon="pi pi-pencil"
                      severity="secondary"
                      text
                      rounded
                      size="small"
                      :aria-label="`Edit rule ${idx + 1}`"
                      data-testid="routing-rule-edit"
                      @click="openEdit(idx)"
                    />
                    <Button
                      icon="pi pi-trash"
                      severity="danger"
                      text
                      rounded
                      size="small"
                      :aria-label="`Remove rule ${idx + 1}`"
                      data-testid="routing-rule-remove"
                      @click="removeRule(idx)"
                    />
                  </td>
                </tr>
                <tr v-if="problemsOf(idx).length">
                  <td />
                  <td
                    colspan="4"
                    class="pb-2 pr-3 text-xs text-red-600 dark:text-red-400"
                    :data-rule="idx + 1"
                    data-testid="routing-rule-problem"
                  >
                    {{ problemsOf(idx).join('; ') }}
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
        <div
          v-else
          class="rounded border border-dashed border-gray-300 p-4 text-center text-sm text-gray-500 dark:border-gray-700"
          data-testid="routing-empty"
        >
          No routing rules: every member is asked for every path.
        </div>

        <Button
          label="Add rule"
          icon="pi pi-plus"
          severity="secondary"
          outlined
          size="small"
          data-testid="routing-rule-add"
          @click="openAdd"
        />

        <!-- Rules of nested groups (read-only) -->
        <section
          v-if="inherited.length"
          class="space-y-2 border-t border-gray-200 pt-4 dark:border-gray-700"
          data-testid="routing-inherited"
        >
          <div>
            <div class="text-sm font-medium">Rules of nested groups</div>
            <div class="text-xs text-gray-500">
              The rules of a member that is a group apply to its repositories in this group too.
              They are changed in that group, not here.
            </div>
          </div>
          <div class="overflow-x-auto">
            <table class="w-full text-sm">
              <thead>
                <tr class="text-left text-xs font-medium text-gray-500">
                  <th class="py-1.5 pr-3 font-medium">Group</th>
                  <th class="py-1.5 pr-3 font-medium">Member</th>
                  <th class="py-1.5 pr-3 font-medium">Kind</th>
                  <th class="py-1.5 font-medium">Value</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(rule, idx) in inherited"
                  :key="idx"
                  class="border-t border-gray-200 align-top text-gray-600 dark:border-gray-700 dark:text-gray-300"
                  data-testid="routing-inherited-row"
                >
                  <td class="py-2 pr-3 font-mono break-all">{{ rule.group }}</td>
                  <td class="py-2 pr-3 font-mono break-all">{{ rule.member }}</td>
                  <td class="py-2 pr-3 whitespace-nowrap">{{ ruleKindLabel(rule) }}</td>
                  <td class="py-2 font-mono break-all">{{ ruleValue(rule) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <!-- Test a path -->
        <section class="space-y-3 border-t border-gray-200 pt-4 dark:border-gray-700" data-testid="routing-test">
          <div>
            <div class="text-sm font-medium">Test a path</div>
            <div class="text-xs text-gray-500">
              Shows which repositories the group would ask for a path, with the members and rules
              as they are on this page, saved or not. No repository is asked.
            </div>
          </div>
          <div class="flex items-center gap-2">
            <InputText
              v-model="testPath"
              placeholder="com/acme/app/1.0/app-1.0.jar"
              class="flex-1 font-mono text-sm"
              aria-label="Path to test"
              autocomplete="off"
              data-testid="routing-test-path"
              @keydown.enter.prevent="runTest"
            />
            <Button
              label="Test"
              icon="pi pi-play"
              size="small"
              :loading="testing"
              :disabled="!canTest"
              data-testid="routing-test-btn"
              @click="runTest"
            />
          </div>
          <div v-if="testError" class="text-sm text-red-700 dark:text-red-300" data-testid="routing-test-error">
            {{ testError }}
          </div>
          <div v-if="testResult" class="space-y-1" data-testid="routing-test-result">
            <div class="text-xs text-gray-500">
              Asked in this order for <code class="break-all">{{ testResult.path }}</code>:
            </div>
            <div v-if="testResult.members.length === 0" class="text-sm text-gray-500">
              The group has no repositories to ask.
            </div>
            <ol v-else class="divide-y divide-gray-200 dark:divide-gray-800">
              <li
                v-for="(step, idx) in testResult.members"
                :key="`${idx}-${step.member}`"
                class="flex flex-wrap items-start gap-3 py-2"
                :data-member="step.member"
                data-testid="routing-test-step"
              >
                <span class="w-5 shrink-0 text-right text-sm text-gray-400">{{ idx + 1 }}</span>
                <div class="shrink-0">
                  <span class="font-mono text-sm font-semibold text-gray-900 dark:text-white" data-testid="routing-test-member">{{ step.member }}</span>
                  <div v-if="step.through" class="text-xs text-gray-500" data-testid="routing-test-through">
                    through {{ step.through }}
                  </div>
                </div>
                <Tag
                  :value="step.asked ? 'Asked' : 'Skipped'"
                  :severity="step.asked ? 'success' : 'secondary'"
                  class="shrink-0"
                  data-testid="routing-test-verdict"
                />
                <span class="min-w-[12rem] flex-1 text-sm text-gray-800 dark:text-gray-200" data-testid="routing-test-why">{{ step.why }}</span>
              </li>
            </ol>
          </div>
        </section>
      </div>

      <Dialog
        v-model:visible="editorVisible"
        :header="editIndex === null ? 'Add routing rule' : `Edit routing rule ${editIndex + 1}`"
        modal
        :style="{ width: '32rem' }"
      >
        <div class="flex flex-col gap-4" data-testid="routing-rule-editor">
          <div>
            <label class="mb-1 block text-sm font-medium" for="routingRuleMember">Member</label>
            <Select
              v-model="draftMember"
              label-id="routingRuleMember"
              :options="memberOptions"
              option-label="name"
              option-value="name"
              option-disabled="group"
              placeholder="Select a member"
              class="w-full"
              :invalid="submitted && !!memberError"
              data-testid="routing-editor-member"
            >
              <template #option="{ option }">
                <div>
                  <div class="font-mono text-sm">{{ option.name }}</div>
                  <div v-if="option.group" class="text-xs" data-testid="routing-editor-group-hint">
                    A group: its repositories get their rules in that group.
                  </div>
                </div>
              </template>
            </Select>
            <p v-if="memberOptions.length === 0" class="mt-1 text-xs text-gray-500">
              The group has no members yet. Add a member first.
            </p>
            <p v-if="submitted && memberError" class="mt-1 text-xs text-red-600" data-testid="routing-editor-member-error">
              {{ memberError }}
            </p>
          </div>
          <div>
            <span class="mb-1 block text-sm font-medium">Kind</span>
            <SelectButton
              v-model="draftKind"
              :options="KINDS"
              option-label="label"
              option-value="value"
              :allow-empty="false"
              aria-label="Kind of rule"
              data-testid="routing-editor-kind"
            />
            <p class="mt-1 text-xs text-gray-500">
              <template v-if="draftKind === 'prefix'">The member is asked for a path that starts with this text.</template>
              <template v-else>The member is asked for a path the whole of which matches this Java regular expression.</template>
            </p>
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium" for="routingRuleValue">
              {{ draftKind === 'prefix' ? 'Prefix' : 'Pattern' }}
            </label>
            <InputText
              id="routingRuleValue"
              v-model="draftValue"
              :placeholder="draftKind === 'prefix' ? 'org/jboss/' : '(?!com/acme/|internal/).*'"
              class="w-full font-mono text-sm"
              autocomplete="off"
              :invalid="submitted && !!valueError"
              data-testid="routing-editor-value"
              @keydown.enter.prevent="submitRule"
            />
            <p v-if="submitted && valueError" class="mt-1 text-xs text-red-600" data-testid="routing-editor-value-error">
              {{ valueError }}
            </p>
            <p v-else-if="leadingSlash" class="mt-1 text-xs text-amber-600 dark:text-amber-400" data-testid="routing-editor-slash-hint">
              A path has no leading slash, so this prefix matches nothing.
            </p>
            <p v-else-if="draftKind === 'pattern'" class="mt-1 text-xs text-gray-500">
              The server checks the expression when the rule is added.
            </p>
          </div>
        </div>
        <template #footer>
          <Button label="Cancel" severity="secondary" text data-testid="routing-editor-cancel" @click="editorVisible = false" />
          <Button
            :label="editIndex === null ? 'Add rule' : 'Save rule'"
            icon="pi pi-check"
            data-testid="routing-editor-submit"
            @click="submitRule"
          />
        </template>
      </Dialog>
    </template>
  </Card>
</template>
