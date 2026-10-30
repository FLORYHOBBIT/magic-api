<template>
	<div class="mf-page">
		<div class="mf-bar">
			<button class="mf-btn" @click="openTemplates">从模板生成</button>
			<button class="mf-btn" :disabled="jsonMode" @click="autoLayout">自动排列</button>
			<button class="mf-btn" @click="validate">校验</button>
			<button class="mf-btn" @click="toggleJson">{{ jsonMode ? '返回画布' : 'JSON 源码' }}</button>
			<button class="mf-btn" :disabled="jsonMode" @click="resetView">还原缩放</button>
			<span class="mf-tip" :class="tipType">{{ tip }}</span>
		</div>
		<div v-if="!jsonMode" class="mf-body">
			<div class="mf-palette">
				<div v-for="group in GROUPS" :key="group.name" class="mf-group">
					<div class="mf-group-name">{{ group.name }}</div>
					<div v-for="t in group.types" :key="t" class="mf-palette-item" draggable="true"
						 :title="NODE_TYPES[t].description" @dragstart="onPaletteDrag($event, t)" @click="addNode(t)">
						<span class="mf-dot" :style="{ background: NODE_TYPES[t].color }"></span>{{ NODE_TYPES[t].label }}
					</div>
				</div>
			</div>
			<div ref="canvasEl" class="mf-canvas" tabindex="0" @mousedown="onCanvasDown" @wheel.prevent="onWheel"
				 @dragover.prevent @drop="onDrop" @keydown="onKey">
				<div class="mf-viewport" :style="{ transform: `translate(${view.x}px, ${view.y}px) scale(${view.scale})` }">
					<svg class="mf-svg" :width="SIZE.w" :height="SIZE.h">
						<defs>
							<marker id="mf-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
								<path d="M0,0 L10,5 L0,10 z" fill="#64748B"/>
							</marker>
							<marker id="mf-arrow-fail" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
								<path d="M0,0 L10,5 L0,10 z" fill="#E02424"/>
							</marker>
							<marker id="mf-arrow-cond" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
								<path d="M0,0 L10,5 L0,10 z" fill="#D97706"/>
							</marker>
						</defs>
						<g v-for="(edge, index) in def.edges" :key="'e' + index" class="mf-edge-group" @mousedown.stop="select('edge', index)">
							<path :d="edgePath(edge)" class="mf-edge-hit"/>
							<path :d="edgePath(edge)" class="mf-edge" :class="[edgeKind(edge), { selected: isSelected('edge', index) }]"
								  :marker-end="`url(#${edgeKind(edge) === 'failure' ? 'mf-arrow-fail' : edgeKind(edge) === 'condition' ? 'mf-arrow-cond' : 'mf-arrow'})`"/>
							<text v-if="edgeKind(edge) !== 'default'" class="mf-edge-label" :class="edgeKind(edge)" :x="labelPoint(edge).x" :y="labelPoint(edge).y">
								{{ edgeKind(edge) === 'failure' ? '失败' : shorten(edge.condition) }}
							</text>
						</g>
						<path v-if="link" :d="linkPath" class="mf-edge mf-edge-temp" :class="link.kind"/>
					</svg>
					<div v-for="node in def.nodes" :key="node.id" class="mf-node"
						 :class="{ selected: isSelected('node', node.id), trigger: typeOf(node).trigger, ok: traceOf(node) && traceOf(node).success, fail: traceOf(node) && !traceOf(node).success }"
						 :style="{ left: pos(node).x + 'px', top: pos(node).y + 'px', width: NODE.w + 'px', height: NODE.h + 'px' }"
						 @mousedown.stop="onNodeDown($event, node)" @mouseup="onNodeUp(node)">
						<div class="mf-node-type" :style="{ background: typeOf(node).color }">
							{{ typeOf(node).label }}<span v-if="traceOf(node)" class="mf-node-step">#{{ traceOf(node).step }} {{ traceOf(node).duration }}ms</span>
						</div>
						<div class="mf-node-name" :title="node.id">{{ node.name || node.id }}</div>
						<span v-if="!typeOf(node).trigger" class="mf-port mf-port-in"></span>
						<span v-if="!typeOf(node).terminal" class="mf-port mf-port-out" title="拖出：成功 / 条件连线" @mousedown.stop="startLink($event, node, 'default')"></span>
						<span v-if="!typeOf(node).terminal && !typeOf(node).trigger" class="mf-port mf-port-fail" title="拖出：失败连线" @mousedown.stop="startLink($event, node, 'failure')"></span>
					</div>
				</div>
				<div v-if="def.nodes.length === 0" class="mf-empty">从左侧点击或拖入节点，或点「从模板生成」</div>
			</div>
			<div class="mf-props">
				<template v-if="selectedNode">
					<div class="mf-props-title" :style="{ borderColor: typeOf(selectedNode).color }">{{ typeOf(selectedNode).label }}</div>
					<div class="mf-desc">{{ typeOf(selectedNode).description }}</div>
					<div class="mf-field">
						<label class="mf-label">节点 id（引用名）</label>
						<input class="mf-input" :value="selectedNode.id" @change="renameNode(selectedNode, $event.target.value)"/>
					</div>
					<div class="mf-field">
						<label class="mf-label">名称</label>
						<input class="mf-input" :value="selectedNode.name || ''" @input="selectedNode.name = $event.target.value; sync()"/>
					</div>
					<node-form :node="selectedNode" :key="selectedNode.id + ':' + selectedNode.type" @change="sync"/>
					<div v-if="traceOf(selectedNode)" class="mf-field">
						<label class="mf-label">最近一次调试{{ traceOf(selectedNode).success ? '输出' : '错误' }}</label>
						<pre class="mf-pre">{{ traceText(selectedNode) }}</pre>
					</div>
					<button class="mf-btn mf-danger" @click="removeSelected">删除节点</button>
				</template>
				<template v-else-if="selectedEdge">
					<div class="mf-props-title">连线</div>
					<div class="mf-desc">{{ nodeLabel(selectedEdge.source) }} → {{ nodeLabel(selectedEdge.target) }}</div>
					<div class="mf-field">
						<label class="mf-label">类型</label>
						<select class="mf-input" :value="edgeKind(selectedEdge)" @change="setEdgeKind(selectedEdge, $event.target.value)">
							<option value="default">默认（成功，且没有条件命中时）</option>
							<option value="condition">条件</option>
							<option value="failure">失败（节点出错时）</option>
						</select>
					</div>
					<div v-if="edgeKind(selectedEdge) === 'condition'" class="mf-field">
						<label class="mf-label">条件表达式</label>
						<input class="mf-input" :value="selectedEdge.condition" placeholder="prev.size() > 0" @input="selectedEdge.condition = $event.target.value; sync()"/>
						<div class="mf-help">按连线顺序计算，第一个为真的生效；可用 prev、input、vars、节点 id</div>
					</div>
					<button class="mf-btn mf-danger" @click="removeSelected">删除连线</button>
				</template>
				<template v-else>
					<div class="mf-props-title">集成流程</div>
					<div class="mf-desc">
						<p>第一个节点决定流程如何被调用：<b>HTTP 接口</b>、<b>WebService</b> 或 <b>定时</b>（没有触发节点时只能调试运行）。</p>
						<p>从节点右侧圆点拖出成功/条件连线，从底部红点拖出失败连线。选中节点或连线后在这里配置，按 Delete 删除。</p>
						<p>表达式中可用：input（触发输入）、prev（上一个节点输出）、节点 id、vars、error（失败连线中）。各医院不同的值写成 ${var.名称}。</p>
						<p>保存后在底部「集成流程信息」中启用；「运行状态」查看地址、记录、断点。</p>
					</div>
				</template>
			</div>
		</div>
		<div v-else class="mf-json">
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
import { computed, reactive, ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import NodeForm from './node-form.vue'
import { GROUPS, NODE_TYPES, nodeType, defaultConfig } from '../service/node-types.js'
import { debugState } from '../service/debug-state.js'
import { call } from '../service/context.js'

const props = defineProps({
	selectTab: Object,
	bus: Object,
	request: Object,
	Message: Object
})

const NODE = { w: 170, h: 58 }
const SIZE = { w: 6000, h: 4000 }

const def = reactive({ nodes: [], edges: [] })
const extra = ref({})
const view = reactive({ x: 0, y: 0, scale: 1 })
const selection = ref(null)
const link = ref(null)
const canvasEl = ref(null)
const jsonMode = ref(false)
const jsonText = ref('')
const jsonError = ref('')
const tip = ref('')
const tipType = ref('')
const templates = reactive({ open: false, list: [], current: null, values: {}, error: '' })
let lastScript = null

const item = () => props.selectTab && props.selectTab.item

// ---------------------------------------------------------------- 读写定义

const load = script => {
	lastScript = script
	let parsed
	try {
		parsed = script && script.trim() ? JSON.parse(script) : { nodes: [], edges: [] }
		if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
			throw new Error('流程定义必须是 JSON 对象')
		}
	} catch (e) {
		jsonMode.value = true
		jsonText.value = script || ''
		jsonError.value = '流程定义不是合法的 JSON，请修正后点「应用」：' + e.message
		return
	}
	const { nodes, edges, ...rest } = parsed
	def.nodes = Array.isArray(nodes) ? nodes : []
	def.edges = Array.isArray(edges) ? edges : []
	def.nodes.forEach((n, i) => {
		if (!n.config || typeof n.config !== 'object') {
			n.config = {}
		}
		if (!n.ui || typeof n.ui.x !== 'number') {
			n.ui = { ...(n.ui || {}), x: 60 + (i % 5) * 220, y: 80 + Math.floor(i / 5) * 120 }
		}
	})
	extra.value = rest
	if (selection.value && selection.value.kind === 'node' && !def.nodes.find(n => n.id === selection.value.id)) {
		selection.value = null
	}
}

