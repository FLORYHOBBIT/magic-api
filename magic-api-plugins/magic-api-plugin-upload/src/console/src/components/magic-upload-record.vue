<template>
	<div class="magic-upload-record">
		<div class="bar">
			<button class="btn" @click="refresh">刷新</button>
			<span class="tip" :class="tipType">{{ tip }}</span>
		</div>
		<div v-if="!currentId" class="empty">保存后可查看执行记录</div>
		<div v-else-if="records.length === 0" class="empty">暂无执行记录</div>
		<table v-else>
			<thead>
				<tr><th>时间</th><th>来源</th><th>结果</th><th>耗时</th><th>说明</th></tr>
			</thead>
			<tbody>
				<tr v-for="(r, i) in records" :key="i">
					<td>{{ time(r.startTime) }}</td>
					<td>{{ sourceName(r.source) }}</td>
					<td :class="r.success ? 'ok' : 'error'">{{ r.success ? '成功' : '失败' }}</td>
					<td>{{ r.costMillis }} ms</td>
					<td class="msg" :title="r.message || ''">{{ r.message || '' }}</td>
				</tr>
			</tbody>
		</table>
	</div>
</template>
<script setup>
import { computed, inject, onMounted, onUnmounted, ref, unref, watch } from 'vue'
import { fetchRecords } from '../service/record.js'

const info = inject('info')
const records = ref([])
const tip = ref('')
const tipType = ref('')
const currentId = computed(() => (unref(info) && unref(info).id) || '')
let timer = null

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
	if (!currentId.value) {
		records.value = []
		return
	}
	fetchRecords(currentId.value).then(list => {
		records.value = list
		tip.value = ''
	}).catch(message => {
		tip.value = message
		tipType.value = 'error'
	})
}

watch(currentId, refresh, { immediate: true })
onMounted(() => {
	timer = setInterval(refresh, 10000)
})
onUnmounted(() => {
	clearInterval(timer)
})
</script>
<style scoped>
.magic-upload-record {
	display: flex;
	flex-direction: column;
	flex: 1;
	padding: 5px;
	overflow: auto;
}
.magic-upload-record .bar {
	display: flex;
	align-items: center;
	gap: 10px;
	padding-bottom: 5px;
}
.magic-upload-record .tip.error {
	color: #EB2013;
}
.magic-upload-record .empty {
	padding: 20px;
	text-align: center;
	color: #888;
}
.magic-upload-record table {
	width: 100%;
	border-collapse: collapse;
}
.magic-upload-record th,
.magic-upload-record td {
	padding: 4px 8px;
	text-align: left;
	border-bottom: 1px solid #eee;
	white-space: nowrap;
}
.magic-upload-record td.msg {
	max-width: 400px;
	overflow: hidden;
	text-overflow: ellipsis;
	color: #EB2013;
}
.magic-upload-record td.ok {
	color: #0cbb52;
}
.magic-upload-record td.error {
	color: #EB2013;
}
</style>
