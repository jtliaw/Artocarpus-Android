<p align="center">
  <img src="app/src/main/res/drawable-nodpi/artocarpus_icon.png" width="120" alt="Artocarpus Translate">
</p>

<h1 align="center">🍃 Artocarpus Translate</h1>

<p align="center">
  Android 悬浮屏幕翻译 · 玩游戏时框一下就翻译<br>
  免费 · 自己的 Gemini 密钥 · 或完全离线的英文 OCR
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B-green.svg" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Kotlin-1.9-blue.svg" alt="Kotlin">
  <img src="https://img.shields.io/badge/License-MIT-green.svg" alt="MIT">
</p>

---
注意！
62个防毒网站完全没有病毒,自己可以到这一个网址看一看：https://www.virustotal.com/gui/home/upload
---
## ✨ 这是什么？

Artocarpus Translate 是 [Artocarpus](https://github.com/jtliaw/Artocarpus) 桌面版屏幕翻译的手机版。
它在屏幕上放一个小小的叶子圆点，需要时点一下，框住想翻译的文字，译文就直接显示在旁边。

主要为玩模拟器游戏（PSP、PS1、PS2、NDS、3DS、GBA、GBC、GB 等）设计，
也可以翻译网页、聊天、漫画等任何出现在屏幕上的文字。

## 🌟 功能

- 🍃 **叶子悬浮圆点**：平时只是一个小点，不挡画面，可以拖动
- 🔴 **点一下变红，框选翻译**：拖框住文字，松手立刻翻译；再点一下变回白色并关闭
- 💬 **译文浮窗**：贴在框的下方（放不下就在上方），会自动消失，时间可以设置
- 🤖 **Gemini AI 翻译**：任意语言互译，能看懂游戏对话框和注音假名
- 📖 **OCR 离线翻译**：只认英文，完全免费，不需要密钥，第一次联网下载模型后可离线使用
- 🔁 **一键切换**：长按圆点，在 AI 和 OCR 之间切换
- 🌐 **语言选择**：简体中文、繁體中文、English、日本語、한국어、马来语、印尼语、西班牙语、法语、德语
- 🕘 **翻译历史**：保存最近 100 条原文和译文
- 🔒 **省额度**：同一画面不重复请求，图片精度默认最低

## 📲 使用方法

1. 安装 APK，打开 App
2. 选择 **翻译方式** 和 **译文语言**
3. 如果用 Gemini：填入自己的 API 密钥（免费申请：https://aistudio.google.com/app/apikey ）
4. 点 **启动悬浮翻译**，依次允许「显示在其他应用上层」和「截取屏幕」
5. 切换到游戏或任何 App：

| 操作 | 效果 |
|------|------|
| 点一下白色叶子 | 变红，开始框选 |
| 拖动框住文字 | 松手立刻翻译 |
| 点蓝色框 | 重新翻译同一区域 |
| 再点一下红色叶子 | 变回白色，全部关闭 |
| 长按叶子 | 重译 / AI·OCR 切换 / 退出 |
| 拖动叶子 | 移动位置 |

## 🔑 两种翻译方式

| | 🤖 Gemini AI | 📖 OCR 离线 |
|---|---|---|
| 语言 | 任意语言 | 仅英文 |
| 费用 | 免费额度，用自己的密钥 | 完全免费 |
| 需要密钥 | 是 | 否 |
| 需要联网 | 是 | 第一次下载模型（约 30MB），之后不需要 |
| 适合 | 日文游戏、带注音的对话 | 英文游戏、网页、聊天 |

## 🔐 隐私

- API 密钥只保存在你的手机里
- 使用 Gemini 时，框选区域的截图会发送给 Google Gemini；OCR 模式的识别和翻译都在手机上完成
- 本 App 不收集任何数据，不含广告

## 实在不了解的系统请询问AI
## 📱 荣耀 / MagicOS 设置
荣耀系统对后台管得比较严，悬浮窗软件容易被关掉，建议在设置里给本 App：

- ✅ 允许 **显示在其他应用的上层**
- ✅ 允许 **自启动 / 后台活动**
- ✅ 电池设置为 **不受限制**（不优化）

App 主界面最下面有直达这些设置页的按钮。

## ⚠️ 已知限制

- 点蓝色框重译时，这次点击不会传给游戏（安卓的限制）。模拟器用屏幕上的 A 键推进对话，影响不大；也可以在设置里关闭这个功能
- 屏幕旋转后需要重新框选
- 个别带安全保护的 App（银行、DRM 视频）截到的是黑屏
- PS2、3DS 模拟器本身比较吃性能，再叠加悬浮翻译可能更卡
- OCR 对很小的像素字体识别可能不如 Gemini 稳定

## 🛠️ 自己打包（不需要安装 Android 开发环境）

1. Fork 或下载本仓库，推送到你自己的 GitHub
2. 打开仓库的 **Actions → Build APK**，等它跑完
3. 在页面底部的 **Artifacts** 下载 `ScreenTranslator-APK`，解压得到 `app-debug.apk`
4. 传到手机安装（需要允许「安装未知应用」）

## 📁 项目结构

app/src/main/java/com/jtliaw/screentranslator/
├── MainActivity.kt 设置界面
├── TranslateService.kt 悬浮圆点、框选、截屏、译文浮窗
├── Gemini.kt Gemini 请求与提示词
├── Ocr.kt 离线 OCR 与离线翻译（ML Kit）
└── Store.kt 设置、语言列表、翻译历史


## 🙏 致谢

- [Google Gemini](https://ai.google.dev/)
- [Google ML Kit](https://developers.google.com/ml-kit)
- 桌面版：[Artocarpus](https://github.com/jtliaw/Artocarpus)

## 📄 授权

MIT License
