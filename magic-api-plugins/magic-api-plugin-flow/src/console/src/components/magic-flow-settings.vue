<template>
	<div class="mf-settings">
		<div class="mf-tabs settings-tabs" role="tablist" aria-label="集成配置" @keydown="onTabKeydown">
			<button v-for="item in tabs" :key="item.id" class="settings-tab" :class="{ active: tab === item.id }" role="tab" :aria-selected="tab === item.id" :tabindex="tab === item.id ? 0 : -1" :disabled="busy" @click="switchTab(item.id)">{{ item.label }}</button>
			<span class="mf-tip" :class="tipType">{{ tip }}</span>
		</div>
		<div v-if="tab === 'vars'" class="mf-pane">
			<div class="package-preview-heading"><h3 class="settings-heading">差异项配置</h3><span>{{ vars.list.length }} 项配置</span></div>
			<div class="mf-help">各医院不同的值（对方地址、账号、机构编码等）。流程和外部连接中写 ${var.名称} 引用；保存在 {{ vars.file }}，不随配置包导出。</div>
			<div v-if="vars.missing && vars.missing.length" class="error">已引用但未配置：{{ vars.missing.join('、') }}</div>
			<table>
				<thead><tr><th style="width:22%">名称</th><th style="width:30%">值</th><th style="width:8%">密文</th><th>说明</th><th style="width:90px"></th></tr></thead>
				<tbody>
				<tr v-for="v in vars.list" :key="v.name">
					<td>{{ v.name }}</td>
					<td><input class="mf-input" :placeholder="v.secret ? (v.configured ? '已配置，留空不修改' : '未配置') : ''" v-model="v.edit"/></td>
					<td><input type="checkbox" v-model="v.secret"/></td>
					<td><input class="mf-input" v-model="v.description"/></td>
					<td>
						<button class="settings-link" @click="saveVar(v)">保存</button>
						<button class="settings-link error" @click="removeVar(v)">删除</button>
					</td>
				</tr>
				<tr>
					<td><input class="mf-input" placeholder="新差异项名称" v-model="draft.name"/></td>
					<td><input class="mf-input" v-model="draft.edit"/></td>
					<td><input type="checkbox" v-model="draft.secret"/></td>
					<td><input class="mf-input" v-model="draft.description"/></td>
					<td><button class="settings-link" @click="saveVar(draft, true)">添加</button></td>
				</tr>
				</tbody>
			</table>
		</div>
		<div v-else-if="tab === 'export'" class="mf-pane">
			<section class="package-card"><div class="package-card-heading"><span class="package-icon">ZIP</span><div><h3>导出集成配置</h3><p>导出流程及其引用的外部连接，方便在其他环境导入。</p></div></div>
			<div class="package-note">连接的地址、端口、账号、目录、密码会变成差异项，在目标医院导入时填写；密码不会出包。</div>
			<div class="mf-actions">
				<button class="mf-btn" :disabled="!info || !info.id" @click="exportPackage([info.id])">导出当前流程</button>
				<button class="mf-btn" @click="exportPackage([])">导出全部流程</button>
			</div>
			</section>
		</div>
		<div v-else class="mf-pane">
			<section class="package-card">
				<div class="package-card-heading"><span class="package-icon">ZIP</span><div><h3>导入集成配置</h3><p>选择配置包后自动检查内容，确认预览与差异项后再导入。</p></div></div>
				<div class="package-file-picker">
					<input ref="fileInput" type="file" accept=".zip,application/zip" hidden :disabled="busy" @change="chooseFile"/>
					<button class="mf-btn" :disabled="busy" @click="fileInput && fileInput.click()">{{ fileName ? '重新选择配置包' : '选择 ZIP 配置包' }}</button>
					<div class="package-file-description"><strong>{{ fileName || '尚未选择文件' }}</strong><span>{{ fileName ? formatSize(fileSize) : '支持 .zip 格式，选择文件不会立即导入' }}</span></div>
					<span v-if="busy" class="package-progress" role="status">{{ phase === 'import' ? '正在导入…' : '正在检查配置包…' }}</span>
				</div>
				<div class="package-note">导入前自动全量备份工作目录。同 id 或同路径的流程、同名的外部连接会被覆盖。</div>
			</section>
			<section v-if="preview" class="package-card package-preview">
				<div class="package-preview-heading"><h3>配置包预览</h3><span>{{ preview.flows.length }} 个流程 · {{ preview.channels.length }} 个外部连接 · {{ preview.variables.length }} 项差异项</span></div>
				<div><b>流程：</b><span v-for="f in preview.flows" :key="f.path" class="mf-chip">{{ f.name }}（{{ f.path }}，{{ f.action === 'create' ? '新建' : '覆盖' }}）</span></div>
				<div v-if="preview.channels.length"><b>外部连接：</b><span v-for="c in preview.channels" :key="c.name" class="mf-chip">{{ c.name }}（{{ c.action === 'create' ? '新建' : '覆盖' }}）</span></div>
				<table v-if="preview.variables.length">
					<thead><tr><th style="width:22%">差异项</th><th style="width:34%">本院的值</th><th>说明 / 示例</th></tr></thead>
					<tbody>
					<tr v-for="v in preview.variables" :key="v.name">
						<td>{{ v.name }}<span v-if="v.secret" class="warn">（密文）</span></td>
						<td><input class="mf-input" :type="v.secret ? 'password' : 'text'" v-model="values[v.name]" :disabled="busy" :placeholder="v.configured ? '已配置，留空保留' : '必填'"/></td>
						<td>{{ v.description || '' }}<span v-if="v.example" class="mf-help">　示例：{{ v.example }}</span>
							<div class="mf-help">{{ (v.usedBy || []).join('、') }}</div></td>
					</tr>
					</tbody>
				</table>
				<div class="mf-actions">
					<button class="mf-btn package-primary" :disabled="busy" @click="doImport">{{ phase === 'import' ? '正在导入…' : '确认导入配置包' }}</button>
				</div>
			</section>
			<div v-if="result" class="package-card package-result" role="status">
				<div class="ok">导入完成，备份：{{ result.backup }}</div>
				<div v-for="f in result.flows" :key="f.name">流程 {{ f.name }}：{{ f.action === 'create' ? '新建' : '覆盖' }}</div>
				<div v-for="c in result.channels" :key="c.name">外部连接 {{ c.name }}：{{ c.action === 'create' ? '新建' : '覆盖' }}</div>
			</div>
		</div>
	</div>
