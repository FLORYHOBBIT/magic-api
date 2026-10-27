# magic-api-plugin-upload 数据上传插件

每家医院独立部署一套 magic-api 服务时，用同一个插件完成"定时把本院数据分批上传到各类外部平台"。
插件代码对所有医院通用，各医院之间的差异（查什么数据、传到哪里、报文格式、签名规则）全部写在
magic-api 界面里的**定时任务脚本**和**上传通道**中，不需要改 Java 代码。

能力概览：

- 分批读取（按递增字段只传新增数据，或分页全量），每批大小可配置
- 断点续传：中途失败后再次执行，从失败的那一批继续，已成功的批次不重传
- 每批失败自动重试（等待时间逐次翻倍）；重试后仍失败可选择停止或跳过（跳过的批次保存原始数据，之后补传）
- 同一任务不会同时执行两次（同一实例多线程、多实例部署都适用）
- 通道：HTTP（JSON/表单）、WebService（SOAP 1.1/1.2）、本地或共享目录、FTP、SFTP
- 上传记录、批次回执、失败批次落库；失败告警（日志、钉钉、企业微信、飞书、通用 Webhook，可扩展）
- 通道里的密码、密钥加密保存

## 1. 引入与配置

宿主工程在引入 `magic-api-spring-boot-starter` 的基础上增加：

```xml
<dependency>
    <groupId>org.ssssssss</groupId>
    <artifactId>magic-api-plugin-upload</artifactId>
    <version>2.2.2</version>
</dependency>
<!-- 定时触发使用 task 插件 -->
<dependency>
    <groupId>org.ssssssss</groupId>
    <artifactId>magic-api-plugin-task</artifactId>
    <version>2.2.2</version>
</dependency>
```

`application.yml`（除 `secret-key` 外都有默认值）：

```yaml
magic-api:
  upload:
    enable: true                 # 关闭插件
    secret-key: ${UPLOAD_SECRET} # 通道密码的加密密钥，生产环境必须配置，放在环境变量中，不要写进代码库
    datasource:                  # 断点表、记录表所在数据源 key，空 = 主数据源
    init-schema: true            # 首次使用时自动建表（Oracle、SQL Server）
    table-prefix: magic_upload_
    default-batch-size: 1000
    default-retry: 3
    retry-backoff: 1s            # 首次重试等待，之后每次翻倍，最长 1 分钟
    lock-timeout: 2h             # 任务锁过期时间，进程崩溃后锁最多保留这么久
    record-empty-runs: false     # 没有新数据时是否写执行记录（秒级、分钟级任务保持 false）
    alert:
      enable: true
      hospital: 省人民医院        # 显示在告警标题中
      silence: 10m               # 同一任务同类告警在静默期内只发一次
      alert-on-partial: true     # 有批次被跳过时是否告警
      webhook:
        url:                     # 配置后启用 Webhook 告警
        type: dingtalk           # dingtalk / wecom / feishu / generic
        secret:                  # 钉钉、飞书机器人的加签密钥
        timeout: 5s
```

**secret-key 注意事项**：没有配置时，通道中的密码以明文保存，启动时打印警告。配置后保存的密码显示为 `ENC(...)`。
更换 secret-key 后旧密文无法解密，需要在界面中重新填写密码。

## 2. 快速开始

在 magic-api 界面新建一个**定时任务**（task 插件），cron 填 `0 0 2 1 * *`（每月 1 日 02:00），脚本：

```javascript
import uploader

return uploader.job('outpatient-monthly')          // 任务名：断点、记录、任务锁都按它区分，同一份数据固定用同一个名字
    .source("select id, patient_name, dept, visit_time from outpatient where visit_time >= #{start}", 'id')
    .http({
        url: 'https://platform.example.gov.cn/api/outpatient',
        headers: {'X-Org-Code': 'H001'},
        successField: 'code', successValues: '0', messageField: 'msg'
    })
    .batchSize(500)
    .retry(3)
    .onFailure('skip')
    .run()
```

`#{start}` 取脚本中的同名变量（与 `db.select` 规则相同），也可以作为第三个参数传入：`.source(sql, 'id', {start: '2026-10-01'})`。

返回值包含 `status`（SUCCESS / PARTIAL / FAILED / SKIPPED）、`totalRows`、`successRows`、`failedBatches`、`message`、`startCheckpoint`、`endCheckpoint` 等。

### 调度频率示例（task 插件使用 6 位 cron，第 1 位是秒）

| 频率 | cron |
|---|---|
| 每 30 秒 | `*/30 * * * * *` |
| 每 5 分钟 | `0 */5 * * * *` |
| 每天 01:30 | `0 30 1 * * *` |
| 每月 1 日 02:00 | `0 0 2 1 * *` |

同一任务上一次还没执行完时，task 插件不会再次触发；多实例部署时由任务锁保证只有一个实例在执行，其余实例返回 `SKIPPED`。

## 3. 数据来源

