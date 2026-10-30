<template>
	<div class="mf-page">
		<div class="mf-bar">
			<button class="mf-btn" @click="openTemplates">从模板生成</button>
			<button class="mf-btn" :disabled="jsonMode" title="Ctrl+Z" @click="lf && lf.undo()">撤销</button>
			<button class="mf-btn" :disabled="jsonMode" title="Ctrl+Y" @click="lf && lf.redo()">重做</button>
			<button class="mf-btn" :class="{ active: boxSelect }" :disabled="jsonMode" @click="toggleBoxSelect">框选</button>
			<button class="mf-btn" :disabled="jsonMode" @click="autoLayout">自动排列</button>
			<button class="mf-btn" :disabled="jsonMode" @click="fit">适应画布</button>
			<button class="mf-btn" @click="validate">校验</button>
			<button class="mf-btn" @click="toggleJson">{{ jsonMode ? '返回画布' : 'JSON 源码' }}</button>
			<span class="mf-tip" :class="tipType">{{ tip }}</span>
		</div>
		<div v-show="!jsonMode" class="mf-body">
			<div class="mf-palette">
				<div v-for="group in GROUPS" :key="group.name" class="mf-group">
					<div class="mf-group-name">{{ group.name }}</div>
					<div v-for="t in group.types" :key="t" class="mf-palette-item" :title="NODE_TYPES[t].description"
						 @mousedown="onPaletteDown($event, t)">
						<span class="mf-dot" :style="{ background: NODE_TYPES[t].color }"></span>{{ NODE_TYPES[t].label }}
					</div>
				</div>
			</div>
			<div class="mf-canvas-wrap">
				<div ref="canvasEl" class="mf-canvas"></div>
				<div v-if="empty" class="mf-empty">从左侧拖入或点击节点，或点「从模板生成」</div>
			</div>
			<div class="mf-props">
				<template v-if="selectedNode">
					<div class="mf-props-title" :style="{ borderColor: nodeType(selectedNode.type).color }">{{ nodeType(selectedNode.type).label }}</div>
					<div class="mf-desc">{{ nodeType(selectedNode.type).description }}</div>
					<div class="mf-field">
						<label class="mf-label">节点 id（引用名）</label>
						<input class="mf-input" :value="selectedNode.id" @change="renameNode($event.target.value)"/>
					</div>
					<div class="mf-field">
						<label class="mf-label">名称</label>
						<input class="mf-input" v-model="selectedNode.name" @input="commitNode"/>
					</div>
					<node-form :node="selectedNode" :key="selectedNode.id + ':' + selectedNode.type" @change="commitNode"/>
					<div v-if="traceOf(selectedNode.id)" class="mf-field">
						<label class="mf-label">最近一次调试{{ traceOf(selectedNode.id).success ? '输出' : '错误' }}</label>
						<pre class="mf-pre">{{ traceText(selectedNode.id) }}</pre>
					</div>
					<button class="mf-btn mf-danger" @click="removeSelected">删除节点</button>
				</template>
				<template v-else-if="selectedEdge">
					<div class="mf-props-title">连线</div>
					<div class="mf-desc">{{ nodeLabel(selectedEdge.source) }} → {{ nodeLabel(selectedEdge.target) }}</div>
					<div class="mf-field">
						<label class="mf-label">类型</label>
						<select class="mf-input" :value="edgeKind(selectedEdge.condition)" @change="setEdgeKind($event.target.value)">
							<option value="default">默认（成功，且没有条件命中时）</option>
							<option value="condition">条件</option>
							<option value="failure">失败（节点出错时）</option>
						</select>
					</div>
					<div v-if="edgeKind(selectedEdge.condition) === 'condition'" class="mf-field">
						<label class="mf-label">条件表达式</label>
						<input class="mf-input" v-model="selectedEdge.condition" placeholder="prev.size() > 0" @change="commitEdge"/>
						<div class="mf-help">按连线顺序计算，第一个为真的生效；可用 prev、input、vars、节点 id</div>
					</div>
					<button class="mf-btn mf-danger" @click="removeSelected">删除连线</button>
				</template>
				<template v-else>
					<div class="mf-props-title">集成流程</div>
					<div class="mf-desc">
						<p>第一个节点决定流程如何被调用：<b>HTTP 接口</b>、<b>WebService</b> 或 <b>定时</b>（没有触发节点时只能调试运行）。</p>
						<p>从节点右侧蓝点拖出成功/条件连线，从底部红点拖出失败连线。选中后在这里配置。</p>
						<p>画布快捷键（先点一下画布）：Delete / Backspace 删除，Ctrl+Z 撤销，Ctrl+Y 重做，Ctrl+C / Ctrl+V 复制粘贴；Ctrl + 滚轮缩放，拖动空白处平移，「框选」后拖出矩形多选。</p>
						<p>表达式中可用：input（触发输入）、prev（上一个节点输出）、节点 id、vars、error（失败连线中）。各医院不同的值写成 ${var.名称}。</p>
						<p>保存后在底部「集成流程信息」中启用；「运行状态」查看地址、记录、断点。</p>
					</div>
				</template>
			</div>
		</div>
		<div v-if="jsonMode" class="mf-json">
			<textarea class="mf-input mf-code mf-json-text" spellcheck="false" v-model="jsonText"></textarea>
			<div class="mf-json-actions">
				<button class="mf-btn" @click="applyJson">应用</button>
				<span v-if="jsonError" class="mf-tip error">{{ jsonError }}</span>
			</div>
		</div>
		<div v-if="templates.open" class="mf-mask" @mousedown.self="templates.open = false">
			<div class="mf-dialog">
				<div class="mf-props-title">从模板生成</div>
				<div class="mf-dialog-body">
					<div class="mf-template-list">
						<div v-for="t in templates.list" :key="t.id" class="mf-template" :class="{ selected: templates.current && templates.current.id === t.id }" @click="chooseTemplate(t)">
							<b>{{ t.name }}</b>
							<div class="mf-help">{{ t.description }}</div>
						</div>
					</div>
					<div class="mf-template-params" v-if="templates.current">
						<div v-for="p in templates.current.params" :key="p.key" class="mf-field">
							<label class="mf-label">{{ p.label }}</label>
							<select v-if="p.options" class="mf-input" v-model="templates.values[p.key]">
								<option v-for="o in p.options" :key="o" :value="o">{{ o }}</option>
							</select>
							<textarea v-else-if="p.multiline" class="mf-input mf-code" rows="4" v-model="templates.values[p.key]"></textarea>
							<input v-else class="mf-input" v-model="templates.values[p.key]"/>
							<div v-if="p.description" class="mf-help">{{ p.description }}</div>
						</div>
					</div>
				</div>
				<div class="mf-json-actions">
					<button class="mf-btn" :disabled="!templates.current" @click="applyTemplate">生成（替换当前流程）</button>
					<button class="mf-btn" @click="templates.open = false">取消</button>
					<span v-if="templates.error" class="mf-tip error">{{ templates.error }}</span>
				</div>
			</div>
		</div>
	</div>
