<template>
    <teleport to=".magic-editor">
    <div class="flow-template-mask" @mousedown.self="close">
        <section ref="dialog" class="flow-template-dialog" tabindex="-1" role="dialog" aria-modal="true" :aria-busy="state.generating || state.loading" aria-labelledby="flow-template-title" @keydown="onKeydown">
            <header class="template-header">
                <div><span class="template-eyebrow">集成流程 / 场景模板</span><h2 id="flow-template-title">从模板创建流程</h2><p>选择业务场景，填写参数后生成到画布。</p></div>
                <button class="template-close" aria-label="关闭模板选择" :disabled="state.generating" @click="close">×</button>
            </header>
            <div class="template-content">
                <aside class="template-sidebar">
                    <label class="template-search"><span>搜索模板</span><input ref="search" v-model="keyword" placeholder="输入场景名称或关键词" :disabled="state.generating"/></label>
                    <div class="template-list-caption">可用场景 <span>{{ filtered.length }}</span></div>
                    <div class="template-cards">
                        <div v-if="state.loading" class="template-empty">正在加载模板…</div>
                        <div v-else-if="!filtered.length" class="template-empty">{{ keyword ? '没有匹配的模板，试试其他关键词。' : '暂无可用模板。' }}</div>
                        <button v-for="(template, i) in filtered" :key="template.id" class="template-card" :class="{ selected: state.current && state.current.id === template.id }"
                            :aria-pressed="!!state.current && state.current.id === template.id" :disabled="state.generating" @click="$emit('choose', template)">
                            <span class="template-card-heading"><span class="template-number">{{ String(i + 1).padStart(2, '0') }}</span><strong>{{ template.name }}</strong><span v-if="state.current && state.current.id === template.id" class="template-check">✓</span></span>
                            <span class="template-card-description">{{ template.description }}</span>
                        </button>
                    </div>
                </aside>
                <main v-if="state.current" class="template-details">
                    <div class="template-details-heading"><span class="template-eyebrow">参数配置</span><h3>{{ state.current.name }}</h3><p>{{ state.current.description }}</p></div>
                    <form class="template-fields" @submit.prevent="$emit('apply')">
                        <label v-for="parameter in state.current.params" :key="parameter.key" class="template-field">
                            <span class="template-field-label">{{ parameter.label }}</span>
                            <select v-if="parameter.options" v-model="state.values[parameter.key]" :disabled="state.generating"><option v-for="option in parameter.options" :key="option" :value="option">{{ option }}</option></select>
                            <textarea v-else-if="parameter.multiline" v-model="state.values[parameter.key]" class="template-code" rows="5" spellcheck="false" :disabled="state.generating"></textarea>
                            <input v-else v-model="state.values[parameter.key]" :disabled="state.generating"/>
                            <span v-if="parameter.description" class="template-field-help">{{ parameter.description }}</span>
                        </label>
                        <p v-if="!(state.current.params || []).length" class="template-empty">该模板无需填写参数，可以直接生成。</p>
                    </form>
                </main>
                <main v-else class="template-empty template-details">从左侧选择一个场景模板，查看并配置参数。</main>
            </div>
            <div v-if="state.error" class="template-error" role="alert">{{ state.error }}</div>
            <footer class="template-footer"><span>生成将替换当前画布；生成后请检查节点配置并保存。</span><div><button class="mf-btn" :disabled="state.generating" @click="close">取消</button><button class="mf-btn template-primary" :disabled="!state.current || state.generating" @click="$emit('apply')">{{ state.generating ? '正在生成…' : '生成流程' }}</button></div></footer>
        </section>
    </div>
    </teleport>
