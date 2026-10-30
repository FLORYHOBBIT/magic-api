<template>
	<div class="mf-form">
		<template v-for="field in visibleFields" :key="field.key">
			<div class="mf-field">
				<label class="mf-label">{{ field.label }}</label>
				<select v-if="field.type === 'select'" class="mf-input" :value="valueOf(field)" @change="set(field.key, $event.target.value)">
					<option v-for="opt in options(field)" :key="opt[0]" :value="opt[0]">{{ opt[1] }}</option>
				</select>
				<label v-else-if="field.type === 'checkbox'" class="mf-check">
					<input type="checkbox" :checked="config[field.key] === true || config[field.key] === 'true'" @change="set(field.key, $event.target.checked)"/>
				</label>
				<textarea v-else-if="field.type === 'code' || field.type === 'textarea'" class="mf-input mf-code" spellcheck="false"
						  :rows="field.rows || 6" :placeholder="field.placeholder" :value="text(field.key)"
						  @input="set(field.key, $event.target.value)"></textarea>
				<div v-else-if="field.type === 'kv'" class="mf-table">
					<div class="mf-row" v-for="(row, index) in kvRows(field.key)" :key="index">
						<input class="mf-input" style="width:40%" placeholder="名称" :value="row[0]" @change="kvSet(field.key, index, 0, $event.target.value)"/>
						<input class="mf-input" style="width:52%" placeholder="值" :value="row[1]" @change="kvSet(field.key, index, 1, $event.target.value)"/>
						<span class="mf-del" title="删除" @click="kvRemove(field.key, index)">×</span>
					</div>
					<span class="mf-add" @click="kvAdd(field.key)">+ 添加</span>
				</div>
				<div v-else-if="field.type === 'list'" class="mf-table">
					<div class="mf-row mf-head">
						<span v-for="item in field.items" :key="item.key" :style="{ width: item.width }">{{ item.label }}</span>
					</div>
					<div class="mf-row" v-for="(row, index) in list(field.key)" :key="index">
						<template v-for="item in field.items" :key="item.key">
							<select v-if="item.type === 'select'" class="mf-input" :style="{ width: item.width }" :value="row[item.key] || ''" @change="listSet(field.key, index, item.key, $event.target.value)">
								<option v-for="opt in options(item)" :key="opt[0]" :value="opt[0]">{{ opt[1] }}</option>
							</select>
							<span v-else-if="item.type === 'checkbox'" :style="{ width: item.width, textAlign: 'center' }">
								<input type="checkbox" :checked="row[item.key] === true" @change="listSet(field.key, index, item.key, $event.target.checked)"/>
							</span>
							<input v-else class="mf-input" :style="{ width: item.width }" :placeholder="item.placeholder" :value="row[item.key] == null ? '' : row[item.key]"
								   @change="listSet(field.key, index, item.key, $event.target.value)"/>
						</template>
						<span class="mf-del" title="删除" @click="listRemove(field.key, index)">×</span>
					</div>
					<span class="mf-add" @click="listAdd(field.key)">+ 添加</span>
				</div>
				<input v-else class="mf-input" :placeholder="field.placeholder" :value="text(field.key)" @input="set(field.key, $event.target.value)"/>
				<div v-if="field.help" class="mf-help">{{ field.help }}</div>
			</div>
		</template>
		<div v-if="type.custom || showRaw" class="mf-field">
			<label class="mf-label">配置（JSON）</label>
			<textarea class="mf-input mf-code" rows="10" spellcheck="false" :value="rawText" @change="applyRaw($event.target.value)"></textarea>
			<div v-if="rawError" class="mf-error">{{ rawError }}</div>
		</div>
		<span class="mf-add" @click="showRaw = !showRaw">{{ showRaw ? '收起 JSON' : '以 JSON 编辑配置' }}</span>
	</div>
</template>
<script setup>
import { computed, ref } from 'vue'
import { nodeType } from '../service/node-types.js'

const props = defineProps({
	node: { type: Object, required: true }
})
const emit = defineEmits(['change'])

