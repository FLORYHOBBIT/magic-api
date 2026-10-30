<template>
	<div class="mf-status">
		<div class="mf-status-bar">
			<button class="mf-btn" @click="refresh">刷新</button>
			<template v-if="status && status.startType === 'timer'">
				<button class="mf-btn" @click="action('/flow/run', '立即执行')">立即执行</button>
				<button class="mf-btn" @click="action('/flow/retry-failed', '补传失败批次')">补传失败批次</button>
				<button class="mf-btn" @click="confirmReset">清除断点</button>
			</template>
			<span class="mf-tip" :class="tipType">{{ tip }}</span>
		</div>
		<div v-if="!info || !info.id" class="mf-empty">保存流程后查看运行状态</div>
		<div v-else-if="status" class="mf-status-body">
			<div class="mf-section">
				<b>触发：</b>
				<span v-if="!status.enabled" class="warn">未启用（在「集成流程信息」中勾选启用后保存）</span>
				<span v-else-if="status.triggers.length === 0 && status.errors.length === 0" class="warn">没有触发节点，只能调试运行</span>
				<span v-for="t in status.triggers" :key="t" class="ok">{{ t }}</span>
				<span v-for="e in status.errors" :key="e" class="error">注册失败：{{ e }}</span>
			</div>
			<div v-if="status.job" class="mf-section">
				<b>断点：</b>{{ status.job.checkpoint == null ? '无（从头读取）' : status.job.checkpoint }}
				<span v-if="status.job.failedBatches.length" class="error">　待补传批次 {{ status.job.failedBatches.length }} 个</span>
			</div>
			<div v-if="status.jobError" class="mf-section error">{{ status.jobError }}</div>
			<table v-if="status.job && status.job.records.length">
				<thead><tr><th>开始时间</th><th>状态</th><th>批次</th><th>失败批次</th><th>行数</th><th>耗时</th><th>说明</th></tr></thead>
				<tbody>
				<tr v-for="r in status.job.records" :key="r.id">
					<td>{{ time(r.startTime) }}</td>
					<td :class="r.status === 'SUCCESS' ? 'ok' : 'error'">{{ r.status }}</td>
					<td>{{ r.totalBatches }}</td>
					<td>{{ r.failedBatches }}</td>
					<td>{{ r.successRows }}/{{ r.totalRows }}</td>
					<td>{{ r.costMillis }}ms</td>
					<td class="msg">{{ r.message }}</td>
				</tr>
				</tbody>
			</table>
			<div class="mf-section"><b>最近运行（本实例内存，重启清空）：</b></div>
			<table v-if="status.records.length">
				<thead><tr><th>时间</th><th>触发</th><th>结果</th><th>耗时</th><th>出错节点</th><th>说明</th></tr></thead>
				<tbody>
				<tr v-for="(r, i) in status.records" :key="i" :class="{ selected: selected === i }" @click="selected = i">
					<td>{{ time(r.startTime) }}</td>
					<td>{{ r.trigger }}</td>
					<td :class="r.success ? 'ok' : 'error'">{{ r.success ? '成功' : '失败' }}</td>
					<td>{{ r.duration }}ms</td>
					<td>{{ r.errorNode || '' }}</td>
					<td class="msg">{{ r.message || '' }}</td>
				</tr>
				</tbody>
			</table>
			<div v-else class="mf-empty-line">暂无</div>
			<div v-if="status.records[selected]" class="mf-detail">
				<div><b>输入：</b><pre>{{ status.records[selected].input }}</pre></div>
				<div><b>输出：</b><pre>{{ status.records[selected].output }}</pre></div>
			</div>
		</div>
	</div>
</template>
<script setup>
import { inject, ref, watch } from 'vue'
import { call } from '../service/context.js'

const info = inject('info')
const status = ref(null)
const tip = ref('')
const tipType = ref('')
const selected = ref(-1)

const time = value => {
	if (!value) {
		return ''
	}
	const d = new Date(value)
	const p = n => String(n).padStart(2, '0')
	return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

const refresh = () => {
	const id = info.value && info.value.id
	if (!id) {
		status.value = null
		return
	}
	call('get', '/flow/status', { id }).then(data => {
		status.value = data
		selected.value = -1
	}).catch(message => {
		tip.value = message
		tipType.value = 'error'
	})
}

const action = (url, name) => {
	tip.value = name + '中…'
	tipType.value = ''
	call('post', url, { id: info.value.id }).then(result => {
		const text = result && typeof result === 'object'
			? (result.status || (result.successBatches !== undefined ? `成功 ${result.successBatches} 批，失败 ${result.failedBatches} 批` : '完成')) + (result.message ? '：' + result.message : '')
			: '完成'
		tip.value = name + '：' + text
		tipType.value = result && (result.status === 'FAILED' || result.failedBatches > 0) ? 'error' : 'ok'
		refresh()
	}).catch(message => {
		tip.value = name + '失败：' + message
		tipType.value = 'error'
	})
}

const confirmReset = () => {
	if (window.confirm('清除断点后，下次将从头读取全部数据（可能重复上传），确定吗？')) {
		action('/flow/reset-checkpoint', '清除断点')
	}
}

watch(() => info.value && info.value.id, refresh, { immediate: true })
</script>
<style scoped>
.mf-status { display: flex; flex-direction: column; height: 100%; overflow: hidden; font-size: 12px; }
.mf-status-bar { display: flex; gap: 6px; align-items: center; padding: 4px 6px; }
.mf-status-body { flex: 1; overflow: auto; padding: 0 6px 6px; }
.mf-btn { padding: 2px 10px; font-size: 12px; cursor: pointer; border: 1px solid var(--magic-input-border-color, #bbb); background: transparent; color: inherit; border-radius: 2px; }
.mf-section { margin: 4px 0; display: flex; flex-wrap: wrap; gap: 10px; }
.mf-empty, .mf-empty-line { color: #999; padding: 6px; }
table { border-collapse: collapse; width: 100%; margin-bottom: 6px; }
th, td { text-align: left; padding: 2px 6px; white-space: nowrap; }
td.msg { white-space: normal; word-break: break-all; }
tbody tr { cursor: pointer; }
tbody tr.selected { background: rgba(37, 99, 235, .12); }
.ok { color: #0E9F6E; }
.error { color: #E02424; }
.warn { color: #D97706; }
.mf-tip { margin-left: 8px; }
.mf-detail { display: flex; gap: 8px; }
.mf-detail > div { flex: 1; min-width: 0; }
.mf-detail pre { margin: 0; max-height: 160px; overflow: auto; white-space: pre-wrap; word-break: break-all; font-family: Consolas, monospace; }
</style>