const serialize = () => JSON.stringify({ ...extra.value, nodes: def.nodes, edges: def.edges }, null, 2)

const sync = () => {
	const script = serialize()
	lastScript = script
	if (item()) {
		item().script = script
	}
}

watch(() => item() && item().script, script => {
	if (script !== lastScript) {
		load(script)
	}
})

onMounted(() => {
	load(item() ? item().script : '')
	window.addEventListener('mousemove', onMove)
	window.addEventListener('mouseup', onUp)
})
onBeforeUnmount(() => {
	window.removeEventListener('mousemove', onMove)
	window.removeEventListener('mouseup', onUp)
})

// ---------------------------------------------------------------- 节点与连线

const typeOf = node => nodeType(node.type)
const pos = node => node.ui || { x: 0, y: 0 }
const nodeById = id => def.nodes.find(n => n.id === id)
const nodeLabel = id => {
	const n = nodeById(id)
	return n ? (n.name || n.id) : id
}

const uniqueId = type => {
	const base = /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(type) ? type : 'node'
	let i = 1
	while (nodeById(base + i)) {
		i++
	}
	return base + i
}

const addNode = (type, point) => {
	const rect = canvasEl.value ? canvasEl.value.getBoundingClientRect() : { width: 600, height: 400 }
	const p = point || toCanvas(rect.left + rect.width / 2 - NODE.w / 2 + (def.nodes.length % 4) * 20, rect.top + rect.height / 3 + (def.nodes.length % 4) * 20)
	const node = { id: uniqueId(type), type, name: nodeType(type).label, config: defaultConfig(type), ui: { x: Math.round(p.x), y: Math.round(p.y) } }
	if (nodeType(type).trigger) {
		const existing = def.nodes.find(n => nodeType(n.type).trigger)
		if (existing) {
			showTip('一个流程只能有一个触发节点，已有「' + (existing.name || existing.id) + '」', 'error')
			return
		}
	}
	def.nodes.push(node)
	// 自动连到当前选中的节点后面
	if (selection.value && selection.value.kind === 'node' && !nodeType(type).trigger) {
		const from = nodeById(selection.value.id)
		if (from && !typeOf(from).terminal && !def.edges.some(e => e.source === from.id && isDefault(e))) {
			def.edges.push({ source: from.id, target: node.id })
			node.ui = { x: from.ui.x + 230, y: from.ui.y }
		}
	}
	select('node', node.id)
	sync()
}