const showRaw = ref(false)
const rawError = ref('')

const type = computed(() => nodeType(props.node.type))
const config = computed(() => {
	if (!props.node.config || typeof props.node.config !== 'object') {
		props.node.config = {}
	}
	return props.node.config
})
const visibleFields = computed(() => type.value.fields.filter(f => !f.show || f.show(config.value)))
const rawText = computed(() => JSON.stringify(config.value, null, 2))

const options = field => (field.options || []).map(o => Array.isArray(o) ? o : [o, o === '' ? '（不转换）' : o])
const valueOf = field => {
	const v = config.value[field.key]
	return v === undefined || v === null ? (field.default === undefined ? '' : field.default) : String(v)
}
const text = key => {
	const v = config.value[key]
	return v === undefined || v === null ? '' : (typeof v === 'object' ? JSON.stringify(v) : String(v))
}

const set = (key, value) => {
	if (value === '' || value === undefined) {
		delete config.value[key]
	} else {
		config.value[key] = value
	}
	emit('change')
}

const kvRows = key => {
	const v = config.value[key]
	return v && typeof v === 'object' ? Object.entries(v) : []
}
const kvWrite = (key, rows) => {
	const map = {}
	rows.forEach(([k, v]) => {
		if (k !== undefined && k !== null && String(k).trim() !== '') {
			map[String(k).trim()] = v == null ? '' : v
		}
	})
	config.value[key] = map
	emit('change')
}
const kvSet = (key, index, column, value) => {
	const rows = kvRows(key)
	rows[index][column] = value
	kvWrite(key, rows)
}
const kvAdd = key => {
	const rows = kvRows(key)
	let name = 'name'
	let i = 1
	while (rows.some(r => r[0] === name)) {
		name = 'name' + (i++)
	}
	rows.push([name, ''])
	kvWrite(key, rows)
}
const kvRemove = (key, index) => {
	const rows = kvRows(key)
	rows.splice(index, 1)
	kvWrite(key, rows)
}

const list = key => {
	if (!Array.isArray(config.value[key])) {
		config.value[key] = []
	}
	return config.value[key]
}
const listSet = (key, index, item, value) => {
	const row = list(key)[index]
	if (value === '' || value === undefined) {
		delete row[item]
	} else {
		row[item] = value
	}
	emit('change')
}
const listAdd = key => {
	list(key).push({})
	emit('change')
}
const listRemove = (key, index) => {
	list(key).splice(index, 1)
	emit('change')
}

const applyRaw = value => {
	try {
		const parsed = JSON.parse(value || '{}')
		if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
			throw new Error('必须是 JSON 对象')
		}
		props.node.config = parsed
		rawError.value = ''
		emit('change')
	} catch (e) {
		rawError.value = e.message
	}
}
</script>
<style scoped>
.mf-form { display: flex; flex-direction: column; gap: 8px; }
.mf-field { display: flex; flex-direction: column; gap: 3px; }
.mf-label { font-weight: 600; font-size: 12px; }
.mf-input {
	box-sizing: border-box; width: 100%; padding: 3px 5px; font-size: 12px;
	border: 1px solid var(--magic-input-border-color, #bbb); border-radius: 2px;
	background: var(--magic-input-background, transparent); color: inherit; outline: none;
}
.mf-input:focus { border-color: #2563EB; }
.mf-code { font-family: Consolas, 'Courier New', monospace; resize: vertical; white-space: pre-wrap; word-break: break-all; }
.mf-check { display: inline-flex; align-items: center; }
.mf-help { color: #888; font-size: 11px; }
.mf-error { color: #E02424; font-size: 11px; }
.mf-table { display: flex; flex-direction: column; gap: 3px; }
.mf-row { display: flex; gap: 3px; align-items: center; }
.mf-row > * { flex-shrink: 0; }
.mf-head span { font-size: 11px; color: #888; }
.mf-del { cursor: pointer; color: #E02424; width: 14px; text-align: center; }
.mf-add { cursor: pointer; color: #2563EB; font-size: 12px; align-self: flex-start; }
</style>
