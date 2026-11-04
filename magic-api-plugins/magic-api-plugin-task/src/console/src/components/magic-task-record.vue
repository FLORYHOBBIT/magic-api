<template>
	<div class="magic-task-record execution-record-panel">
		<div class="bar">
			<button class="btn" :disabled="loading" @click="refresh">{{ loading ? '刷新中…' : '刷新' }}</button>
			<div class="record-stats"><span>最近 {{ records.length }} 次执行</span><span>成功 {{ records.filter(r => r.success).length }}</span><span>失败 {{ records.filter(r => !r.success).length }}</span></div>
			<label class="record-filter">结果<select v-model="resultFilter" aria-label="筛选执行结果"><option value="all">全部结果</option><option value="success">成功</option><option value="failure">失败</option></select></label>
			<span class="tip" :class="tipType">{{ tip }}</span>
		</div>
		<div v-if="!currentId" class="empty">保存后可查看执行记录</div>
		<div v-else-if="visibleRecords.length === 0" class="empty">{{ records.length ? '没有符合条件的执行记录' : '暂无执行记录' }}</div>
		<div v-else class="record-table-scroll"><table>
			<thead>
				<tr><th>执行时间</th><th>触发来源</th><th>执行结果</th><th class="record-duration">耗时</th><th>说明</th><th>操作</th></tr>
			</thead>
			<tbody>
				<tr v-for="(r, i) in visibleRecords" :key="r.runId || i">
					<td class="record-time">{{ time(r.startTime) }}</td>
					<td><span class="record-source">{{ sourceName(r.source) }}</span></td>
					<td><span class="record-result" :class="r.success ? 'ok' : 'error'">{{ r.success ? '成功' : '失败' }}</span></td>
					<td class="record-duration">{{ r.costMillis }} <span class="record-muted">ms</span></td>
					<td class="msg" :title="r.message || ''"><span v-if="r.message">{{ r.message }}</span><span v-else class="record-muted">—</span></td>
					<td><button v-if="r.logAvailable" class="btn btn-link" @click="showRecordLog(currentId, r.runId)">查看日志 →</button><span v-else class="record-muted">未采集</span></td>
				</tr>
			</tbody>
		</table></div>
	</div>
</template>
<script setup>
import { computed, inject, onMounted, onUnmounted, ref, unref, watch } from 'vue'
import { fetchRecords, showRecordLog } from '../service/record.js'

const info = inject('info')
const opened = inject('opened')
const records = ref([])
const loading = ref(false)
const resultFilter = ref('all')
const visibleRecords = computed(() => records.value.filter(row => resultFilter.value === 'all' || row.success === (resultFilter.value === 'success')))
const tip = ref('')
const tipType = ref('')
const currentId = computed(() => unref(opened)?.type === 'task' ? (unref(info)?.id || '') : '')
let timer = null
let sequence = 0
let pendingId = ''

const time = value => {
	if (!value) {
		return ''
	}
	const d = new Date(value)
	const p = n => String(n).padStart(2, '0')
	return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

const sourceName = source => (source === 'cron' ? '定时触发' : '测试执行')

const refresh = () => {
	const id = currentId.value
	if (!currentId.value) {
		sequence++
		records.value = []
		tip.value = ''
		return
	}
	if (pendingId === id) return
	pendingId = id
	const version = ++sequence
	loading.value = true
	fetchRecords(id).then(list => {
		if (version !== sequence || id !== currentId.value) return
		records.value = list
		tip.value = ''
	}).catch(message => {
		if (version !== sequence || id !== currentId.value) return
		tip.value = message
		tipType.value = 'error'
	}).finally(() => {
		if (version === sequence) loading.value = false
		if (pendingId === id) pendingId = ''
	})
}

watch(currentId, () => { sequence++; pendingId = ''; loading.value = false; resultFilter.value = 'all'; records.value = []; refresh() }, { immediate: true })
onMounted(() => {
	timer = setInterval(() => { if (!document.hidden) refresh() }, 10000)
})
onUnmounted(() => {
	sequence++
	clearInterval(timer)
})
</script>
<style scoped>
.magic-task-record {
	display: flex;
	flex-direction: column;
	flex: 1;
	padding: 5px;
	overflow: auto;
}
.magic-task-record .bar {
	display: flex;
	align-items: center;
	gap: 10px;
	padding-bottom: 5px;
}
.magic-task-record .tip.error {
	color: #EB2013;
}
.magic-task-record .empty {
	padding: 20px;
	text-align: center;
	color: #888;
}
.magic-task-record table {
	width: 100%;
	border-collapse: collapse;
}
.magic-task-record th,
.magic-task-record td {
	padding: 4px 8px;
	text-align: left;
	border-bottom: 1px solid #eee;
	white-space: nowrap;
}
.magic-task-record td.msg {
	max-width: 400px;
	overflow: hidden;
	text-overflow: ellipsis;
	color: #EB2013;
}
.magic-task-record td.ok {
	color: #0cbb52;
}
.magic-task-record td.error {
	color: #EB2013;
}
</style>
