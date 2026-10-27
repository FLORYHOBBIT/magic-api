<template>
	<div class="magic-upload-channel-info" v-if="item">
		<form>
			<label>{{ $i('upload.form.protocol') }}</label>
			<magic-select v-model:value="item.protocol" :options="protocols" width="110px"/>
			<label>{{ $i('upload.form.name') }}</label>
			<magic-input v-model:value="item.name" :placeholder="$i('upload.form.placeholder.name')" width="260px"/>
			<label>{{ $i('upload.form.description') }}</label>
			<magic-input v-model:value="item.description" :placeholder="$i('upload.form.placeholder.description')" width="auto" style="flex:1"/>
		</form>
		<div class="fields">
			<div class="field" v-for="field in fields" :key="item.protocol + field.key">
				<label :title="field.key">{{ field.label }}</label>
				<magic-input v-model:value="item.options[field.key]" :placeholder="field.placeholder || ''" width="auto" style="flex:1"/>
			</div>
		</div>
		<div class="tip">{{ $i('upload.tip') }}</div>
	</div>
</template>
<script setup>
import { inject, computed, unref, watch } from 'vue'

const $i = inject('i18n.format')
const info = inject('info')
const item = computed(() => unref(info))

// 确保旧数据或新建资源有默认结构
watch(item, value => {
	if (value) {
		if (!value.protocol) value.protocol = 'http'
		if (!value.options) value.options = {}
	}
}, { immediate: true })

const protocols = [
	{ value: 'http', text: 'HTTP' },
	{ value: 'soap', text: 'WebService' },
	{ value: 'file', text: '文件目录' },
	{ value: 'ftp', text: 'FTP' },
	{ value: 'sftp', text: 'SFTP' }
]

const timeouts = [
	{ key: 'connectTimeout', label: '连接超时(ms)', placeholder: '10000' },
	{ key: 'readTimeout', label: '读取超时(ms)', placeholder: '60000' }
]
const success = [
	{ key: 'successField', label: '成功字段', placeholder: '如 code、result.code；WebService 用 XML 路径如 return/code' },
	{ key: 'successValues', label: '成功值', placeholder: '多个用逗号分隔，如 0,200' },
	{ key: 'messageField', label: '错误信息字段', placeholder: '如 msg' }
]
const fileOptions = [
	{ key: 'format', label: '文件格式', placeholder: 'json 或 csv' },
	{ key: 'fileName', label: '文件名', placeholder: '{job}_{datetime}_{batch}.{ext}' },
	{ key: 'encoding', label: '文件编码', placeholder: 'UTF-8，对方要求时填 GBK' }
]
const FIELDS = {
	http: [
		{ key: 'url', label: '上传地址', placeholder: 'https://...' },
		{ key: 'method', label: '请求方法', placeholder: 'POST、PUT、PATCH' },
		{ key: 'contentType', label: '内容类型', placeholder: 'json 或 form' },
		{ key: 'headers', label: '请求头', placeholder: '{"X-Token":"abc"}' },
		{ key: 'params', label: '查询参数', placeholder: '{"orgCode":"H001"}' },
		...success,
		{ key: 'appSecret', label: '签名密钥', placeholder: '脚本中用 options.appSecret 读取，加密保存' },
		{ key: 'signer', label: '签名器', placeholder: '自定义 UploadSigner Bean 的名称' },
		...timeouts
	],
	soap: [
		{ key: 'url', label: '服务地址', placeholder: 'http://.../services/UploadService' },
		{ key: 'version', label: 'SOAP版本', placeholder: '1.1 或 1.2' },
		{ key: 'soapAction', label: 'SOAPAction', placeholder: '' },
		{ key: 'headers', label: '请求头', placeholder: '{"X-Token":"abc"}' },
		...success,
		{ key: 'appSecret', label: '签名密钥', placeholder: '脚本中用 options.appSecret 读取，加密保存' },
		...timeouts
	],
	file: [
		{ key: 'dir', label: '目录', placeholder: '如 D:/upload 或已挂载的共享目录' },
		...fileOptions
	],
	ftp: [
		{ key: 'host', label: '地址', placeholder: '' },
		{ key: 'port', label: '端口', placeholder: '21' },
		{ key: 'username', label: '用户名', placeholder: '' },
		{ key: 'password', label: '密码', placeholder: '加密保存' },
		{ key: 'dir', label: '远程目录', placeholder: '/upload' },
		{ key: 'passive', label: '被动模式', placeholder: 'true' },
		{ key: 'controlEncoding', label: '控制编码', placeholder: 'UTF-8，中文文件名乱码时填 GBK' },
		...fileOptions,
		...timeouts
	],
	sftp: [
		{ key: 'host', label: '地址', placeholder: '' },
		{ key: 'port', label: '端口', placeholder: '22' },
		{ key: 'username', label: '用户名', placeholder: '' },
		{ key: 'password', label: '密码', placeholder: '与私钥二选一，加密保存' },
		{ key: 'privateKey', label: '私钥', placeholder: 'PEM 内容，加密保存' },
		{ key: 'passphrase', label: '私钥口令', placeholder: '加密保存' },
		{ key: 'knownHosts', label: 'known_hosts', placeholder: 'known_hosts 文件路径（校验服务器身份）' },
		{ key: 'strictHostKey', label: '校验主机', placeholder: 'true；仅在可信内网可填 false' },
		{ key: 'dir', label: '远程目录', placeholder: '/upload' },
		...fileOptions,
		...timeouts
	]
}

const fields = computed(() => FIELDS[(item.value && item.value.protocol) || 'http'] || FIELDS.http)
</script>
<style scoped>
.magic-upload-channel-info {
	display: flex;
	flex-direction: column;
	flex: 1;
	padding: 5px;
	overflow: auto;
}
.magic-upload-channel-info form {
	display: flex;
}
.magic-upload-channel-info label {
	display: inline-block;
	width: 90px;
	height: var(--magic-input-height);
	line-height: var(--magic-input-height);
	font-weight: 400;
	text-align: right;
	padding: 0 5px;
	white-space: nowrap;
	overflow: hidden;
	text-overflow: ellipsis;
}
.magic-upload-channel-info .fields {
	display: grid;
	grid-template-columns: repeat(auto-fill, minmax(420px, 1fr));
	padding-top: 5px;
}
.magic-upload-channel-info .field {
	display: flex;
	padding: 2px 0;
}
.magic-upload-channel-info .tip {
	padding: 5px 10px;
	color: #999;
}
</style>
