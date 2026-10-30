# magic-api-plugin-flow 集成流程

在 magic-api 编辑器中新增「集成流程」资源：在画布上用节点和连线配置接口，不写或少写脚本。支持四类接口：

| 场景 | 起始节点 | 说明 |
| --- | --- | --- |
| 对外 HTTP 接口 | HTTP 接口 | 地址 = `magic-api.prefix` + `magic-api.flow.http-prefix` + 分组路径 + 流程路径 |
| 对外 WebService | WebService | 地址 = `magic-api.prefix` + `/ws/服务名`，`?wsdl` 自动生成 WSDL |
| 定时推送/上传并回写 | 定时 | 分批读取 → 组报文 → 调用外部系统（HTTP / WebService / 文件 / FTP / SFTP）→ 成功/失败回写 |
| 表对表同步 | 定时 | 增量读取源库 → 写入目标表（有则更新、无则插入） |

流程和接口、函数一样存放在 magic-api 工作目录（`flow/` 下），参与 magic-api 的导入导出、备份、Git 同步。

## 引入

```xml
<dependency>
    <groupId>org.ssssssss</groupId>
    <artifactId>magic-api-plugin-flow</artifactId>
    <version>2.2.2</version>
</dependency>
```

依赖 `magic-api-plugin-upload`（外部连接、分批引擎、断点与运行记录），会一并引入。

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `magic-api.flow.enable` | `true` | 关闭插件 |
| `magic-api.flow.http-prefix` | 空 | 对外 HTTP 接口前缀 |
| `magic-api.flow.soap-prefix` | `/ws` | 对外 WebService 前缀 |
| `magic-api.flow.scheduler-pool-size` | `4` | 定时流程线程数 |
| `magic-api.flow.variables-file` | `./magic-flow/variables.json` | 差异项保存位置（不在工作目录，不随配置导出） |
| `magic-api.flow.backup-dir` | `./magic-flow/backups` | 导入配置包前的全量备份目录 |
| `magic-api.flow.vars.名称` | | 差异项的默认值 |
| `magic-api.flow.record-size` | `50` | 每个流程在内存中保留的最近运行记录 |
| `magic-api.upload.secret-key` | | 外部连接密码、差异项密文的加密密钥，**生产环境必须配置** |

## 编辑器

- **画布**：左侧节点面板（点击或拖入），从节点右侧蓝点拖出成功/条件连线，从底部红点拖出失败连线；选中节点或连线在右侧配置，Delete 删除；滚轮缩放、拖动空白处平移；「JSON 源码」可直接编辑定义。
- **从模板生成**：HTTP 查询接口、WebService 服务、定时上传并回写、表对表同步，填几个参数生成流程骨架。
- **执行测试**（Ctrl+Q）：用编辑器中的定义（含未保存修改）真实执行一次，**会访问数据库、调用外部系统**。节点上显示执行顺序与耗时，底部「调试运行」可看每个节点的输出。
- **集成流程信息**：启用（启用后才注册 HTTP / WebService / 定时）、名称、路径、说明。
- **运行状态**：已注册的地址或 cron、注册失败原因（如路径冲突）、最近运行记录；定时流程另有断点、执行记录、失败批次，可「立即执行」「补传失败批次」「清除断点」。
- **集成配置**：差异项维护、配置包导出与导入。

## 流程定义

```json
{
  "nodes": [
    {"id": "start", "type": "http", "config": {"method": "GET"}},
    {"id": "q", "type": "query", "config": {"sql": "select * from his_patient where patient_id = #{input.patientId}"}},
    {"id": "end", "type": "return", "config": {"value": "{code: 0, data: q}"}}
  ],
  "edges": [{"source": "start", "target": "q"}, {"source": "q", "target": "end"}]
}
```

