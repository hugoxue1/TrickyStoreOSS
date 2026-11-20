# CHANGELOG-ckb — TrickyStoreOSS ckb 系谱版本迭代记录

> **范围**：本文件只记录 **ckb 系谱**（本仓 fork 后的全部本地修改线）的版本迭代。
> 上游（beakthoven/TrickyStoreOSS）的变更见 [changelog.md](changelog.md)，本文件不重复。
>
> **系谱**：fork 自 `beakthoven/TrickyStoreOSS` v3.0.0 代码线（fork 点 `f76aac0`，2026-08-20；
> 上游 v3.0.0 tag = `f57cf4f`）。`origin` = `github.com/hugoxue1/TrickyStoreOSS`，
> `upstream` = `github.com/beakthoven/TrickyStoreOSS`。开发主线分支 `upgrade-test`
> （v3.1.1-ckb1 起）；`main` 停留在 v3.1.0-ckb1（c653d35）+ 一笔 P0-2 等价修复（`2eda474`，未并回 upgrade-test）。
>
> **版本纪律（此后每次 ckb 发版必须执行）**：
> 1. 每个新版本必须在**本文件顶部**（现有版本之前）追加一节，含：版本号、日期、git commit 范围（短哈希）、设备部署（哪台设备何时刷了哪个 build，附证据）、变更分组（features/fixes/build）、已知问题、证据文档链接；
> 2. 版本号统一改 `app/build.gradle.kts` 的 `verName`（versionCode = `git rev-list HEAD --count`，自动生成，构建 zip 名 = `Tricky-Store-OSS-<verName>-<count>-<hash>-<variant>.zip`）；
> 3. 关键坑必须同步入 docs 仓编号档并在本节引用（相对路径，如 `docs/工程项目总览/219_....md`）;
> 4. 设备侧热修必须回写源码入库（先例：A13 热修 v4/v5/v6 → `11c98d9`/`ad93e93`/`722707a`）——只有热修没有 commit = 迭代记录断链，禁止；
> 5. 改机循环会整体还原模块与配置（219 §7.4 实录）——改机后必查模块版本 / keybox 系谱与格式 / target 名单三件。

---

## v3.1.2-ckb1（2026-11-19）

- **commit**：`52eb6a9`（2026-11-19 00:16:51 +0800，upgrade-test）。变更研制日期 2026-11-18（docs 改机环境治理/218 号档当日闭环）。
- **版本号**：`verName` v3.1.1-ckb1 → v3.1.2-ckb1（`52eb6a9` 内一并提交）。
- **commit 范围**：`b96f736..52eb6a9`。
- **在役构建**：`Tricky-Store-OSS-v3.1.2-ckb1-232-b96f736-Release.zip`（sha256 `a00b9834…`）——
  **构建自提交 52eb6a9 之前的工作区**（b96f736 时点 count=232，版本号改动在工作区未提交时出包），
  故设备 module.prop 显示 `v3.1.2-ckb1 (232-b96f736-release)`。`52eb6a9` 本身 count=233。

### 设备部署

| 设备 | 日期 | 结果 |
|---|---|---|
| A13（22095RA98C，MIUI14/A13，keystore2） | 2026-11-18 夜 | 全套实测闭环：注入 boot+46.2s（旧版 ~155s）；Hunter v6.72 108 项高风险 0；Momo v4.4.1 双绿（仅 Debugging mode）；12 分钟稳定（三进程零重启、FATAL 0）。证据：`docs/工程项目总览/改机环境治理/218_TrickyStoreOSS开机生效窗口压缩_健康门多信号改造_2026-11-18.md` §三/§四 |
| MI9（cepheus，MIUI/A11，legacy `keystore`） | 2026-11-19 凌晨 | 装机成功（module.prop 显 v3.1.2 (232)，含 service.apk 3429399B）；00:04 reboot 后 adb 未回归，**时延/门退出路径/三件套/Hunter/Momo/12min 复测未闭环**。证据：同上 218 号档 §六 |

### 变更

