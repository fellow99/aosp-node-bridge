# 101-probe-app · 进程内 Node 探针载体技术方案（As-Built / 先行）

> 模块：101-probe-app · 状态：✅ 已实现（本批开发 + APK 构建；设备运行由编排者统一排队）
> 对应 spec：[spec.md](./spec.md) · 用例：[test-cases.md](./test-cases.md) · 任务：[tasks.md](./tasks.md)
> 工程：aosp-node-bridge · 更新：2026-11-05

## 1. 技术上下文

`probe-app` 是接入 **node-bridge 对接契约**（`docs/000-工程规划.md` §5/§6）的消费方验证载体：
一个独立 Android app，把「加载 `libnode_bridge.so` → 起 node 运行时 → 执行 `selfcheck.js` →
host 桥往返 → 版本契约」在真实设备上按固定主流程拉通，产出三态 JSON 报告。

运行环境：
- Android app（minSdk 24 / targetSdk 36 / 仅 arm64-v8a），进程内 Node（`IN_PROCESS`）。
- 引擎 `libnode.so` + `libc++_shared.so` 由 `io.github.fellow99:node-runtime:26.13.0` AAR runtimeOnly 提供；
- 桥 `libnode_bridge.so` + NAPI addon `libnode_host_client.so` + 载荷
  `assets/node-bridge/{bootstrap,host,selfcheck}.js` 由本仓 `:node-bridge` 库模块提供。

依赖（真实版本）：
| 依赖 | 版本/坐标 | 形态 |
|---|---|---|
| `project(":node-bridge")` | 本仓 `node-bridge/` | implementation |
| `io.github.fellow99:node-runtime` | 26.13.0 | runtimeOnly（AAR 带 jniLibs + META-INF/node-runtime.properties） |
| AGP | `libs.versions.toml`（compileSdk 36） | alias(libs.plugins.android.application) |

## 2. 加载链与启动（镜像规划 §5.10 加载顺序）

1. `MainActivity.onCreate` → `NodeRuntime.getInstance(this)`：`NodeRuntime` 单例内首次触碰
   `NativeBridge` 触发 `System.loadLibrary("node_bridge")`（失败记 `loadError`，不抛给 UI）。
2. `start(NodeRuntimeOptions(entry="selfcheck.js", env={NODE_BRIDGE_PORT,HOME}))`：
   `NodeRuntime` 物化 `assets/node-bridge/` → `<filesDir>/node-bridge/payload`（marker 幂等），
   `resolveEntry` 校验 `selfcheck.js` 在位，组装 envPairs（含 `NODE_BRIDGE_BRIDGE_HANDLE`，
   供 addon `setBridge` 绕开 JNI 命名空间隔离），再经 `NativeBridge.start(...)` 投递；
   返回 `ok=true` 仅是投递语义（规划 §5.2）。
3. `awaitRunning(60s)` 轮询：Native monitor 见「Node 有非 `[bridge]` 前缀输出」且 `stage=start`
   即置 `Running`。

## 3. selfcheck.js 输出契约（PRB-03 断言）

- 监听 `127.0.0.1:<PORT>`（默认 15599）HTTP 服务，端点为 `/facts /log /diag /host /intl /`。
- 起服时打印：`[selfcheck] listening http://127.0.0.1:<PORT>  node=<process.version> platform=<process.platform>`。
- 探针断言日志含 `[selfcheck] listening` 且 `node=v26.13.0` 且 `platform=android`
  （`selfcheck.js:316-319`；`/facts` 亦含 `node`/`platform` 可复核）。
- `EADDRINUSE` 时自回退随机端口并写 `real_port.txt`（多载体并发兜底）。

## 4. host 桥往返（PRB-04）

- Kotlin 侧（`registerHostHandlers()`）注册 `echo` / `user.info` / `shutdown`（handler 主线程被调，
  入参/出参 JSON 串，经 `NodeHostBridge.register`）。
- Node 侧 `host.invoke('echo',…)` → NAPI addon `process.dlopen(libnode_host_client.so)`
  → dlsym 槽位表 → `pollHostRequest`/`completeHostRequest`（`NodeHostBridge.dispatch`）。
