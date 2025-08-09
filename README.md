# Android Video Player App

一个功能完整的Android视频播放应用，支持在线和本地视频播放，SRT字幕显示，以及全局悬浮窗控制。

## 主要功能

### 1. 视频播放
- 支持在线视频URL播放（HTTP/HTTPS）
- 支持本地视频文件播放
- 基于ExoPlayer的高性能播放引擎
- 支持多种视频格式（MP4、MKV、AVI等）

### 2. 字幕功能
- 支持SRT字幕文件
- 可加载在线字幕URL或本地字幕文件
- 实时字幕显示
- 字幕面板功能（类似YouTube转录）
  - 显示完整字幕列表
  - 点击字幕可跳转到对应时间点
  - 高亮显示当前播放的字幕

### 3. 全局悬浮窗
- 应用切换到后台时自动显示悬浮控制窗口
- 悬浮窗功能：
  - 播放/暂停按钮
  - 后退5秒按钮
  - 关闭悬浮窗按钮
- 可拖拽的悬浮窗位置

### 4. 权限管理
- 自动处理存储权限申请
- 悬浮窗权限引导
- 优雅的权限请求流程

## 技术架构

### 核心技术栈
- **Kotlin** - 主要开发语言
- **ExoPlayer (Media3)** - 视频播放引擎
- **Android Jetpack** - 现代Android开发组件
- **Material Design 3** - UI设计规范
- **OkHttp** - 网络请求库
- **Coroutines** - 异步处理

### 项目结构
```
app/
├── src/main/java/com/videoplayerapp/
│   ├── MainActivity.kt              # 主界面Activity
│   ├── PlayerActivity.kt            # 视频播放Activity
│   ├── model/
│   │   └── SubtitleItem.kt          # 字幕数据模型
│   ├── adapter/
│   │   └── SubtitleAdapter.kt       # 字幕列表适配器
│   ├── service/
│   │   └── FloatingPlayerService.kt # 悬浮窗服务
│   └── utils/
│       └── SubtitleParser.kt        # SRT字幕解析工具
├── src/main/res/
│   ├── layout/                      # 布局文件
│   ├── drawable/                    # 图标资源
│   ├── values/                      # 颜色、字符串、主题
│   └── xml/                         # 备份规则等配置
└── build.gradle.kts                 # 模块构建配置
```

## 使用方法

### 1. 安装应用
- 在Android Studio中打开项目
- 连接Android设备或启动模拟器
- 点击运行按钮安装应用

### 2. 播放视频
1. 启动应用
2. 在"Video Source"区域：
   - 输入视频URL，或
   - 点击"Select Video File"选择本地视频文件
3. （可选）在"Subtitles"区域：
   - 输入字幕文件URL，或
   - 点击"Select Subtitle File"选择本地SRT文件
4. 点击"Play Video"开始播放

### 3. 使用字幕面板
- 在视频播放界面点击"Show Subtitles Panel"
- 在右侧面板中查看完整字幕列表
- 点击任意字幕行可跳转到对应时间点

### 4. 使用悬浮窗
- 播放视频时按Home键或切换到其他应用
- 悬浮窗将自动显示在屏幕上
- 使用悬浮窗按钮控制播放：
  - 🔄 后退5秒
  - ⏸️/▶️ 播放/暂停
  - ✖️ 关闭悬浮窗

## 权限说明

应用需要以下权限：
- **INTERNET** - 加载在线视频和字幕
- **READ_EXTERNAL_STORAGE / READ_MEDIA_VIDEO** - 访问本地视频文件
- **SYSTEM_ALERT_WINDOW** - 显示悬浮窗
- **FOREGROUND_SERVICE** - 后台服务支持

## 支持的格式

### 视频格式
- MP4, MKV, AVI, MOV, 3GP
- DASH, HLS流媒体
- 大多数常见编解码器

### 字幕格式
- SRT (SubRip Subtitle)
- 支持UTF-8编码
- 标准时间码格式

## 开发环境要求

- Android Studio Arctic Fox或更高版本
- Android SDK 24+（Android 7.0）
- Kotlin 1.9.22
- Gradle 8.2

## 构建说明

```bash
# 克隆项目
git clone <repository-url>
cd VideoPlayerApp

# 构建Debug版本
./gradlew assembleDebug

# 安装到连接的设备
./gradlew installDebug
```

## 故障排除

### 常见问题

1. **无法播放网络视频**
   - 检查网络连接
   - 确认视频URL有效
   - 某些HTTPS视频可能需要特殊证书

2. **悬浮窗不显示**
   - 前往设置->应用权限->悬浮窗权限
   - 手动开启应用的悬浮窗权限

3. **无法选择本地文件**
   - 检查存储权限是否已授予
   - 确保文件路径可访问

4. **字幕不显示**
   - 确认SRT文件格式正确
   - 检查文件编码为UTF-8
   - 验证时间码格式

## 贡献指南

欢迎提交Issue和Pull Request来改进这个项目。请确保：
- 代码符合Kotlin编码规范
- 新功能包含适当的测试
- 更新相关文档

## 许可证

本项目采用MIT许可证，详见LICENSE文件。