**features/fixes（仅 Kotlin daemon，shell 层未动）**
- 健康门重写（`Main.kt` `waitForHealthyKeystore`）：固定 120s×1s `KeyStore.load` 死等 → 多信号条件门：
  ① load 成功快路径（辅助线程跑 + `join(1500ms)`）；② `boot_completed=1` + `init.svc.keystore2|keystore=running` + 同一 keystore PID 连续存活 ≥10s（PID 稳定窗，防 keystore 崩溃重启循环——历史互锁事故病灶的实质防护）；③ 60s 硬上限兜底 "injecting anyway"。每轮 logcat 可审计退出路径；参数支持 `persist.tsoss.health_cap_ms` / `persist.tsoss.pid_stable_ms` 现场覆盖。
- probe 并行化：`AttestUtils.probe()` 移入后台与健康门并行（约束保持：probe 结果先于拦截器上线就绪）；AAID 120s 超时本轮未动，新增实际等待时长日志。
- 背景：A13/MI9 双机的 `KeyStore.load` 在 daemon 上下文恒 fail → 旧 120s 门 = 纯延时器（207 §十三、改机 218 §一），压缩不改终态语义。

**build**
- 版本号 bump（见上）；无打包脚本改动。

### 已知问题
- MI9 复测未闭环（218 号档 §七 遗留 1；铁律 19：A13 绿 ≠ MI9 绿）。
- 2026-11-20 MI9 改机循环把模块+配置整体还原为车队模板（v3.1.2-232 → 230-722707a 模板，keybox/target 同毫秒覆盖）——当日发现并修复配置层（详见 v3.1.1-ckb1 节 2026-11-20 行与部署对照表）；**模块 zip 是否重刷回 232 未在档明示**。
- AAID 120s 超时是否缩短：待多 boot 数据再定（218 号档 §七 遗留 2）。

### 文档引用
- `docs/工程项目总览/改机环境治理/218_TrickyStoreOSS开机生效窗口压缩_健康门多信号改造_2026-11-18.md`（主档：改造+A13 实测+MI9 研判/部署）
- `docs/工程项目总览/207_TrickyStoreOSS_上游升级评估与回滚_2026-10-30.md`（头部 2026-11-18 增补：120s 门定谳修订）

---

## v3.1.1-ckb1（2026-10-30 ~ 2026-11-13）

- **版本号来源**：无独立 bump commit——`verName` v3.1.0-ckb1 → v3.1.1-ckb1 在上游合并 commit `e93407f`（2026-10-30 06:40:29 +0800）内一并完成。
- **commit 范围**：`c653d35..b96f736`（upgrade-test first-parent 序）。
- **在役构建**（有部署实证的两个）：
  - `v3.1.1-ckb1 (223-718493d-release)`——2026-10-31 E2 检查点构建（count=223），MI9 在役（2026-11-19 adb 基线实测；含 0a242f4 120s 门、不含 11c98d9 A13 修复、模块目录 classes.dex 无 service.apk=407fd55 之前形态）；
  - `230-722707a`——2026-11-10 workspace sync 构建（count=230），存在于 MI9 改机车队模板（219 §7.4），并作为 2026-11-20 覆盖 MI9 的模板版本被 adb 见证。

### 阶段一：上游升级（2026-10-29 ~ 10-31）——升级受阻→定谳→三层修复

- `e93407f`（10-30）merge upstream v3.1.0+34 commits（`f76aac0..dda5277`）——保留全部 CKB 定制；采纳上游 probe 三态架构 / KeystoreInterceptor keystore2 重写 / supervisor 预算 / Logger。
- `50cf715` / `4f3c56d`（10-30）合并编译修复：KeystoreInterceptor 整体取上游（旧 IKeystoreService 架构废弃）；inject 调用点 `inherited_lib_fd<0` 表达式 + Credentials/Logger 转换——zipRelease 构建通过。
- `3518bf5`（10-30 08:55）post-fs-data 等 keystore2 就绪再启动 daemon——新架构 probe 在 keystore2 未启动时抛 ISE 且 FAILED 被永久缓存（AUTO 全落 GENERATE 的根因）。
- `a1b0b3e`（10-30 09:16）probe FAILED 兜底改判 LEAF_HACK——daemon 上下文假阴性不再落 GENERATE（Hunter 必检出）；UNAVAILABLE 维持不拦截。
- `c44dbed`（10-30 09:32）keystore2 注入与 system_server 启动竞态修复——后台等 boot_completed 再注入（开机循环第一层根因，修复后 boot 完成恢复）。
- `2b52e3a`（10-30 11:56）daemon 注入等 boot_completed + keystore2 就绪 + 单实例守卫——keeper/post-fs-data/service 任意启动路径统一竞态防护。
- `216e15e`（10-30 15:23）daemon 脚本 flock 内核级单实例锁（post-fs-data 与 keeper 并发路径互斥）。
- `e3e2711`（10-30 16:11）flock 在 Android shell 不存在 → 改锁文件 + pid 存活校验。
- `dd39fd9`（10-30 16:16）pid 复用误判 → cmdline 验证 TrickyStoreOSS 才算活实例 + 开机清旧锁。
- `0a242f4`（10-30 17:07）**keystore 互锁根治**：注入前 keystore 健康门（120s 带超时探测）+ 初始化重试上限 60 次 + 退避——半死 keystore 不再被注入拖死全体 App（settings FinalizerWatchdog 60s 超时实证；"桌面卡死→自动重启"模式根治）。
- `718493d`（10-31 14:02）E2 开工前检查点：CHANGELOG 更新入库（upstream 格式重写）——**= 223 构建**。

