# 午睡闹钟

Android 午睡计时应用。上下拖动绳结设定 1～45 分钟，开始后播放内置离线助眠音频，到点切换为内置闹铃音。

## 构建和安装

需要 JDK 17、Android SDK 和 Android Studio。首次构建需要网络获取 Gradle 与依赖。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

调试安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。也可用 Android Studio 打开项目并运行。

发布包开启 R8 代码压缩与资源裁剪，体积约为调试包的四分之三：

```powershell
.\gradlew.bat :app:assembleRelease
```

签名信息不写入仓库，通过环境变量提供；未设置时 `assembleRelease` 只会产出未签名包：

| 环境变量 | 含义 |
| --- | --- |
| `NAP_KEYSTORE` | 密钥库文件路径 |
| `NAP_KEYSTORE_PASSWORD` | 密钥库密码 |
| `NAP_KEY_ALIAS` | 密钥别名，缺省为 `nap-timer` |
| `NAP_KEY_PASSWORD` | 密钥密码，缺省同密钥库密码 |

## 使用说明

- 首次使用时，需要允许通知和精确闹钟；授予权限后再次点击「开始午睡」。
- 可拖动绳结或使用加减按钮调整分钟数。点击音效名称可选择柔和旋律、雨声实录、海浪实录、粉红噪音、三首钢琴曲或静音，并预听。音量由手机控制。
- 午睡中可在页面或通知中停止，点击即结束（不再二次确认）；到点后在页面或通知中关闭闹钟。
- 设备重启后，未到期的午睡会尝试恢复闹钟；助眠音乐会暂停，并通过通知提示。若重启期间已经到点，应用会结束该次午睡并提示"未能响铃"，不会补响。
- 助眠音频与闹铃音均内置于应用，无需联网。应用不调用系统时钟应用，但使用 Android 系统调度到点提醒。

## 音频来源

雨声和海浪录音均由 Mike Koenig 创作，采用 [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) 许可。为方便循环播放，录音经过交叉淡化并转换为 OGG 格式。

- 雨声：[Rain Background](https://soundbible.com/2011-Rain-Background.html)
- 海浪：[Crisp Ocean Waves](https://soundbible.com/1936-Crisp-Ocean-Waves.html)

以下钢琴曲采用 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) 许可：

- [City of Sleep](https://matthewcwright.com/1ymr8)，Matthew C. Wright（[matthewcwright.com](https://matthewcwright.com/)）。
- [Wading in Moonlight](https://ffm.to/xoy59o7)，Matthew C. Wright（[matthewcwright.com](https://matthewcwright.com/)）。
- [Meditation Impromptu 03](https://incompetech.com/music/royalty-free/index.html?isrc=USUAN1100161)，Kevin MacLeod（[incompetech.com](https://incompetech.com/)）。

柔和旋律、粉红噪音和闹铃音为项目生成的合成音频。

## 系统限制

不同 Android 版本和厂商的省电策略可能影响提醒。强制停止应用、关机或设备重启期间不能保证响铃；重启后恢复依赖系统广播和精确闹钟权限。静音、勿扰和系统音量设置可能影响声音，请先在目标设备上验证。

Android 12 起对后台启动前台服务有限制。若闹铃到点时系统拒绝启动播放服务，应用会改用"午睡闹钟（备用提示）"通知，以系统默认闹铃音提示，而不是静默失败；同时提示"闹铃无法播放，已改用系统闹铃声音提示"。请保留该通知渠道的系统声音设置。