</template>
<script setup>
import { computed, reactive, ref, watch, onMounted, onBeforeUnmount, shallowRef } from 'vue'
import LogicFlow from '@logicflow/core'
import { SelectionSelect } from '@logicflow/extension/es/components/selection-select'
import '@logicflow/core/dist/index.css'
import NodeForm from './node-form.vue'
import { GROUPS, NODE_TYPES, nodeType, defaultConfig } from '../service/node-types.js'
import { NODE, EDGE, NODE_W, NODE_H, register, toGraph, fromGraph, setTraces, edgeKind, edgeLabel, isDefault, isFailure } from '../service/logicflow.js'
import { debugState } from '../service/debug-state.js'
import { call } from '../service/context.js'

const props = defineProps({
	selectTab: Object,
	bus: Object,
	request: Object,
	Message: Object
})

const canvasEl = ref(null)
const lf = shallowRef(null)
const extra = ref({})
const empty = ref(true)
const jsonMode = ref(false)
const jsonText = ref('')
const jsonError = ref('')
const tip = ref('')
const tipType = ref('')
const boxSelect = ref(false)
const selectedNode = ref(null)
const selectedEdge = ref(null)
const templates = reactive({ open: false, list: [], current: null, values: {}, error: '' })
let lastScript = null
let loading = false

const item = () => props.selectTab && props.selectTab.item

const showTip = (text, type) => {
	tip.value = text
	tipType.value = type || ''
}

// ---------------------------------------------------------------- 读写

