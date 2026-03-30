# OpenClaw IMApp Android

全功能 Android App，与 OpenClaw AI 助手实时对话。

## 功能

- **Token 登录**：输入服务器地址与服务端生成的登录 Token
- **实时聊天**：WebSocket 长连接，收发文字 + 表情消息
- **媒体消息**：图片、语音（长按录音）、视频、文件
- **输入状态**：Agent 思考时显示"正在输入..."动画
- **推送通知**：App 后台时收到 Agent 消息弹出系统通知（需配置 FCM）
- **设备管理**：查看并移除已登录设备
- **自动重连**：网络断开时指数退避自动重连

## 构建要求

- Android Studio Hedgehog+ 或 JDK 17 + Gradle 8.x
- minSdk 26 (Android 8.0+)
- targetSdk 35

## 快速开始

```bash
# 1. 克隆项目
cd openclaw-imapp/android

# 2. 配置 FCM（可选，用于推送通知）
# 将 google-services.json 放到 app/ 目录
# 参考 app/google-services.json.example

# 3. 构建 APK
./gradlew assembleDebug

# APK 路径: app/build/outputs/apk/debug/app-debug.apk
```

## 使用说明

1. 安装 APK 到 Android 设备
2. 打开 App，输入服务器地址：`http://122.51.4.46:3100`
3. 在 OpenClaw 所在电脑上执行 `openclaw channels login --channel openclaw-imapp`
4. 将输出的服务器地址与 Token 填入 App
5. 登录成功后直接进入聊天界面

## 项目结构

```
app/src/main/java/ai/openclaw/imapp/
├── ImappApplication.kt          # Hilt 应用类
├── MainActivity.kt              # 导航入口
├── SplashScreen.kt              # 启动页（验证 token）
├── data/
│   ├── api/
│   │   ├── ApiClientFactory.kt  # Retrofit + OkHttp
│   │   ├── ImappApiService.kt   # REST 接口定义
│   │   ├── ImappWebSocketManager.kt  # WS 管理 + 自动重连
│   │   └── ServerConfigStore.kt # DataStore 配置存储
│   ├── model/
│   │   └── Models.kt            # 所有数据类
│   └── repository/
│       └── ImappRepository.kt   # 业务逻辑封装
├── di/
│   └── AppModule.kt             # Hilt 依赖注入
├── ui/
│   ├── login/
│   │   ├── LoginViewModel.kt    # 登录状态管理
│   │   └── LoginScreen.kt       # Token 登录界面
│   ├── chat/
│   │   ├── ChatViewModel.kt     # 聊天状态管理
│   │   ├── ChatScreen.kt        # 聊天界面
│   │   └── MessageBubble.kt     # 消息气泡组件
│   ├── settings/
│   │   ├── SettingsViewModel.kt
│   │   └── SettingsScreen.kt    # 设置 + 设备管理
│   └── theme/
│       └── Theme.kt             # Material Design 3 主题
└── service/
    └── ImappFirebaseService.kt  # FCM 推送服务
```
