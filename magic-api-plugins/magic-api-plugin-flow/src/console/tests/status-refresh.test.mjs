import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import vm from 'node:vm'

const require = createRequire(import.meta.url)
const vue = require('vue')
const { parse, compileScript } = require('@vue/compiler-sfc')
const source = readFileSync(new URL('../src/components/magic-flow-status.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
const compiled = compileScript(descriptor, { id: 'status-test' }).content
    .replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g, (_, names) => `const {${names.replace(/\bas\b/g, ':')}} = Vue;`)
    .replace(/import\s*\{ call \}[^\n]+/, '')
    .replace('export default', 'globalThis.Status =')
const flush = () => new Promise(resolve => setImmediate(resolve))
const statusData = trigger => ({ enabled: true, startType: 'http', triggers: [trigger], errors: [], records: [] })

function fixture() {
    const info = vue.ref({ id: 'flow-a' })
    const requests = []
    let unmount = () => {}
    let stopWatch
    const runtime = { ...vue, inject: () => info,
        watch: (source, callback, options) => { stopWatch = vue.watch(source, callback, { ...options, flush: 'sync' }) },
        onBeforeUnmount: callback => { unmount = callback } }
    const context = vm.createContext({ Vue: runtime,
        call: (method, url, data) => new Promise((resolve, reject) => requests.push({ method, url, data, resolve, reject })) })
    vm.runInContext(compiled, context)
    const state = context.Status.setup({}, { expose() {} })
    return { state, info, requests, close() { stopWatch(); unmount() } }
}

test('successful refresh removes an earlier not-found error', async () => {
    const f = fixture()
    f.requests[0].reject('找不到流程：flow-a'); await flush()
    assert.equal(f.state.tip.value, '找不到流程：flow-a')
    f.state.refresh()
    f.requests[1].resolve(statusData('/a')); await flush()
    assert.equal(f.state.tip.value, '')
    assert.equal(f.state.tipType.value, '')
    assert.equal(f.state.status.value.triggers[0], '/a')
    f.close()
})

test('failed refresh discards the previous successful status', async () => {
    const f = fixture()
    f.requests[0].resolve(statusData('/a')); await flush()
    f.state.refresh(); f.requests[1].reject('找不到流程：flow-a'); await flush()
    assert.equal(f.state.status.value, null)
    assert.equal(f.state.tipType.value, 'error')
    f.close()
})

test('late responses from another flow cannot overwrite current status or error', async () => {
    const f = fixture()
    f.info.value = { id: 'flow-b' }
    f.requests[1].resolve(statusData('/b')); await flush()
    f.requests[0].reject('找不到流程：flow-a'); await flush()
    assert.equal(f.state.status.value.triggers[0], '/b')
    assert.equal(f.state.tip.value, '')
    f.close()
})

test('latest refresh wins when requests for the same flow finish out of order', async () => {
    const f = fixture()
    f.state.refresh()
    f.requests[1].resolve(statusData('/latest')); await flush()
    f.requests[0].resolve(statusData('/old')); await flush()
    assert.equal(f.state.status.value.triggers[0], '/latest')
    f.close()
})

test('unsaved flow and unmounted view ignore pending responses', async () => {
    const f = fixture()
    f.info.value = {}
    f.requests[0].resolve(statusData('/old')); await flush()
    assert.equal(f.state.status.value, null)
    assert.equal(f.state.tip.value, '')
    f.info.value = { id: 'flow-c' }
    f.close()
    f.requests[1].reject('late error'); await flush()
    assert.equal(f.state.tip.value, '')
})

test('action success remains visible after status refresh, and double submission is blocked', async () => {
    const f = fixture()
    f.requests[0].resolve(statusData('/a')); await flush()
    f.state.action('/flow/run', '立即执行')
    f.state.action('/flow/run', '立即执行')
    assert.equal(f.requests.length, 2)
    f.requests[1].resolve({ status: 'SUCCESS' }); await flush()
    f.requests[2].resolve(statusData('/a')); await flush()
    assert.equal(f.state.tip.value, '立即执行：SUCCESS')
    assert.equal(f.state.tipType.value, 'ok')
    f.close()
})

test('late action results do not update or refresh a newly selected flow', async () => {
    const f = fixture()
    f.requests[0].resolve(statusData('/a')); await flush()
    f.state.action('/flow/run', '立即执行')
    f.info.value = { id: 'flow-b' }
    f.requests[2].resolve(statusData('/b')); await flush()
    f.requests[1].resolve({ status: 'SUCCESS' }); await flush()
    assert.equal(f.requests.length, 3)
    assert.equal(f.state.tip.value, '')
    assert.equal(f.state.status.value.triggers[0], '/b')
    f.close()
})
