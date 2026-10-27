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
- `master` = 上游代码 + fork 自研内容（已合并 PR #1），**不直接修改、不直接推送**，只通过 PR 合并。
- 所有开发在 `feature/**` 分支完成，经 PR 合并到 `master`。
- **严禁向 upstream 推送**，也不要向上游仓库发 PR（GitHub 在 fork 中创建 PR 时默认选上游仓库，注意改为 FLORYHOBBIT/magic-api）。
- 本地 `master` 检出在主工作目录 `E:/orca/magic-api`；其他工作树中不能再检出 `master`，更新本地 master 请在该目录执行
  `git merge --ff-only origin/master`。

### 同步上游更新

`master` 已包含 fork 自己的提交，不能再用 `--ff-only` 快进到上游。同步也走分支 + PR：

```bash
git fetch upstream --tags
git fetch origin
git switch -c sync/upstream-YYYYMMDD origin/master
git merge upstream/master          # 有冲突时解决后 git add + git commit
build.cmd                          # JDK17 全量 clean verify 必须通过
git push -u origin sync/upstream-YYYYMMDD
gh pr create --repo FLORYHOBBIT/magic-api --base master --head sync/upstream-YYYYMMDD
# PR 的 CI 通过后合并；进行中的 feature 分支再 merge 最新的 origin/master 跟进
```

减少冲突的约定：fork 的改动尽量放在独立模块（如 `magic-api-plugin-upload`）；对上游文件只做追加式的小改动
（目前是父 POM 的 surefire 版本、`magic-api/pom.xml` 的 test 依赖、`magic-api-plugins/pom.xml` 的一行 module、`.gitignore`）。
冲突通常只会出现在这几处 pom 中，保留双方内容即可。

## 5. CI

`.github/workflows/build.yml`：PR→master / push feature 分支触发，使用 **JDK17 + Node18** 执行 `mvnw clean verify`。

## 6. 二次开发落点建议

- 新能力优先**新增独立插件模块**（`magic-api-plugins/magic-api-plugin-xxx`），避免改核心。
- 通过实现扩展接口 + `@ConditionalOnMissingBean` 覆盖默认 Bean：
  `RequestInterceptor` / `AuthorizationInterceptor` / `ResultProvider` /
  `@MagicModule`+`DynamicModule` / `MagicResourceStorage` / `MagicDynamicRegistry`。
- 尽量不改 `magic-api` 核心源码，以降低与上游合并冲突。


## 7. 数据上传插件（magic-api-plugin-upload）

fork 自研插件，使用说明见 `magic-api-plugins/magic-api-plugin-upload/README.md`。维护要点：

- 对上游文件的改动只有 `magic-api-plugins/pom.xml` 中的一行 `<module>`，其余代码都在插件目录内。
- 包结构：`engine`（分批、重试、失败策略，不涉及 I/O）、`store`（断点、锁、记录、失败批次）、`source`（数据来源）、
  `channel`（HTTP/SOAP/文件/FTP/SFTP 通道）、`resource`（界面维护的上传通道）、`script`（uploader 脚本模块）、
  `alert`（告警）、`service`（任务执行器）、`starter`（自动配置）；前端在 `src/console`（构建方式同 task 插件，需要 Node.js）。
- 只构建并测试插件：`build.cmd -pl magic-api-plugins/magic-api-plugin-upload -am clean verify`。
- 修改 `UploadSchema` 后重新生成手工建表脚本：
  `build.cmd -pl magic-api-plugins/magic-api-plugin-upload -am verify -Dtest=SchemaSqlFilesTest -Dsurefire.failIfNoSpecifiedTests=false -Dupload.writeDdl=true`。
- 数据库相关测试使用 H2 的 Oracle/SQL Server 兼容模式；H2 无法正确执行 SQLServer2005 方言的 offset 分页，该组合已从测试中排除，需在真实库上验证。
- 新增依赖：`commons-net 3.9.0`（FTP）、`com.github.mwiede:jsch 0.2.16`（SFTP），仅在插件中引入。