const renameNode = (node, value) => {
	const id = (value || '').trim()
	if (!id || id === node.id) {
		return
	}
	if (nodeById(id)) {
		showTip('节点 id 重复：' + id, 'error')
		return
	}
	def.edges.forEach(e => {
		if (e.source === node.id) e.source = id
		if (e.target === node.id) e.target = id
	})
	node.id = id
	selection.value = { kind: 'node', id }
	if (!/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(id)) {
		showTip('id 不是合法变量名，表达式中需写 nodes[\'' + id + '\']', 'warn')
	}
	sync()
}

const isDefault = e => !e.condition || !String(e.condition).trim() || String(e.condition).trim() === 'success'
const isFailure = e => e.condition && String(e.condition).trim() === 'failure'
const edgeKind = e => isFailure(e) ? 'failure' : isDefault(e) ? 'default' : 'condition'

const setEdgeKind = (edge, kind) => {
	if (kind === 'failure') {
		if (def.edges.some(e => e !== edge && e.source === edge.source && isFailure(e))) {
			showTip('这个节点已有失败连线', 'error')
			return
		}
		edge.condition = 'failure'
	} else if (kind === 'default') {
		if (def.edges.some(e => e !== edge && e.source === edge.source && isDefault(e))) {
			showTip('这个节点已有默认连线', 'error')
			return
		}
		delete edge.condition
	} else {
		edge.condition = isDefault(edge) || isFailure(edge) ? 'prev != null' : edge.condition
	}
	sync()
}

