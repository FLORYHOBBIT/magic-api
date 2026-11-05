import MagicFlow from './service/magic-flow.js'
import { setContext } from './service/context.js'
import localZhCN from './i18n/zh-cn.js'
import localEn from './i18n/en.js'
import MagicFlowInfo from './components/magic-flow-info.vue'
import MagicFlowDebug from './components/magic-flow-debug.vue'
import MagicFlowStatus from './components/magic-flow-status.vue'
import MagicFlowSettings from './components/magic-flow-settings.vue'
import MagicFlowMsg from './components/magic-flow-msg.vue'
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
        // 基础信息置首，其余工具条按声明顺序插在 TODO 前。
        toolbars: [{
            id: 'flow-info',
            position: 'first',
            type: 'flow',
            title: 'flow.title',
            icon: 'parameter',
            component: MagicFlowInfo,
        }, {
            id: 'flow-debug',
            type: 'flow',
            title: 'flow.debug.title',
            icon: 'run',
            component: MagicFlowDebug,
        }, {
            id: 'flow-status',
            type: 'flow',
            title: 'flow.status.title',
            icon: 'history',
            component: MagicFlowStatus,
        }, {
            id: 'flow-msg',
            type: 'flow',
            title: 'flow.msg.title',
            icon: 'event',
            component: MagicFlowMsg,
        }, {
            id: 'flow-settings',
            type: 'flow',
            title: 'flow.settings.title',
            icon: 'list',
            component: MagicFlowSettings,
        }]
    }
}
