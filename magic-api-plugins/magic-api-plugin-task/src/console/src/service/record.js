let request = null

export function setRecordRequest(value) {
    request = value
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
