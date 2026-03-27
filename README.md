# OpenClaw IMApp

OpenClaw 专属即时通讯插件 + Android App，通过 Token 认证安全访问 OpenClaw Agent，无需将 OpenClaw 暴露到公网。

## 架构

```
┌─────────────┐    WebSocket + Token    ┌──────────────────┐
│  Android App│ ◄──────────────────────► │  IMApp Plugin     │
│  (Kotlin)   │    HTTP REST API         │  (Node.js)        │
└─────────────┘                          │  Express + WS     │
                                         └────────┬─────────┘
                                                  │ OpenClaw SDK
                                                  ▼
                                         ┌──────────────────┐
                                         │  OpenClaw Agent   │
                                         └──────────────────┘
```

## 安全机制

- **Token 认证**：服务端生成 Token，App 手动输入绑定设备（最多 5 台）
- **WebSocket 加密**：生产环境建议配合 HTTPS/WSS 使用
- **Token 有效期**：30 天，支持通过命令行撤销
- **设备绑定**：每个 Token 绑定设备码，可独立管理
- **不暴露 OpenClaw**：插件作为独立 HTTP 服务运行，OpenClaw 本体无需开放公网端口

## 功能

- ✅ 文本消息实时通讯（WebSocket）
- ✅ 流式传输（打字机效果）
- ✅ 图片、语音、视频、文件发送
- ✅ 历史消息（从 OpenClaw session 文件读取）
- ✅ FCM 离线推送
- ✅ 多设备管理
- ✅ 自动重连 + 网络变化监听
- ✅ 前台 Service 保活

## 快速开始

### 1. 安装插件

将 `plugin/` 目录复制到 OpenClaw 插件目录：

```bash
cp -r plugin/ ~/.openclaw/extensions/openclaw-imapp/
cd ~/.openclaw/extensions/openclaw-imapp/
npm install
```

### 2. 配置

在 OpenClaw 配置文件中添加（可选）：

```yaml
channels:
  openclaw-imapp:
    baseUrl: "http://your-server:3100"
```

重启 OpenClaw 后插件自动启动，默认监听 `0.0.0.0:3100`。

可通过环境变量覆盖：
- `IMAPP_PORT` — 监听端口（默认 3100）
- `IMAPP_BASE_URL` — 公网地址

### 3. 生成设备 Token

```bash
openclaw channels login --channel openclaw-imapp
```

输出示例：
```
═══════════════════════════════════════════════════
              🎉 新设备 Token 已生成！
═══════════════════════════════════════════════════

  设备名称：Android Device
  设备码：  6B5E66AB
  Token：  imapp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx

═══════════════════════════════════════════════════
```

### 4. 管理设备

```bash
# 列出所有设备
openclaw channels login --channel openclaw-imapp list

# 撤销设备
openclaw channels login --channel openclaw-imapp revoke <设备码>
```

### 5. Android App

#### 构建前提

- Android Studio (推荐最新版)
- JDK 17+
- Gradle 8.7

#### 配置 FCM 推送（可选）

1. 在 [Firebase Console](https://console.firebase.google.com/) 创建项目
2. 添加 Android App，包名 `ai.openclaw.imapp`
3. 下载 `google-services.json` 替换 `android-app/app/google-services.json`
4. 在插件配置中设置 FCM Server Key

#### 构建

```bash
cd android-app
./gradlew assembleDebug    # Debug 版
./gradlew assembleRelease  # Release 版
```

#### 使用

1. 安装 APK
2. 输入服务器地址（如 `http://your-server:3100`）
3. 输入 Token 完成登录

## 目录结构

```
openclaw-imapp/
├── README.md                    # 本文件
├── plugin/                      # OpenClaw 插件源码
│   ├── dist/                    # 编译后的插件代码
│   │   ├── index.js             # 插件入口
│   │   ├── auth/                # Token 认证
│   │   ├── db/                  # SQLite 数据库
│   │   ├── routes/              # HTTP REST API
│   │   ├── websocket/           # WebSocket 消息处理
│   │   ├── media/               # 媒体文件处理
│   │   └── util/                # 工具函数
│   ├── openclaw.plugin.json     # 插件元数据
│   ├── package.json             # 依赖声明
│   └── tsconfig.json            # TypeScript 配置
└── android-app/                 # Android App 源码
    ├── app/
    │   └── src/main/java/ai/openclaw/imapp/
    │       ├── MainActivity.kt          # 主 Activity
    │       ├── SplashScreen.kt          # 启动页
    │       ├── ImappApplication.kt      # Application
    │       ├── data/                    # 数据层
    │       │   ├── api/                 # API + WebSocket
    │       │   ├── model/               # 数据模型
    │       │   └── repository/          # 数据仓库
    │       ├── di/                      # Hilt 依赖注入
    │       ├── service/                 # 保活 + FCM
    │       ├── ui/                      # Compose UI
    │       │   ├── chat/                # 聊天界面
    │       │   ├── login/               # 登录界面
    │       │   ├── settings/            # 设置界面
    │       │   └── theme/               # 主题
    │       └── util/                    # 工具类
    ├── build.gradle.kts
    └── settings.gradle.kts
```

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/imapp/auth/verify` | Token 验证 |
| GET  | `/imapp/health` | 健康检查 |
| POST | `/imapp/messages/history` | 获取历史消息 |
| POST | `/imapp/media/upload-url` | 获取上传 URL |
| POST | `/imapp/media/upload/:fileId` | 上传媒体文件 |
| GET  | `/imapp/media/:fileId` | 下载媒体文件 |
| POST | `/imapp/fcm/register` | 注册 FCM Token |
| WS   | `/imapp/ws?token=xxx&user_id=xxx` | WebSocket 连接 |

## 技术栈

### 插件
- Node.js + TypeScript
- Express (HTTP)
- ws (WebSocket)
- better-sqlite3 (本地数据库)
- OpenClaw Plugin SDK

### Android App
- Kotlin + Jetpack Compose
- Hilt (依赖注入)
- OkHttp (网络 + WebSocket)
- Retrofit (REST API)
- Room (本地缓存)
- Navigation Compose
- Material 3

## 许可证

MIT
