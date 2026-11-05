# 101-probe-app · 进程内 Node 探针载体（probe-app）功能规格

> 模块：101-probe-app · 状态：✅ 已实现（本批开发 + APK 构建；设备运行由编排者统一排队，本批**不跑设备**）
> 所属工程：aosp-node-bridge · 规划基线：[`docs/000-工程规划.md`](../../docs/000-工程规划.md)
> §5.1（分层）/ §5.2（进程与线程模型）/ §5.10（加载顺序）/ §6（消费方对接契约）
> 模块边界：本模块是**消费方**的验证实现（规划 §6 承诺/责任一分到底），**不新增库能力**。

## 1. 目的

定义「进程内 Node 探针载体」的行为契约：一个独立 Android app（`probe-app`，本仓 gradle 构建的
`:probe-app` 模块）把 `node-bridge` 库的对外接口（`NodeRuntime` / `NodeHostBridge` / `NativeBridge`
JNI 面）在真实设备上按固定主流程拉通，并逐条给出 **PASS / FAIL / BLOCKED** 判定，产出机器可读的
JSON 报告（`filesDir/probe-report.json`），作为后续多设备统一排队测试（编排者）的执行载体。

本模块只做「消费方按对接契约使用库」的执行验证，**不修改库本身**、不做业务语义、不引入网络依赖
（默认全部探针本地闭环，无外部网络请求）。

> 逆向事实源（as-built）：`probe-app/` 全部源码 + `node-bridge/src/main/java/org/fellow99/nodebridge/*.kt`
> + `node-bridge/src/main/assets/node-bridge/selfcheck.js` + `docs/000-工程规划.md` §5/§6。

## 2. 范围

**在范围内（101-probe-app）：** bridge 加载（`System.loadLibrary("node_bridge")`）、运行时启动与
状态机轮询（`start`/`isRunning`/`stage`）、`selfcheck.js` 可执行且输出断言、宿主桥双向往返
（`anb_host_call` / `pollHostRequest→completeHostRequest`）、版本契约（node-runtime 元数据 vs
`process.version`）、崩溃信标（`lastCrashJson`）可读、报告产物（`files/probe-report.json`）三态结构、
无头自动化（`--ez autorun true` intent）、UI「RUN ALL PROBES」按钮。

**不在范围内（101-probe-app）：** 库本体行为（→ 001-runtime-core/002-jni-bootstrap/003-host-bridge 等
模块）；WebView/渲染/前台服务/上架形态（→ 消费方）；`--jitless` 判定矩阵（→ P0-1，库的 Phase 0 探针）；
x86/x86_64/arm32（本仓只发 arm64-v8a，规划 §1.2/README §发布版本）；探针 App 的发布（不发布，版本号 = 配对 Node 版本）。

## 3. 功能需求

> 术语：探针（Probe）= `MainActivity` 中一条带 `id`/`title`/`check` 的记录；判据 check 返回
> `null` = PASS，`"BLOCKED:…"` = BLOCKED，其余 = FAIL（沿用 aosp-node-cli probe-app 判定约定）。

### 3.1 加载与启动域

- **FR-101-PROBE-001（MUST）**：bridge 加载成功——`probe-app` **显式**执行
  `System.loadLibrary("node_bridge")`（libnode_bridge.so 与 NAPI addon libnode_host_client.so 随
  `:node-bridge` 模块 jniLibs 合并进 APK），且 `NodeRuntime.nativeAvailable` 为真；失败时记 detail
  （.so 没进 APK / ABI 不匹配 / SELinux exec 拒绝均可被捕获）。对应探针 **PRB-01**。
- **FR-101-PROBE-002（MUST）**：`start` 启动 node 运行时——`NodeRuntime.start(NodeRuntimeOptions(entry="selfcheck.js"))`
  返回 `ok=true`（投递语义，规划 §5.2），并在超时窗口内状态机到达 `Running`（`awaitRunning` 轮询，
  判据见 001-runtime-core）。对应探针 **PRB-02** 前半。
- **FR-101-PROBE-003（MUST）**：`isRunning`/`stage` 正确——`NodeRuntime.stage` 返回取值
  `init|stdio|env|dlopen|dlsym|start|exited|failed` 之一且不触发 `failed`；`state` 为 `Running` 时进程存活
  （`pid > 0`）。对应探针 **PRB-02** 后半。

