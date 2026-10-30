<template>
	<div class="mf-msg">
		<div class="mf-msg-bar">
			<select v-model="query.type" @change="search(1)">
				<option value="">全部类型</option>
				<option v-for="t in types" :key="t.type" :value="t.type">{{ t.type }}</option>
			</select>
			<select v-model="query.status" @change="search(1)">
				<option value="">全部状态</option>
				<option v-for="s in STATUS" :key="s[0]" :value="s[0]">{{ s[1] }}</option>
			</select>
			<select v-model="query.cbStatus" @change="search(1)">
				<option value="">回调不限</option>
				<option v-for="s in CB_STATUS" :key="s[0]" :value="s[0]">回调{{ s[1] }}</option>
			</select>
			<input v-model="query.msgId" placeholder="消息 id" @keyup.enter="search(1)"/>
			<input v-model="query.from" placeholder="接收起 yyyy-MM-dd" class="date" @keyup.enter="search(1)"/>
			<input v-model="query.to" placeholder="接收止" class="date" @keyup.enter="search(1)"/>
			<button class="mf-btn" @click="search(1)">查询</button>
			<span class="sep"></span>
			<button class="mf-btn" :disabled="!checked.length" @click="batch('/flow/msg/retry', '重新处理')">重新处理</button>
			<button class="mf-btn" :disabled="!checked.length" @click="batch('/flow/msg/ignore', '忽略')">忽略</button>
			<button class="mf-btn" :disabled="!checked.length" @click="batch('/flow/msg/callback', '重新回调')">重新回调</button>
			<span class="mf-tip" :class="tipType">{{ tip }}</span>
		</div>
		<div class="mf-msg-types">
			<span v-if="!types.length" class="muted">还没有消息类型：新建流程，起始节点选「消息」并启用</span>
			<span v-for="t in types" :key="t.type" class="mf-type" :class="{ active: query.type === t.type }" @click="pick(t.type)"
				  :title="t.url ? '接收地址：POST ' + t.url : ''">
				<b>{{ t.type }}</b>
				<span v-if="!t.flowName" class="error">无流程</span>
				<span v-else class="muted">{{ t.flowName }}</span>
				<span v-if="t.configError" class="error">配置错误</span>
				<span :class="t.backlog ? 'warn' : 'muted'">积压 {{ t.backlog }}</span>
				<span v-if="t.processing" class="muted">处理中 {{ t.processing }}</span>
				<span v-if="t.dead" class="error">死信 {{ t.dead }}</span>
				<span v-if="t.callbackFailed" class="error">回调失败 {{ t.callbackFailed }}</span>
				<span v-if="t.order && t.order !== 'none'" class="muted">顺序</span>
			</span>
		</div>
		<div class="mf-msg-body">
			<div class="mf-msg-list">
				<table>
					<thead>
					<tr>
						<th><input type="checkbox" :checked="rows.length && checked.length === rows.length" @change="toggleAll($event.target.checked)"/></th>
						<th>接收时间</th><th>类型</th><th>消息 id</th><th>状态</th><th>次数</th><th>耗时</th><th>回调</th><th>分组</th><th>说明</th>
					</tr>
					</thead>
					<tbody>
					<tr v-for="r in rows" :key="r.id" :class="{ selected: detail && detail.id === r.id }" @click="open(r)">
						<td @click.stop><input type="checkbox" :value="r.id" v-model="checked"/></td>
						<td>{{ time(r.receiveTime) }}</td>
						<td>{{ r.type }}</td>
						<td>{{ r.msgId }}</td>
						<td :class="statusClass(r.status)">{{ label(STATUS, r.status) }}</td>
						<td>{{ r.attempts }}</td>
						<td>{{ r.costMs ? r.costMs + 'ms' : '' }}</td>
						<td :class="statusClass(r.cbStatus)">{{ r.cbStatus ? label(CB_STATUS, r.cbStatus) : '' }}</td>
						<td>{{ r.orderKey && r.orderKey !== '*' ? r.orderKey : '' }}</td>
						<td class="msg">{{ r.status === 'RETRY' && r.nextTime ? time(r.nextTime) + ' 重试；' : '' }}{{ r.error || r.cbError || '' }}</td>
					</tr>
					</tbody>
				</table>
				<div v-if="!rows.length" class="muted pad">没有消息</div>
				<div class="mf-pager">
					共 {{ total }} 条
					<button class="mf-btn" :disabled="page <= 1" @click="search(page - 1)">上一页</button>
					第 {{ page }} 页
					<button class="mf-btn" :disabled="page * size >= total" @click="search(page + 1)">下一页</button>
				</div>
			</div>
			<div v-if="detail" class="mf-msg-detail">
				<div class="head">
					<b>{{ detail.type }} / {{ detail.msgId }}</b>
					<span :class="statusClass(detail.status)">{{ label(STATUS, detail.status) }}</span>
					<span class="muted">第 {{ detail.attempts }} 次 · 来源 {{ detail.clientIp || '-' }}</span>
				</div>
				<div v-if="detail.error" class="error">出错节点 {{ detail.errorNode || '-' }}：{{ detail.error }}</div>
				<div class="label">报文</div>
				<pre>{{ pretty(detail.payload) || '（无报文）' }}</pre>
				<template v-if="trace.length">
					<div class="label">最后一次失败的执行轨迹</div>
					<table class="trace">
						<thead><tr><th>#</th><th>节点</th><th>类型</th><th>状态</th><th>耗时</th><th>说明</th></tr></thead>
						<tbody>
						<tr v-for="(t, i) in trace" :key="i" :class="t.success === false ? 'error' : ''">
							<td>{{ i + 1 }}</td><td>{{ t.name || t.nodeId }}</td><td>{{ t.type }}</td><td>{{ t.success === false ? '失败' : '成功' }}</td><td>{{ t.duration }}ms</td>
							<td class="msg">{{ t.error || '' }}</td>
						</tr>
						</tbody>
					</table>
				</template>
				<template v-if="detail.result">
					<div class="label">流程返回</div>
					<pre>{{ pretty(detail.result) }}</pre>
				</template>
				<template v-if="detail.cbStatus">
					<div class="label">回调：<span :class="statusClass(detail.cbStatus)">{{ label(CB_STATUS, detail.cbStatus) }}</span>（{{ detail.cbAttempts }} 次，{{ time(detail.cbTime) }}）</div>
					<div v-if="detail.cbError" class="error">{{ detail.cbError }}</div>
					<pre v-if="detail.cbResponse">{{ pretty(detail.cbResponse) }}</pre>
				</template>
				<div class="label">附加信息（参数、请求头）</div>
				<pre>{{ pretty(detail.meta) }}</pre>
			</div>
		</div>
	</div>
