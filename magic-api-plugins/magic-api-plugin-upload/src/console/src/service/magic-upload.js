export default function (bus, constants, $i, Message, request) {
    return {
        getIcon: item => [(item.protocol || 'http').toUpperCase(), '#0E9F6E'],
        name: $i('upload.name'),
        language: 'magicscript',
        defaultScript: `// 报文转换（可选，留空则直接发送本批数据）
// 可用变量：rows 本批数据；batch 批次信息（jobName、index、size、attempt）；options 通道参数
// 修改 headers、params 可追加请求头与查询参数，例如 headers['X-Sign'] = ...
// 返回值为请求体：对象按 JSON（或表单）发送，字符串原样发送；WebService 通道需返回 XML 字符串
return rows`,
        // 测试：用已保存的通道配置发送一行样例数据
        doTest: (opened) => {
            const info = opened.item
            const fullName = opened.path()
            opened.running = true
            bus.status($i('upload.test.start', fullName))
            // 不传自定义配置，沿用编辑器默认的请求设置（登录 token、参数编码）
            request.sendPost('/upload/channel/test', { id: info.id }).success(res => {
                const result = res || {}
                const text = result.success
                    ? $i('upload.test.success', result.costMillis)
                    : $i('upload.test.failure', result.message || '')
                bus.status(text)
                if (!result.success) {
                    console.warn('[magic-upload] 通道测试失败', result)
                }
            }).end(() => {
                opened.running = false
            })
        },
        runnable: true,
        requirePath: false,
        merge: item => {
            item.protocol = item.protocol || 'http'
            item.options = item.options || {}
            return item
        }
    }
}