const parse = script => {
	const parsed = script && script.trim() ? JSON.parse(script) : { nodes: [], edges: [] }
	if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
		throw new Error('流程定义必须是 JSON 对象')
	}
	return parsed
}

const load = script => {
	lastScript = script
	let parsed
	try {
		parsed = parse(script)
	} catch (e) {
		jsonMode.value = true
		jsonText.value = script || ''
		jsonError.value = '流程定义不是合法的 JSON，请修正后点「应用」：' + e.message
		return
	}
	const { nodes, edges, ...rest } = parsed
	extra.value = rest
	loading = true
	try {
		lf.value.render(toGraph({ nodes: Array.isArray(nodes) ? nodes : [], edges: Array.isArray(edges) ? edges : [] }))
	} finally {
		loading = false
	}
	empty.value = !(nodes && nodes.length)
	clearSelection()
	refreshTraces()
}

const currentDefinition = () => ({ ...extra.value, ...fromGraph(lf.value.getGraphRawData()) })

const serialize = () => JSON.stringify(currentDefinition(), null, 2)

const sync = () => {
	if (loading || !lf.value) {
		return
	}
	const script = serialize()
	empty.value = lf.value.graphModel.nodes.length === 0
	if (script === lastScript) {
		return
	}
	lastScript = script
	if (item()) {
		item().script = script
	}
}

watch(() => item() && item().script, script => {
	if (lf.value && script !== lastScript) {
		load(script)
	}
})

// ---------------------------------------------------------------- 初始化

let nodeSeq = 0
const generated = new Set()
const uniqueId = type => {
	const base = /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(type) ? type : 'node'
	let i = 1
	while (lf.value.getNodeModelById(base + i)) {
		i++
	}
	return base + i
}

onMounted(() => {
	const instance = new LogicFlow({
		container: canvasEl.value,
		grid: { size: 16, visible: true, type: 'dot', config: { color: 'rgba(128,128,128,.35)', thickness: 1 } },
		keyboard: {
			enabled: true,
			shortcuts: [{
				keys: ['delete'],
				callback: () => {
					removeSelected()
					return false
				}
			}]
		},
		snapline: true,
		textEdit: false,
		edgeTextDraggable: false,
		adjustEdge: false,
		allowRotate: false,
		allowResize: false,
		stopScrollGraph: true,
		plugins: [SelectionSelect],
		idGenerator: () => {
			const id = 'n' + Date.now().toString(36) + (nodeSeq++).toString(36)
			generated.add(id)
			return id
		},
		style: { anchor: { r: 5 } }
	})
	register(instance)
	lf.value = instance

	instance.on('history:change', sync)
	// 快捷键绑在画布容器上；焦点落在节点内部元素时，节点删除后焦点会回到 body，导致 Ctrl+Z 等失效
	const focusCanvas = () => setTimeout(() => {
		const active = document.activeElement
		if (instance.container && (!active || active === document.body || instance.container.contains(active))) {
			instance.container.focus({ preventScroll: true })
		}
	}, 0)
	instance.on('node:click,edge:click,blank:click,node:delete,edge:delete,node:dnd-add,edge:add,history:change', focusCanvas)
	instance.on('node:click', ({ data }) => selectNode(data.id))
	instance.on('edge:click', ({ data }) => selectEdge(data.id))
	instance.on('blank:click', clearSelection)
	instance.on('edge:add', ({ data }) => onEdgeAdd(data))
	instance.on('node:dnd-add', ({ data }) => onDndAdd(data))
	// 复制粘贴产生的节点用随机 id，改为按类型编号（如 query2），便于在表达式中引用
	instance.on('node:add', ({ data }) => {
		if (!loading && data && generated.has(data.id) && instance.getNodeModelById(data.id)) {
			generated.delete(data.id)
			instance.changeNodeId(data.id, uniqueId(data.properties && data.properties.flowType))
		}
	})
	instance.on('connection:not-allowed', ({ msg }) => showTip(msg || '不能这样连线', 'error'))
	instance.on('node:delete,edge:delete', () => clearSelection())

	load(item() ? item().script : '')
	instance.on('graph:rendered', () => sync())
})

onBeforeUnmount(() => {
	if (lf.value) {
		lf.value.destroy && lf.value.destroy()
		lf.value = null
	}
})

// ---------------------------------------------------------------- 节点

