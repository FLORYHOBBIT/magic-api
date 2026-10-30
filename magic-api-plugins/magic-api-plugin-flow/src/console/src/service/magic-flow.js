import { debugState, saveDebugInput } from './debug-state.js'

// 新建流程时的默认定义：一个脚本节点 + 返回节点。
// Task 4 之前流程以 JSON 文本编辑，之后由画布生成。
const DEFAULT_DEFINITION = JSON.stringify({
    nodes: [
        { id: 'start', type: 'script', name: '开始', config: { script: "return 'Hello ' + (input.name || 'magic-api-flow')" } },
        { id: 'end', type: 'return', name: '返回', config: { value: 'start' } }
    ],
    edges: [
        { source: 'start', target: 'end' }
    ]
}, null, 2)

export default function (bus, constants, $i, Message, request) {
    return {
        getIcon: item => ['FLOW', '#0E9F6E'],
        name: $i('flow.name'),
        // 编辑区先用 JSON 编辑器
        language: 'json',
        defaultScript: DEFAULT_DEFINITION,
        // "执行测试"：用编辑器中的定义（含未保存修改）调试运行，结果显示在"调试运行"工具条
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
