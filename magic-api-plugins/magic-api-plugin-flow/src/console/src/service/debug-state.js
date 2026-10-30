import { reactive } from 'vue'

// 每个流程的调试输入与最近一次结果。不随流程保存，输入记在浏览器本地便于反复调试。
const states = reactive({})
const STORAGE_PREFIX = 'magic-flow-debug-input:'

export function debugState(id) {
    const key = id || '__new__'
    if (!states[key]) {
        let input = '{}'
        try {
            input = localStorage.getItem(STORAGE_PREFIX + key) || '{}'
        } catch (e) {
            // 浏览器禁用本地存储时忽略
        }
        states[key] = { input, result: null, selected: -1 }
    }
    return states[key]
}

export function saveDebugInput(id, input) {
    try {
        localStorage.setItem(STORAGE_PREFIX + (id || '__new__'), input || '{}')
    } catch (e) {
        // ignore
    }
}
