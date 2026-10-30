import { debugState, saveDebugInput } from './debug-state.js'

// 新建流程时的默认定义：HTTP 触发 + 返回，打开后在画布上编辑
const DEFAULT_DEFINITION = JSON.stringify({
    nodes: [
        { id: 'start', type: 'http', name: 'HTTP 接口', config: { method: 'POST', auth: 'none' }, ui: { x: 80, y: 120 } },
        { id: 'end', type: 'return', name: '返回', config: { value: "{code: 0, data: input}" }, ui: { x: 340, y: 120 } }
    ],
    edges: [
        { source: 'start', target: 'end' }
    ]
}, null, 2)

export default function (bus, constants, $i, Message, request, canvas) {
    return {
        getIcon: item => ['FLOW', '#0E9F6E'],
        name: $i('flow.name'),
        language: 'json',
        // 编辑区使用流程画布组件（画布内可切换 JSON 源码）
        pageType: 'component',
        component: canvas,
        defaultScript: DEFAULT_DEFINITION,
        // "执行测试"：用编辑器中的定义（含未保存修改）调试运行，结果显示在"调试运行"工具条和画布节点上
        doTest: (opened) => {
            const fullName = opened.path()
            const state = debugState(opened.item.id)
            let input = (state.input || '').trim() || '{}'
            try {
                JSON.parse(input)
            } catch (e) {
                bus.status($i('flow.debug.inputInvalid', e.message), false)
                bus.$emit(Message.SWITCH_TOOLBAR, 'flow-debug')
                return
            }
            saveDebugInput(opened.item.id, input)
            opened.running = true
            bus.status($i('flow.debug.start', fullName))
            request.sendPost('/flow/debug', { id: opened.item.id, definition: opened.item.script, input }).success(result => {
                state.result = result
                bus.status(result.success
                    ? $i('flow.debug.success', result.duration)
                    : $i('flow.debug.failure', result.duration, result.message || ''), result.success)
                bus.$emit(Message.SWITCH_TOOLBAR, 'flow-debug')
            }).end(() => {
                opened.running = false
            })
        },
        runnable: true,
        requirePath: true,
        merge: item => item
    }
}
