# CHANGELOG

本文件记录 LLMHub 项目每次会话所产生的更改，按时间倒序排列。

---

## 2026-08-27 — Bug 排查与修复专项（详细见 修改20260827（1）.md）

### 修复
- **Provider Done 双发（P1）**：OpenAICompatible / Anthropic 两个 Provider 增加 `AtomicBoolean doneSent`，`[DONE]`/`message_stop` 帧与 `onClosed` 不再双发 Done，消除 ChatRepository 对同一 assistant 消息的重复收尾写库。
- **forceRefresh 并发竞态（P2）**：`RemoteUsageRepository.forceRefresh` 的 in-flight 抢占改为 `compareAndSet` 原子循环，快速连点「立即同步」不再并发打两次平台限流接口。
- **deprecation 警告清零（P3）**：`BillingWebPanel` 的 ArrowBack/ArrowForward 换 AutoMirrored 版本；删除 `databaseEnabled`（Android 5.0+ 行为等价）；删除 WebCookieManager 未使用 import。

### 验证
- `kspDebugKotlin + compileDebugKotlin`：BUILD SUCCESSFUL，`w:` 警告 0 条（修复前 3 条）
- `assembleDebug`：BUILD SUCCESSFUL（42 tasks）

### 构建环境备注
- 项目内 `.gradle/buildOutputCleanup.lock` 被常驻 Java 进程占用导致 Gradle 启动失败；绕行方案：`--project-cache-dir` 指向独立临时目录。

---

## 2026-08-23 — 项目初始化 + Gradle 镜像修复

### 概述

从零搭建「LLMHub」安卓原生应用：聚合多家大模型 API（OpenAI / Claude / Gemini / 通义 / 文心 / 智谱 / DeepSeek / Kimi / 自定义），统一聊天界面 + 流式 SSE 输出 + API Key 加密管理 + 消耗统计。

**技术栈**：Android 原生 Kotlin + Jetpack Compose + Material 3 + Hilt + Room + OkHttp（SSE）+ Retrofit + kotlinx.serialization。
**架构**：MVVM + Repository。
**UI 风格**：极简卡片风（暖棕主色 #7C6A4F + 奶白背景 #FAF7F2）。

### 新增文件

#### 项目配置（根 + app）
- `settings.gradle.kts` — 仓库声明（含阿里云镜像）
- `build.gradle.kts`（root）— 插件聚合
- `gradle.properties` — JVM/AndroidX 参数
- `gradle/libs.versions.toml` — Version Catalog 统一依赖版本
- `gradle/wrapper/gradle-wrapper.properties` — Gradle 8.9（腾讯云镜像）
- `app/build.gradle.kts` — 应用模块配置（compileSdk 34 / minSdk 26 / Java 17）
- `app/proguard-rules.pro` — Hilt / Retrofit / Moshi / kotlinx-serialization 保留规则

