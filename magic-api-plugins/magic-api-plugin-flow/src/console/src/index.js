import MagicFlow from './service/magic-flow.js'
import { setContext } from './service/context.js'
import localZhCN from './i18n/zh-cn.js'
import localEn from './i18n/en.js'
import MagicFlowInfo from './components/magic-flow-info.vue'
import MagicFlowDebug from './components/magic-flow-debug.vue'
import MagicFlowStatus from './components/magic-flow-status.vue'
import MagicFlowSettings from './components/magic-flow-settings.vue'
import MagicFlowCanvas from './components/magic-flow-canvas.vue'
import 'vite-plugin-svg-icons/register'

export default (opt) => {
    const i18n = opt.i18n
    i18n.add('zh-cn', localZhCN)
    i18n.add('en', localEn)
    setContext(opt)
    return {
        // 左侧资源：类型与后端存储目录（flow）一致；编辑区为流程画布
        resource: [{
            type: 'flow',
            icon: '#magic-flow-flow',
            title: 'flow.name',
            service: MagicFlow(opt.bus, opt.constants, i18n.format, opt.Message, opt.request, MagicFlowCanvas),
        }],
        // 底部工具条（编辑器按倒序插入，所以这里倒着写：显示顺序为 信息、调试运行、运行状态、集成配置）
        toolbars: [{
            id: 'flow-settings',
            type: 'flow',
            title: 'flow.settings.title',
            icon: 'list',
            component: MagicFlowSettings,
        }, {
            id: 'flow-status',
            type: 'flow',
            title: 'flow.status.title',
            icon: 'history',
            component: MagicFlowStatus,
        }, {
            id: 'flow-debug',
            type: 'flow',
            title: 'flow.debug.title',
            icon: 'run',
            component: MagicFlowDebug,
        }, {
            type: 'flow',
            title: 'flow.title',
            icon: 'parameter',
            component: MagicFlowInfo,
        }]
    }
}