const select = (kind, id) => {
	selection.value = { kind, id }
	nextTick(() => canvasEl.value && canvasEl.value.focus({ preventScroll: true }))
}
const isSelected = (kind, id) => selection.value && selection.value.kind === kind && selection.value.id === id
const selectedNode = computed(() => selection.value && selection.value.kind === 'node' ? nodeById(selection.value.id) : null)
const selectedEdge = computed(() => selection.value && selection.value.kind === 'edge' ? def.edges[selection.value.id] : null)

const removeSelected = () => {
	if (!selection.value) {
		return
	}
	if (selection.value.kind === 'node') {
		const id = selection.value.id
		def.nodes = def.nodes.filter(n => n.id !== id)
		def.edges = def.edges.filter(e => e.source !== id && e.target !== id)
	} else {
		def.edges.splice(selection.value.id, 1)
	}
	selection.value = null
	sync()
}

const onKey = e => {
	const tag = (e.target && e.target.tagName) || ''
	if ((e.key === 'Delete' || e.key === 'Backspace') && tag !== 'INPUT' && tag !== 'TEXTAREA' && tag !== 'SELECT') {
		e.preventDefault()
		removeSelected()
	}
}

// ---------------------------------------------------------------- 几何

const outPoint = n => ({ x: pos(n).x + NODE.w, y: pos(n).y + NODE.h / 2 })
const failPoint = n => ({ x: pos(n).x + NODE.w / 2, y: pos(n).y + NODE.h })
const inPoint = n => ({ x: pos(n).x, y: pos(n).y + NODE.h / 2 })

const curve = (s, t, down) => {
	const dx = Math.max(40, Math.abs(t.x - s.x) / 2)
	const c1 = down ? { x: s.x, y: s.y + 60 } : { x: s.x + dx, y: s.y }
	const c2 = { x: t.x - dx, y: t.y }
	return `M${s.x},${s.y} C${c1.x},${c1.y} ${c2.x},${c2.y} ${t.x},${t.y}`
}

const edgePath = edge => {
	const s = nodeById(edge.source)
	const t = nodeById(edge.target)
	if (!s || !t) {
		return ''
	}
	return isFailure(edge) ? curve(failPoint(s), inPoint(t), true) : curve(outPoint(s), inPoint(t), false)
}

const labelPoint = edge => {
	const s = nodeById(edge.source)
	const t = nodeById(edge.target)
	if (!s || !t) {
		return { x: 0, y: 0 }
	}
	const a = isFailure(edge) ? failPoint(s) : outPoint(s)
	const b = inPoint(t)
	return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 - 6 }
}

const shorten = text => {
	const s = String(text || '')
	return s.length > 24 ? s.substring(0, 24) + '…' : s
}

const toCanvas = (clientX, clientY) => {
	const rect = canvasEl.value.getBoundingClientRect()
	return { x: (clientX - rect.left - view.x) / view.scale, y: (clientY - rect.top - view.y) / view.scale }
}

