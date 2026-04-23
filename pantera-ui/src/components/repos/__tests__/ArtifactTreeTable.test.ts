import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ArtifactTreeTable from '../ArtifactTreeTable.vue'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import type { TreeEntry } from '@/types'

const items: TreeEntry[] = []

function mountTable(overrides: Partial<Parameters<typeof mount>[1]> = {}) {
  return mount(ArtifactTreeTable, {
    props: {
      items,
      sortBy: 'name',
      sortDir: 'asc',
      loading: false,
    },
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
    },
    ...overrides,
  })
}

describe('ArtifactTreeTable', () => {
  it('renders three column headers: Name, Uploaded, Size', () => {
    const wrapper = mountTable()
    const headers = wrapper.findAll('[data-testid="tree-header"]')
    expect(headers).toHaveLength(3)
    expect(headers[0].text()).toContain('Name')
    expect(headers[1].text()).toContain('Uploaded')
    expect(headers[2].text()).toContain('Size')
  })

  it('emits sort event with the clicked column key', async () => {
    const wrapper = mountTable()
    const headers = wrapper.findAll('[data-testid="tree-header"]')
    await headers[1].trigger('click')
    await headers[2].trigger('click')
    await headers[0].trigger('click')
    const emitted = wrapper.emitted('sort') as Array<['name' | 'date' | 'size']>
    expect(emitted).toEqual([['date'], ['size'], ['name']])
  })

  it('sets aria-sort on the active column and "none" on the others', () => {
    const wrapper = mountTable({
      props: {
        items,
        sortBy: 'date',
        sortDir: 'desc',
        loading: false,
      },
    })
    const headers = wrapper.findAll('[data-testid="tree-header"]')
    expect(headers[0].attributes('aria-sort')).toBe('none')
    expect(headers[1].attributes('aria-sort')).toBe('descending')
    expect(headers[2].attributes('aria-sort')).toBe('none')
  })

  it('renders a file row with name, uploaded (absolute + relative), size, and kind pill', () => {
    const fileItems: TreeEntry[] = [
      {
        name: 'pantera-main-2.1.4.jar',
        path: '/com/auto1/pantera/pantera-main-2.1.4.jar',
        type: 'file',
        size: 2_411_520,
        modified: '2026-04-23T14:02:00Z',
        artifact_kind: 'ARTIFACT',
      },
    ]
    const wrapper = mountTable({
      props: { items: fileItems, sortBy: 'name', sortDir: 'asc', loading: false },
    })
    const row = wrapper.get('[data-testid="tree-row"]')
    expect(row.text()).toContain('pantera-main-2.1.4.jar')
    expect(row.text()).toContain('artifact') // kind pill
    expect(row.text()).toContain('2.3 MB')    // formatted size
    expect(row.get('[data-testid="tree-uploaded-abs"]').text())
      .toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/)
    expect(row.get('[data-testid="tree-uploaded-rel"]').text())
      .toMatch(/ago/)
  })

  it('renders a directory row with em-dashes in Uploaded and Size cells and no kind pill', () => {
    const dirItems: TreeEntry[] = [
      { name: 'com', path: '/com', type: 'directory' },
    ]
    const wrapper = mountTable({
      props: { items: dirItems, sortBy: 'name', sortDir: 'asc', loading: false },
    })
    const row = wrapper.get('[data-testid="tree-row"]')
    expect(row.text()).toContain('com')
    expect(row.text()).not.toContain('artifact')
    expect(row.find('[data-testid="tree-uploaded-empty"]').exists()).toBe(true)
    expect(row.text()).toContain('—')
  })

  it('renders em-dashes when a file has no modified / size / artifact_kind', () => {
    const items: TreeEntry[] = [
      { name: 'orphan.jar', path: '/orphan.jar', type: 'file' },
    ]
    const wrapper = mountTable({
      props: { items, sortBy: 'name', sortDir: 'asc', loading: false },
    })
    const row = wrapper.get('[data-testid="tree-row"]')
    expect(row.text()).toContain('orphan.jar')
    expect(row.text()).not.toContain('artifact')
    expect(row.find('[data-testid="tree-uploaded-empty"]').exists()).toBe(true)
  })

  it('emits navigate with the clicked entry (file and directory)', async () => {
    const items: TreeEntry[] = [
      { name: 'com', path: '/com', type: 'directory' },
      { name: 'a.jar', path: '/a.jar', type: 'file', size: 10 },
    ]
    const wrapper = mountTable({
      props: { items, sortBy: 'name', sortDir: 'asc', loading: false },
    })
    const rows = wrapper.findAll('[data-testid="tree-row"]')
    await rows[0].trigger('click')
    await rows[1].trigger('click')
    const emitted = wrapper.emitted('navigate') as TreeEntry[][]
    expect(emitted).toHaveLength(2)
    expect(emitted[0][0].path).toBe('/com')
    expect(emitted[1][0].path).toBe('/a.jar')
  })
})
