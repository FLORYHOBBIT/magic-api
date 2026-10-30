// 节点类型目录：画布左侧的节点面板与右侧的配置表单都由这里生成。
// 字段 type：text（默认）/ textarea / code / select / checkbox / kv（键值对象）/ list（对象列表，items 为子字段）
// show(config) 返回 false 时隐藏该字段；help 为字段下方的提示。

const yesNo = [['', '默认'], ['true', '是'], ['false', '否']]

export const GROUPS = [
    { name: '触发', types: ['http', 'soap', 'timer'] },
    { name: '数据', types: ['query', 'write', 'save'] },
    { name: '报文', types: ['map', 'nest', 'template', 'xml', 'json', 'validate'] },
    { name: '调用', types: ['call'] },
    { name: '控制', types: ['script', 'return'] }
]

export const NODE_TYPES = {
    http: {
        label: 'HTTP 接口', color: '#2563EB', trigger: true,
        description: '发布为 HTTP 接口，地址为 分组路径/流程路径。input 为参数与报文，input.body 为原始报文，input.headers 为请求头。',
        fields: [
            { key: 'method', label: '请求方法', type: 'select', options: ['GET', 'POST', 'PUT', 'DELETE'], default: 'POST' },
            { key: 'auth', label: '鉴权', type: 'select', options: [['none', '不鉴权'], ['token', '请求头令牌']], default: 'none' },
            { key: 'tokenHeader', label: '令牌请求头', placeholder: 'X-Token', show: c => c.auth === 'token' },
            { key: 'token', label: '令牌', placeholder: '${var.接口令牌}', show: c => c.auth === 'token', help: '建议写成差异项，各医院分别填写' },
            { key: 'allowIps', label: '允许的 IP', placeholder: '192.168.1.*, 10.0.0.5', help: '为空不限制' },
            { key: 'contentType', label: '响应格式', type: 'select', options: [['auto', '自动'], ['json', 'JSON'], ['xml', 'XML'], ['text', '文本']], default: 'auto' },
            { key: 'errorStatus', label: '失败状态码', placeholder: '500' }
        ]
    },
    soap: {
        label: 'WebService', color: '#7C3AED', trigger: true,
        description: '发布为 WebService 方法，同一服务名的流程合并为一个服务，WSDL 为 /ws/服务名?wsdl。参数为字符串，用 input.参数名 读取。',
        fields: [
            { key: 'service', label: '服务名', placeholder: 'HisService' },
            { key: 'operation', label: '方法名', placeholder: 'queryPatient' },
            { key: 'params', label: '参数名', placeholder: 'message', help: '逗号分隔' },
            { key: 'resultName', label: '返回值元素', placeholder: 'return' },
            { key: 'namespace', label: '命名空间', placeholder: 'http://flow.magicapi.ssssssss.org/服务名' }
        ]
    },
    timer: {
        label: '定时', color: '#0891B2', trigger: true,
        description: '按 cron 定时执行。读取数据时每批执行一次后续节点，input.rows 为本批数据，input.batch 为批次信息。',
        fields: [
            { key: 'cron', label: '执行时间', placeholder: '0 0/10 * * * ?', help: '6 位 cron：秒 分 时 日 月 周' },
            {
                key: 'mode', label: '读取方式', type: 'select', default: 'once', options: [
                    ['once', '不读取，每次执行一次'],
                    ['pending', '待处理数据（按标志查询，每次全部）'],
                    ['keyset', '增量（按递增字段，断点保留）'],
                    ['offset', '分页（按排序读取全部）']
                ]
            },
            { key: 'sql', label: '查询 SQL', type: 'code', show: c => c.mode && c.mode !== 'once' },
            { key: 'datasource', label: '数据源', placeholder: '空为主数据源', show: c => c.mode && c.mode !== 'once' },
            { key: 'keyColumn', label: '唯一递增字段', placeholder: 'id', show: c => c.mode === 'keyset' || c.mode === 'pending' },
            { key: 'orderBy', label: '排序字段', placeholder: 'dept, id desc', show: c => c.mode === 'offset' },
            { key: 'batchSize', label: '每批条数', placeholder: '500', show: c => c.mode && c.mode !== 'once' },
            { key: 'retry', label: '失败重试次数', placeholder: '0' },
            { key: 'retryInterval', label: '重试间隔（秒）', placeholder: '5' },
            { key: 'failurePolicy', label: '批次失败时', type: 'select', options: [['stop', '停止，下次从这批继续'], ['skip', '记录后跳过，之后补传']], default: 'stop' },
            { key: 'maxBatches', label: '单次最多批数', placeholder: '0 不限' },
            { key: 'columnCase', label: '列名格式', type: 'select', options: ['lower', 'camel', 'upper', 'raw'], default: 'lower' }
        ]
    },
    query: {
        label: '查库', color: '#059669',
        description: '执行查询 SQL。参数写 #{input.xxx}、#{节点id.字段}，支持 magic-api 的 SQL 语法。',
        fields: [
            { key: 'sql', label: 'SQL', type: 'code' },
            { key: 'datasource', label: '数据源', placeholder: '空为主数据源' },
            { key: 'result', label: '结果', type: 'select', options: [['list', '多行'], ['one', '第一行'], ['value', '第一行第一列'], ['count', '整数']], default: 'list' },
            { key: 'columnCase', label: '列名格式', type: 'select', options: ['lower', 'camel', 'upper', 'raw'], default: 'lower' }
        ]
    },
    write: {
        label: '写库', color: '#16A34A',
        description: '执行 insert / update / delete 语句；逐行执行时用 #{row.字段}。',
        fields: [
            { key: 'sql', label: 'SQL', type: 'code' },
            { key: 'forEach', label: '逐行执行', placeholder: '如 input.rows、q1', help: '表达式，为空执行一次' },
            { key: 'transaction', label: '整批事务', type: 'select', options: yesNo },
            { key: 'datasource', label: '数据源', placeholder: '空为主数据源' }
        ]
    },
    save: {
        label: '写入表', color: '#15803D',
        description: '按字段写入目标表（不写 SQL），用于表对表同步。字段名取自行的键。',
        fields: [
            { key: 'table', label: '目标表', placeholder: 'schema.table' },
            { key: 'rows', label: '数据', placeholder: 'prev', help: '表达式，默认上一个节点的输出' },
            { key: 'mode', label: '方式', type: 'select', options: [['upsert', '有则更新，无则插入'], ['insert', '插入'], ['update', '更新'], ['replace', '先删后插']], default: 'upsert' },
            { key: 'keys', label: '主键字段', placeholder: 'id', help: '逗号分隔' },
            { key: 'columns', label: '只写字段', placeholder: '为空写全部' },
            { key: 'exclude', label: '不写字段' },
            { key: 'transaction', label: '整批事务', type: 'select', options: yesNo },
            { key: 'datasource', label: '目标数据源', placeholder: '空为主数据源' }
        ]
    },
    map: {
        label: '字段映射', color: '#D97706',
        description: '把每行转换为对方要求的字段名、类型和格式。值为表达式，可用 row、index。',
        fields: [
            { key: 'source', label: '数据', placeholder: 'prev' },
            {
                key: 'fields', label: '字段', type: 'list', items: [
                    { key: 'name', label: '目标字段', width: '22%' },
                    { key: 'value', label: '值（表达式）', placeholder: 'row.字段', width: '32%' },
                    { key: 'type', label: '类型', type: 'select', options: ['', 'string', 'number', 'int', 'date', 'boolean'], width: '14%' },
                    { key: 'format', label: '格式', placeholder: 'yyyy-MM-dd', width: '16%' },
                    { key: 'default', label: '默认值', width: '16%' }
                ]
            },
            { key: 'keepOthers', label: '保留其他字段', type: 'checkbox' }
        ]
    },
    nest: {
        label: '主从组装', color: '#B45309',
        description: '把主表行与子表行按关联字段组装为嵌套结构。',
        fields: [
            { key: 'master', label: '主表数据', placeholder: 'prev' },
            {
                key: 'children', label: '子表', type: 'list', items: [
                    { key: 'name', label: '子节点名', width: '20%' },
                    { key: 'source', label: '子表数据', placeholder: 'q2', width: '22%' },
                    { key: 'masterKey', label: '主表字段', width: '20%' },
                    { key: 'childKey', label: '子表字段', width: '20%' },
                    { key: 'single', label: '单条', type: 'checkbox', width: '9%' },
                    { key: 'removeKey', label: '去掉关联', type: 'checkbox', width: '9%' }
                ]
            }
        ]
    },
    template: {
        label: '报文模板', color: '#C2410C',
        description: '按模板生成报文：{{表达式}} 输出值（自动转义），{{#each list as r}}…{{/each}} 循环，{{#if 条件}}…{{else}}…{{/if}} 条件。',
        fields: [
            { key: 'format', label: '格式', type: 'select', options: ['json', 'xml', 'text'], default: 'json' },
            { key: 'template', label: '模板', type: 'code', rows: 14 }
        ]
    },
    xml: {
        label: 'XML 转换', color: '#9A3412',
        description: '解析 XML 为对象，或把对象生成 XML。',
        fields: [
            { key: 'action', label: '操作', type: 'select', options: [['parse', '解析 XML'], ['build', '生成 XML']], default: 'parse' },
            { key: 'source', label: '来源', placeholder: 'prev' },
            { key: 'path', label: '取元素', placeholder: 'Envelope.Body.response', show: c => c.action !== 'build' },
            { key: 'root', label: '根元素', show: c => c.action === 'build' },
            { key: 'itemName', label: '列表项元素', placeholder: 'item', show: c => c.action === 'build' },
            { key: 'declaration', label: '输出声明', type: 'select', options: yesNo, show: c => c.action === 'build' }
        ]
    },
    json: {
        label: 'JSON 转换', color: '#9A3412',
        description: '解析 JSON 文本，或把对象转为 JSON 文本。',
        fields: [
            { key: 'action', label: '操作', type: 'select', options: [['parse', '解析 JSON'], ['stringify', '生成 JSON']], default: 'parse' },
            { key: 'source', label: '来源', placeholder: 'prev' },
            { key: 'pretty', label: '格式化', type: 'checkbox', show: c => c.action === 'stringify' }
        ]
    },
    validate: {
        label: '数据校验', color: '#BE123C',
        description: '上传前检查必填、长度、格式，输出 valid（合格行）和 invalid（不合格行及原因）。',
        fields: [
            { key: 'source', label: '数据', placeholder: 'prev' },
            {
                key: 'rules', label: '规则', type: 'list', items: [
                    { key: 'field', label: '字段', width: '22%' },
                    { key: 'rule', label: '规则', type: 'select', options: ['required', 'maxLength', 'regex', 'expr'], width: '18%' },
                    { key: 'value', label: '值', placeholder: '长度/正则/表达式', width: '30%' },
                    { key: 'message', label: '提示', width: '30%' }
                ]
            },
            { key: 'mode', label: '有不合格时', type: 'select', options: [['split', '分开，继续'], ['fail', '节点失败']], default: 'split' }
        ]
    },
    call: {
        label: '调用外部系统', color: '#DB2777',
        description: '通过外部连接（数据上传 → 上传通道）发送报文。输出 success、message、response、data（回执解析结果）。',
        fields: [
            { key: 'channel', label: '外部连接', placeholder: '上传通道名称' },
            { key: 'body', label: '请求体', type: 'code', rows: 4, placeholder: 'prev', help: '表达式；字符串原样发送，对象按 JSON 发送' },
            { key: 'headers', label: '请求头', type: 'kv', help: '值中可用 {{表达式}}' },
            { key: 'params', label: '查询参数', type: 'kv' },
            { key: 'forEach', label: '逐条发送', placeholder: '如 input.rows', help: '为空发送一次；逐条时 body 中可用 item、index' },
            { key: 'retry', label: '失败重试次数', placeholder: '0', help: '只重试网络错误、超时、5xx' },
            { key: 'retryInterval', label: '重试间隔（毫秒）', placeholder: '1000' },
            { key: 'cacheSeconds', label: '结果缓存（秒）', placeholder: '0', help: '用于登录取令牌' },
            { key: 'cacheKey', label: '缓存键' }
        ]
    },
    script: {
        label: '脚本', color: '#475569',
        description: '执行 magic-script，返回值为节点输出。只用于表单节点覆盖不到的处理。',
        fields: [
            { key: 'script', label: '脚本', type: 'code', rows: 14 }
        ]
    },
    return: {
        label: '返回', color: '#334155', terminal: true,
        description: '结束流程并返回结果（对外接口的响应内容）。',
        fields: [
            { key: 'value', label: '返回值', type: 'code', rows: 4, placeholder: 'prev', help: '表达式，如 {code: 0, data: q1}' }
        ]
    }
}

export function nodeType(type) {
    return NODE_TYPES[type] || { label: type, color: '#64748B', description: '自定义节点，配置以 JSON 编辑', fields: [], custom: true }
}

export function defaultConfig(type) {
    const config = {}
    nodeType(type).fields.forEach(f => {
        if (f.default !== undefined) {
            config[f.key] = f.default
        }
    })
    return config
}