const linkPath = computed(() => {
	if (!link.value) {
		return ''
	}
	const s = nodeById(link.value.source)
	if (!s) {
		return ''
	}
	const start = link.value.kind === 'failure' ? failPoint(s) : outPoint(s)
	return curve(start, link.value.point, link.value.kind === 'failure')
})

// ---------------------------------------------------------------- 鼠标

let drag = null

const onCanvasDown = e => {
	if (e.button !== 0) {
		return
	}
	selection.value = null
	drag = { kind: 'pan', startX: e.clientX, startY: e.clientY, x: view.x, y: view.y }
	canvasEl.value.focus({ preventScroll: true })
}

const onNodeDown = (e, node) => {
	if (e.button !== 0) {
		return
	}
	select('node', node.id)
	drag = { kind: 'node', node, startX: e.clientX, startY: e.clientY, x: pos(node).x, y: pos(node).y, moved: false }
}

const startLink = (e, node, kind) => {
	link.value = { source: node.id, kind, point: toCanvas(e.clientX, e.clientY) }
}

const onMove = e => {
	if (link.value) {
		link.value.point = toCanvas(e.clientX, e.clientY)
		return
	}
	if (!drag) {
		return
	}
	const dx = e.clientX - drag.startX
	const dy = e.clientY - drag.startY
	if (drag.kind === 'pan') {
		view.x = drag.x + dx
		view.y = drag.y + dy
	} else if (drag.kind === 'node') {
		drag.moved = drag.moved || Math.abs(dx) + Math.abs(dy) > 2
		drag.node.ui = { ...drag.node.ui, x: Math.max(0, Math.round(drag.x + dx / view.scale)), y: Math.max(0, Math.round(drag.y + dy / view.scale)) }
	}
}

const onUp = () => {
	if (drag && drag.kind === 'node' && drag.moved) {
		sync()
	}
	drag = null
	// 连线在节点的 mouseup 中完成；松开在空白处则取消
	setTimeout(() => {
		link.value = null
	}, 0)
}

const onNodeUp = target => {
	if (!link.value) {
		return
	}
	const { source, kind } = link.value
	link.value = null
	if (source === target.id) {
		return
	}
	if (typeOf(target).trigger) {
		showTip('触发节点只能是起始节点，不能连入', 'error')
		return
	}
	const edge = { source, target: target.id }
	if (kind === 'failure') {
		if (def.edges.some(e => e.source === source && isFailure(e))) {
			showTip('这个节点已有失败连线', 'error')
			return
		}
		edge.condition = 'failure'
	} else if (def.edges.some(e => e.source === source && isDefault(e))) {
		edge.condition = 'prev != null'
		showTip('已有默认连线，新连线设为条件连线，请填写条件', 'warn')
	}
	def.edges.push(edge)
	select('edge', def.edges.length - 1)
	sync()
}

const onWheel = e => {
	const rect = canvasEl.value.getBoundingClientRect()
	const before = toCanvas(e.clientX, e.clientY)
	const scale = Math.min(2, Math.max(0.3, view.scale * (e.deltaY < 0 ? 1.1 : 0.9)))
	view.scale = scale
	view.x = e.clientX - rect.left - before.x * scale
	view.y = e.clientY - rect.top - before.y * scale
}

const resetView = () => {
	view.x = 0
	view.y = 0
	view.scale = 1
}

const onPaletteDrag = (e, type) => {
	e.dataTransfer.setData('text/plain', 'magic-flow:' + type)
}

const onDrop = e => {
	const data = e.dataTransfer.getData('text/plain') || ''
	if (data.startsWith('magic-flow:')) {
		const p = toCanvas(e.clientX, e.clientY)
		addNode(data.substring(11), { x: p.x - NODE.w / 2, y: p.y - NODE.h / 2 })
	}
}

// ---------------------------------------------------------------- 自动排列

const autoLayout = () => {
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
		n.ui = { ...(n.ui || {}), x: 60 + d * 230, y: 60 + (rows[d] - 1) * 110 }
	})
	resetView()
	sync()
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
const traceOf = node => trace.value[node.id]
const traceText = node => {
	const t = traceOf(node)
	if (!t) {
		return ''
	}
	return t.success ? JSON.stringify(t.output, null, 2) : t.error
}

