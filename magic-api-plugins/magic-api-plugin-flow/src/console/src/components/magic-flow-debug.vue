<template>
	<div class="magic-flow-debug">
		<div class="magic-flow-debug-input">
			<div class="magic-flow-debug-title">{{ $i('flow.debug.input') }}</div>
			<textarea v-model="state.input" spellcheck="false" :placeholder="$i('flow.debug.inputPlaceholder')" @change="saveInput"></textarea>
		</div>
		<div class="magic-flow-debug-result">
			<div class="magic-flow-debug-title" v-if="!state.result">{{ $i('flow.debug.empty') }}</div>
			<template v-else>
				<div class="magic-flow-debug-title" :class="state.result.success ? 'ok' : 'fail'">
					{{ state.result.success ? $i('flow.debug.success', state.result.duration) : $i('flow.debug.failure', state.result.duration, state.result.message || '') }}
				</div>
				<table>
					<thead>
					<tr>
						<th>#</th>
						<th>{{ $i('flow.debug.node') }}</th>
						<th>{{ $i('flow.debug.type') }}</th>
						<th>{{ $i('flow.debug.status') }}</th>
						<th>{{ $i('flow.debug.duration') }}</th>
						<th>{{ $i('flow.debug.next') }}</th>
					</tr>
					</thead>
					<tbody>
					<tr v-for="(t, index) in state.result.trace" :key="index" :class="{ selected: state.selected === index, fail: !t.success }" @click="state.selected = index">
						<td>{{ index + 1 }}</td>
						<td>{{ t.name || t.nodeId }}</td>
						<td>{{ t.type }}</td>
						<td>{{ t.success ? 'OK' : 'ERROR' }}</td>
						<td>{{ t.duration }}ms</td>
						<td>{{ t.next || '-' }}</td>
					</tr>
					</tbody>
				</table>
				<pre class="magic-flow-debug-output">{{ selectedText }}</pre>
			</template>
		</div>
	</div>
</template>
<script setup>
import { inject, computed, watch } from 'vue'
import { debugState, saveDebugInput } from '../service/debug-state.js'

const $i = inject('i18n.format')
const info = inject('info')

const state = computed(() => debugState(info.value && info.value.id))

const selectedText = computed(() => {
	const result = state.value.result
	if (!result) {
		return ''
	}
	const trace = result.trace || []
	const t = trace[state.value.selected]
	if (!t) {
		return JSON.stringify(result.value, null, 2)
	}
	return t.success ? JSON.stringify(t.output, null, 2) : t.error
})

watch(() => state.value.result, () => {
	// 新结果默认选中出错节点，否则选中最后一个节点
	const trace = (state.value.result && state.value.result.trace) || []
	const failed = trace.findIndex(t => !t.success)
	state.value.selected = failed > -1 ? failed : trace.length - 1
})

const saveInput = () => saveDebugInput(info.value && info.value.id, state.value.input)
</script>
<style scoped>
.magic-flow-debug {
	display: flex;
	flex: 1;
	height: 100%;
	overflow: hidden;
}
.magic-flow-debug-input {
	width: 320px;
	display: flex;
	flex-direction: column;
	border-right: 1px solid var(--magic-toolbox-border-color, #ccc);
}
.magic-flow-debug-input textarea {
	flex: 1;
	resize: none;
	border: none;
	outline: none;
	padding: 5px;
	font-family: Consolas, monospace;
	background: transparent;
	color: inherit;
}
.magic-flow-debug-result {
	flex: 1;
	display: flex;
	flex-direction: column;
	overflow: auto;
}
.magic-flow-debug-title {
	padding: 3px 5px;
	font-weight: bold;
}
.magic-flow-debug-title.ok { color: #0E9F6E; }
.magic-flow-debug-title.fail, tr.fail td { color: #E02424; }
.magic-flow-debug table {
	border-collapse: collapse;
	width: 100%;
}
.magic-flow-debug th, .magic-flow-debug td {
	text-align: left;
	padding: 2px 6px;
	white-space: nowrap;
}
.magic-flow-debug tbody tr { cursor: pointer; }
.magic-flow-debug tbody tr.selected { background: var(--magic-list-selected-background, rgba(0, 120, 215, .15)); }
.magic-flow-debug-output {
	flex: 1;
	margin: 0;
	padding: 5px;
	font-family: Consolas, monospace;
	white-space: pre-wrap;
	word-break: break-all;
}
</style>