| 写法 | 适用 | 断点 |
|---|---|---|
| `.source(sql, 'id')` | 有唯一且递增的字段（自增主键、序列、唯一流水号） | 跨次保留，下次只传新增数据 |
| `.page(sql, 'dept, id desc')` | 没有递增字段，例如月度统计结果 | 全部传完后清除，下次重新全量；中途失败时续传。SQL 或参数变化（如换月份）时自动从头开始 |
| `.data(list)` | 脚本中已准备好的数据（调用其他接口、加工后的结果） | 同上 |
| `.datasource('his')` | 从指定数据源读取，默认主数据源 | |

注意：

- 递增字段必须**唯一**。用更新时间这类可能重复的字段时，批次边界上值相同的行可能被漏掉。
- SQL 里写的 `ORDER BY` 和末尾分号会被自动去掉，排序由插件按递增字段或 `page()` 的排序字段添加。
- Oracle 返回的列名是大写（`ID`、`PATIENT_NAME`），报文转换里读取字段时请用大写，或在 SQL 中用双引号别名。
- `.maxBatches(n)` 限制一次最多上传 n 批，剩余数据下次继续（适合对方限流或希望错峰的情况）。

## 4. 上传通道

通道有两种用法：

1. **脚本内联**：`.http({...})`、`.soap({...})`、`.file({...})`、`.ftp({...})`、`.sftp({...})`，报文转换用 `.body(batch => ...)`。
2. **界面维护**：在编辑器左侧"上传通道"中新建，保存后脚本里用 `.channel('通道名称')` 引用。密码加密保存，
   可以点"测试"用已保存的配置发送一行样例数据。**推荐生产环境使用这种方式**，避免密码写在脚本里。

### 公共参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| connectTimeout | 10000 | 连接超时，毫秒 |
| readTimeout | 60000 | 读取超时，毫秒 |
| signer | | 自定义签名器 Bean 的名称（见第 7 节） |

### HTTP

| 参数 | 默认值 | 说明 |
|---|---|---|
| url | 必填 | |
| method | POST | POST、PUT、PATCH |
| contentType | json | json 或 form |
| headers / params | | 请求头、查询参数；界面中填 JSON 字符串 |
| successField | | 业务结果字段路径，如 `code`、`result.code`、`data.0.status`；不填只看 HTTP 状态码 |
| successValues | | 成功值，多个用逗号分隔 |
| messageField | | 失败时读取错误信息的字段 |
| retryOnBusinessFailure | false | 业务返回失败时是否重试 |

成功判定：HTTP 2xx（配置了 successField 时还要求字段值在成功值中）。超时、连接失败、408、429、5xx 自动重试；
其他 4xx 和业务失败默认不重试，因为重试也不会成功。

### WebService（SOAP）

| 参数 | 默认值 | 说明 |
|---|---|---|
| url | 必填 | 服务地址（不含 `?wsdl`） |
| version | 1.1 | 1.1 或 1.2 |
| soapAction | | |
| successField | | XML 路径，不含命名空间前缀，如 `return/code`；结果放在节点里的 JSON/XML 字符串中时用 `return#code` |

报文转换必须返回 XML 字符串：可以是完整的 SOAP Envelope，也可以只返回 Body 里的内容（自动包装）。
SOAP Fault 的 faultcode 为 Client/Sender 时不重试，其他重试。

```javascript
return uploader.job('yb-settle')
    .source('select * from settle_list', 'SETTLE_ID')
    .soap({url: 'http://10.1.1.1:8080/services/UploadService', soapAction: 'upload',
           successField: 'return#code', successValues: '0', messageField: 'return#msg'})
    .body(batch => {
        var items = batch.rows.map(r => `<item><id>${r.SETTLE_ID}</id><amt>${r.AMOUNT}</amt></item>`).join('')
        return `<ns:upload xmlns:ns="http://ws.yb.gov/"><orgCode>H001</orgCode><data><![CDATA[<list>${items}</list>]]></data></ns:upload>`
    })
    .run()
```

### 文件目录、FTP、SFTP

每批生成一个文件，先写临时文件再改名，对方不会读到写了一半的文件。同一批重试时文件名不变（覆盖上一次写入）。

| 参数 | 默认值 | 说明 |
|---|---|---|
| format | json | json 或 csv |
| fileName | `{job}_{datetime}_{batch}.{ext}` | 可用 `{job}` `{batch}`（4 位序号）`{date}` `{datetime}` `{ext}` |
| encoding | UTF-8 | 对方要求时改为 GBK |
| csvHeader | true | CSV 是否输出表头 |
| dir | | 文件目录：本地/共享目录必填；FTP/SFTP 为远程目录，不存在时自动创建 |
| host / port / username / password | | FTP 端口默认 21，SFTP 默认 22 |
| passive / controlEncoding | true / UTF-8 | FTP 被动模式；中文文件名乱码时控制编码改为 GBK |
| privateKey / passphrase | | SFTP 私钥（PEM 内容）与口令，可代替密码 |
| knownHosts / strictHostKey | / true | SFTP 默认校验服务器主机密钥，需要提供 known_hosts 文件路径；只有在可信内网才设置 strictHostKey=false |