**升级受阻定谳（2026-10-30，207 号档全链）**：MI9 部署失败三层收敛——①注入时序竞态（已修 c44dbed）；②注入协议与 MIUI A11 keystore 互等死锁（双 wchan 铁证，上游新架构缺陷）；③新版注入器远程调用序列 × MIUI A11 keystore 不兼容（ESRCH errno 级证据链）。当时处置：回滚旧模块保产、tricky_store 禁用；合并成果保全于 origin/upgrade-test。10-30 晚 `0a242f4` 根治组合部署 MI9 验证通过（207 §十三）。11-01 MI9 重刷后恢复记录为 "TrickyStore v3.1.0-ckb1 启用且健康"（212 号档 ⚡增补）。

### 阶段二：A13 熔断双重坑根治 + MI9 service.apk/守护链修复（2026-11-04 ~ 11-05）

- 背景：A13 于 2026-11-04 装入 v3.1.1-ckb1（11c98d9 之前的构建，确切 hash 未定谳）→ keystore 等待属性按 MI9 命名写死（`init.svc.keystore`；A13 = `keystore2`）→ 每次开机内联死等 120s → 定制 APatch deferred 模块阶段 6s 看门狗超时 → `fail_module_stage` 熔断批量禁用四模块（217 号档）。
- `11c98d9`（11-05 01:21）A13 双重坑根治：keystore 双命名等待 + post-fs-data 零阻塞 + unshare 孤儿化 + 循环单实例守卫 + 熔断指纹自愈看门器。
- `ddcb5e6`（11-05 01:21）`.gitattributes` 锁定 module 脚本 LF（Android sh 对 CRLF 敏感）。
- `ecd6762`（11-05 08:54）打包 `module/keybox.xml` 换装 **TEE 系谱**有效钥匙箱——旧软件系谱 EC 叶子 2026-01-08 已过期（217 §7.2）；新箱叶子/中间 2030-12-19、根 2034-11-18；A13 实测 Hunter chainTrust 通过（高风险 9→6→1→全绿）。
- `407fd55`（11-05 09:44）打包 service.apk **全变体**产出——release 分支只抽 classes.dex 致 A11（MI9）daemon ClassNotFound 静默死亡 → 无注入 → momo TEE broken（217 §7.1c-bis 实录）；release APK 签名链修复后可直接做 CLASSPATH。
- `ad93e93`（11-05 10:31）post-fs-data.sh **v6**——派生方式回退普通子 shell 后台化（去 unshare：MI9 boot apd 域拒绝 mnt ns → 整链失败，217 §7.1c）+ 全部修复保留（双命名等待/零阻塞/守卫/自愈看门器）；daemon 守护循环含 boot 竞态等待。MI9 重启终验全链闭环（217 §7.1c-bis）。
- `a41a99c`（11-05 13:24）module/keybox.xml 加部署警示注释（勿换 TEE 系谱箱，momo v4.4.1 识别该系谱链）——**该 commit 把注释放在 `<?xml?>` 声明之前，为 v3.1.1 期内最大隐患**（见阶段三）。