### 3.2 载荷与桥域

- **FR-101-PROBE-004（MUST）**：`selfcheck.js` 可执行且输出符合预期——库把
  `assets/node-bridge/`（bootstrap.js/host.js/selfcheck.js）物化到 `<filesDir>/node-bridge/payload` 后，
  以 `selfcheck.js` 为入口启动；其在 `127.0.0.1:<port>` 起 HTTP 服务并打印
  `[selfcheck] listening … node=<process.version> platform=<process.platform>`
  ‌（`selfcheck.js:316-319`）。断言：`NodeRuntime.logs()` 含 `[selfcheck] listening` 且
  `node=v26.13.0` 且 `platform=android`。对应探针 **PRB-03**。
- **FR-101-PROBE-005（MUST）**：bridge 双向调用往返——Node 侧 `host.invoke('echo', …)`
  （`selfcheck.js:170-183` 的 `anb_host_call` 路径）经 Kotlin 注册的 `echo` handler 回程，断言：
  ① 注册 handler 的结果正确回传（证明 `pollHostRequest→completeHostRequest` 往返成立）；② 未注册名
  `definitely.not.registered` 得到结构化 `{ok:false,error:'unregistered'}` 拒绝（不静默、不崩溃，
  规划 §5.4 失败语义）。对应探针 **PRB-04**。

### 3.3 契约与产出域

- **FR-101-PROBE-006（MUST）**：版本契约——`node-runtime` 元数据 `META-INF/node-runtime.properties`
  的 `node.version` 必须等于 26.13.0，且与进程内 `process.version`（经 `/facts` 回程）一致；
  探针内置版本常量 `EXPECTED_NODE_VERSION=26.13.0`（对应规划 §7.5 运行时版本矩阵，字节对齐
  README「Runtime 26.13.0」）。任一来源取不到 → BLOCKED 并给 detail。对应探针 **PRB-05**。
- **FR-101-PROBE-007（MUST）**：崩溃信标可读——`NodeRuntime.lastCrash()`（`NativeBridge.lastCrashJson()`）
  返回为空（干净进程）或为可解析的有效记录；**可读性**是判据，不要求必然有崩溃。对应探针
  **PRB-02** 附 + 汇总可另列。
- **FR-101-PROBE-008（MUST）**：报告产物——每轮执行后写 `filesDir/probe-report.json`，结构为
  `{runAt, device, expected, probes[]}`，`probes[]` 每条含 `id/title/verdict/detail/exit/ms/stdout/stderr`，
  `verdict ∈ {PASS, FAIL, BLOCKED}`；三态聚合并打印到 UI。对应执行器与汇总逻辑。

### 3.4 无头自动化域

- **FR-101-PROBE-009（MUST）**：autorun intent——`adb shell am start -n <pkg>/.MainActivity --ez autorun true`
  启动即自动跑批（不点按钮），便于编排者排队采集；`runAll()` 幂等（重复启动不重复跑交叉风险由
  单实例 + guarded 标志兜住）。

## 4. 用户故事

- 作为 **测试/QA（编排者）**，我需要在真实设备上一键（含无头）跑完 bridge 加载→启动→selfcheck→
  桥往返→版本 五类探针，得到可证伪的三态 JSON 报告，从而在统一排队里给设备打分。
- 作为 **库维护者**，我需要一个紧贴对接契约 §6 的消费方参照实现，证明 `node-bridge`+`node-runtime`
  双包配对在 arm64-v8a 应用域可用，别把「host 能编」当「设备能跑」。
- 作为 **消费方开发者**，我需要一个最小可读的接入样例（先注册 host handler、再 start、再经
  HTTP `/host` 打桥）来对照自己的集成方式。

## 5. 验收场景（Given/When/Then）

1. **加载成功**：Given arm64-v8a 设备且 APK 含 libnode_bridge.so；When 点 RUN ALL PROBES；
   Then PRB-01 = PASS（loadLibrary 不抛，`nativeAvailable=true`）。
2. **运行时拉起来**：Given 库可物化 payload；When PRB-02 执行 start；Then 返回 ok、`awaitRunning`
   内状态到 `Running`、stage 不失败、lastCrash 空或可解析。
3. **selfcheck 输出**：Given Node 已 running；When 检查 `logs()`；Then 含 `[selfcheck] listening`
   且 `node=v26.13.0` 且 `platform=android`。