const nodeLabel = id => {
	const model = lf.value && lf.value.getNodeModelById(id)
	return model ? (model.properties.name || id) : id
}

const triggerExists = exceptId => lf.value.graphModel.nodes.find(n => n.id !== exceptId && nodeType(n.properties.flowType).trigger)

const newNodeData = type => ({
	type: NODE,
	properties: { flowType: type, name: nodeType(type).label, config: defaultConfig(type) }
})

const onPaletteDown = (e, type) => {
	if (e.button !== 0) {
		return
	}
	const startX = e.clientX
	const startY = e.clientY
	let dragging = false
	const move = ev => {
		if (!dragging && Math.abs(ev.clientX - startX) + Math.abs(ev.clientY - startY) > 4) {
			dragging = true
			lf.value.dnd.startDrag(newNodeData(type))
		}
	}
	const up = () => {
		document.removeEventListener('mousemove', move)
		document.removeEventListener('mouseup', up)
		if (!dragging) {
			addNode(type)
		}
	}
	document.addEventListener('mousemove', move)
	document.addEventListener('mouseup', up)
}

const onDndAdd = data => {
	const type = data.properties && data.properties.flowType
	if (nodeType(type).trigger && triggerExists(data.id)) {
		lf.value.deleteNode(data.id)
		showTip('一个流程只能有一个触发节点', 'error')
		return
	}
	const id = uniqueId(type)
	lf.value.changeNodeId(data.id, id)
	selectNode(id)
}

const addNode = type => {
	if (nodeType(type).trigger && triggerExists()) {
		showTip('一个流程只能有一个触发节点', 'error')
		return
	}
	const from = selectedNode.value && lf.value.getNodeModelById(selectedNode.value.id)
	let x
	let y
	if (from) {
		x = from.x + 230
		y = from.y
	} else {
		const { transformModel } = lf.value.graphModel
		const rect = canvasEl.value.getBoundingClientRect()
		const [cx, cy] = transformModel.HtmlPointToCanvasPoint([rect.width / 2, rect.height / 3])
		x = cx + (lf.value.graphModel.nodes.length % 4) * 20
		y = cy + (lf.value.graphModel.nodes.length % 4) * 20
	}
	const id = uniqueId(type)
	lf.value.addNode({ ...newNodeData(type), id, x, y })
	// 自动接到当前选中节点后面
	if (from && !nodeType(from.properties.flowType).terminal && !nodeType(type).trigger) {
		const hasDefault = lf.value.getGraphRawData().edges.some(e => e.sourceNodeId === from.id && isDefault(e.properties && e.properties.condition))
		if (!hasDefault) {
			lf.value.addEdge({ type: EDGE, sourceNodeId: from.id, targetNodeId: id, sourceAnchorId: `${from.id}_out`, targetAnchorId: `${id}_in` })
		}
	}
	selectNode(id)
}

const selectNode = id => {
	const model = lf.value.getNodeModelById(id)
	if (!model) {
		return
	}
	const p = model.properties
	selectedEdge.value = null
	selectedNode.value = reactive({ id, type: p.flowType, name: p.name || '', config: JSON.parse(JSON.stringify(p.config || {})) })
}

const commitNode = () => {
	const node = selectedNode.value
	if (!node || !lf.value.getNodeModelById(node.id)) {
		return
	}
	lf.value.setProperties(node.id, { name: node.name, config: JSON.parse(JSON.stringify(node.config || {})) })
}

const renameNode = value => {
	const node = selectedNode.value
	const id = (value || '').trim()
	if (!node || !id || id === node.id) {
		return
	}
	if (lf.value.getNodeModelById(id)) {
		showTip('节点 id 重复：' + id, 'error')
		return
	}
	lf.value.changeNodeId(node.id, id)
	if (!/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(id)) {
		showTip('id 不是合法变量名，表达式中需写 nodes[\'' + id + '\']', 'warn')
	}
	selectNode(id)
	sync()
}

// ---------------------------------------------------------------- 连线

const edgesFrom = (source, exceptId) => lf.value.getGraphRawData().edges.filter(e => e.sourceNodeId === source && e.id !== exceptId)