**MI9 keybox 系谱反撤（2026-11-05 13:0x，217 §7.1c-bis 终局修复）**：momo v4.4.1 认得 TEE 系谱链（title=TEE serial=97523f88）→ 报 TEE 损坏；keybox **回滚旧软件系谱版**（git `ecd6762~1` 的 module/keybox.xml，7513B）→ momo TEE 损坏消除。**铁律确立：momo 场景 = 旧软件箱；勿用 TEE 系谱箱**（详见下"keybox 系谱平行记录"）。

### 阶段三：workspace sync + keybox 结构修复（2026-11-10 ~ 11-13）

- `722707a`（11-10 10:32）chore: sync workspace changes 2026-11-10（module/post-fs-data.sh 45 行回写；= **230 构建**）。
- `0aadaaf`（11-13 13:14）**关键修复**：module/keybox.xml 修复拼接损坏——`a41a99c` 加警示注释时把注释放在 `<?xml?>` 声明之前，XML 规范要求声明必须在文件首字节 → TS 解析必失败（XmlPullParserException）→ 拦截退化 → Hunter 属性/证书矛盾三卡；A13 设备实锤修复（Momo/Hunter 双绿）。警示内容移至 README；新增加载校验防复发。
- `b96f736`（11-13 13:15）README 加 keybox.xml 结构纪律警示（声明必须首字节/打包后必须解析器校验）。

### 设备部署（本版本期间）

| 设备 | 日期 | 构建 | 结果 |
|---|---|---|---|
| MI9 | 2026-10-29~30 | e93407f..4f3c56d 试合并部署 | 升级受阻定谳，回滚保产（207 号档） |
| MI9 | 2026-10-30 晚 | 0a242f4 根治组合 | "桌面卡死→自动重启"根治，部署验证（207 §十三） |
| MI9 | 2026-11-01 | 重刷后恢复（212 记录为 v3.1.0-ckb1） | TS 启用且健康 |
| MI9 | 2026-11-01~11-19 之间（确切刷入日**未定谳**） | 223-718493d | 在役；2026-11-19 adb 基线实测在案（改机 218 §六） |
| A13 | 2026-11-04 | v3.1.1-ckb1（11c98d9 前构建，hash 未定谳） | 熔断批量禁用（217 号档） |
| A13 | 2026-11-05 | 设备热修 v4/v5 + 源码 11c98d9/ddcb5e6 + ecd6762 箱 | keystore2 注入成功；Hunter 全绿零检出（217 §四/§7.1e） |
| MI9 | 2026-11-05 09:2x | 自建 v3.1.1-ckb1（ecd6762）+ 当日热修 407fd55/ad93e93 | daemon 复活全链闭环；momo TEE 损坏→回 sw 箱消除（217 §7.1c-bis） |
| MI9 | 2026-11-07 | v3.1.0-ckb1 + sw 旧箱平衡态定稿（218 root §六） | momo 移出 target=真 TEE 直出；TS 服务其余名单 |
| A13 | 2026-11-10 | 230-722707a（源码 sync；**230 zip 是否刷 A13 未定谳**） | — |
| MI9 | 2026-11-20 | 被改机循环覆盖为 230-722707a 模板（详见部署对照表） | Hunter 红脸三层因果定谳并修复 |

### 已知问题（v3.1.1-ckb1 遗留）
- MIUI A11 legacy keystore 上 TSOSS 服务形态与 momo v4.4.1 根本性互斥（217 §7.1e-bis/§7.1h；见"未定谳/待验证"节）。
- RAW_AVD_SEQUENCE（sepolicy live 加载痕迹，217 §7.1c-bis 新检出；HUNTER-03）。
- MI9 daemon ~1 小时静默死亡（外部 SIGKILL 主嫌，217 §7.1e-bis 终态快照 2；后经 v6 守护链复验存活）。
- keybox 拼接损坏事故（a41a99c → 0aadaaf）——设备侧残留形态在 2026-11-20 被改机循环再次激活（219 §7.1①）。

### 文档引用
- `docs/工程项目总览/207_TrickyStoreOSS_上游升级评估与回滚_2026-10-30.md`
- `docs/工程项目总览/212_AI接手手册_2026-10-31交接.md`（§⚡11-01 增补：TS 禁用禁令/健康判据三件套；11-05 增补：熔断指纹）
- `docs/工程项目总览/改机环境治理/217_A13模块批量禁用与TrickyStore双重坑_2026-11-05.md`
- `docs/工程项目总览/218_TS类引擎进化参考与keybox系谱策略_2026-11-07.md`（根目录 218）
- `docs/工程项目总览/219_设备面API切换console域名_激活全量恢复_2026-11-20.md`（§七）