- 起始节点：没有入线的节点，必须唯一；触发节点（HTTP / WebService / 定时）只能是起始节点。没有触发节点的流程只能调试运行。
- 连线 `condition`：空 = 默认连线；`failure` = 节点出错时走（变量 `error`）；其他为表达式，按顺序第一个为真的生效，都不命中走默认连线。每个节点最多一条默认、一条失败连线。
- 返回节点执行后流程结束；没有返回节点时返回最后一个节点的输出。单次最多 10000 步。

### 变量

| 变量 | 说明 |
| --- | --- |
| `input` | 触发输入。HTTP：参数 + JSON 报文字段，`input.body` 原始报文，`input.xml` XML 报文解析结果，`input.headers`（小写），`input.clientIp`；WebService：各参数（字符串）；定时：`input.rows` 本批数据、`input.batch` |
| `prev` | 上一个节点的输出 |
| 节点 id | 节点输出，如 `q1[0].name`；id 不是合法变量名时用 `nodes['id']` |
| `vars` | 流程变量，脚本节点可写入 |
| `error` | 失败连线中：`{node, name, message, output}`，`output` 为出错节点的输出（如外部系统回执） |
| `row` / `index` / `item` | 逐行执行时的当前行与序号 |

### 节点

| 类型 | 名称 | 主要配置 |
| --- | --- | --- |
| `http` | HTTP 接口 | `method`、`auth`（none / token）+ `tokenHeader` + `token`、`allowIps`（支持 `192.168.1.*`）、`contentType`（auto / json / xml / text）、`errorStatus` |
| `soap` | WebService | `service`、`operation`、`params`（逗号分隔，均为字符串；参数内是未转义的 XML 时取原文）、`resultName`、`namespace`。同一 service 的流程合并为一个服务；返回对象时转为 XML 文本；失败返回 SOAP Fault |
| `timer` | 定时 | `cron`（6 位）、`mode`：`once` 每次执行一次 / `pending` 每次读取全部待处理数据（适合"查未上传 → 上传 → 回写标志"）/ `keyset` 按递增字段增量、断点保留 / `offset` 分页读取全部；`sql`、`datasource`、`keyColumn`、`orderBy`、`batchSize`、`retry`、`retryInterval`、`failurePolicy`（stop / skip）、`maxBatches`、`columnCase` |
| `query` | 查库 | `sql`、`datasource`、`result`（list / one / value / count）、`columnCase`（默认 lower） |
| `write` | 写库 | `sql`、`forEach`、`transaction`（默认整批事务）、`datasource`；输出 `{affected, executed}` |
| `save` | 写入表 | `table`、`rows`、`mode`（upsert / insert / update / replace）、`keys`、`columns`、`exclude`、`transaction`、`datasource`；输出 `{inserted, updated, deleted, total}` |
| `map` | 字段映射 | `source`、`fields: [{name, value, type, format, default}]`（type：string / number / int / date / boolean）、`keepOthers` |
| `nest` | 主从组装 | `master`、`children: [{name, source, masterKey, childKey, single, removeKey}]` |
| `template` | 报文模板 | `format`（json / xml / text）、`template`：`{{表达式}}` 转义输出，`{{{表达式}}}` 原样，`{{#each list as r}}…{{/each}}`，`{{#if 条件}}…{{else}}…{{/if}}` |
| `xml` | XML 转换 | `action`（parse / build）、`source`、`path`、`root`、`itemName`、`declaration`。解析禁止外部实体 |
| `json` | JSON 转换 | `action`（parse / stringify）、`source`、`pretty` |
| `validate` | 数据校验 | `rules: [{field, rule, value, message}]`（required / maxLength / regex / expr）、`mode`（split / fail）；输出 `{valid, invalid, total, invalidCount}` |
| `call` | 调用外部系统 | `channel`（外部连接名称）、`body`、`headers` / `params`（值中可用 `{{表达式}}`）、`forEach`、`retry`、`retryInterval`、`cacheSeconds` + `cacheKey`（登录取令牌）；输出 `{success, message, request, response, data, retryable}` |
| `script` | 脚本 | `script`，magic-script，可用 db、http、log 等模块 |
| `return` | 返回 | `value` 表达式 |

