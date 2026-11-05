# 101-probe-app · 任务拆解（tasks.md）

> 模块：101-probe-app · 生成：2026-11-05 · 状态：本批 T1–T4 ✅ 完成（设备执行 T5 ⬜ 后续批次）

## T1 — 规格与工程登记

- [x] T1.1 读 `docs/000-工程规划.md` §5.1/§5.2/§5.10/§6 与 `README_zh.md` §快速开始，确认对接契约与验证粒度。
- [x] T1.2 生成 `specs/101-probe-app/{spec.md,plan.md,tasks.md,test-cases.md}`（spec 先行，RFC2119 FR 编号）。
- [x] T1.3 登记 `specs/README.md` + `specs/SPECS_CHECKLIST.md`（新增 101-probe-app 模块行）。
- [x] T1.4 根 README（中/英）「仓库结构 / 本地构建」补 101-probe-app 索引与构建命令。

## T2 — probe-app 工程骨架（本仓 gradle 构建内 `:probe-app` 单 app module）

- [x] T2.1 `settings.gradle.kts` 追加 `include(":probe-app")`。
- [x] T2.2 `probe-app/build.gradle.kts`：AGP 9.2.1（复用版本目录）、`compileSdk 36` / `minSdk 24` / `targetSdk 36`、
  `abiFilters arm64-v8a`、`packaging.jniLibs.useLegacyPackaging=false`、`implementation(project(":node-bridge"))` +
  `runtimeOnly("io.github.fellow99:node-runtime:26.13.0")`。
- [x] T2.3 `probe-app/settings.gradle.kts` + `gradle.properties`（与 aosp-node-cli/probe-app 结构对齐；根构建内不生效）。
- [x] T2.4 `.gitignore` 追加 `probe-app/build`、`probe-app/local.properties`、`probe-app/app/libs`（对齐口径）。

## T3 — MainActivity 探针实现（Java，单一 Activity）

- [x] T3.1 UI：TextView 滚动日志 + 「RUN ALL PROBES」按钮 + autorun intent（`--ez autorun true`）。
- [x] T3.2 主流程：loadLibrary → start(selfcheck.js) → 状态轮询 → selfcheck 输出断言 → bridge 往返 → 版本契约 → 写报告。
- [x] T3.3 探针 PRB-01…PRB-05（判据依据见 spec §3 与 plan §探针映射）。
- [x] T3.4 报告 `files/probe-report.json`（runAt/device/expected/probes[] 三态）+ 超时保护。
- [x] T3.5 manifest / network_security_config / strings。

## T4 — 构建验证

- [x] T4.1 用 Gradle 9.4.1 发行版在根跑 `gradle :probe-app:assembleDebug --console=plain` → **BUILD SUCCESSFUL**。
- [x] T4.2 核对 APK 产物路径与内容（含 libnode_bridge.so / libnode.so / node-runtime.properties 可达性）。
- [x] T4.3 写 `logs/20261105-1/DEV_CHECKLIST.md`。

## T5 — 设备运行（⬜ 后续批次，编排者统一排队）

- [ ] T5.1 安装 APK 到 arm64-v8a 真机/模拟器。
- [ ] T5.2 无头跑批（`am start --ez autorun true`）并 `adb run-as <pkg> cat files/probe-report.json` 取回。
- [ ] T5.3 结果三态归档（PASS/FAIL/BLOCKED 明细回填 spec §9）。