// ---------------------------------------------------------------- 工具栏

const showTip = (text, type) => {
	tip.value = text
	tipType.value = type || ''
}

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
		const parsed = JSON.parse(jsonText.value || '{}')
		if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
			throw new Error('流程定义必须是 JSON 对象')
		}
		load(JSON.stringify(parsed, null, 2))
		sync()
		jsonError.value = ''
		jsonMode.value = false
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
	if (def.nodes.length > 0 && !window.confirm('生成的流程会替换当前画布中的全部节点，确定吗？')) {
		return
	}
	call('post', '/flow/template/apply', { id: templates.current.id, params: JSON.stringify(templates.values) }).then(definition => {
		load(definition)
		sync()
		templates.open = false
		selection.value = null
		resetView()
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
.mf-btn:hover:not(:disabled) { border-color: #2563EB; color: #2563EB; }
.mf-btn:disabled { opacity: .5; cursor: default; }
.mf-danger { color: #E02424; border-color: #E02424; align-self: flex-start; margin-top: 10px; }
.mf-tip { margin-left: 8px; }
.mf-tip.ok { color: #0E9F6E; }
.mf-tip.error { color: #E02424; }
.mf-tip.warn { color: #D97706; }
.mf-body { flex: 1; display: flex; overflow: hidden; }
.mf-palette { width: 130px; overflow: auto; border-right: 1px solid var(--magic-toolbox-border-color, #ddd); padding: 4px 0; }
.mf-group-name { padding: 4px 8px; color: #888; font-size: 11px; }
.mf-palette-item { padding: 4px 10px; cursor: pointer; user-select: none; display: flex; align-items: center; gap: 6px; }
.mf-palette-item:hover { background: rgba(37, 99, 235, .1); }
.mf-dot { width: 8px; height: 8px; border-radius: 2px; display: inline-block; }
.mf-canvas { flex: 1; position: relative; overflow: hidden; outline: none; cursor: grab;
	background-image: radial-gradient(rgba(128, 128, 128, .25) 1px, transparent 1px); background-size: 16px 16px; }
.mf-viewport { position: absolute; left: 0; top: 0; transform-origin: 0 0; }
.mf-svg { position: absolute; left: 0; top: 0; overflow: visible; }
.mf-edge { fill: none; stroke: #64748B; stroke-width: 1.6; }
.mf-edge.failure { stroke: #E02424; stroke-dasharray: 5 3; }
.mf-edge.condition { stroke: #D97706; }
.mf-edge.selected { stroke-width: 3; }
.mf-edge-hit { fill: none; stroke: transparent; stroke-width: 12; cursor: pointer; }
.mf-edge-temp { stroke-dasharray: 4 4; pointer-events: none; }
.mf-edge-label { font-size: 11px; fill: #D97706; text-anchor: middle; pointer-events: none; }
.mf-edge-label.failure { fill: #E02424; }
.mf-node { position: absolute; box-sizing: border-box; border: 1px solid #94A3B8; border-radius: 4px; background: var(--magic-background, #fff);
	box-shadow: 0 1px 3px rgba(0, 0, 0, .15); cursor: move; user-select: none; }
.mf-node.selected { border-color: #2563EB; box-shadow: 0 0 0 2px rgba(37, 99, 235, .35); }
.mf-node.ok { border-color: #0E9F6E; }
.mf-node.fail { border-color: #E02424; box-shadow: 0 0 0 2px rgba(224, 36, 36, .3); }
.mf-node-type { color: #fff; padding: 2px 6px; font-size: 11px; border-radius: 3px 3px 0 0; display: flex; justify-content: space-between; }
.mf-node-step { opacity: .85; }
.mf-node-name { padding: 6px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mf-port { position: absolute; width: 10px; height: 10px; border-radius: 50%; border: 2px solid #fff; box-sizing: border-box; }
.mf-port-in { left: -5px; top: 24px; background: #94A3B8; }
.mf-port-out { right: -6px; top: 23px; width: 12px; height: 12px; background: #2563EB; cursor: crosshair; }
.mf-port-fail { left: 79px; bottom: -6px; width: 12px; height: 12px; background: #E02424; cursor: crosshair; }
.mf-port-out:hover, .mf-port-fail:hover { transform: scale(1.3); }
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