4. **桥往返**：Given Kotlin 已注册 `echo`；When Node 经 HTTP `/host?name=echo` 发起；Then 回程结果
   等于 handler 返回值；未注册名结构化拒绝。
5. **版本契约**：Given node-runtime 26.13.0 就位；When PRB-05 读取元数据 + `/facts`；Then 两者都断言
   26.13.0。
6. **报告**：Given 跑批结束；When 检查 `filesDir/probe-report.json`；Then 三态齐全、JSON 可解析、
   `adb run-as <pkg> cat files/probe-report.json` 可取回。

## 6. 实体表

| 实体 | 字段 | 说明 | 出处 |
|---|---|---|---|
| `Probe` | `id`/`title`/`run`/`check` | 一条探针：id 即 PRB-xx，run 执行副作用，check 返回 null=PASS / `BLOCKED:`=BLOCKED / 其余=FAIL | `MainActivity.java` |
| `NativeBridge`（JNI 面） | `start/isRunning/stage/lastError/lastCrashJson/pollHostRequest/completeHostRequest/bridgeHandle/probe` | 库内部对象；probe-app 只经 `NodeRuntime` 公开面消费，不直碰 `internal` | `node-bridge/…/NativeBridge.kt` |
| `NodeRuntime` | `getInstance/start/awaitRunning/state/stage/lastError/lastCrash/logs/facts/probeEngine/shutdown` | probe-app 的唯⼀入口（公开面） | `node-bridge/…/NodeRuntime.kt` |
| `NodeHostBridge` | `register/unregister/names` | Kotlin 侧 handler 注册表，handler 在主线程被调 | `node-bridge/…/NodeHostBridge.kt` |
| 报告 JSON | `runAt/device/expected/probes[]` | `filesDir/probe-report.json`；`probes[]` 含逐条 verdict | `MainActivity.java` |

## 7. NFR

- **平台前提（MUST）**：`arm64-v8a` 唯一 ABI（`abiFilters`），`minSdk 24` / `targetSdk 36`。
- **App 域注入（MUST）**：全部加载/执行都发生在应用进程与 `filesDir`/`nativeLibraryDir`，不依赖
  `adb shell` 域；loopback 明文对 `127.0.0.1` 放行（`network_security_config`）供 `/facts` 回程。
- **无网络默认（MUST）**：探针链路零外部网络；`0.0.0.0` 不监听，仅 `127.0.0.1`。
- **可靠性与超时（SHOULD）**：每探针独立超时（启动 60 s、HTTP 15 s），异常/超时不中断整体跑批
  （try/catch + 三态）。
- **可维护性（SHOULD）**：探针为声明式数组 + 判据 lambda；新增探针只加一条记录。
- **单例纪律（MUST）**：`node::Start` 每进程一次，全跑批共享一个 `NodeRuntime` 实例；不允许二次 start。

## 8. 依赖

| 依赖 | 锚点 | 说明 |
|---|---|---|
| `:node-bridge`（project） | `node-bridge/` | Kotlin API + jniLibs + assets；`implementation(project(":node-bridge"))` |
| `io.github.fellow99:node-runtime:26.13.0` | Maven（本机 gradle 缓存已命中） | 引擎包 `libnode.so` + `libc++_shared.so` + `META-INF/node-runtime.properties`；`runtimeOnly`（编译期不需引擎符号） |
| 规划文档 | `docs/000-工程规划.md` §5.1/§5.2/§5.10/§6 | 双包咬合契约、加载顺序、消费方责任边界 |
| 参照样例 | `samples/minimal-app` | AGP/Gradle 配置姿势、network_security_config、useLegacyPackaging 口径 |

## 9. 状态与遗留

- 本批：spec → plan → tasks → test-cases 文档齐备；`probe-app` 实现落地；`:probe-app:assembleDebug`
  BUILD SUCCESSFUL（详见 `logs/20261105-1/DEV_CHECKLIST.md`）。
- **设备运行 ⬜ 后续批次**：真实设备执行（PASS/FAIL/BLOCKED 结果）由编排者统一排队；本批只做
  开发 + APK 构建验证，不跑设备。
- 「未实现/待验证」一律以 ⬜ + 批次标注，不写 TODO/TBD 占位。
