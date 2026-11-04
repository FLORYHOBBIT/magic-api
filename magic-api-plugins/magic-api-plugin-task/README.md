# 定时任务日志

定时触发和手动测试结束后，会保存该次执行的详细日志。在编辑器底部打开“运行日志”：

- “执行历史日志”默认跟随最新一次执行，每 5 秒检查新记录，也可以选择某次历史执行。
- “本次测试实时日志”保留原来的浏览器 WebSocket 日志。执行测试时自动切换到此页。
- “执行记录”中的“查看日志”可直接打开相应批次。

执行记录支持按成功/失败筛选，统一展示触发来源、结果和耗时。历史日志按条目显示时间、级别、线程及任务名称；长 SQL 和异常堆栈可以折叠，支持全文复制及自动换行。

历史日志在任务执行结束后保存。运行中、进程异常退出前尚未完成的执行，请查看服务器的 `logs/magic-api.log`。
已有的旧摘要无法还原详细日志，会显示“未采集”。日志按服务器生效的日志级别采集 `log.info/warn/error` 等日志及异常堆栈，不额外捕获 `System.out`。

## 配置

以下为默认值，现有部署不增加配置也能启用。调整时在部署目录的 `config/application.yml` 中合并：

```yaml
magic-api:
  execution-record:
    enable: true
    dir: logs/execution-records
    max-records: 200
    log-enabled: true
    max-log-chars: 262144
```

`max-log-chars` 按字符计数，单次最多允许设置为 1048576；超出后截断，网页显示提示。设置 `log-enabled: false` 或 `max-log-chars: 0` 只记录摘要。

文件布局：

```text
logs/execution-records/<任务ID>.jsonl
logs/execution-records/<任务ID>-logs/<执行runId>.log
```

日志文件为 UTF-8，与摘要分开，列表查询不会读取详细日志。记录超过 `2 × max-records` 时压缩到最近 `max-records` 条，并清理不再保留的日志；正常写入的每个资源最多暂存 `2 × max-records` 次日志。任务删除后的记录沿用原有保留行为，不自动删除。

详情接口为 `GET /magic/web/execution-records/logs?id=<任务ID>&runId=<执行ID>`，与摘要接口一样校验资源的 VIEW 权限，不能跨资源读取日志。保留原有服务器日志输出及 WebSocket 推送。

## 更新部署

本功能同时涉及核心、Spring Boot starter、任务插件和编辑器，需整体更新应用，不能只替换任务插件 JAR。保留服务器的 `config`、`data` 和 `logs` 目录。更新后重启应用，并在浏览器整页刷新。

本地检查：核心日志与权限测试、任务脚本定时/测试执行、前端乱序响应与历史选择测试；不等同于目标服务器和业务脚本验证。
