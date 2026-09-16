# deekseep-openfork

面向官方 DeepSeek Android App 的 LSPosed/Xposed 模块，fork 自
[lllucccian/Deekseep](https://github.com/lllucccian/Deekseep) `v1.7.4-fix`（Open 版）。

本仓库独立维护，**不是**上游项目，与 DeepSeek、幻方、LSPosed、libxposed、Xposed 均无关联。

[English](README.md)

---

## 本 fork 改了什么

| 改动 | 说明 |
|---|---|
| **开源的本地 API** | 上游把本地 API 放在闭源 Closed 版里，以服务器密钥加密的 payload 下发。本 fork 用纯 Java 源码实现同等能力：在 DeepSeek 进程内提供 OpenAI / Anthropic 兼容的 HTTP 接口，复用宿主自己的鉴权传输与 PoW，不经过任何第三方。→ [docs/LOCAL-API.md](docs/LOCAL-API.md) |
| **Windows 宿主构建** | 修掉编码、`d8` argv 超长、classpath 转换、`renameTo` 语义等平台问题，使 `scripts/build-all.sh` 与全部回归测试可在 Git Bash / MSYS2 下通过。→ [docs/BUILDING-WINDOWS.md](docs/BUILDING-WINDOWS.md) |
| **可用的 CI** | 上游 workflow 每次必挂：`android-actions/setup-android` 会去装早已下架的 `tools` 包，`sdkmanager` 直接退出 1。改为直接安装 SDK，本 fork 的 Action 已跑绿。 |
| **构建健壮性** | `scripts/androidx-path-parser.sh` 现在校验固定 AAR 的摘要，下载被截断时直接报错，而不是拿空的 `classes.jar` 继续编译。 |

功能主体是上游的工作，除上述修复外未作改动。

## 真机验证环境

| | |
|---|---|
| 机型 | Nothing Phone (A142)，Android 16 |
| 框架 | KernelSU + LSPosed 2.2.0 |
| 宿主 | DeepSeek 2.3.6，`versionCode` 249（国内版） |

只有这一组合做过端到端验证；其余支持的宿主版本继承自上游，本仓库未测试。

## 本地 API

在模块主页开启：**本地 API · 实验性**。

```bash
# OpenAI 兼容
curl http://127.0.0.1:8765/v1/chat/completions \
  -H "Authorization: Bearer $DEEKSEEP_KEY" -H 'Content-Type: application/json' \
  -d '{"model":"deepseek-v4-flash","messages":[{"role":"user","content":"hi"}]}'

# Anthropic 兼容（Claude Code）
export ANTHROPIC_BASE_URL=http://127.0.0.1:8765
export ANTHROPIC_AUTH_TOKEN=$DEEKSEEP_KEY
```

OpenAI 模式提供 `/v1/models`、`/v1/chat/completions`、`/v1/responses`；Anthropic 模式提供
`/v1/messages`、`/v1/messages/count_tokens`。默认监听 `http://127.0.0.1:8765`。
配置项、请求链路与已知缺口见 [docs/LOCAL-API.md](docs/LOCAL-API.md)。

## 功能

继承上游功能核心，未作改动：账号与隐私、聊天与历史、外观、备份恢复、全局搜索、通知、
进程管理、远程功能开关、Agent 工具、诊断，以及 DeepSeek 2.2.x–2.3.6 的兼容层。
详见 [docs/FEATURES.md](docs/FEATURES.md) 与 [DISCLAIMER.md](DISCLAIMER.md)。

## 安装

1. 先安装并配置好 LSPosed/Xposed，并开启模块作用域。
2. 从 [Releases](../../releases) 下载 APK 安装。
3. 在 LSPosed 中启用 **Deekseep**，作用域**只勾选** `com.deepseek.chat`。
4. 强制停止并重新打开 DeepSeek；只有框架不会重载目标进程时才需要重启设备。

发布用 APK 使用本地生成的开发签名（`module/debug.keystore`，未入库）。对外分发前请自行重签。

## 构建

```bash
# Linux / CI：JDK 17、Android SDK platform 35、build-tools 35.0.0
export ANDROID_SDK_ROOT=/path/to/android-sdk
bash scripts/build-all.sh          # 构建两个渠道并跑全部回归测试
```

Windows（Git Bash / MSYS2）需要额外设置几个环境变量，见
[docs/BUILDING-WINDOWS.md](docs/BUILDING-WINDOWS.md)。

产物在 `dist/`，附 `SHA256SUMS.txt`。

## 已知缺口

* `usage` token 计数恒为 `0`：原生 DeepSeek 流里没有 usage 块。
* 每个请求使用一次性会话，跨请求尚无会话复用。
* `forceReasoning`、`longContextRelay`、`antiCensor` 只落了配置与界面，未接到原生请求上。
* `https: true`（模块自签 CA）未在真机上验证。

## 上游与许可

fork 自 [lllucccian/Deekseep](https://github.com/lllucccian/Deekseep) `v1.7.4-fix`。
功能核心、Closed 版及其发布流程均属于上游项目；上游的交流群（QQ、Telegram）与赞助由原作者
运营，与本 fork 无关。

按上游许可要求，本仓库同样以 [GPL-3.0-only](LICENSE) 授权。见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