---

## v3.1.0-ckb1（2026-08-22 ~ 2026-09-08）

- **commit 范围**：`d66caf2..c653d35`（12 笔定制提交，main 分支；fork 点 `f76aac0`）。
- **版本号**：`verName` v3.0.0 → v3.1.0-ckb1（`40aa544`，2026-09-07 23:00:31 +0800，含完整定制变更日志入仓 CHANGELOG.md）。

### features
- `d66caf2`（08-22）**APatch exact injection**：customize.sh 检测 `su --inject-capable` + `.apatch_exact_inject_v1` marker；inject/main.cpp `APATCH_INJECT_LIBRARY_FD` 继承 FD 路径；InterceptorUtils exact 模式（`su --inject-target PID --inject-library PATH`）；Config.kt 强制 `teeBroken=false`（LEAF_HACK）。机制档：`docs/工程项目总览/105_APatch_Exact_Inject_TrickyStoreOSS_交接文档_2026-08-22.md`。

### fixes
- `16d02ed`（08-22）keystore2 类 lazy 加载——Android 10/11 NoClassDefFoundError 兼容。
- `38c48da`（08-22）attestKey 在 LEAF_HACK 模式处理（Android 10/11）——异步 callback（IKeystoreCertificateChainCallback）不走 reply Parcel → onPreTransact 伪造（105 号档 坑10；MI9/A11 momo TEE 检测通过）。
- `e85dca9`（08-24）daemon 启动移入 post-fs-data.sh——APatch domainless 模式不执行 service.sh（105 号档 §八；08-23 Redmi light/A13 实录修复）。
- `e2715fd`（08-24）service.apk 替代 bare classes.dex 作 app_process classpath。
- `ea90741`（08-24）service.apk 用真 APK（app-release-unsigned.apk）而非裸 dex。
- `39b1001`（08-26）**daemon 重启循环永久死亡修复**——`|| exit 1` 杀死 while 循环 + Kotlin 三处 `exitProcess(1)` → Momo 偶发报 bootloader unlocked。机制档：`docs/工程项目总览/129_TrickyStoreOSS_daemon_重启循环永久死亡_坑记录_2026-08-26.md`。
- `dba14c4`（09-07）service.apk 用 debug keystore 签名——Android 11 unsigned APK 拒载 dex → `ClassNotFoundException: MainKt` → SIGABRT → watchdog 重启循环（fix-1）。
- `e6a5912`（09-07）inject/main.cpp fingerprint/biometrics 进程排除——消除 FORTIFY `pthread_mutex_lock called on a destroyed mutex` 崩溃（fix-2）。

### build
- `edf4b2a`（09-07）cherry-pick 上游 v3.1.0 功能修复（`9096730` ECDSA 备用名 + `2696256` logd 未就绪跳日志）（fix-3）。
- `40aa544`（09-07）版本号 + 定制变更日志。
- `c653d35`（09-08）签名配置修复：`signingConfigs.create('release')` 引用项目根 debug.keystore（与内建 debug 冲突）。

### 设备部署
- MI9（A11）：08-22 exact inject 闭环验证（policyload=0、注入成功、momo 过、Hunter policyload 类检测消除）——105 号档 §1.3；08-26 momo bootloader 修复——129 号档；09-07 service.apk 签名链修复后 A11 正常（commit dba14c4 消息）。
- Redmi light（A13，HyperOS；105 号档记载，**与现役 A13 22095RA98C 是否同一台未定谳**）：08-23 daemon post-fs-data 启动修复——105 号档 §八。

### 已知问题（当代记录）
- APatch su 未就绪时回退 legacy 注入静默失败（129 号档 §三隐患，v3.1.1 线修复）。
- Hunter `TEE check` / `Find Verified Boot Mark` 待最终验证（105 号档 §1.3）。

