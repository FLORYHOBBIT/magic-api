import MagicFlow from './service/magic-flow.js'
import localZhCN from './i18n/zh-cn.js'
import localEn from './i18n/en.js'
import MagicFlowInfo from './components/magic-flow-info.vue'
import MagicFlowDebug from './components/magic-flow-debug.vue'
import 'vite-plugin-svg-icons/register'

export default (opt) => {
    const i18n = opt.i18n
    i18n.add('zh-cn', localZhCN)
    i18n.add('en', localEn)
    return {
        // 左侧资源：类型与后端存储目录（flow）一致
        resource: [{
            type: 'flow',
            icon: '#magic-flow-flow',
            title: 'flow.name',
            service: MagicFlow(opt.bus, opt.constants, i18n.format, opt.Message, opt.request),
        }],
        // 底部工具条（编辑器按倒序插入，所以"信息"写在最后显示在最前）
        toolbars: [{
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
