# TrickyStore OSS 定制版变更日志

> 本 fork 基于 [beakthoven/TrickyStoreOSS](https://github.com/beakthoven/TrickyStoreOSS) v3.0.0（上游已到 v3.1.0），保留 7 个定制提交 + 3 个根因修复。

---

## v3.1.0-ckb1（2026-09-07）

### 修复

#### fix-1: service.apk debug 签名（消除 MainKt ClassNotFound → SIGABRT → 系统重启）

- **文件**：`app/build.gradle.kts`（+signingConfig）、新增 `debug.keystore`
- **根因**：service.apk 为 unsigned（APK 内零签名文件——`META-INF/*.RSA/.SF` 全无）。Android 11 的 `app_process -Djava.class.path=service.apk` 加载 unsigned APK 时，ART 的 DexPathList **拒绝解析其内 dex** → `ClassNotFoundException: io.github.beakthoven.TrickyStoreOSS.MainKt` → `SIGABRT` → tombstone → `service.sh` 的 `while true; do ./daemon` 无限重启循环反复产生崩溃事件 → system_server watchdog 超时 → 系统紧急重启
- **为什么这么修**：根因在"APK 无签名→ART 拒绝"，所以直接在构建链加签名——不修改 daemon 脚本（保留 `service.apk` 加载链——之前的 e2715fd/ea90741 修的加载方式本身没错，错在 APK 没签名）。自签 debug keystore（2048RSA 10000 天）仅满足 ART 的 APK 信任校验，不用于分发
- **效果**：`app_process -Djava.class.path=service.apk MainKt` 正常加载，Main.kt 进入 `maintainService()` 永驻——不再产生 SIGABRT/tombstone/重启循环

#### fix-2: inject 二进制加 fingerprint/biometrics 进程排除（消除 FORTIFY 崩溃）

- **文件**：`app/src/main/cpp/inject/main.cpp`（main() 函数 +33 行）
- **根因**：TrickyStore 注入 keystore 进程后 PLT hook 了 `libbinder.so` 的 `ioctl` 函数（`binder_interceptor.cpp` 的 `initializeBinderInterception()` 中 `plti_add_hook`）。fingerprint HAL（`android.hardware.biometrics.fingerprint@2.1-service`）的 binder 事务也走同一路径的 `ioctl`——hook 后的 ioctl 在指纹服务的 mutex 生命周期中打断了 FORTIFY 保护 → `pthread_mutex_lock called on a destroyed mutex` → `SIGABRT`（tombstone #29）
- **为什么这么修**：不修改 hook 逻辑本身（那是 TrickyStore 的核心功能——binder 拦截注入是全部能力的基础），仅在 **inject 二进制的入口**处读 `/proc/<pid>/cmdline`，匹配 `fingerprint`/`biometrics` 族进程名则安全跳过注入（返回 EXIT_SUCCESS——有意跳过而非错误）。这样 keystore 进程的 hook 不变（功能保留），fingerprint HAL 不被注入（冲突消失）
- **效果**：fingerprint HAL 进程不再被注入，不再产生 FORTIFY 崩溃

#### fix-3（上游 cherry-pick）: ECDSA 算法备用名 + logd 兼容

- **上游 `9096730`**：`KeyBoxUtils.kt` +1 行——`derived.uppercase()` 匹配 `"ECDSA"` 备用名→映射到 `KEY_ALGORITHM_EC`。某些 keybox 文件的 ECDSA 私钥 Java 解析返回 `"ECDSA"` 而非 `"EC"`——原来走 `else -> derived` 导致算法名不匹配
- **上游 `2696256`**：`logging.cpp` + `Logger.kt`——`SystemProperties.get("init.svc.logd") != "running"` 时跳过日志输出。防早期启动阶段 logd 未就绪时 Log.println 抛异常
- **适配**：上游将 `TAG` 常量从 public 改 private 并 reformat 了 20 个 .kt 文件——我们跳过 reformat（保护 7 个定制提交的 import 链），改为 TAG 保持 public + Logger object 并存

### 版本

- `v3.1.0-ckb1`——功能等价上游 v3.1.0 + 7 个定制 + 3 个根因修复
- 上游 12 个新提交中 10 个为 CI/构建/文档噪音（gradle bump/workflow/FUNDING.yml/changelog），已跳过

### 定制提交保留清单（全部无损）

| 提交 | 功能 |
|---|---|
| `d66caf2` | APatch exact injection 支持 |
| `16d02ed` | Android 10-11 keystore2 类延迟加载兼容 |
| `38c48da` | Android 10/11 LEAF_HACK 模式 attestKey 处理 |
| `e85dca9` | APatch domainless 模式 daemon 启动 |
| `e2715fd` | service.apk 替代 bare classes.dex 作为 app_process classpath |
| `ea90741` | 使用完整 APK 而非 bare dex 作为 service.apk |
| `39b1001` | daemon 重启循环永久死亡导致 Momo 检测修复 |