### 文档引用
- `docs/工程项目总览/105_APatch_Exact_Inject_TrickyStoreOSS_交接文档_2026-08-22.md`
- `docs/工程项目总览/129_TrickyStoreOSS_daemon_重启循环永久死亡_坑记录_2026-08-26.md`
- 仓内 `changelog.md` @ `40aa544`（"TrickyStore OSS 定制版变更日志" 46 行，2026-10-31 `718493d` 被 upstream 格式重写覆盖——ckb 内容仅存于该 commit 历史）

---

## v3.0.0（fork 基线，上游）

- 上游 beakthoven/TrickyStoreOSS v3.0.0：tag `v3.0.0` = `f57cf4f`（2026-08-03）；`verName=v3.0.0` 设于 `08244b2`（2026-07-20）。
- ckb fork 点：`f76aac0`（2026-08-20 23:14:56 +0530，"app: Reformat kotlin files"，fork 时上游 main HEAD；v3.0.0 tag 之后、上游 v3.1.0 tag `41383f5` 之前）。
- 本版本无 ckb 修改；全部门外内容归上游 changelog.md。

---

## 部署-版本-设备对照表

> 构建 = `versionCode-短hash`（versionCode = `git rev-list HEAD --count`）。keybox 系谱：**sw** = 旧软件系谱（Google SW attestation，DeviceID="sw"，EC 叶子 2026-01-08 过期）；**TEE** = TEE 系谱（title=TEE serial=97523f88，EC+RSA，2030/2034）。"—" = 当时未记录。

| 日期 | 设备 | 模块版本 | 构建 | keybox 系谱 | 结果 / 证据 |
|---|---|---|---|---|---|
| 2026-08-22 | MI9 (A11) | 开发版（pre-v3.1.0-ckb1） | d66caf2/16d02ed/38c48da 时点 | — | exact inject 全链验证 ✅（105 §1.3） |
| 2026-08-23 | Redmi light (A13 HyperOS) | 开发版 | e85dca9 | — | daemon post-fs-data 启动修复 ✅（105 §八） |
| 2026-08-26 | MI9 | 开发版 | 39b1001 | — | momo bootloader 偶发检出修复 ✅（129） |
| 2026-09-07 | MI9 (A11) | v3.1.0-ckb1 | 40aa544（dba14c4 签名链） | — | service.apk debug 签名修复 A11 ClassNotFound ✅ |
| 2026-09-08 | （通用） | v3.1.0-ckb1 | c653d35 | — | release 签名配置修复；**main 停留此点=在役基线** |
| 2026-10-29~30 | MI9 | v3.1.1-ckb1 试合并 | e93407f..4f3c56d | — | 升级受阻定谳（互锁死锁+注入器缺陷），回滚保产（207） |
| 2026-10-30 晚 | MI9 | v3.1.1-ckb1 | 0a242f4 时点 | — | 健康门根治"桌面卡死→自动重启" ✅（207 §十三） |
| 2026-11-01 | MI9 | 记录为 v3.1.0-ckb1 | — | — | 重刷后恢复，TS 启用且健康（212 ⚡增补） |
| 2026-10-31~11-19 间 | MI9 | v3.1.1-ckb1 | **223-718493d** | sw（过期） | 在役；11-19 adb 基线实测（改机 218 §六；确切刷入日未定谳） |
| 2026-11-04 | A13 (22095RA98C) | v3.1.1-ckb1（11c98d9 前） | hash 未定谳 | sw（过期） | 熔断批量禁用事故（217 §一~三） |
| 2026-11-05 | A13 | v3.1.1-ckb1 热修 v4/v5 | 11c98d9/ddcb5e6 | **TEE**（ecd6762） | keystore2 注入成功；Hunter 9→全绿 ✅（217 §四/§7.1e） |
| 2026-11-05 | MI9 | v3.1.1-ckb1 自建+热修 v6 | ecd6762→407fd55/ad93e93 | TEE→13:0x 回 **sw**（7513B, `ecd6762~1`） | 全链闭环；momo TEE 损坏消除 ✅（217 §7.1c-bis） |
| 2026-11-07 | MI9 | v3.1.0-ckb1（平衡态定稿） | — | sw（7513B ffcf2175） | momo 移出 target=真 TEE 直出；TS 服务其余名单（根 218 §六） |
| 2026-11-10 | A13 | v3.1.1-ckb1 | **230-722707a** | TEE | 源码 sync 入库；230 zip 是否刷 A13 未定谳 |
| 2026-11-18 夜 | A13 | v3.1.2-ckb1 | **232-b96f736**（sha256 a00b9834…） | TEE | 注入 boot+46.2s；Hunter 108 项 0 高风险；Momo 双绿；12min 稳定 ✅（改机 218 §三/§四） |
| 2026-11-19 凌晨 | MI9 | v3.1.2-ckb1 | **232-b96f736** | TEE（模板态） | 装机成功；reboot 后 adb 未回归，复测未闭环（改机 218 §六） |
| 2026-11-20 | MI9 | **被改机循环覆盖**：232 → 230-722707a 模板 | 230-722707a | a41a99c 时代**损坏形态**（注释在声明前）→ 仓库修复版（0aadaaf）→ **sw**（`ecd6762~1`） | Hunter 红脸三层因果定谳；终态 sw 箱+hunter 回 target=全绿；momo 转圈未定谳（219 §七/§7.4） |

