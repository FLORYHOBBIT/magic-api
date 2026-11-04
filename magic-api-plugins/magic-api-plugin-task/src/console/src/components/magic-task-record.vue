<template>
	<div class="magic-task-record">
		<div class="bar">
			<button class="btn" @click="refresh">刷新</button>
			<span class="tip" :class="tipType">{{ tip }}</span>
		</div>
		<div v-if="!currentId" class="empty">保存后可查看执行记录</div>
		<div v-else-if="records.length === 0" class="empty">暂无执行记录</div>
		<table v-else>
			<thead>
				<tr><th>时间</th><th>来源</th><th>结果</th><th>耗时</th><th>说明</th><th>日志</th></tr>
			</thead>
			<tbody>
				<tr v-for="(r, i) in records" :key="i">
					<td>{{ time(r.startTime) }}</td>
					<td>{{ sourceName(r.source) }}</td>
					<td :class="r.success ? 'ok' : 'error'">{{ r.success ? '成功' : '失败' }}</td>
					<td>{{ r.costMillis }} ms</td>
					<td class="msg" :title="r.message || ''">{{ r.message || '' }}</td>
					<td><button v-if="r.logAvailable" class="btn" @click="showRecordLog(currentId, r.runId)">查看日志</button><span v-else>未采集</span></td>
				</tr>
			</tbody>
		</table>
	</div>
</template>
<script setup>
import { computed, inject, onMounted, onUnmounted, ref, unref, watch } from 'vue'
import { fetchRecords, showRecordLog } from '../service/record.js'

const info = inject('info')
const opened = inject('opened')
const records = ref([])
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
	fetchRecords(id).then(list => {
		if (version !== sequence || id !== currentId.value) return
		records.value = list
		tip.value = ''
	}).catch(message => {
		if (version !== sequence || id !== currentId.value) return
		tip.value = message
		tipType.value = 'error'
	}).finally(() => {
		if (pendingId === id) pendingId = ''
	})
}

watch(currentId, () => { sequence++; pendingId = ''; records.value = []; refresh() }, { immediate: true })
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
