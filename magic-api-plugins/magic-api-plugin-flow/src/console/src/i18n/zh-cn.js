export default {
    flow: {
        title: '集成流程信息',
        name: '集成流程',
        status: { title: '运行状态' },
        msg: { title: '消息中心' },
        settings: { title: '集成配置' },
        form: {
            name: '流程名称',
            path: '流程路径',
            placeholder: {
                name: '请输入流程名称',
                path: '请输入流程路径',
                description: '请输入流程说明'
            }
        },
        debug: {
            title: '调试运行',
            input: '调试输入（JSON，流程中用 input 引用）',
            inputPlaceholder: '{"patientId": "1001"}',
            inputInvalid: '调试输入不是合法的 JSON：{0}',
            empty: '点击"执行测试"运行当前流程（会真实访问数据库）',
            start: '开始调试运行集成流程「{0}」',
            success: '运行成功，耗时 {0} ms',
            failure: '运行失败，耗时 {0} ms：{1}',
            node: '节点',
            type: '类型',
            status: '状态',
            duration: '耗时',
            next: '下一节点'
        }
    }
}
