import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import vm from 'node:vm'
const require = createRequire(import.meta.url)
const vue = require('vue')
const { parse, compileScript } = require('@vue/compiler-sfc')
const filterSource = readFileSync(new URL('../src/service/template-list.js', import.meta.url), 'utf8')
const { filterTemplates } = await import('data:text/javascript;base64,' + Buffer.from(filterSource).toString('base64'))
const component = readFileSync(new URL('../src/components/flow-template-dialog.vue', import.meta.url), 'utf8')
const { descriptor } = parse(component)
const templates = [{ id:'http',name:'HTTP 查询接口',description:'外部系统查询',params:[{key:'sql',label:'SQL',multiline:true}] },{id:'timer',name:'定时上传',description:'批次推送',params:[]}]

function compiled(inlineTemplate, runtime = vue) {
    const script = compileScript(descriptor, { id:'dialog-test',inlineTemplate }).content
        .replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g, (_, names) => `const {${names.replace(/\bas\b/g, ':')}} = Vue;`)
        .replace(/import\s*\{\s*filterTemplates\s*\}\s*from[^\n]+/g, '')
        .replace('export default', 'globalThis.Dialog =')
    const context = vm.createContext({ Vue: runtime,filterTemplates,document:{ activeElement:null } })
    vm.runInContext(script, context)
    return { Dialog:context.Dialog,context }
}

test('search accepts Chinese and case-insensitive names without modifying templates', () => {
    assert.equal(filterTemplates(templates,'http')[0].id,'http')
    assert.equal(filterTemplates(templates,'  批次  ')[0].id,'timer')
    assert.equal(filterTemplates(templates,'不存在').length,0)
    assert.equal(templates.length,2)
})

test('server render includes accessible dialog, selection and parameter values as text', async () => {
    const { renderToString } = require('@vue/server-renderer')
    const { Dialog } = compiled(true)
    const state = { list:templates,current:templates[0],values:{sql:'select <sample>'},loading:false,generating:false,error:'' }
    const ssr = {}
    const shell = await renderToString(vue.createSSRApp(Dialog,{state}), ssr)
    assert(shell.includes('teleport start'))
    assert(ssr.teleports['.magic-editor'], 'Dialog must mount at the workbench root, outside the resized canvas')
    const html = ssr.teleports['.magic-editor']
    assert(html.includes('role="dialog"'))
    assert(html.includes('aria-modal="true"'))
    assert(html.includes('aria-pressed="true"'))
    assert(html.includes('select &lt;sample&gt;'))
    assert(html.includes('生成流程'))
})

test('generation disables parameter controls and close action', async () => {
    const { renderToString } = require('@vue/server-renderer')
    const { Dialog } = compiled(true)
    const ssr = {}
    await renderToString(vue.createSSRApp(Dialog,{state:{list:templates,current:templates[0],values:{sql:'select 1'},generating:true}}), ssr)
    const html = ssr.teleports['.magic-editor']
    assert(html.includes('aria-busy="true"'))
    assert.match(html, /<textarea[^>]*disabled/)
    assert(html.includes('正在生成…'))
})

test('Escape closes an idle dialog and is ignored during generation', () => {
    const runtime = { ...vue,onMounted:()=>{},onBeforeUnmount:()=>{} }
    const { Dialog } = compiled(false,runtime)
    const state = {list:templates,generating:false}
    const events=[]
    const setup = Dialog.setup({state},{expose:()=>{},emit:event=>events.push(event)})
    const event={key:'Escape',preventDefault(){},stopPropagation(){}}
    setup.onKeydown(event)
    assert.deepEqual(events,['close'])
    state.generating=true
    setup.onKeydown(event)
    assert.deepEqual(events,['close'])
})

test('Tab loops between the first and last dialog controls', () => {
    const { Dialog,context } = compiled(false,{...vue,onMounted:()=>{},onBeforeUnmount:()=>{}})
    const setup=Dialog.setup({state:{list:templates}},{expose:()=>{},emit:()=>{}})
    let focused=''
    const first={focus(){focused='first'}},last={focus(){focused='last'}}
    setup.dialog.value={querySelectorAll:()=>[first,last]}
    context.document.activeElement=last
    setup.onKeydown({key:'Tab',shiftKey:false,preventDefault(){}})
    assert.equal(focused,'first')
    context.document.activeElement=first
    setup.onKeydown({key:'Tab',shiftKey:true,preventDefault(){}})
    assert.equal(focused,'last')
})