#### 资源
- `app/src/main/AndroidManifest.xml` — 入口 + INTERNET 权限
- `app/src/main/res/values/strings.xml` — 全部 UI 文案
- `app/src/main/res/values/colors.xml` — 颜色
- `app/src/main/res/values/themes.xml` — XML 主题
- `app/src/main/res/xml/backup_rules.xml` / `data_extraction_rules.xml` — 备份规则
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` / `ic_launcher_round.xml` — 自适应图标
- `app/src/main/res/drawable/ic_launcher_background.xml` / `ic_launcher_foreground.xml` — 图标前景/背景

#### 数据层（`app/src/main/java/com/llmhub/app/data/`）
- `model/ModelProvider.kt` — 9 家服务商枚举 + ProtocolKind 协议路由
- `model/ChatModels.kt` — ChatMessage / ChatRole / ChatSession（Room Entity）
- `model/ConfigModels.kt` — ModelConfig / ApiKeyConfig
- `model/UsageModels.kt` — UsageRecord / ModelUsageStat / UsageSummary
- `db/Converters.kt` — Room 枚举类型转换（容错）
- `db/dao/ChatDao.kt` / `ModelConfigDao.kt` / `ApiKeyDao.kt` / `UsageDao.kt` — DAO
- `db/LlmHubDatabase.kt` — Room 数据库
- `prefs/SecureKeyStore.kt` — EncryptedSharedPreferences 加密存储 API Key
- `remote/LlmProvider.kt` — 统一接口 + ChatRequest + ChatStreamEvent
- `remote/JsonExt.kt` — JSON 容错取值助手
- `remote/OpenAICompatibleProvider.kt` — OpenAI 兼容协议（覆盖 7 家国产/海外）
- `remote/AnthropicProvider.kt` — Claude 原生 SSE 协议
- `remote/GeminiProvider.kt` — Gemini `?alt=sse` 流式协议
- `remote/LlmProviderFactory.kt` — 按 ProtocolKind 路由
- `repository/ChatRepository.kt` — 流式拼装 + 消息落库 + 消耗记录
- `repository/ApiKeyRepository.kt` — Key CRUD + 解密读取
- `repository/ModelConfigRepository.kt` — 模型 CRUD + 默认设置
- `repository/UsageRepository.kt` — 消耗查询

#### 依赖注入（`app/src/main/java/com/llmhub/app/di/`）
- `DatabaseModule.kt` — Room + 4 个 DAO 提供
- `NetworkModule.kt` — OkHttpClient（120s 读超时）+ Json

#### 应用入口
- `app/src/main/java/com/llmhub/app/LLMHubApplication.kt` — `@HiltAndroidApp`
- `app/src/main/java/com/llmhub/app/MainActivity.kt` — `@AndroidEntryPoint` + Compose 入口

#### UI（`app/src/main/java/com/llmhub/app/ui/`）
- `theme/Color.kt` / `Type.kt` / `Theme.kt` — 极简卡片风主题
- `components/Card.kt` — LLMHubCard / EmptyState 通用组件
- `components/AppScaffold.kt` — 底部导航骨架
- `navigation/NavRoute.kt` — 4 个 Tab 路由
- `navigation/NavGraph.kt` — NavHost
- `chat/ChatViewModel.kt` / `ChatScreen.kt` — 聊天页（消息气泡 + 模型选择 + 历史会话 + 流式输入栏）
- `keys/ApiKeyViewModel.kt` / `ApiKeyScreen.kt` — 密钥管理页（显示/隐藏 + 复制 + 编辑 + 删除）
- `models/ModelConfigViewModel.kt` / `ModelConfigScreen.kt` — 模型管理页（含价格/温度/maxTokens 表单）
- `stats/UsageViewModel.kt` / `UsageScreen.kt` — 消耗统计页（时间范围 + 汇总 + 按模型分布）

### 关键设计点

1. **统一 Provider 抽象**：新增服务商只需在 `ModelProvider` 加枚举条目，OpenAI 兼容协议零代码扩展。
2. **API Key 安全**：明文 Key 绝不进数据库，仅以 `apikey_${id}` 加密存于 EncryptedSharedPreferences（Android Keystore 托管）。
3. **流式 SSE**：用 OkHttp `EventSources` + `callbackFlow` 把各家 SSE 协议统一成 `Flow<ChatStreamEvent>`。
4. **协议差异处理**：
   - Claude 的 `system` 提到顶层字段
   - Gemini 的 system 放 `systemInstruction.parts[].text`，role 用 `user`/`model`
   - OpenAI 兼容路径智能识别 `/v1` / `/v4` 后缀
5. **消耗统计**：每次请求结束写 UsageRecord，统计页按时间范围 + 按模型聚合。

### 后续修复

- `ChatRepository.kt` — 把 `accumulated` / `inputTokens` / `outputTokens` / `failed` / `startTime` 提到 `try` 块之前，让 catch 块能访问（修复作用域编译错误）。
- `OpenAICompatibleProvider.kt` — 删除残留的 `trySend0` helper，统一 parseChunk 返回事件给外层 trySend。
- `JsonExt.kt` — 修正 `objectOrNull` / `arrayOrNull` 实现错误，加正确的 helper。
- `ApiKeyScreen.kt` — 把 `ProviderDropdown` 改名为 public 的 `ProviderDropdownField` 供 `ModelConfigScreen` 复用；KeyCard 显示真实明文而非占位文案。

### Gradle 镜像调整（解决国内下载超时）

- `gradle/wrapper/gradle-wrapper.properties` — `distributionUrl` 改为 `https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip`，`networkTimeout` 10000 → 60000。
- `settings.gradle.kts` — `pluginManagement` 与 `dependencyResolutionManagement` 的 `repositories` 加入阿里云 `google` / `public` / `gradle-plugin` 镜像（放在官方源之前优先使用）。

**镜像可达性验证**：腾讯云 Gradle 8.9（136114148 bytes / HTTP 200）、阿里云 maven public（HTTP 200）均通过。

### 已知限制（待后续迭代）

- Gradle Wrapper `jar` 未生成：Android Studio 首次同步会自动补全。
- `menuAnchor()` 用了无参形式（Material 3 1.3 起 deprecated，仍可用）。
- 流式期间每次 Delta 触发 DB 写：性能足够，换取 UI 自动刷新简单性。
- 暂不支持图片/文件多模态输入、对话导出、模型并排对比、夜间模式调优。