- `/host?name=echo`：断言 `result.ok && result.handledBy=="kotlin"`；
- `/host?name=definitely.not.registered`：断言 `result.ok==false && result.error=="unregistered"`
  （结构化拒绝，规划 §5.4）。

## 5. 版本契约（PRB-05）

- `/facts` `.node`（`process.version`）== `v26.13.0`；
- `META-INF/node-runtime.properties` `.node.version` == `26.13.0`（AAR 资源经 classloader 读取，
  未展开到 APK classpath 时按 PASS-FACT 记录，以 `/facts` 为准）。

## 6. 报告与无头（FR-101-PROBE-008/009）

- 汇总写 `filesDir/probe-report.json`：`{runAt, device, expected, summary{pass,fail,blocked}, probes[]}`，
  `probes[]` 每条 `{id,title,verdict,detail,ms}`；verdict ∈ {PASS, FAIL, BLOCKED}（沿用 node-cli
  probe-app 判定约定：detail 空=PASS、`BLOCKED:` 前缀=BLOCKED、`PASS-` 前缀=PASS、其余=FAIL）。
- `adb shell am start -n org.fellow99.nodebridge.probe/.MainActivity --ez autorun true` 无头跑批。

## 7. 文件清单

| 文件 | 用途 |
|---|---|
| `probe-app/build.gradle.kts` | app 模块配置：namespace/applicationId/ABI/依赖姿势 |
| `probe-app/src/main/AndroidManifest.xml` | 单 Activity + INTERNET + loopback 明文放行 |
| `probe-app/src/main/res/xml/network_security_config.xml` | 127.0.0.1/localhost 明文放行 |
| `probe-app/src/main/java/org/fellow99/nodebridge/probe/MainActivity.kt` | 探针载体：PRB-01…05 + 报告 |
| `specs/101-probe-app/{spec,plan,tasks,test-cases}.md` | 本模块四件套 |

## 8. FR 交叉引用

| FR | 实现锚点 |
|---|---|
| FR-101-PROBE-001（加载） | `MainActivity.kt` PRB-01；`NodeRuntime.nativeAvailable/nativeLoadError` |
| FR-101-PROBE-002/003/007（启动/状态/崩溃信标） | `MainActivity.kt` PRB-02；`start/awaitRunning/state/stage/lastCrash` |
| FR-101-PROBE-004（selfcheck 输出） | `MainActivity.kt` PRB-03；`runtime.logs()` + `selfcheck.js` listening 行 |
| FR-101-PROBE-005（桥往返） | `MainActivity.kt` PRB-04a/b；`/host` + `registerHostHandlers` |
| FR-101-PROBE-006（版本契约） | `MainActivity.kt` PRB-05；`/facts` + `META-INF/node-runtime.properties` |
| FR-101-PROBE-008（报告） | `MainActivity.kt` `summarize/writeReport` |
| FR-101-PROBE-009（无头） | `MainActivity.kt` `onCreate` autorun intent |

## 9. 构建与运行

```bash
# 构建（根工程，Gradle 9.4.1 发行版）
D:/node-bridge-workspace/aosp-node-cli/.dist/gradle-dist/gradle-9.4.1/bin/gradle :probe-app:assembleDebug --console=plain

# 安装运行 + 取报告（设备端由编排者统一排队）
adb install -r probe-app/build/outputs/apk/debug/probe-app-debug.apk
adb shell am start -n org.fellow99.nodebridge.probe/.MainActivity --ez autorun true
adb shell run-as org.fellow99.nodebridge.probe cat files/probe-report.json > probe-report.json
```

## 10. 遗留项

- 设备运行（PRB-01…05 实跑 + 三态回填）⬜ 编排者统一排队；
- `--jitless` / JIT / Wasm 判定矩阵属库 Phase-0 载体（samples/minimal-app），不在本模块；
- AAR `META-INF/node-runtime.properties` 是否随依赖展开到 APK classpath：实跑时以 PASS-FACT 记录实测。
