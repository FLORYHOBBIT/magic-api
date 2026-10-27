import MagicUpload from './service/magic-upload.js'
import localZhCN from './i18n/zh-cn.js'
import localEn from './i18n/en.js'
import MagicUploadChannelInfo from './components/magic-upload-channel-info.vue'
import 'vite-plugin-svg-icons/register'

export default (opt) => {
    const i18n = opt.i18n
    i18n.add('zh-cn', localZhCN)
    i18n.add('en', localEn)
    return {
        // 左侧资源：类型与后端存储目录一致
        resource: [{
            type: 'upload-channel',
            icon: '#magic-upload-upload',
            title: 'upload.name',
            service: MagicUpload(opt.bus, opt.constants, i18n.format, opt.Message, opt.request),
        }],
        // 打开上传通道时，底部显示通道参数表单
        toolbars: [{
            type: 'upload-channel',
            title: 'upload.title',
            icon: 'parameter',
            component: MagicUploadChannelInfo,
        }]
    }
}
