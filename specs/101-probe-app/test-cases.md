# 101-probe-app · 探针用例（test-cases.md）

> 模块：101-probe-app · 主流程探针正典：PRB-01…PRB-05
> 判据禁止项（沿用 aosp-node-cli 纪律）：① 不用「logcat 有没有那行」判执行；② 不用「点击看起来生效」判 UI；
> ③ 不伪造 PASS（依赖缺失如实标 BLOCKED/FAIL）；④ 不把「host 能编」当「设备能跑」。

| 用例 | 标题 | 执行 | 判据（check 命中即 PASS） | FR |
|---|---|---|---|---|
| PRB-01 | bridge 加载（`System.loadLibrary("node_bridge")`） | 显式 loadLibrary + `NodeRuntime.nativeAvailable` | loadLibrary 不抛 **且** `nativeAvailable==true`；否则 FAIL/Blocked(detail=.so 未进 APK/ABI/SELinux) | FR-101-PROBE-001 |
| PRB-02 | start + 启动状态机轮询 + 崩溃信标可读 | `start(NodeRuntimeOptions(entry="selfcheck.js", env={NODE_BRIDGE_PORT:15577}))` → `awaitRunning(60s)` | `ok==true` 且状态到 `Running` 且 `stage != failed` 且 `rt.pid>0`；`lastCrash()` 空或可解析 | FR-101-PROBE-002/003/007 |
| PRB-03 | selfcheck.js 可执行且输出符合预期 | 检查 `rt.logs()`（stdout 回程） | 存在一行同时含 `[selfcheck] listening` + `node=v26.13.0` + `platform=android` | FR-101-PROBE-004 |
| PRB-04 | bridge 双向返回（echo / unregistered） | Kotlin 注册 `echo`；HTTP GET `127.0.0.1:15577/host?name=echo&args={"x":1}` 与 `?name=definitely.not.registered` | echo 返回 `{"ok":true,"result":{…sentinel…}}`（handler 结果回程成立）；unregistered → `ok:false,error=unregistered` | FR-101-PROBE-005 |
| PRB-05 | 版本契约（node-runtime 元数据 vs process.version） | 读 `META-INF/node-runtime.properties`（build 期 extract 兜底）+ `HTTP /facts` 的 `node` 字段 | `node.version==26.13.0` **且** `process.version=="v26.13.0"`；任一来源缺失 → BLOCKED | FR-101-PROBE-006 |

## 3. 边界与失败路径（仍须三态可判定）

| 场景 | 期望 | 判据 |
|---|---|---|
| `libnode_bridge.so` 缺失/ABI 不匹配 | PRB-01 FAIL/BLOCKED，detail 可读，不崩溃 | detail 非空 |
| start 失败（如 dlopen/dlsym/JIT 崩） | PRB-02 FAIL 且 state=Failed/Exited，lastCrash 可解析 | state 落 Failed/Exited |
| selfcheck 未起服务（超时） | PRB-03/04/05 超时 FAIL，日志含 [bridge] 行证据 | 超时 + stderr/日志非空 |
| 端口 15577 被占用 | selfcheck 回退随机端口（selfcheck.js:302-313），`[selfcheck] listening http://127.0.0.1:<随机>` 仍命中 PRB-03；PRB-04/05 需按随机端口重试 | 日志解析实际端口后重试 |
| 重复跑批（二次 start） | LIB 侧单例禁二次 start；探针经 guarded 标志避免本 app 内重复启动 | 不抛错、报告仍可写 |

## 4. 设备执行（⬜ 后续批次）

```bash
# 安装
adb install -r probe-app/build/outputs/apk/debug/probe-app-debug.apk
# 无头跑批
adb shell am start -n org.fellow99.nodebridge.probe/.MainActivity --ez autorun true
# 取报告
adb shell run-as org.fellow99.nodebridge.probe cat files/probe-report.json
```

> 本批仅完成构建验证（`:probe-app:assembleDebug` BUILD SUCCESSFUL）；以上设备侧步骤由编排者统一排队执行，结果回填 spec §9。

## 附、统一队列跑批记录（2026-11-06）

- 载体：`emulator-5554`（sdk_gphone64_x86_64 API 37 × ndk_translation；App 域 ABI=arm64）；由编排者统一排队串行执行（安装→`--ez autorun true`→`run-as cat` 回读报告）。
- 报告：`logs/20261105-1/report-node-bridge.json`（工程内留档，不入库）。
- 汇总：6 探针：PASS 4 / FAIL 2（PRB-04a/b /host 回程：载体无 host addon ⇒ addon-unavailable，预期载体限制）。
- 判读纪律：翻译模拟器结果**仅记录**，正式 G3 需原生 arm64 复测；FAIL/BLOCKED 均按本节/Spec 既有分类如实记录，未伪造 PASS。