---

## keybox 系谱平行记录（与版本迭代并行的钥匙箱史）

| 系谱 | 特征 | 检测器反应 | 有效期 |
|---|---|---|---|
| **sw 旧软件箱** | Google SW attestation 泄露系谱，`DeviceID="sw"`；仓库版 = `ecd6762~1` 的 module/keybox.xml（7513B）；MI9 设备侧 ffcf2175（217 同时记 7627B §7.1c-bis / 7513B §7.1i，差异未定谳） | momo v4.4.1 **不识别（过）**；Hunter chainTrust 不深验有效期（v6.72 MI9 2026-11-20 实证绿；A13 2026-11-05 实证 FAILED，见未定谳节） | EC 叶子 notAfter **2026-01-08（已过期）**；根 2016-2036 |
| **TEE 系谱箱** | `title=TEE` serial=**97523f88**，`DeviceID="Luis tryigit.dev/keybox"`，EC+RSA 双钥匙 | momo v4.4.1 **识别系谱链（报 TEE 损坏）**；Hunter bundled 信任通过（A13 2026-11-05 高风险 9→1→全绿） | 叶子/中间 2030-12-19；根 2034-11-18 |

**换装史**（时间序）：
1. ~2026-11-05 前：双机均为 sw 旧箱（过期未知）。
2. 2026-11-05：A13 排查 Hunter chainTrust=FAILED → 定谳过期（217 §7.2）→ `ecd6762` 打包源换 TEE 箱 + A13 设备换装；同日 MI9（§7.1c-bis）先换 TEE 箱，13:0x 因 momo 识别系谱回滚 sw 箱 → **铁律：momo 场景用 sw 箱**（根 218 §五）。
3. 2026-11-05 13:24：`a41a99c` 给 module/keybox.xml 加警示注释——**注释误置于 `<?xml?>` 声明前 = 拼接损坏形态**。
4. 2026-11-13：`0aadaaf` 修复结构（声明首字节）+ 加载校验；`b96f736` 警示移入 README。
5. 2026-11-20（MI9，219 §七）：改机循环从车队模板还原 **a41a99c 时代损坏形态** keybox → `XmlPullParserException` → 拦截器注册成功但**零钥匙箱材料**（tee_status 迟迟不写）→ 换仓库修复版 → 换试验 TEE 箱（踩铁律，Hunter 深验红）→ 终态换回 **sw 旧箱**（git `ecd6762~1` 原版）+ hunter 回 target = Hunter 全绿（复现 11-07 平衡态）。
6. 教训（219 §7.4）：改机模板/备份里的 keybox+target 应按机型分设备维护（A13 用 TEE 箱是对的；MI9 用 sw 旧箱）。

---

## 未定谳 / 待验证

