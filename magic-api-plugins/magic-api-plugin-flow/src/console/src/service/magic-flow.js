// 新建流程时的默认定义：一个起始的脚本节点 + 返回节点。
// Task 4 之前流程以 JSON 文本编辑，之后由画布生成。
const DEFAULT_DEFINITION = JSON.stringify({
    nodes: [
        { id: 'start', type: 'script', name: '开始', config: { script: "return 'Hello magic-api-flow'" } },
        { id: 'end', type: 'return', name: '返回', config: { value: '${start}' } }
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
        // "执行测试"：先做结构校验，Task 2 起改为调试运行并展示节点轨迹
        doTest: (opened) => {
            opened.running = true
            const fullName = opened.path()
            bus.status(`开始校验集成流程「${fullName}」`)
            request.sendPost('/flow/validate', { id: opened.item.id, definition: opened.item.script }).success(() => {
                bus.status(`集成流程「${fullName}」校验通过`)
            }).end(() => {
                opened.running = false
            })
        },
        runnable: true,
        requirePath: true,
        merge: item => item
    }
}
