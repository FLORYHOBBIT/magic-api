export default {
    upload: {
        title: '上传通道参数',
        name: '上传通道',
        form: {
            protocol: '协议',
            name: '通道名称',
            description: '说明',
            placeholder: {
                name: '脚本中用 uploader.job(..).channel("通道名称") 引用',
                description: '例如：省全民健康信息平台-门诊数据',
                json: 'JSON 对象，例如 {"X-Token":"abc"}'
            }
        },
        test: {
            start: '开始测试上传通道「{0}」（使用已保存的配置）',
            success: '上传通道测试成功，耗时 {0} ms',
            failure: '上传通道测试失败：{0}'
        },
        tip: '密码、密钥保存后以 ENC(...) 密文显示；不修改则保持原值，重新填写会覆盖。测试使用已保存的配置。'
    }
}