### 外部连接

调用节点使用上传插件的「上传通道」作为外部连接：地址、超时、固定请求头、签名器（`UploadSigner` Bean）、成功判定（`successField` / `successValues` / `messageField`）、密码加密都在连接中配置。调用节点不执行连接的报文转换脚本，报文由流程生成。

网络错误、超时、HTTP 5xx 为可重试失败；业务拒绝（成功字段不符、4xx、SOAP Client Fault）不重试。

## 定时上传的运行治理

复用上传插件的分批引擎：任务锁（多实例只有一个在跑）、断点、执行记录、失败批次、告警（`magic-api.upload.alert.*`）。

- 每批执行一次触发节点之后的流程。流程成功 = 本批成功、断点前进；流程失败 = 本批失败，按 `retry` 重试，`failurePolicy=stop` 停在这一批（下次从这里继续），`skip` 记录失败批次后继续，之后在「运行状态」中补传。
- 调用节点接了失败连线并完成回写时，流程算成功（失败已记录在业务表中，按回写标志下次重传）。
- 任务名为 `flow:` + 流程路径，修改流程路径相当于新任务。

## 差异项与配置包

各医院不同的值（对方地址、账号、机构编码、令牌等）写成 `${var.名称}` 或 `${var.名称:默认值}`，可用于节点配置、触发配置、外部连接参数。取值顺序：差异项文件 → `magic-api.flow.vars.名称` → 默认值，都没有时运行报错并提示名称。

**导出**（集成配置 → 导出配置包）：流程及其引用的外部连接打成 zip。连接的 `url`、`host`、`port`、`username`、`dir` 和密码、密钥替换为差异项 `${var.连接名.参数名}`，密码不出包。清单列出所有差异项及说明、示例值。

**导入**（集成配置 → 导入配置包）：选择 zip 后预览将新建/覆盖的流程和连接，逐项填写本院的差异项（已配置的可留空），缺项不导入。导入前自动全量备份工作目录（`backup-dir`，开启了 magic-api 备份时同时写备份记录）。同 id 或同路径的流程、同名的连接覆盖，分组按路径查找或创建。

## 验收示例

[`examples/insur-3501.json`](examples/insur-3501.json) 用流程复刻了 `magic-scripts/insur_trace/upload_3501.ms`（医保追溯码商品盘存上传）：待上传数据逐行组报文、签名、上传，成功回写状态 1，失败回写状态 0 并累计失败次数、下次重传。原脚本中 `case_interface_config` 的配置改为差异项 `gdyb.*`。`FlowAcceptanceTest` 对照脚本版验证报文字段、签名、回写和重传行为。

签名需要几行脚本（`script` 节点）；更通用的做法是实现 `UploadSigner` Bean 并在外部连接中配置 `signer`。

## 扩展节点类型

实现 `org.ssssssss.magicapi.flow.engine.FlowNodeHandler` 并注册为 Spring Bean；同名类型时 `@Order` 靠前的生效。需要出错时也留下输出，抛出 `FlowNodeException`。画布对未内置的类型以 JSON 编辑配置。

## 错误码

| 代码 | 含义 |
| --- | --- |
| 5001–5006 | 流程定义为空、JSON 格式错误、节点 id 缺失或重复、缺少类型、连线指向不存在的节点 |
| 5007 | 找不到流程 |
| 5008 | 节点类型不支持 |
| 5009 | 起始节点不唯一 |
| 5010 | 执行步数超限（可能成环） |
| 5011 / 5013 | 节点缺少必填配置 / 配置值不正确 |
| 5012 | 同一节点有多条默认连线或失败连线 |
| 5014 | 触发节点不是起始节点 |
| 5015 / 5016 | 差异项未配置 / 名称不合法 |
| 5017 | 不是定时流程 |
| 5018 | 配置包不正确 |
| 5019 | 找不到场景模板 |
| 5020 / 5021 | HTTP 地址冲突 / WebService 方法重复 |