const onEdgeAdd = data => {
	if (loading) {
		return
	}
	const failure = String(data.sourceAnchorId || '').endsWith('_fail')
	const others = edgesFrom(data.sourceNodeId, data.id)
	if (failure) {
		if (others.some(e => isFailure(e.properties && e.properties.condition))) {
			lf.value.deleteEdge(data.id)
			showTip('这个节点已有失败连线', 'error')
			return
		}
		lf.value.setProperties(data.id, { condition: 'failure' })
		lf.value.updateText(data.id, edgeLabel('failure'))
	} else if (others.some(e => isDefault(e.properties && e.properties.condition))) {
		lf.value.setProperties(data.id, { condition: 'prev != null' })
		lf.value.updateText(data.id, edgeLabel('prev != null'))
		showTip('已有默认连线，新连线设为条件连线，请填写条件', 'warn')
	}
	selectEdge(data.id)
}

const selectEdge = id => {
	const model = lf.value.getEdgeModelById(id)
	if (!model) {
		return
	}
	selectedNode.value = null
	selectedEdge.value = reactive({ id, source: model.sourceNodeId, target: model.targetNodeId, condition: model.properties.condition })
}

const applyEdge = (condition, sourceAnchor) => {
	const edge = selectedEdge.value
	const model = lf.value.getEdgeModelById(edge.id)
	// 失败连线从底部锚点出，其他从右侧锚点出；锚点变化时重建连线
	if (sourceAnchor && model.sourceAnchorId !== sourceAnchor) {
		lf.value.deleteEdge(edge.id)
		const added = lf.value.addEdge({
			type: EDGE, sourceNodeId: edge.source, targetNodeId: edge.target, sourceAnchorId: sourceAnchor, targetAnchorId: `${edge.target}_in`,
			properties: condition == null ? {} : { condition }, text: edgeLabel(condition)
		})
		selectEdge(added.id)
		return
	}
	lf.value.setProperties(edge.id, { condition })
	lf.value.updateText(edge.id, edgeLabel(condition))
	edge.condition = condition
	sync()
}

const setEdgeKind = kind => {
	const edge = selectedEdge.value
	const others = edgesFrom(edge.source, edge.id)
	if (kind === 'failure') {
		if (others.some(e => isFailure(e.properties && e.properties.condition))) {
			showTip('这个节点已有失败连线', 'error')
			return
		}
		applyEdge('failure', `${edge.source}_fail`)
	} else if (kind === 'default') {
		if (others.some(e => isDefault(e.properties && e.properties.condition))) {
			showTip('这个节点已有默认连线', 'error')
			return
		}
		applyEdge(undefined, `${edge.source}_out`)
	} else {
		applyEdge(isDefault(edge.condition) || isFailure(edge.condition) ? 'prev != null' : edge.condition, `${edge.source}_out`)
	}
}

const commitEdge = () => {
	const edge = selectedEdge.value
	if (edge) {
		applyEdge(edge.condition && String(edge.condition).trim() ? edge.condition : 'prev != null')
	}
}

// ---------------------------------------------------------------- 选择与删除

const clearSelection = () => {
	selectedNode.value = null
	selectedEdge.value = null
}

const removeSelected = () => {
	const selected = lf.value.getSelectElements(true)
	if (selected.nodes.length || selected.edges.length) {
		selected.edges.forEach(e => lf.value.deleteEdge(e.id))
		selected.nodes.forEach(n => lf.value.deleteNode(n.id))
	} else if (selectedNode.value) {
		lf.value.deleteNode(selectedNode.value.id)
	} else if (selectedEdge.value) {
		lf.value.deleteEdge(selectedEdge.value.id)
	}
	clearSelection()
}

const toggleBoxSelect = () => {
	const plugin = lf.value.extension.selectionSelect
	boxSelect.value = !boxSelect.value
	if (boxSelect.value) {
		plugin.openSelectionSelect()
		showTip('框选已开启：在空白处拖出矩形选择多个节点，再点一次关闭', 'ok')
	} else {
		plugin.closeSelectionSelect()
		showTip('', '')
	}
}

// ---------------------------------------------------------------- 排列