</template>
<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { filterTemplates } from '../service/template-list.js'
const props = defineProps({ state: { type: Object, required: true } })
const emit = defineEmits(['choose', 'apply', 'close'])
const keyword = ref('')
const search = ref(null)
const dialog = ref(null)
const filtered = computed(() => filterTemplates(props.state.list, keyword.value))
let previousFocus
const close = () => { if (!props.state.generating) emit('close') }
const onKeydown = event => {
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close() }
    if (event.key !== 'Tab' || !dialog.value) return
    const focusable = Array.from(dialog.value.querySelectorAll('button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled)'))
    if (!focusable.length) { event.preventDefault(); dialog.value.focus(); return }
    const first = focusable[0], last = focusable[focusable.length - 1]
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
onMounted(() => { previousFocus = document.activeElement; nextTick(() => search.value && search.value.focus()) })
onBeforeUnmount(() => { if (previousFocus && previousFocus.isConnected) previousFocus.focus() })
</script>
<style scoped>
.flow-template-mask{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;background:rgba(15,23,42,.38);z-index:900000;padding:24px;box-sizing:border-box}
.flow-template-dialog{display:flex;flex-direction:column;width:min(1080px,100%);height:min(720px,100%);min-height:0;background:var(--navbar-body-background-color,var(--main-background-color));color:var(--main-color);border:1px solid var(--main-border-color);border-radius:12px;box-shadow:0 18px 60px rgba(0,0,0,.24);overflow:hidden;font:13px/1.5 'Segoe UI','Microsoft YaHei',sans-serif;text-align:left}
.template-header{display:flex;justify-content:space-between;gap:16px;padding:18px 22px 16px;border-bottom:1px solid var(--main-border-color);flex-shrink:0}
.template-eyebrow{font-size:11px;color:var(--resource-span-color);letter-spacing:.4px}
.template-header h2{margin:3px 0 4px;font-size:19px;font-weight:600;line-height:1.4}
.template-header p,.template-details-heading p{margin:0;color:var(--resource-span-color);font-size:12px;line-height:1.65}
.template-close{align-self:flex-start;width:30px;height:30px;flex-shrink:0;border:0;border-radius:6px;background:transparent;color:var(--resource-span-color);font:24px/1 sans-serif;cursor:pointer}
.template-close:hover:not(:disabled){background:var(--main-hover-background-color);color:var(--main-color)}
.template-content{display:grid;grid-template-columns:290px minmax(0,1fr);flex:1;min-height:0;overflow:hidden}
.template-sidebar{display:flex;flex-direction:column;min-height:0;background:var(--main-background-color);border-right:1px solid var(--main-border-color);padding:16px 12px;gap:10px}
.template-search{display:flex;flex-direction:column;gap:7px;padding:0 2px;font-size:12px;font-weight:500}
.template-list-caption{display:flex;justify-content:space-between;padding:0 4px;color:var(--resource-span-color);font-size:11px}
.template-cards{display:flex;flex-direction:column;gap:8px;min-height:0;overflow:auto;padding:1px 3px 3px}
.template-card{display:flex;flex-direction:column;gap:7px;text-align:left;border:1px solid var(--main-border-color);border-radius:7px;padding:12px;background:var(--navbar-body-background-color,var(--main-background-color));color:inherit;cursor:pointer;font:inherit;flex-shrink:0}
.template-card:hover:not(:disabled){border-color:var(--input-focus-color,#287bde)}
.template-card.selected{border-color:var(--input-focus-color,#287bde);box-shadow:inset 3px 0 var(--input-focus-color,#287bde);background:var(--select-hover-background-color)}
.template-card-heading{display:flex;align-items:center;gap:8px;font-size:13px}
.template-card-heading strong{font-weight:600;flex:1}
.template-number{font-size:10px;letter-spacing:.5px;color:var(--resource-span-color);font-variant-numeric:tabular-nums}
.template-check{color:var(--input-focus-color,#287bde);font-weight:700}
.template-card-description{font-size:12px;line-height:1.65;color:var(--resource-span-color)}
.template-details{padding:20px 24px;min-width:0;min-height:0;overflow:auto}
.template-details-heading{padding-bottom:18px;border-bottom:1px solid var(--main-border-color);margin-bottom:18px}
.template-details-heading h3{font-size:16px;font-weight:600;margin:3px 0 7px}
.template-fields{display:flex;flex-direction:column;gap:20px}
.template-field{display:flex;flex-direction:column;gap:8px}
.template-field-label{font-size:13px;font-weight:600}
.template-field-help{font-size:12px;color:var(--resource-span-color);line-height:1.6}
.template-field :is(input,select,textarea),.template-search input{box-sizing:border-box;width:100%;min-height:34px;border:1px solid var(--input-border-color,var(--main-border-color));border-radius:6px;padding:7px 10px;background:var(--input-background-color);color:var(--main-color);font:13px/1.5 'Segoe UI','Microsoft YaHei',sans-serif;outline:0}
.template-field textarea.template-code{min-height:116px;resize:vertical;font:13px/1.7 JetBrainsMono,Consolas,monospace;tab-size:4}
.template-field :is(input,select,textarea):focus,.template-search input:focus{border-color:var(--input-focus-color);box-shadow:0 0 0 2px rgba(40,123,222,.12)}
.template-card:focus-visible,.template-close:focus-visible{outline:2px solid var(--input-focus-color);outline-offset:2px}
.template-empty{color:var(--resource-span-color);padding:22px 10px;line-height:1.7;font-size:13px}
.template-error{color:#df5353;background:rgba(223,83,83,.07);padding:9px 22px;font-size:12px;border-top:1px solid var(--main-border-color)}
.template-footer{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px;padding:14px 20px;border-top:1px solid var(--main-border-color);background:var(--main-background-color);flex-shrink:0}
.template-footer>span{font-size:12px;color:var(--resource-span-color);flex:1;min-width:180px}
.template-footer>div{display:flex;gap:8px}
.template-footer .mf-btn{box-sizing:border-box;min-height:34px;padding:6px 15px;border:1px solid var(--input-border-color);border-radius:6px;background:var(--input-background-color);color:var(--main-color);font:inherit;line-height:20px;cursor:pointer}
.template-footer .mf-btn:hover:not(:disabled){border-color:var(--input-focus-color);background:var(--main-hover-background-color)}
.template-footer .mf-btn:focus-visible{outline:2px solid var(--input-focus-color);outline-offset:2px}
.template-footer .template-primary{background:var(--input-focus-color,#287bde);color:#fff;border-color:var(--input-focus-color,#287bde);min-width:96px}
.template-footer .template-primary:hover:not(:disabled){filter:brightness(1.06);background:var(--input-focus-color,#287bde);color:#fff}
:disabled{opacity:.5;cursor:default}
@media(max-width:900px){.template-content{grid-template-columns:240px minmax(0,1fr)}.template-details{padding:16px}.template-header{padding:14px 18px}.template-footer{padding:10px 16px}}
@media(max-height:700px){.template-header{padding-top:12px;padding-bottom:12px}.template-header h2{font-size:17px}.template-details{padding-top:14px}.template-fields{gap:14px}.template-footer{padding-top:10px;padding-bottom:10px}}
</style>
