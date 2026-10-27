# 本地开发说明 (Fork 二次开发)

> 本文件为 fork 维护专用，记录构建环境约定与开发规范，供 Kiro / Codex / OpenCode 等多 Agent 共享。

## 1. JDK 要求（重要）

本工程是**多 JDK 混合工程**：

| 模块 | 编译要求 |
|---|---|
| `magic-api`（核心）、`magic-editor`、`magic-api-servlet-javaee`、多数数据类插件 | `source/target = 8` |
| `magic-api-servlet-jakarta`、`magic-api-plugin-springdoc` | `source/target = 17`（Spring Boot 3 生态，pom 中硬编码） |

因此**全量构建必须使用 JDK 17**（向下兼容 source=8 的模块）。仅构建核心子集时 JDK 8 亦可。

部分前端插件（`task`、`component`）构建时会执行 `vite build`，需本机具备 **Node.js** 环境。

## 2. 构建方式

### 推荐：使用封装脚本（自动指向 JDK17）

```bat
REM Windows
build.cmd                     REM = clean verify（全量）
build.cmd clean package
build.cmd -pl magic-api test  REM 只构建/测试核心模块
```

```bash
# Linux / macOS
./build.sh
./build.sh -pl magic-api test
```

脚本默认 `JDK17_HOME=D:\Environment\Java\jdk-17.0.20.1+1`，可用环境变量 `JDK17_HOME` 覆盖。

### 或手动指定 JAVA_HOME

```powershell
$env:JAVA_HOME='D:\Environment\Java\jdk-17.0.20.1+1'
.\mvnw.cmd clean verify
```

### 仅核心子集（JDK8 亦可，用于快速迭代）

```
mvnw -pl magic-api,magic-editor,magic-api-servlet/magic-api-servlet-javaee clean verify
```

## 3. 测试

- 测试框架：JUnit5（`spring-boot-starter-test`，test scope）。
- 父 POM 已将 `maven-surefire-plugin` 固定为 3.1.2（Spring Boot 2.4.5 默认的 2.12.4 无法发现 JUnit5 用例）。
- 冒烟用例：`magic-api/src/test/java/org/ssssssss/magicapi/SmokeTest.java`。

## 4. Git / 分支规范

- `origin` = FLORYHOBBIT/magic-api（自己的 Fork）
- `upstream` = ssssssss-team/magic-api（原作者，**push 地址已禁用**）
- `master` 仅作上游纯净镜像，**不直接修改**。
- 所有开发在 `feature/**` 分支完成，经 PR 合并到 `master`。
- **严禁向 upstream 推送**。

### 同步上游更新

```bash
git fetch upstream --tags
git checkout master
git merge --ff-only upstream/master
git push origin master
# feature 分支再 rebase/merge master 跟进
```

## 5. CI

`.github/workflows/build.yml`：PR→master / push feature 分支触发，使用 **JDK17 + Node18** 执行 `mvnw clean verify`。

## 6. 二次开发落点建议

- 新能力优先**新增独立插件模块**（`magic-api-plugins/magic-api-plugin-xxx`），避免改核心。
- 通过实现扩展接口 + `@ConditionalOnMissingBean` 覆盖默认 Bean：
  `RequestInterceptor` / `AuthorizationInterceptor` / `ResultProvider` /
  `@MagicModule`+`DynamicModule` / `MagicResourceStorage` / `MagicDynamicRegistry`。
- 尽量不改 `magic-api` 核心源码，以降低与上游合并冲突。