const autoLayout = () => {
	const def = currentDefinition()
	const incoming = {}
	def.edges.forEach(e => {
		incoming[e.target] = (incoming[e.target] || 0) + 1
	})
	const depth = {}
	const queue = def.nodes.filter(n => !incoming[n.id]).map(n => n.id)
	queue.forEach(id => {
		depth[id] = 0
	})
	let guard = 0
	while (queue.length && guard++ < 10000) {
		const id = queue.shift()
		def.edges.filter(e => e.source === id).forEach(e => {
			if (depth[e.target] === undefined) {
				depth[e.target] = depth[id] + 1
				queue.push(e.target)
			}
		})
	}
	const rows = {}
	def.nodes.forEach(n => {
		const d = depth[n.id] === undefined ? 0 : depth[n.id]
		rows[d] = (rows[d] || 0) + 1
		lf.value.graphModel.moveNode2Coordinate(n.id, 60 + d * 230 + NODE_W / 2, 60 + (rows[d] - 1) * 110 + NODE_H / 2, true)
	})
	fit()
	sync()
}

const fit = () => {
	if (lf.value.graphModel.nodes.length) {
		lf.value.fitView(60, 60)
	} else {
		lf.value.resetZoom()
		lf.value.resetTranslate()
	}
}

// ---------------------------------------------------------------- 调试轨迹

const trace = computed(() => {
	const state = item() ? debugState(item().id) : null
	const result = state && state.result
	const map = {}
	if (result && Array.isArray(result.trace)) {
		result.trace.forEach((t, i) => {
			map[t.nodeId] = { ...t, step: i + 1 }
		})
	}
	return map
})
const refreshTraces = () => setTraces(trace.value)
watch(trace, refreshTraces)
const traceOf = id => trace.value[id]
const traceText = id => {
	const t = traceOf(id)
	return t ? (t.success ? JSON.stringify(t.output, null, 2) : t.error) : ''
}

// ---------------------------------------------------------------- 工具栏

const toggleJson = () => {
	if (jsonMode.value) {
		applyJson()
	} else {
		jsonText.value = serialize()
		jsonError.value = ''
		jsonMode.value = true
	}
}

const applyJson = () => {
	try {
		const parsed = parse(jsonText.value || '{}')
		jsonMode.value = false
		jsonError.value = ''
		load(JSON.stringify(parsed, null, 2))
		lastScript = null
		sync()
	} catch (e) {
		jsonError.value = e.message
	}
}

const validate = () => {
	const definition = jsonMode.value ? jsonText.value : serialize()
	call('post', '/flow/validate', { id: item() && item().id, definition })
		.then(() => showTip('校验通过', 'ok'))
		.catch(message => showTip('校验失败：' + message, 'error'))
}

const openTemplates = () => {
	templates.open = true
	templates.error = ''
	call('get', '/flow/templates').then(list => {
		templates.list = list || []
	}).catch(message => {
		templates.error = message
	})
}

const chooseTemplate = t => {
	templates.current = t
	const values = {}
	;(t.params || []).forEach(p => {
		values[p.key] = p.default == null ? '' : String(p.default)
	})
	templates.values = values
}

