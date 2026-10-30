# magic-api 与 SkyWalking 集成指南

本文用于说明在 `magic-api` 场景下如何正确接入 SkyWalking，以及“启动时加了 agent 但看不到链路”时的常见排查步骤。

## 1. 适用范围

- 通过 `magic-api-spring-boot-starter` 启动的 Spring Boot 应用
- 使用 SkyWalking Java Agent（`-javaagent`）采集链路

## 2. 基础接入步骤

### 2.1 准备 SkyWalking Agent

从 SkyWalking 官方发布包中解压 `skywalking-agent` 目录，确保可访问：

- `skywalking-agent/skywalking-agent.jar`
- `skywalking-agent/config/agent.config`

### 2.2 启动参数示例

```bash
java \
  -javaagent:/opt/skywalking-agent/skywalking-agent.jar \
  -Dskywalking.agent.service_name=magic-api-demo \
  -Dskywalking.collector.backend_service=127.0.0.1:11800 \
  -jar app.jar
```

也可使用环境变量方式配置（与 `agent.config` 一致）：

```bash
export SW_AGENT_NAME=magic-api-demo
export SW_AGENT_COLLECTOR_BACKEND_SERVICES=127.0.0.1:11800
```

## 3. 最小验证流程

1. 启动应用后，检查启动日志里是否出现 `SkyWalking agent started`
2. 访问一个实际 API（而不是仅访问 `magic-api` 的静态页面）
3. 在 SkyWalking UI 中确认：
   - 服务名已出现（如 `magic-api-demo`）
   - `trace` 中可见请求链路
   - 请求耗时、错误率指标有数据

## 4. 常见“不生效”排查

### 4.1 仅访问了静态页面

如果只打开了编辑器页面（如 `/magic/web`），通常不会形成你期望的业务调用链。  
请触发真实的业务 API 请求后再观察 trace。

### 4.2 Agent 实际未加载

检查是否存在以下问题：

- `-javaagent` 路径错误
- 启动脚本被覆盖（例如容器入口脚本没有携带 `-javaagent`）
- JDK 版本与 Agent 版本不兼容

### 4.3 OAP 地址不可达

确认 `skywalking.collector.backend_service` 对当前运行环境可达（网络、防火墙、端口映射均正常）。

### 4.4 服务名配置不一致

如果应用实例很多，建议显式配置服务名并保持唯一且稳定，避免在 UI 中误判“未上报”。

### 4.5 异步线程场景链路断开

在跨线程执行（例如线程池异步任务）时，可能出现上下文未自动透传导致链路断点。  
如果你的脚本或扩展逻辑包含异步执行，请在该部分增加上下文透传或手动埋点。

## 5. 可选：业务扩展代码中手动埋点

若需要对自定义模块/扩展逻辑进行更细粒度追踪，可在业务工程中引入 SkyWalking toolkit 后手动埋点（示例）：

```java
import org.apache.skywalking.apm.toolkit.trace.ActiveSpan;
import org.apache.skywalking.apm.toolkit.trace.Trace;

public class DemoService {

    @Trace
    public void execute(String apiName) {
        ActiveSpan.tag("magic.api.name", apiName);
    }
}
```

> 注意：手动埋点依赖请按你的 SkyWalking 版本选择对应 toolkit 组件版本。

## 6. 建议

- 先完成“基础接入 + 最小验证”，再逐步扩展到网关、异步调用、外部存储等复杂链路。
- 遇到“无数据”时优先从“Agent 是否加载成功”和“OAP 是否可达”两项开始排查。
