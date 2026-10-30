// 节点类型目录：画布左侧的节点面板与右侧的配置表单都由这里生成。
// 字段 type：text（默认）/ textarea / code / select / checkbox / kv（键值对象）/ list（对象列表，items 为子字段）
// / action（按钮，buttons: [{label, run(config) => 提示文字}]）/ preview（只读，preview(config) => Promise<文字>）
// show(config) 返回 false 时隐藏该字段；help 为字段下方的提示。

import { call } from './context.js'

const yesNo = [['', '默认'], ['true', '是'], ['false', '否']]

export const GROUPS = [
    { name: '触发', types: ['http', 'soap', 'timer', 'msg'] },
    { name: '数据', types: ['query', 'write', 'save'] },
    { name: '报文', types: ['map', 'nest', 'template', 'xml', 'json', 'validate'] },
    { name: '调用', types: ['call', 'proc'] },
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
    msg: {
        label: '消息', color: '#0D9488', trigger: true,
        description: '接收院内系统推送的消息：先入库、立即回执，再由本流程异步处理（一种消息类型对应一个流程）。' +
            'input.msgId 为消息 id，input.payload 为原始报文，input.body / input.xml 为解析后的 JSON / XML，input.attempt 为第几次处理。' +
            '只有消息 id 时，后面接「调用外部系统」或「查库」取详情。消息记录在底部「消息中心」查看。',
        fields: [
            { key: 'type', label: '消息类型', placeholder: 'lis.result', help: '字母、数字、点、横线；HTTP 地址为 POST /msg/类型' },
            {
                key: 'receive', label: '接收方式', type: 'select', default: 'http', options: [
                    ['http', 'HTTP + 通用 WebService'],
                    ['soap', '自定义 WebService 方法（兼容老系统）']
                ], help: '通用 WebService 为 /ws/msg 的 sendMessage(type, msgId, payload)'
            },
            { key: 'service', label: '服务名', placeholder: 'HIP', show: c => c.receive === 'soap' },
            { key: 'operation', label: '方法名', placeholder: 'HIPMessageServer', show: c => c.receive === 'soap' },
            { key: 'params', label: '参数名', placeholder: 'action,message', show: c => c.receive === 'soap', help: '逗号分隔，最后一个参数作为报文' },
            { key: 'typeFrom', label: '区分类型', placeholder: "'hip.' + input.action", show: c => c.receive === 'soap', help: '多个类型共用一个方法时填写，表达式结果为消息类型' },
            { key: 'namespace', label: '命名空间', show: c => c.receive === 'soap' },
            { key: 'msgId', label: '消息 id', placeholder: 'input.msgId', help: "表达式，如 input.xml.Request.Header.MsgId、input.headers['x-msg-id']" },
            { key: 'auth', label: '鉴权', type: 'select', options: [['none', '不鉴权'], ['token', '请求头令牌']], default: 'none' },
            { key: 'tokenHeader', label: '令牌请求头', placeholder: 'X-Token', show: c => c.auth === 'token' },
            { key: 'token', label: '令牌', placeholder: '${var.消息令牌}', show: c => c.auth === 'token' },
            { key: 'allowIps', label: '允许的 IP', placeholder: '192.168.1.*', help: '为空不限制' },
            { key: 'reply', label: '回执模板', type: 'code', rows: 3, placeholder: '为空：{"code":0,"message":"已接收","msgId":...}', help: '可用 msgId、type、duplicate（重复消息）' },
            { key: 'replyFormat', label: '回执格式', type: 'select', options: [['auto', '自动（HTTP 为 JSON，WebService 为 XML）'], ['json', 'JSON'], ['xml', 'XML'], ['text', '文本']], default: 'auto' },
            { key: 'concurrency', label: '并发数', placeholder: '2' },
            { key: 'maxRetries', label: '失败重试次数', placeholder: '5', help: '用完转死信，可在消息中心人工重试' },
            { key: 'backoff', label: '重试等待（秒）', placeholder: '60,300,1800', help: '逗号分隔，依次使用，超出时用最后一个' },
            {
                key: 'order', label: '顺序消费', type: 'select', default: 'none', options: [
                    ['none', '不要求顺序'],
                    ['type', '整个类型按接收顺序逐条处理'],
                    ['key', '按分组键（组内有序、组间并发）']
                ]
            },
            { key: 'orderKey', label: '分组键', placeholder: 'input.body.patientId', show: c => c.order === 'key' },
            { key: 'onDead', label: '前一条成为死信时', type: 'select', options: [['block', '阻塞，等人工重试或忽略'], ['skip', '跳过，继续后面的']], default: 'block', show: c => c.order && c.order !== 'none' },
            {
                key: 'callback', label: '处理完回调', type: 'select', default: 'none', options: [
                    ['none', '不回调'], ['success', '成功时'], ['dead', '成为死信时'], ['both', '成功和死信时']
                ]
            },
            { key: 'callbackChannel', label: '回调连接', placeholder: '外部连接名称', show: c => c.callback && c.callback !== 'none' },
            { key: 'callbackFormat', label: '回调格式', type: 'select', options: ['json', 'xml', 'text'], default: 'json', show: c => c.callback && c.callback !== 'none' },
            { key: 'callbackBody', label: '回调报文', type: 'code', rows: 4, show: c => c.callback && c.callback !== 'none', help: '模板，可用 msgId、type、status、result（流程返回值）、error、body、xml' },
            { key: 'callbackDeadBody', label: '死信回调报文', type: 'code', rows: 3, show: c => c.callback === 'both', help: '为空用回调报文' },
            { key: 'callbackHeaders', label: '回调请求头', type: 'kv', show: c => c.callback && c.callback !== 'none' },
            { key: 'callbackRetries', label: '回调重试次数', placeholder: '5', show: c => c.callback && c.callback !== 'none' },
            { key: 'callbackBackoff', label: '回调重试等待（秒）', placeholder: '60,300,1800', show: c => c.callback && c.callback !== 'none' },
            { key: 'alertDead', label: '死信告警', type: 'checkbox' },
            { key: 'alertBacklog', label: '积压告警（条）', placeholder: '0 不告警' },
            { key: 'alertDelay', label: '延迟告警（分钟）', placeholder: '0 不告警', help: '最早一条待处理消息超过该时间' },
            { key: 'alertCallback', label: '回调失败告警', type: 'checkbox', show: c => c.callback && c.callback !== 'none' },
            { key: 'alertBlocked', label: '顺序阻塞告警', type: 'checkbox', show: c => c.order && c.order !== 'none' }
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
    proc: {
        label: '调用存储过程', color: '#9333EA',
        description: '调用数据库存储过程或函数（Oracle 11g 及以上）。输出为出参：节点id.参数名；函数返回值为 节点id.return，游标出参为行列表。' +
            '返回节点写 p1.出参 可原样返回报文，写 {code: 0, data: p1} 为包装后的 JSON。',
        fields: [
            { key: 'datasource', label: '数据源', placeholder: '空为主数据源' },
            { key: 'mode', label: '调用方式', type: 'select', default: 'form', options: [['form', '表单（过程名 + 参数表）'], ['sql', '手写语句']] },
            { key: 'name', label: '过程名', placeholder: 'pkg_name.proc_name', show: c => c.mode !== 'sql', help: '过程、包.过程 或 用户.包.过程' },
            {
                key: 'sql', label: '调用语句', type: 'code', rows: 4, show: c => c.mode === 'sql',
                placeholder: 'begin pkg.proc(:as_method, :ab_in_parm, :rb_out_parm); end;',
                help: '用 :参数名 引用参数表；同一参数出现多次时每处单独绑定，块内要复用请先赋给局部变量'
            },
            {
                key: '_args', type: 'action', show: c => c.mode !== 'sql', buttons: [{ label: '读取参数', run: readProcArguments }],
                help: '按数据库中的参数定义填写参数表，已填写的值保留'
            },
            {
                key: 'params', label: '参数', type: 'list', items: [
                    { key: 'name', label: '参数名', width: '22%' },
                    { key: 'mode', label: '方向', type: 'select', options: [['in', '入参'], ['out', '出参'], ['inout', '出入参']], width: '13%' },
                    { key: 'type', label: '类型', type: 'select', options: ['varchar', 'number', 'integer', 'date', 'timestamp', 'clob', 'blob', 'cursor'], width: '16%' },
                    { key: 'value', label: '值（表达式）', placeholder: "input.xxx、'常量'", width: '28%' },
                    { key: 'parse', label: '出参处理', type: 'select', options: [['string', '字符串'], ['json', 'JSON'], ['xml', 'XML']], width: '13%' }
                ],
                help: '表单方式下值为空的入参不传（使用过程默认值）；要传空值请写 null。出参处理对字符串 / CLOB 出参生效'
            },
            { key: 'returnType', label: '函数返回类型', type: 'select', show: c => c.mode !== 'sql', options: [['', '不是函数'], 'varchar', 'number', 'integer', 'date', 'timestamp', 'clob', 'cursor'] },
            { key: 'returnParse', label: '返回值处理', type: 'select', show: c => c.mode !== 'sql' && c.returnType, options: [['string', '字符串'], ['json', 'JSON'], ['xml', 'XML']] },
            { key: 'columnCase', label: '游标列名', type: 'select', options: ['lower', 'camel', 'upper', 'raw'], default: 'lower' },
            { key: 'timeout', label: '超时（秒）', placeholder: '0 不限制' },
            { key: '_preview', label: '生成的语句', type: 'preview', preview: previewProc }
        ]
    },    script: {
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

// 「调用存储过程」：读取参数定义填写参数表（保留已填的值与出参处理）
async function readProcArguments(config) {
    if (!config.name || !String(config.name).trim()) {
        throw '请先填写过程名'
    }
    const overloads = await call('get', '/flow/proc/arguments', { datasource: config.datasource || '', name: config.name })
    const args = overloads[0] || []
    const old = {}
    ;(Array.isArray(config.params) ? config.params : []).forEach(p => { if (p && p.name) old[String(p.name).toLowerCase()] = p })
    const params = []
    const unsupported = []
    let returnType = ''
    args.forEach(a => {
        if (a.position === 0) {
            returnType = a.type || ''
            if (!a.type) unsupported.push('返回值 ' + a.dataType)
            return
        }
        if (!a.type) {
            unsupported.push(a.name + ' ' + a.dataType)
        }
        const prev = old[a.name] || {}
        const row = { name: a.name, mode: a.mode, type: a.type || 'varchar' }
        if (a.mode !== 'out' && prev.value !== undefined && prev.value !== '') row.value = prev.value
        if (a.mode !== 'in') row.parse = prev.parse || 'string'
        params.push(row)
    })
    config.params = params
    if (returnType) config.returnType = returnType
    else delete config.returnType
    const notes = ['读取到 ' + params.length + ' 个参数' + (returnType ? '（函数，返回 ' + returnType + '）' : '')]
    if (overloads.length > 1) notes.push('该过程有 ' + overloads.length + ' 个重载，已按第一个填写')
    if (unsupported.length) notes.push('不支持的类型：' + unsupported.join('、') + '，请改用手写语句')
    const defaulted = args.filter(a => a.defaulted && a.mode === 'in').map(a => a.name)
    if (defaulted.length) notes.push('有默认值（不填则不传）：' + defaulted.join('、'))
    return notes.join('；')
}

function previewProc(config) {
    return call('json', '/flow/proc/preview', config)
}