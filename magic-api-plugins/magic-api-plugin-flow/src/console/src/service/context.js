// 插件加载时保存编辑器提供的 request / bus / Message，供工具条组件使用
const context = { request: null, bus: null, Message: null }

export function setContext(opt) {
    context.request = opt.request
    context.bus = opt.bus
    context.Message = opt.Message
}

export default context

// 编辑器 request 的 Promise 包装：成功返回 data，失败 reject(message)
export function call(method, url, data) {
    return new Promise((resolve, reject) => {
        let req
        if (method === 'json') {
            req = context.request.sendJson(url, data)
        } else if (method === 'get') {
            req = context.request.sendGet(url, data)
        } else {
            req = context.request.sendPost(url, data)
        }
        let done = false
        req.success(res => {
            done = true
            resolve(res)
        })
        req.exception((code, message) => {
            done = true
            reject(message || ('错误码 ' + code))
        })
        req.error(data => {
            done = true
            reject((data && data.message) || '网络错误')
        })
        req.end(() => {
            if (!done) {
                reject('请求失败')
            }
        })
    })
}
