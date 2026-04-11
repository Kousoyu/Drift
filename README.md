<div align="center">
  <img src="app/src/main/res/mipmap-xxhdpi/ic_launcher.webp" width="100" height="100" alt="Drift Logo">
  <h1>Drift (漂移)</h1>
  <p><strong>一个纯净、高性能的漫画与轻小说聚合阅读器</strong></p>

  <!-- 如果你有编译状态、版本徽章可以放这里 -->
  [![Release](https://img.shields.io/github/v/release/Kousoyu/Drift?color=blue&label=最新版本)](https://github.com/Kousoyu/Drift/releases/latest)
  [![License](https://img.shields.io/github/license/Kousoyu/Drift)](LICENSE)
  [![Android](https://img.shields.io/badge/Platform-Android-3DDC84.svg?logo=android)](#)
</div>

---

## 📖 项目简介

**Drift** 是一款基于 Android 原生开发的聚合类阅读 App。它化繁为简，将**漫画**与**轻小说**的阅读体验完美融合在一个应用中。

本项目由纯 Kotlin 编写，没有滥用庞大的第三方框架或沉重的跨平台引擎。在保证**极致流畅**和**极速秒开**的同时，提供了深度的个性化配置与稳如泰山的性能表现。

## ✨ 核心特性

### 📚 聚合书库 (无缝切换源)
* **漫画源**：原生解析并深度整合了 **包子漫画**、**拷贝漫画** 与 **漫画柜**。
* **小说源**：高配解析 **哔哩轻小说**，图文并茂完美呈现。
* **规避封锁**：为拷贝漫画等存在反爬限制的源，专门重写了请求防盗链 Header 与 UA，并能在接口封锁时进行优雅降级。

### 🛋️ 沉浸式阅读器
* **漫画模式**：支持全屏阅读、长图无缝拼接滚屏、章节首尾无缝直达下一话。
* **小说模式**：自定义**字号与行距**，相关配置基于 `SharedPreferences` 永久生效，无惧后台被杀。首行自动缩进、完美支持小说内的插图解析与渲染。
* **护眼与排版**：精细调优的行内布局，告别野鸡 App 常有的排版错乱感。

### ⚡ 引擎级性能优化
* **连接池复用**：全局网络请求与 Coil 图片加载器共享底层 `OkHttpClient` 的 HTTP 连接池与 DNS 缓存，大幅降低了首图加载的握手延迟（TLS/TCP 握手0等待）。
* **精准缓存控制**：设置了合理的图片磁盘缓存阈值 (128MB)，既保证了秒级阅读，又防止了 App 体积过度膨胀。
* **UI 渲染降级**：通过骨架屏结构 (Skeleton) 掩盖数据加载等待期，提升用户直观感受。

### 🔄 坚如磐石的应用内更新
* **防污染下载**：采用原生系统 `DownloadManager` 获取最新 APK。
* **多代理自动降级**：鉴于国内 GitHub 访问网络环境极差，更新器内置了 `gh-proxy` → `mirror` → `直连` 三级代理轮询策略，确保更新包 100% 下载成功。
* **免打扰弹窗**：更新进度内联于弹窗中，包含断网等异常下的重试策略，并采用语义化版本 (`v1.2.1 > v1.2`) 进行严谨检测。

## 📥 下载安装

请前往 [Releases 页面](https://github.com/Kousoyu/Drift/releases/latest) 下载最新版本的 `.apk` 文件进行安装。

> **当前最新版本**：**v1.0.0** (重新起航版)   
> *支持 Android 8.0 (API 26) 及以上版本系统。*

## 🛠️ 本地编译与开发

项目采用 Android Studio (JBR/Java 21) 进行构建。

1. 克隆代码库：
   ```bash
   git clone https://github.com/Kousoyu/Drift.git
   ```
2. 使用 Android Studio 打开该项目。
3. 等待 Gradle 同步完成。
4. 运行 `app` 模块即可安装到测试机。

### 技术栈构成
* 语言：**Kotlin 1.9+**
* UI：**Jetpack Compose** (纯声明式 UI，现代安卓规范)
* 网络：**Retrofit2** + **OkHttp3** + **Jsoup** (用于解析后端网页数据)
* 数据库：**Room** (本地书架存储维护)
* 图片渲染：**Coil** (现代、轻量级 Kotlin 图片加载库)

## 📌 TODO / 后续规划
- [x] 书架模块：添加小说与漫画各自的阅读进度。
- [x] 小说页面下拉刷新与书架新章追更检测 (蓝点角标)。
- [ ] 书架排序系统进阶：增加 "最近阅读优先" 的通用逻辑。
- [ ] 离线下载与章节缓存功能。
- [ ] 大文件/超大章节 UI 层的延迟加载长列表优化组件拆分。

## 📜 许可协议

本机完全开源。代码遵循 **MIT License** 许可协议。  
**注意**：本程序仅作为技术交流与页面解析研究之用，不提供任何中心化的服务器数据，所有数据均动态实时抓取自第三方公共网络。

---
*由 [Kousoyu](https://github.com/Kousoyu) 倾力打造。享受纯粹的阅读。*
