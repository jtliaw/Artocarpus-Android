# Artocarpus Translate（Android）

悬浮条 →「框选」→ 框住对话 → 自动翻译 → 译文浮窗贴在框的下方/上方（放不下自动换到上方）。
提示词、术语表、mediaResolution、假名过多重试，均移植自 Artocarpus 的 skylark_ai.py。

## 打包（不需要安装 Android 开发环境）
1. 在 GitHub 新建一个仓库（建议单独一个，例如 `Artocarpus-Android`）。
2. 把本文件夹里所有文件（包括隐藏的 `.github` 文件夹）推上去：
   ```
   git init && git add . && git commit -m "init"
   git branch -M main
   git remote add origin https://github.com/你的用户名/仓库名.git
   git push -u origin main
   ```
3. 仓库页面 → Actions → Build APK，跑完后在页面底部 Artifacts 下载 `ScreenTranslator-APK`，解压得到 `app-debug.apk`。
4. 传到手机安装（允许“安装未知应用”）。

## 第一次使用
1. 打开 App，选翻译方式和译文语言。
   - Gemini AI：任意语言，需要自己的 API 密钥。
   - OCR 离线：只认英文，免费，不需要密钥；第一次要联网下载翻译模型。
2. 点「启动悬浮翻译」：允许悬浮窗 → 回来再点一次 → 允许截屏。
3. 平时屏幕上只有一个叶子小圆点：
   - 点一下：直接框选，拖框住对话，松手立刻翻译。
   - 长按：弹出小菜单（重译 / AI·OCR 切换 / 退出）。
   - 拖动：移动位置。
4. 之后点蓝框 = 重译同一区域。译文浮窗会在设定的时间后自动消失（也可以点一下关掉）。

## 荣耀 MagicOS 设置
设置里给本 App：悬浮窗权限、允许自启动/后台活动、电池“不受限制”。否则容易被系统杀掉。
