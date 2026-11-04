import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'

const source = readFileSync(new URL('../src/components/magic-flow-canvas.vue', import.meta.url), 'utf8')
const syncStart = source.indexOf('const sync = () => {')
const syncCode = source.slice(syncStart, source.indexOf('\nwatch(', syncStart))
const teardownStart = source.indexOf('onBeforeUnmount(() => {')
const teardownCode = source.slice(teardownStart, source.indexOf('\n\n//', teardownStart))
const definition = { nodes: [{ id: 'start', type: 'http' }], edges: [] }

function canvas() {
    let teardown
    const state = { graph: structuredClone(definition), current: { script: JSON.stringify(definition) } }
    const context = vm.createContext({
        lf: { value: null },
        empty: { value: false },
        item: () => state.current,
        serialize: () => JSON.stringify(state.graph),
        onBeforeUnmount: callback => { teardown = callback }
    })
    context.lf.value = {
        graphModel: { nodes: state.graph.nodes },
        destroy() {
            state.graph = { nodes: [], edges: [] }
            this.graphModel.nodes = []
            // LogicFlow.destroy -> clearData -> render -> graph:rendered -> sync.
            vm.runInContext('sync()', context)
        }
    }
    vm.runInContext(`let loading = false; let lastScript = ${JSON.stringify(state.current.script)};\n${syncCode}\n${teardownCode}`, context)
    return { state, close: () => teardown(), sync: () => vm.runInContext('sync()', context) }
}

test('closing a saved flow preserves its shared definition for reopening', () => {
    const view = canvas()
    const saved = view.state.current.script
    view.close()
    assert.equal(view.state.current.script, saved)
    // A pending history callback after destruction must also be harmless.
    view.sync()
    assert.equal(view.state.current.script, saved)
})

test('switching to another resource does not overwrite its script on destruction', () => {
    const view = canvas()
    view.state.current = { script: 'return "another resource"' }
    view.close()
    assert.equal(view.state.current.script, 'return "another resource"')
})

test('normal edits and intentional node deletion still update the definition', () => {
    const view = canvas()
    view.state.graph.nodes.push({ id: 'end', type: 'return' })
    view.sync()
    assert.equal(JSON.parse(view.state.current.script).nodes.length, 2)
    view.state.graph.nodes.splice(0)
    view.sync()
    assert.equal(JSON.parse(view.state.current.script).nodes.length, 0)
})
