<template>
	<div class="mf-settings">
		<div class="mf-tabs">
			<span :class="{ active: tab === 'vars' }" @click="tab = 'vars'; loadVars()">差异项</span>
			<span :class="{ active: tab === 'export' }" @click="tab = 'export'">导出配置包</span>
			<span :class="{ active: tab === 'import' }" @click="tab = 'import'">导入配置包</span>
			<span class="mf-tip" :class="tipType">{{ tip }}</span>
		</div>
		<div v-if="tab === 'vars'" class="mf-pane">
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
						<span class="mf-link" @click="saveVar(v)">保存</span>
						<span class="mf-link error" @click="removeVar(v)">删除</span>
					</td>
				</tr>
				<tr>
					<td><input class="mf-input" placeholder="新差异项名称" v-model="draft.name"/></td>
					<td><input class="mf-input" v-model="draft.edit"/></td>
					<td><input type="checkbox" v-model="draft.secret"/></td>
					<td><input class="mf-input" v-model="draft.description"/></td>
					<td><span class="mf-link" @click="saveVar(draft, true)">添加</span></td>
				</tr>
				</tbody>
			</table>
		</div>
		<div v-else-if="tab === 'export'" class="mf-pane">
			<div class="mf-help">导出流程及其引用的外部连接。连接的地址、端口、账号、目录、密码会变成差异项，在目标医院导入时填写；密码不会出包。</div>
			<div class="mf-actions">
				<button class="mf-btn" :disabled="!info || !info.id" @click="exportPackage([info.id])">导出当前流程</button>
				<button class="mf-btn" @click="exportPackage([])">导出全部流程</button>
			</div>
		</div>
		<div v-else class="mf-pane">
			<div class="mf-help">导入前自动全量备份工作目录。同 id 或同路径的流程、同名的外部连接会被覆盖。</div>
			<div class="mf-actions">
				<input type="file" accept=".zip" @change="chooseFile"/>
			</div>
			<template v-if="preview">
				<div><b>流程：</b><span v-for="f in preview.flows" :key="f.path" class="mf-chip">{{ f.name }}（{{ f.path }}，{{ f.action === 'create' ? '新建' : '覆盖' }}）</span></div>
				<div v-if="preview.channels.length"><b>外部连接：</b><span v-for="c in preview.channels" :key="c.name" class="mf-chip">{{ c.name }}（{{ c.action === 'create' ? '新建' : '覆盖' }}）</span></div>
				<table v-if="preview.variables.length">
					<thead><tr><th style="width:22%">差异项</th><th style="width:34%">本院的值</th><th>说明 / 示例</th></tr></thead>
					<tbody>
					<tr v-for="v in preview.variables" :key="v.name">
						<td>{{ v.name }}<span v-if="v.secret" class="warn">（密文）</span></td>
						<td><input class="mf-input" :type="v.secret ? 'password' : 'text'" v-model="values[v.name]" :placeholder="v.configured ? '已配置，留空保留' : '必填'"/></td>
						<td>{{ v.description || '' }}<span v-if="v.example" class="mf-help">　示例：{{ v.example }}</span>
							<div class="mf-help">{{ (v.usedBy || []).join('、') }}</div></td>
					</tr>
					</tbody>
				</table>
				<div class="mf-actions">
					<button class="mf-btn" @click="doImport">导入</button>
				</div>
			</template>
			<div v-if="result">
				<div class="ok">导入完成，备份：{{ result.backup }}</div>
				<div v-for="f in result.flows" :key="f.name">流程 {{ f.name }}：{{ f.action === 'create' ? '新建' : '覆盖' }}</div>
				<div v-for="c in result.channels" :key="c.name">外部连接 {{ c.name }}：{{ c.action === 'create' ? '新建' : '覆盖' }}</div>
			</div>
		</div>
	</div>
</template>
<script setup>
import { inject, reactive, ref } from 'vue'
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
	const file = e.target.files && e.target.files[0]
	preview.value = null
	result.value = null
	if (!file) {
		return
	}
	const reader = new FileReader()
	reader.onload = () => {
		content.value = reader.result
		call('json', '/flow/package/preview', { content: reader.result }).then(data => {
			preview.value = data
			Object.keys(values).forEach(k => delete values[k])
			show('', '')
		}).catch(message => show(message, 'error'))
	}
	reader.readAsDataURL(file)
}

const doImport = () => {
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
	}).catch(message => show(message, 'error'))
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
</style>