`.body()` 返回字符串时作为文件内容原样写入，返回列表时按 format 输出。

### 报文转换与签名

内联写法中 `.body(batch => ...)` 的参数包含 `rows`、`jobName`、`index`、`size`、`attempt`，以及可修改的 `headers`、`params`：

```javascript
// appSecret 仅为演示；生产环境请放在界面维护的通道参数中（加密保存），在通道脚本里用 options.appSecret 读取
.body(batch => {
    var ts = '' + new Date().getTime()
    var body = uploader.json({orgCode: 'H001', timestamp: ts, list: batch.rows})   // 先序列化，再按原文签名
    batch.headers['X-Timestamp'] = ts
    batch.headers['X-Sign'] = uploader.hmacSha256(appSecret, body + ts)
    return body                                                                // 返回字符串时原样发送
})
```

界面维护的通道把报文转换写在通道的脚本里，可用变量 `rows`、`batch`、`options`（通道参数，已解密，如 `options.appSecret`）、`headers`、`params`。

签名辅助函数：`uploader.md5` / `sha1` / `sha256` / `hmacSha256` / `hmacSha256Base64` / `hmacSha1Base64` / `base64` / `base64Decode` / `sortedQuery`（按参数名排序拼接 `a=1&b=2`）/ `json`。

## 5. 失败处理与补传

| 策略 | 行为 | 适用 |
|---|---|---|
| `onFailure('stop')`（默认） | 停止任务，断点停在失败批次之前，下次执行从这一批继续 | 数据有先后依赖、对方要求按顺序 |
| `onFailure('skip')` | 把失败批次的原始数据存入失败批次表，继续上传后面的数据 | 批次之间互不影响 |

补传跳过的批次（使用相同的通道配置，可以单独建一个每小时执行的定时任务）：

```javascript
return uploader.job('outpatient-monthly').channel('省平台-门诊').retryFailed()
```

其他操作：

```javascript
uploader.records('outpatient-monthly', 20)   // 最近 20 次执行记录
uploader.failed('outpatient-monthly', 50)    // 待补传的失败批次（含数据）
uploader.checkpoint('outpatient-monthly')    // 当前断点
uploader.reset('outpatient-monthly')         // 清除断点，下次从头上传（例如对方要求重传全部数据）
```

断点保存失败时任务会立即停止，避免下次重复上传；失败批次记录失败时也会停止，避免数据被跳过后丢失。

## 6. 数据表

| 表 | 内容 |
|---|---|
| magic_upload_checkpoint | 每个任务的断点 |
| magic_upload_lock | 任务锁 |
| magic_upload_record | 每次执行的汇总 |
| magic_upload_batch_log | 每一批的结果、重试次数、对方回执（超过 65536 个字符的部分截断） |
| magic_upload_failed_batch | 跳过的失败批次及原始数据 |

默认在第一次使用时自动建表（Oracle、SQL Server）。账号没有建表权限时，由 DBA 执行 `db/oracle.sql` 或 `db/sqlserver.sql`，
并配置 `init-schema: false`。其他数据库可参考 Oracle 脚本手工调整类型（如 MySQL 中 CLOB 改为 LONGTEXT）。

批次日志和执行记录不会自动清理，秒级任务请配合 `record-empty-runs: false`（默认），并定期清理历史数据。

## 7. 扩展点

注册为 Spring Bean 即可生效：

| 接口 | 用途 |
|---|---|
| `UploadSigner` | 平台专用签名（如国密 SM3）。`name()` 返回的名称填到通道参数 `signer` |
| `UploadAlertNotifier` | 其他告警方式（短信、邮件、院内消息平台） |
| `UploadChannelEncryptor` | 替换默认的 AES 加密（如接入密钥管理服务） |
| `Dialect` | magic-api 不支持的数据库方言，SQL 模块与上传插件共用 |

```java
@Component
public class Sm3Signer implements UploadSigner {
    @Override
    public String name() { return "sm3"; }

    @Override
    public void sign(SignContext ctx) {
        ctx.getHeaders().put("X-Sign", Sm3.hex(ctx.getBody() + appSecret));
    }
}
```

## 8. 部署前需要现场确认的事项

插件测试运行在 H2 数据库的 Oracle/SQL Server 兼容模式和本地模拟服务上，以下内容需要在真实环境中验证一次：

1. **建表语句与分页 SQL**：首次部署后执行一次任务，确认自动建表成功、分批读取的条数正确。
2. **SQL Server 2005 方言的分页全量读取**（`.page()`，JDBC 地址为 `jdbc:sqlserver:` 时生效）：测试用的 H2 无法正确执行这种分页
   SQL，需要在真实 SQL Server 上确认没有重复或遗漏。`.source()` 按递增字段读取不受影响。
3. **FTP、SFTP**：只做了流程测试，需要用对方提供的服务器实际传一次，确认目录权限、改名行为和中文文件名编码。
4. **上传通道界面**：在宿主应用中打开编辑器，确认"上传通道"能新建、保存、测试。
5. **多实例部署**：任务锁依赖服务器时间，各服务器需要做时间同步。