</template>
<script setup>
import { computed, inject, nextTick, reactive, ref } from 'vue'
import context, { call } from '../service/context.js'

const info = inject('info')
const tab = ref('vars')
const tip = ref('')
const tipType = ref('')
const vars = reactive({ file: '', list: [], missing: [] })
const draft = reactive({ name: '', edit: '', secret: false, description: '' })
const preview = ref(null)
const content = ref(null)
const values = reactive({})
const result = ref(null)
const fileInput = ref(null)
const fileName = ref('')
const fileSize = ref(0)
const phase = ref('')
const busy = computed(() => phase.value !== '')
const tabs = [{ id: 'vars', label: '差异项' }, { id: 'export', label: '导出配置包' }, { id: 'import', label: '导入配置包' }]
const formatSize = size => size >= 1048576 ? `${(size / 1048576).toFixed(1)} MB` : size >= 1024 ? `${(size / 1024).toFixed(1)} KB` : `${size} B`
const switchTab = value => {
	if (busy.value) return
	tab.value = value
	show('', '')
	if (value === 'vars') loadVars()
}
const onTabKeydown = event => {
	if (busy.value || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
	event.preventDefault()
	const current = tabs.findIndex(item => item.id === tab.value)
	const index = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (current + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length
	const element = event.currentTarget
	switchTab(tabs[index].id)
	nextTick(() => element.querySelectorAll('[role="tab"]')[index].focus())
}

const show = (text, type) => {
	tip.value = text
	tipType.value = type || ''
}

const loadVars = () => {
	call('get', '/flow/variables').then(data => {
		vars.file = data.file
		vars.missing = data.missing || []
		vars.list = (data.variables || []).map(v => ({ ...v, edit: v.secret ? '' : (v.value || '') }))
	}).catch(message => show(message, 'error'))
}

const saveVar = (v, isNew) => {
	if (!v.name || !v.name.trim()) {
		show('请填写名称', 'error')
		return
	}
	call('post', '/flow/variables/save', { name: v.name.trim(), value: v.edit || '', secret: !!v.secret, description: v.description || '' }).then(() => {
		show('已保存 ' + v.name, 'ok')
		if (isNew) {
			draft.name = ''
			draft.edit = ''
			draft.secret = false
			draft.description = ''
		}
		loadVars()
	}).catch(message => show(message, 'error'))
}

const removeVar = v => {
	if (!window.confirm('删除差异项 ' + v.name + '？引用它的流程将无法运行')) {
		return
	}
	call('post', '/flow/variables/delete', { name: v.name }).then(() => {
		show('已删除', 'ok')
		loadVars()
	}).catch(message => show(message, 'error'))
}

const exportPackage = ids => {
	call('json', '/flow/package/export', { ids }).then(base64 => {
		const bytes = atob(base64)
		const array = new Uint8Array(bytes.length)
		for (let i = 0; i < bytes.length; i++) {
			array[i] = bytes.charCodeAt(i)
		}
		const blob = new Blob([array], { type: 'application/zip' })
		const a = document.createElement('a')
		const now = new Date()
		const p = n => String(n).padStart(2, '0')
		a.href = URL.createObjectURL(blob)
		a.download = `magic-flow-${now.getFullYear()}${p(now.getMonth() + 1)}${p(now.getDate())}${p(now.getHours())}${p(now.getMinutes())}.zip`
		document.body.appendChild(a)
		a.click()
		setTimeout(() => {
			URL.revokeObjectURL(a.href)
			a.remove()
		}, 1000)
		show('已导出', 'ok')
	}).catch(message => show(message, 'error'))
}

const chooseFile = e => {
	if (busy.value) return
	const file = e.target.files && e.target.files[0]
	e.target.value = ''
	preview.value = null
	result.value = null
	content.value = null
	fileName.value = file ? file.name : ''
	fileSize.value = file ? file.size : 0
	show('', '')
	if (!file) {
		return
	}
	if (!/\.zip$/i.test(file.name)) {
		show('请选择 .zip 格式的配置包', 'error')
		return
	}
	phase.value = 'preview'
	const reader = new FileReader()
	reader.onload = () => {
		content.value = reader.result
		call('json', '/flow/package/preview', { content: reader.result }).then(data => {
			preview.value = data
			Object.keys(values).forEach(k => delete values[k])
			show('', '')
		}).catch(message => show(message, 'error')).finally(() => { phase.value = '' })
	}
	reader.onerror = () => { phase.value = ''; show('读取文件失败，请重新选择配置包', 'error') }
	reader.onabort = () => { phase.value = '' }
	reader.readAsDataURL(file)
}

const doImport = () => {
	if (busy.value || !preview.value || !content.value) return
	phase.value = 'import'
	show('', '')
	const filled = {}
	Object.keys(values).forEach(k => {
		if (values[k] !== undefined && values[k] !== '') {
			filled[k] = values[k]
		}
	})
	call('json', '/flow/package/import', { content: content.value, values: filled }).then(data => {
		result.value = data
		preview.value = null
		show('导入完成', 'ok')
		if (context.bus && context.Message) {
			context.bus.$emit(context.Message.RELOAD_RESOURCES)
		}
	}).catch(message => show(message, 'error')).finally(() => { phase.value = '' })
}

loadVars()
</script>
<style scoped>
.mf-settings { display: flex; flex-direction: column; height: 100%; overflow: hidden; font-size: 12px; }
.mf-tabs { display: flex; gap: 14px; padding: 4px 8px; align-items: center; }
.mf-tabs > span:not(.mf-tip) { cursor: pointer; padding-bottom: 2px; }
.mf-tabs > span.active { color: #2563EB; border-bottom: 2px solid #2563EB; }
.mf-pane { flex: 1; overflow: auto; padding: 0 8px 8px; display: flex; flex-direction: column; gap: 6px; }
.mf-help { color: #888; }
.mf-actions { display: flex; gap: 6px; align-items: center; }
.mf-btn { padding: 2px 10px; font-size: 12px; cursor: pointer; border: 1px solid var(--magic-input-border-color, #bbb); background: transparent; color: inherit; border-radius: 2px; }
.mf-btn:disabled { opacity: .5; }
.mf-input { box-sizing: border-box; width: 100%; padding: 2px 5px; font-size: 12px; border: 1px solid var(--magic-input-border-color, #bbb);
	border-radius: 2px; background: transparent; color: inherit; outline: none; }
table { border-collapse: collapse; width: 100%; }
th, td { text-align: left; padding: 2px 4px; vertical-align: top; }
.mf-link { cursor: pointer; color: #2563EB; margin-right: 8px; }
.mf-chip { display: inline-block; margin: 0 8px 4px 0; }
.ok { color: #0E9F6E; }
.error { color: #E02424; }
.mf-link.error { color: #E02424; }
.warn { color: #D97706; }
.settings-tabs{flex-shrink:0}
.settings-tab{border:0;border-bottom:2px solid transparent;padding:7px 3px;background:transparent;color:var(--resource-span-color);font:inherit;cursor:pointer}
.settings-tab.active{color:var(--input-focus-color);border-bottom-color:currentColor;font-weight:600}
.settings-tab:focus-visible{outline:2px solid var(--input-focus-color);outline-offset:2px}
.settings-tab:disabled{opacity:.5;cursor:default}
.settings-heading{margin:0;font-size:15px;font-weight:600}
.settings-link{border:0;background:transparent;color:var(--input-focus-color);padding:3px 5px;min-height:26px;border-radius:4px;font:12px/20px 'Segoe UI','Microsoft YaHei',sans-serif;cursor:pointer}
.settings-link.error{color:#df5353}
.settings-link:hover{background:var(--main-hover-background-color)}
.settings-link:focus-visible{outline:2px solid var(--input-focus-color);outline-offset:1px}
.package-card{border:1px solid var(--main-border-color);border-radius:9px;padding:18px;background:var(--navbar-body-background-color,var(--main-background-color));display:flex;flex-direction:column;gap:16px}
.package-card-heading{display:flex;align-items:center;gap:13px}
.package-icon{display:inline-flex;align-items:center;justify-content:center;width:42px;height:44px;border:1px solid var(--main-border-color);border-radius:8px;background:var(--select-hover-background-color);color:var(--input-focus-color);font-size:11px;font-weight:700;letter-spacing:1px;flex-shrink:0}
.package-card h3{margin:0;font-size:15px;font-weight:600;line-height:1.5}
.package-card p{margin:4px 0 0;font-size:12px;color:var(--resource-span-color);line-height:1.7}
.package-file-picker{display:flex;align-items:center;flex-wrap:wrap;gap:14px;border:1px dashed var(--input-border-color);border-radius:7px;padding:16px;background:var(--main-background-color)}
.package-file-picker input[hidden]{display:none}
.package-file-description{display:flex;flex-direction:column;gap:4px;min-width:0;flex:1}
.package-file-description strong{font-size:13px;font-weight:500;overflow-wrap:anywhere}
.package-file-description span,.package-note,.package-progress,.package-preview-heading>span{font-size:12px;color:var(--resource-span-color);line-height:1.7}
.package-progress{margin-left:auto;color:var(--input-focus-color)}
.package-note{border-left:3px solid var(--main-border-color);padding-left:10px}
.package-preview-heading{display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:12px}
.package-preview table{min-width:600px}
.package-result{border-left:3px solid #239b66;line-height:1.8}
.mf-settings .package-primary{color:#fff;background:var(--input-focus-color);border-color:var(--input-focus-color)}
.mf-settings .package-primary:hover:not(:disabled){color:#fff;background:var(--input-focus-color);filter:brightness(1.06)}
@media(max-width:900px){.package-card{padding:14px}.package-file-picker{padding:12px}}
</style>
