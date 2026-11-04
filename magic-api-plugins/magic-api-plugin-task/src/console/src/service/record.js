let request = null
let bus = null
let Message = null

export function setRecordRequest(value, eventBus, messages) {
    request = value
    bus = eventBus
    Message = messages
}

export function showRecordLog(id, runId) {
    bus.$emit(Message.SWITCH_TOOLBAR, 'log')
    bus.$emit('execution-log:select', { id, runId })
}

export function fetchRecords(id) {
    return new Promise((resolve, reject) => {
        let done = false
        const req = request.sendGet('/execution-records', { id })
        req.success(res => {
            done = true
            resolve(res || [])
        })
        req.exception((code, message) => {
            done = true
            reject(message || ('错误 ' + code))
        })
        req.error(data => {
            done = true
            reject((data && data.message) || '请求错误')
        })
        req.end(() => {
            if (!done) {
                reject('请求失败')
            }
        })
    })
}