1. **过期箱 vs Hunter chainTrust 三方矛盾（217 §7.1h vs §7.2 vs 根 218 §五）**：217 §7.2（A13，2026-11-05）判过期 sw 箱 `chainTrust=FAILED`；根 218 §五（11-07）称 Hunter "不深验有效期"；217 §7.1h（MI9）旧箱 Hunter 全绿。219 §7.3（2026-11-20）补上 MI9 侧数据点：**sw 旧箱 + Hunter v6.72 = 绿（MI9 成立"过期不被深验"）**——但三档表述仍未统一收口，A13 侧 FAILED 的触发条件（检测面/版本差异）未定谳。留档矛盾，不删不改。
2. **momo v4.4.1 × TSOSS 服务形态在 MI9（A11 legacy keystore）的根本性互斥**（217 §7.1e-bis/§7.1h）：TS 启用（注入中）→ momo 报 TEE 损坏；TS 禁用（真 TEE 直出）→ momo 过但 Hunter 报 bootloader 解锁。二选一无第三路（TEESimulator canary-76 在 MI9 的 A11 服务层路由缺口未解，217 §7.1i）。结构性未解。
3. **2026-11-20 momo 扫描挂起观察**（219 §7.2）：momo 回 target 后扫描长时间转圈未出结果——是否为互斥的新变种**待定位**；处置预案：若持续转圈，momo 移出 target（= 根 218 §六平衡态完全复现）。
4. **MI9 223-718493d 的确切刷入日未定谳**：窗口 2026-11-01~11-19（212 记 11-01 为 v3.1.0-ckb1；217 §7.1c-bis 记 11-05 自建 ecd6762 装机；改机 218 §六记 11-19 在役=223）。11-05 装机 → 11-19 实测 223 之间的模块恢复路径（疑似改机模板还原）无在档记录。
5. **230-722707a zip 是否实际刷过 A13 未定谳**：A13 在档链 = 11-04 初装（hash 未定谳）→ 设备热修 v4/v5/v6 → 源码 sync（722707a）→ 11-18 直升 v3.1.2。230 构建实证仅存在于 MI9 改机车队模板（219 §7.4）。
6. **v3.1.2 构建-提交时序错位**：部署 zip = `v3.1.2-ckb1-232-b96f736`，但 `b96f736` 时点 verName 仍为 v3.1.1-ckb1（count=232）；v3.1.2 bump 在出包后随 `52eb6a9`（count=233）入库——由 zip 命名 + gradle 构建逻辑推断为"工作区出包、后补 commit"，属合理重构而非在档直证。
7. **2026-11-20 MI9 模块 zip 是否重刷回 232 未在档明示**：219 §七记录的当日修复为 keybox/target 配置层；改机覆盖（232→230 模板）后模块二进制的恢复动作无直接记录。
8. **105 号档 "Redmi light"（A13，2026-08-23）与现役 A13 22095RA98C 是否同一台设备未定谳**。
9. **main ↔ upgrade-test 分叉**：main 含 `2eda474`（3518bf5 的等价修复，10-30 08:55 同刻）未并回 upgrade-test；upgrade-test 领先 56 commits。下次合流时需处理。

---

## 文档索引（ckb 线全部相关编号档）

| 档 | 内容 |
|---|---|
| `docs/工程项目总览/105_APatch_Exact_Inject_TrickyStoreOSS_交接文档_2026-08-22.md` | exact inject 接入 + A13 daemon 启动修复（v3.1.0-ckb1 主档） |
| `docs/工程项目总览/129_TrickyStoreOSS_daemon_重启循环永久死亡_坑记录_2026-08-26.md` | daemon 永久死亡机制（39b1001） |
| `docs/工程项目总览/207_TrickyStoreOSS_上游升级评估与回滚_2026-10-30.md` | 上游升级受阻全链 + 健康门由来（含 11-18 结论修订） |
| `docs/工程项目总览/212_AI接手手册_2026-10-31交接.md` | TS 红线（禁用禁令/三件套判据/熔断指纹） |
| `docs/工程项目总览/改机环境治理/217_A13模块批量禁用与TrickyStore双重坑_2026-11-05.md` | A13 熔断 + MI9 service.apk/守护链 + momo 系谱铁律 |
| `docs/工程项目总览/218_TS类引擎进化参考与keybox系谱策略_2026-11-07.md` | 引擎盘点 + keybox 系谱策略定谳 |
| `docs/工程项目总览/改机环境治理/218_TrickyStoreOSS开机生效窗口压缩_健康门多信号改造_2026-11-18.md` | v3.1.2 主档（A13 实测 + MI9 研判/部署） |
| `docs/工程项目总览/219_设备面API切换console域名_激活全量恢复_2026-11-20.md` | §七 MI9 Hunter 红脸全案 + 改机还原教训（§7.4） |
