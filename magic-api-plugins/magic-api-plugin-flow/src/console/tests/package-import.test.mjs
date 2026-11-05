import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {createRequire} from 'node:module'
import vm from 'node:vm'
const require=createRequire(import.meta.url)
const vue=require('vue')
const {parse,compileScript}=require('@vue/compiler-sfc')
const {descriptor}=parse(readFileSync(new URL('../src/components/magic-flow-settings.vue',import.meta.url),'utf8'))
const compiled=compileScript(descriptor,{id:'import-test'}).content
    .replace(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?/g,(_,names)=>`const {${names.replace(/\bas\b/g,':')}}=Vue;`)
    .replace(/import context,\s*\{ call \}[^\n]+/,'')
    .replace('export default','globalThis.Settings=')
const flush=()=>new Promise(resolve=>setImmediate(resolve))
function fixture(handler=()=>Promise.resolve({flows:[],channels:[],variables:[]})){
    const calls=[],events=[]
    let reader
    class FileReader { constructor(){reader=this} readAsDataURL(file){this.result='data:application/zip;base64,UEs='} }
    const context=vm.createContext({Vue:{...vue,inject:()=>vue.ref({id:'flow'})},FileReader,
        context:{bus:{$emit:event=>events.push(event)},Message:{RELOAD_RESOURCES:'reload'}},
        call:(method,path,body)=>{if(path==='/flow/variables')return Promise.resolve({file:'test',list:[],missing:[]});calls.push({method,path,body});return handler(path,body)} })
    vm.runInContext(compiled,context)
    const state=context.Settings.setup({},{expose(){}})
    return {state,calls,events,get reader(){return reader},select:name=>state.chooseFile({target:{files:[{name,size:2048}],value:'selected'}})}
}
test('rejects non-ZIP files and does not submit a preview request',async()=>{
    const f=fixture();f.select('wrong.txt');await flush()
    assert.equal(f.calls.length,0);assert.equal(f.state.busy.value,false)
    assert(f.state.tip.value.includes('.zip'))
})
test('ZIP selection shows file information and only previews without importing',async()=>{
    const f=fixture();f.select('config.ZIP')
    assert.equal(f.state.fileName.value,'config.ZIP');assert.equal(f.state.busy.value,true)
    f.reader.onload();await flush()
    assert.equal(f.calls.length,1);assert.equal(f.calls[0].path,'/flow/package/preview')
    assert(f.state.preview.value);assert.equal(f.state.busy.value,false)
    assert.equal(f.state.formatSize(2048),'2.0 KB')
})
test('read errors clear busy state and leave importing unavailable',()=>{
    const f=fixture();f.select('config.zip');f.reader.onerror()
    assert.equal(f.state.busy.value,false);assert.equal(f.state.preview.value,null)
    f.state.doImport();assert.equal(f.calls.length,0)
})
test('import is submitted once and reloads resources only after success',async()=>{
    let resolve
    const f=fixture(()=>new Promise(done=>{resolve=done}))
    f.state.preview.value={flows:[],channels:[],variables:[]};f.state.content.value='package-data'
    f.state.doImport();f.state.doImport()
    assert.equal(f.calls.length,1);assert.equal(f.calls[0].path,'/flow/package/import');assert.equal(f.state.busy.value,true)
    resolve({backup:'backup',flows:[],channels:[]});await flush()
    assert.equal(f.state.busy.value,false);assert.equal(f.state.preview.value,null)
    assert.deepEqual(f.events,['reload'])
})
test('switching settings sections clears stale errors and is blocked during import',()=>{
    const f=fixture();f.state.tip.value='old error';f.state.switchTab('import')
    assert.equal(f.state.tip.value,'');assert.equal(f.state.tab.value,'import')
    f.state.phase.value='import';f.state.switchTab('export')
    assert.equal(f.state.tab.value,'import')
})
