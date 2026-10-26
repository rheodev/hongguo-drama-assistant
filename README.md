# 短剧切集助手

Kotlin 原生安卓 APK。开启无障碍服务并主动开始后，同时识别红果页面中的“广告”及“上滑继续观看短剧”，自动上滑一次。

**该提示可能在广告播放中出现，助手不判断是否完整播放。** 首版仅适配剧集流中的插播广告，不处理独立激励广告。

## 安装与使用

1. 安装构建产物 `app/build/outputs/apk/benchmark/app-benchmark.apk`。
2. 打开助手，阅读权限说明，在系统无障碍设置中开启“短剧切集助手”。部分 Android 13+ 手机需要先在应用信息菜单中允许受限设置。
3. 点击“开始自动切集”，再打开红果短剧。悬浮控制默认缩进屏幕边缘，仅露出小把手；第一次点击只展开，展开后点击播放/暂停图标才切换运行状态。展开后可拖动换边，松手后就近贴边，三秒无操作自动收起。操作前始终检查当前应用。
4. 锁屏、服务重启或滑动失败后需手动重新开始。关闭系统无障碍服务即可移除悬浮按钮。

1.0.2 的展开按钮采用播放/暂停矢量图标、48×48dp 点击区域及 40% 透明背景。收起后只露出约 8dp，边缘触摸区域为 20×48dp。收起使用一次性三秒延时，没有循环检测或持续动画；锁屏和服务关闭时取消该任务。

目标应用为红果短剧（`com.phoenix.read`）。页面文案或无障碍节点发生变化时，需要重新验证兼容性。

`benchmark` 为经过 R8 优化、资源压缩且使用本机调试证书签名的可安装测试版，不是公开发行签名。后续安装更新需使用同一证书；正式分发前应配置自己的发布密钥。

## 本地开发

需要 JDK 17、Android SDK 36、Build Tools 36.0.0。通过 `ANDROID_HOME` 或未提交的 `local.properties` 指定 SDK 目录。

```sh
./gradlew testDebugUnitTest       # 纯逻辑测试
./gradlew lintDebug              # Android 静态检查
./gradlew assembleBenchmark      # 优化且签名的测试 APK
./gradlew assembleRelease        # 优化的未签名发行 APK
adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk
```

构建固定使用 Gradle 9.3.1、AGP 9.1.0 及内置 Kotlin。应用运行时仅依赖 Kotlin 标准库与安卓系统 API；JUnit 仅用于开发测试。

## 结构与触发逻辑

- `app/src/main/java/dev/hongguo/dramaassistant/`：无障碍服务、原生界面、悬浮控制及纯逻辑状态机。
- `app/src/main/res/`：权限配置、主题、文字及图标。
- `app/src/test/`：广告识别、去重、事件合并与取消测试。
- `scripts/measure.py`：通过服务诊断接口采集短时计数及进程 CPU 增量。

事件只订阅红果的窗口、内容及滚动变化，系统事件合并间隔 200ms。真机发现播放器持续发送整页更新，因此事件触发的页面检查合并到最多每 500ms 一次，不延迟已有任务，响应目标仍为 800ms 内。通过系统文字查询先找继续观看提示，命中后再查广告标记；验证滑动结果时检查两者。不逐个读取整棵控件树，也不依赖混淆的控件 ID。

广告只滑一次；手势完成后等待三秒，仅检查一次结果。失败暂停，不循环重试。暂停时退订事件并清空任务。无事件时没有周期扫描。

无截图、OCR、网络权限、唤醒锁、定时保活或额外前台服务。悬浮按钮无持续动画。最近 50 条操作记录仅存内存，界面不在前台时不刷新记录；性能计数仅在查看时读取。

## 验证与耗电

```sh
python3 scripts/measure.py --serial DEVICE_SERIAL --duration 30 --expected-state paused --output captures/paused.json
# 开始自动化并在红果播放后，再采样一次
python3 scripts/measure.py --serial DEVICE_SERIAL --duration 30 --expected-state running --output captures/running.json
```

脚本不会开启服务、改变手机设置或重置电池统计。它只在开始和结束时读取诊断信息，不周期轮询。结果中的 CPU 包含诊断开销；短测不能直接推导小时耗电。

长时验证另行进行：相同亮度、音量、刷新率及网络条件下，交替测试助手禁用和运行，每组 20 分钟、各三次。保持供电条件一致，记录温度、播放内容与系统电池估算。广告内容、网络和系统缓存均会引入误差，不能只凭电池百分比宣称“最低耗电”。

当前未完成长时耗电与多设备兼容性验证。

## 开源许可

本项目采用 [MIT License](LICENSE)。
