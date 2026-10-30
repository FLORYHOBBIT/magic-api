# magic-api-plugin-flow 集成流程

在 magic-api 编辑器中新增"集成流程"资源：用节点和连线配置接口逻辑，不写或少写脚本。
流程和接口、函数一样存放在 magic-api 工作目录（`flow/` 下），随 magic-api 的导入导出、备份、Git 同步一起走。

> 当前阶段：流程以 JSON 编辑，画布在后续版本提供；触发方式（HTTP、WebService、定时）在后续版本提供，目前通过"执行测试"调试运行。

## 引入

```xml
<dependency>
    <groupId>org.ssssssss</groupId>
    <artifactId>magic-api-plugin-flow</artifactId>
    <version>2.2.2</version>
</dependency>
```

关闭：`magic-api.flow.enable=false`。

## 流程定义

```json
{
  "nodes": [
    {"id": "q1", "type": "query", "name": "查结算", "config": {"sql": "select * from settle where patient_id = #{input.patientId}"}},
    {"id": "end", "type": "return", "config": {"value": "{code: 0, data: q1}"}}
  ],
  "edges": [
    {"source": "q1", "target": "end"}
  ]
}
```

- 起始节点：没有入线的节点，必须有且只有一个。
- 连线 `condition`：
  - 空或 `success`：默认连线，每个节点最多一条。
  - `failure`：节点出错时走这条，每个节点最多一条；没有失败连线时节点出错即流程失败。
  - 其他：magic-script 表达式，如 `prev > 0`、`q1.size() == 0`。按定义顺序，第一个为真的生效，都不命中走默认连线。
- 返回节点执行后流程结束；没有返回节点时，返回最后一个节点的输出。
- 单次执行最多 10000 步，防止连线成环导致死循环。

## 变量

SQL 参数（`#{}`）、表达式、脚本中都可以使用：

| 变量 | 说明 |
| --- | --- |
| `input` | 触发输入：调试输入、HTTP 参数与报文、定时任务参数 |
| `vars` | 流程变量，脚本节点可写入，如 `vars.total = 10` |
| `prev` | 上一个节点的输出 |
| 节点 id | 节点输出，如 `q1[0].name`；id 不是合法变量名时用 `nodes['节点id']` |
| `error` | 走失败连线时的错误：`{node, name, message}` |
| `row`、`index` | 写库节点逐行执行时的当前行与序号（从 0 开始） |

`input`、`vars`、`nodes`、`prev`、`error`、`row`、`index`、`flow`、`db`、`http`、`log` 等是保留名，节点 id 用这些名字时只能通过 `nodes[...]` 引用。

## 节点

### query 查库

| 配置 | 说明 |
| --- | --- |
| `sql` | 必填，magic-api SQL 语法：`#{}` 参数、`${}` 拼接、mybatis 标签 |
| `datasource` | 数据源 key，空为主数据源 |
| `result` | `list`（默认）/ `one` 第一行，无数据为 null / `value` 第一行第一列 / `count` 整数 |
| `columnCase` | 列名格式：`lower`（默认，Oracle 大写列名也转小写）/ `camel` / `upper` / `raw` 原样 |

### write 写库

| 配置 | 说明 |
| --- | --- |
| `sql` | 必填，insert / update / delete / merge 语句 |
| `datasource` | 数据源 key，空为主数据源 |
| `forEach` | 可选，表达式，结果为列表时逐行执行，行变量 `row`、`index` |
| `transaction` | 逐行执行时是否在一个事务中，默认 `true`，任一行失败全部回滚 |

输出：`{affected: 受影响行数合计, executed: 执行次数}`。

### script 脚本

`script`：magic-script 脚本，返回值为节点输出，可使用 `db`、`http`、`log` 等模块。只用于表单节点覆盖不到的处理。

### return 返回

`value`：表达式，为空时返回上一个节点的输出。

## 调试运行

打开流程，点"执行测试"：用编辑器中的定义（包括未保存的修改）真实执行一次，**会访问数据库并执行写库节点**。
底部"调试运行"工具条左侧填写调试输入（JSON，流程中为 `input`，按流程记在浏览器本地），右侧列出每个节点的状态、耗时、下一节点，点击行查看该节点输出或错误。列表输出超过 20 行时只显示前 20 行和总数，后续节点仍拿到全部数据。

## 扩展节点类型

实现 `org.ssssssss.magicapi.flow.engine.FlowNodeHandler` 并注册为 Spring Bean：

```java
@Component
public class MqNodeHandler implements FlowNodeHandler {
    public String type() { return "mq"; }
    public void check(FlowNodeContext node) { node.requireString("topic"); }
    public Object execute(FlowNodeContext node) {
        String topic = node.requireString("topic");
        Object body = node.evaluate(node.string("body", "prev"));
        // 发送……
        return "ok";
    }
}
```

同名类型时 `@Order` 靠前的生效，可用于覆盖内置节点。

## 错误码

| 代码 | 含义 |
| --- | --- |
| 5001–5006 | 流程定义为空、JSON 格式错误、节点 id 缺失或重复、缺少类型、连线指向不存在的节点 |
| 5007 | 找不到流程 |
| 5008 | 节点类型不支持 |
| 5009 | 起始节点不唯一 |
| 5010 | 执行步数超限（可能成环） |
| 5011 | 节点缺少必填配置 |
| 5012 | 同一节点有多条默认连线或失败连线 |
| 5013 | 节点配置值不正确 |
