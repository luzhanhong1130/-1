# LLMHub 跨平台应用方案设计（iOS + Android）

> 编制：移动应用开发工程师（掌中灵）
> 日期：2026-08-27

## 核心结论
**KMP + Compose Multiplatform 是成本最低、风险可控、复用率最高（~85%）的跨平台路径。**

## 选型对比
| 方案 | 代码复用率 | 迁移成本 | 结论 |
|------|-----------|---------|------|
| **KMP + CMP** | **~85%** | 低（现有 Kotlin 直接搬） | ✅ 推荐 |
| Flutter/Dart | 逻辑参考，代码 0 复用 | 高（整体重写） | ❌ |
| React Native/TS | 逻辑参考，代码 0 复用 | 高（整体重写） | ❌ |
| 双原生 | 仅算法可移植 | 极高 | ❌ |

## 技术栈替换
| 能力 | 现状 | 跨平台目标 |
|------|------|----------|
| 网络 | OkHttp | Ktor Client（OkHttp/Darwin engine） |
| DB | Room v3 | SQLDelight（Room→SQLDelight 数据迁移） |
| Key 加密 | EncryptedSharedPreferences | Keychain(iOS) + ESP(Android) |
| DI | Hilt | Koin |
| UI | Jetpack Compose | Compose Multiplatform（共享 ~95%） |

## 分阶段计划（8-10 周单人全职）
1. P0 准备（1 周）：shared 模块骨架 + Koin/Ktor/SQLDelight 引入
2. P1 领域层下沉（2 周）：Provider/Fetcher/Repo 平移到 commonMain
3. P2 UI 迁移（2-3 周）：Compose CMP + Hilt 换 Koin
4. P3 iOS 端（2 周）：iosMain actual + SwiftUI 壳
5. P4 打磨发布（1-2 周）：双端性能 + Crashlytics + 商店提审

## 性能目标
- 冷启动 < 3s / 核心内存 < 100MB / Crash-free > 99.5%

---
软件签名：TRAE AI 开发环境
大模型签名：Trae 智能助手