const applyTemplate = () => {
	if (lf.value.graphModel.nodes.length > 0 && !window.confirm('生成的流程会替换当前画布中的全部节点，确定吗？')) {
		return
	}
	call('post', '/flow/template/apply', { id: templates.current.id, params: JSON.stringify(templates.values) }).then(definition => {
		jsonMode.value = false
		load(definition)
		lastScript = null
		sync()
		templates.open = false
		fit()
		showTip('已按模板「' + templates.current.name + '」生成，检查各节点配置后保存', 'ok')
	}).catch(message => {
		templates.error = message
	})
}
</script>
<style scoped>
.mf-page { display: flex; flex-direction: column; width: 100%; height: 100%; overflow: hidden; position: relative; font-size: 12px; }
.mf-bar { display: flex; align-items: center; gap: 6px; padding: 4px 8px; border-bottom: 1px solid var(--magic-toolbox-border-color, #ddd); }
.mf-btn { padding: 2px 10px; font-size: 12px; cursor: pointer; border: 1px solid var(--magic-input-border-color, #bbb); background: transparent; color: inherit; border-radius: 2px; }
.mf-btn:hover:not(:disabled), .mf-btn.active { border-color: #2563EB; color: #2563EB; }
.mf-btn:disabled { opacity: .5; cursor: default; }
.mf-danger { color: #E02424; border-color: #E02424; align-self: flex-start; margin-top: 10px; }
.mf-tip { margin-left: 8px; }
.mf-tip.ok { color: #0E9F6E; }
.mf-tip.error { color: #E02424; }
.mf-tip.warn { color: #D97706; }
.mf-body { flex: 1; display: flex; overflow: hidden; }
.mf-palette { width: 130px; overflow: auto; border-right: 1px solid var(--magic-toolbox-border-color, #ddd); padding: 4px 0; }
.mf-group-name { padding: 4px 8px; color: #888; font-size: 11px; }
.mf-palette-item { padding: 4px 10px; cursor: grab; user-select: none; display: flex; align-items: center; gap: 6px; }
.mf-palette-item:hover { background: rgba(37, 99, 235, .1); }
.mf-dot { width: 8px; height: 8px; border-radius: 2px; display: inline-block; }
.mf-canvas-wrap { flex: 1; position: relative; overflow: hidden; }
.mf-canvas { position: absolute; inset: 0; outline: none; }
.mf-empty { position: absolute; left: 50%; top: 40%; transform: translate(-50%, -50%); color: #999; pointer-events: none; }
.mf-props { width: 340px; overflow: auto; border-left: 1px solid var(--magic-toolbox-border-color, #ddd); padding: 8px; display: flex; flex-direction: column; gap: 8px; }
.mf-props-title { font-weight: 700; font-size: 13px; border-left: 4px solid #2563EB; padding-left: 6px; }
.mf-desc { color: #888; line-height: 1.6; }
.mf-desc p { margin: 0 0 6px; }
.mf-field { display: flex; flex-direction: column; gap: 3px; }
.mf-label { font-weight: 600; }
.mf-help { color: #888; font-size: 11px; }
.mf-input { box-sizing: border-box; width: 100%; padding: 3px 5px; font-size: 12px; border: 1px solid var(--magic-input-border-color, #bbb);
	border-radius: 2px; background: var(--magic-input-background, transparent); color: inherit; outline: none; }
.mf-code { font-family: Consolas, 'Courier New', monospace; resize: vertical; white-space: pre-wrap; word-break: break-all; }
.mf-pre { margin: 0; max-height: 200px; overflow: auto; font-family: Consolas, monospace; font-size: 11px; white-space: pre-wrap; word-break: break-all;
	border: 1px dashed var(--magic-input-border-color, #ccc); padding: 4px; }
.mf-json { flex: 1; display: flex; flex-direction: column; padding: 6px; gap: 6px; overflow: hidden; }
.mf-json-text { flex: 1; resize: none; }
.mf-json-actions { display: flex; align-items: center; gap: 6px; }
.mf-mask { position: absolute; inset: 0; background: rgba(0, 0, 0, .3); display: flex; align-items: center; justify-content: center; z-index: 10; }
.mf-dialog { width: 760px; max-width: 95%; max-height: 90%; display: flex; flex-direction: column; gap: 8px; padding: 10px;
	background: var(--magic-background, #fff); border: 1px solid #94A3B8; border-radius: 4px; }
.mf-dialog-body { display: flex; gap: 10px; overflow: hidden; flex: 1; min-height: 300px; }
.mf-template-list { width: 240px; overflow: auto; display: flex; flex-direction: column; gap: 6px; }
.mf-template { padding: 6px; border: 1px solid var(--magic-input-border-color, #ccc); border-radius: 3px; cursor: pointer; }
.mf-template.selected { border-color: #2563EB; background: rgba(37, 99, 235, .08); }
.mf-template-params { flex: 1; overflow: auto; display: flex; flex-direction: column; gap: 8px; }
</style>
<style>
/* LogicFlow 节点卡片（渲染在 foreignObject 中，不能用 scoped） */
.mf-lf-node { box-sizing: border-box; border: 1px solid #94A3B8; border-radius: 4px; background: var(--magic-background, #fff); color: var(--magic-font-color, #333);
	box-shadow: 0 1px 3px rgba(0, 0, 0, .15); font-size: 12px; overflow: hidden; cursor: move; user-select: none; }
.mf-lf-node.ok { border-color: #0E9F6E; }
.mf-lf-node.fail { border-color: #E02424; box-shadow: 0 0 0 2px rgba(224, 36, 36, .3); }
.mf-lf-type { color: #fff; padding: 2px 6px; font-size: 11px; display: flex; justify-content: space-between; }
.mf-lf-type span { opacity: .85; }
.mf-lf-name { padding: 6px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mf-canvas .lf-graph { background: transparent; }
</style>