</template>
<script setup>
import { inject, reactive, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { call } from '../service/context.js'

const STATUS = [['PENDING', '待处理'], ['PROCESSING', '处理中'], ['RETRY', '待重试'], ['SUCCESS', '成功'], ['DEAD', '死信'], ['IGNORED', '已忽略']]
const CB_STATUS = [['PENDING', '待回调'], ['SENDING', '回调中'], ['RETRY', '待重试'], ['SUCCESS', '成功'], ['FAILED', '失败']]

const info = inject('info')
const types = ref([])
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const checked = ref([])
const detail = ref(null)
const trace = ref([])
const tip = ref('')
const tipType = ref('')
const query = reactive({ type: '', status: '', cbStatus: '', msgId: '', from: '', to: '' })

const label = (list, value) => (list.find(it => it[0] === value) || [value, value])[1]
const statusClass = s => s === 'SUCCESS' ? 'ok' : (s === 'DEAD' || s === 'FAILED') ? 'error' : (s === 'RETRY' || s === 'PENDING') ? 'warn' : ''
const time = value => {
	if (!value) {
		return ''
	}
	const d = new Date(value)
	const p = n => String(n).padStart(2, '0')
	return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
const pretty = text => {
	if (!text) {
		return ''
	}
	try {
		return JSON.stringify(JSON.parse(text), null, 2)
	} catch (e) {
		return text
	}
}
const fail = message => {
	tip.value = message
	tipType.value = 'error'
}

const loadTypes = () => call('get', '/flow/msg/stats').then(data => {
	types.value = data.types || []
}).catch(fail)

const search = p => {
	page.value = p || page.value
	checked.value = []
	return call('get', '/flow/msg/list', { ...query, page: page.value, size }).then(data => {
		rows.value = data.rows || []
		total.value = data.total || 0
	}).catch(fail)
}

const pick = type => {
	query.type = query.type === type ? '' : type
	search(1)
}

const open = row => call('get', '/flow/msg/detail', { id: row.id }).then(data => {
	detail.value = data
	try {
		trace.value = data.trace ? JSON.parse(data.trace) : []
	} catch (e) {
		trace.value = []
	}
}).catch(fail)

const refresh = () => Promise.all([loadTypes(), search()]).then(() => {
	if (detail.value) {
		open(detail.value)
	}
})

const batch = (url, name) => {
	if (name === '忽略' && !window.confirm(`忽略选中的 ${checked.value.length} 条消息？忽略后不再处理（顺序消费时后面的消息继续处理）。`)) {
		return
	}
	call('post', url, { ids: checked.value.join(',') }).then(count => {
		tip.value = `${name}：${count} 条`
		tipType.value = 'ok'
		setTimeout(refresh, 600)
	}).catch(fail)
}

const toggleAll = on => {
	checked.value = on ? rows.value.map(r => r.id) : []
}

// 打开的是消息流程时，默认只看该类型
watch(() => info.value && info.value.script, script => {
	try {
		const def = script ? JSON.parse(script) : null
		const start = def && (def.nodes || []).find(n => n.type === 'msg')
		const type = start && start.config && start.config.type
		if (type && type !== query.type && !String(type).includes('${')) {
			query.type = type
			search(1)
		}
	} catch (e) {
		// 定义未保存或格式错误时不处理
	}
}, { immediate: true })

let timer = null
onMounted(() => {
	refresh()
	timer = setInterval(() => {
		if (document.visibilityState === 'visible') {
			loadTypes()
		}
	}, 10000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>
<style scoped>
.mf-msg { display: flex; flex-direction: column; height: 100%; overflow: hidden; font-size: 12px; }
.mf-msg-bar { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; padding: 4px 6px; }
.mf-msg-bar select, .mf-msg-bar input { height: 22px; font-size: 12px; border: 1px solid var(--magic-input-border-color, #bbb); background: transparent; color: inherit; padding: 0 4px; }
.mf-msg-bar input { width: 130px; }
.mf-msg-bar input.date { width: 110px; }
.mf-msg-bar .sep { width: 10px; }
.mf-btn { padding: 2px 10px; font-size: 12px; cursor: pointer; border: 1px solid var(--magic-input-border-color, #bbb); background: transparent; color: inherit; border-radius: 2px; }
.mf-btn:disabled { opacity: .5; cursor: default; }
.mf-msg-types { display: flex; flex-wrap: wrap; gap: 6px; padding: 0 6px 4px; }
.mf-type { display: inline-flex; gap: 6px; padding: 1px 8px; border: 1px solid var(--magic-input-border-color, #ccc); border-radius: 10px; cursor: pointer; }
.mf-type.active { border-color: #2563EB; background: rgba(37, 99, 235, .1); }
.mf-msg-body { flex: 1; display: flex; overflow: hidden; }
.mf-msg-list { flex: 3; overflow: auto; padding: 0 6px; }
.mf-msg-detail { flex: 2; overflow: auto; padding: 0 8px; border-left: 1px solid var(--magic-input-border-color, #ddd); }
.mf-msg-detail .head { display: flex; gap: 10px; align-items: baseline; margin: 4px 0; }
.mf-msg-detail .label { margin: 6px 0 2px; font-weight: bold; }
.mf-msg-detail pre { margin: 0; max-height: 180px; overflow: auto; white-space: pre-wrap; word-break: break-all; font-family: Consolas, monospace; background: rgba(127, 127, 127, .08); padding: 4px; }
table { border-collapse: collapse; width: 100%; }
th, td { text-align: left; padding: 2px 6px; white-space: nowrap; }
td.msg { white-space: normal; word-break: break-all; }
tbody tr { cursor: pointer; }
tbody tr.selected { background: rgba(37, 99, 235, .12); }
.mf-pager { display: flex; gap: 8px; align-items: center; padding: 4px 0; }
.ok { color: #0E9F6E; }
.error { color: #E02424; }
.warn { color: #D97706; }
.muted { color: #999; }
.pad { padding: 6px; }
.mf-tip { margin-left: 8px; }
</style>
