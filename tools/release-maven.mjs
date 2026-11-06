#!/usr/bin/env node
/**
 * release-maven.mjs —— Maven Central 发布流水线（bridge 部分）。
 *
 * 用法：
 *   node tools/release-maven.mjs --check-version   # 版本一致性 + AAR 结构断言
 *
 * 注意：node-runtime 已分离到 aosp-node-runtime 仓库，其发布验证在该仓库执行。
 */
import { readFileSync, existsSync, readdirSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { resolve, dirname, join } from 'node:path';
import os from 'node:os';

const ROOT = resolve(dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..');

let failures = 0;
function assert(ok, name, detail = '') {
  console.log(`  ${ok ? 'OK  ' : 'FAIL'} ${name}${detail ? `  (${detail})` : ''}`);
  if (!ok) failures++;
  return ok;
}
function section(t) { console.log(`\n${t}`); }

function gradle(...args) {
  return execFileSync('gradle', [...args, '--no-daemon', '-q'], { cwd: ROOT, stdio: 'pipe', shell: process.platform === 'win32' }).toString();
}

// 定位 NDK 自带的 llvm-readelf（DT_NEEDED 门禁用）：env → local.properties → 默认 SDK 目录。
function findNdkReadelf() {
  const candidates = [];
  for (const env of ['ANDROID_NDK_HOME', 'ANDROID_NDK_ROOT']) {
    if (process.env[env]) candidates.push(process.env[env]);
  }
  const sdkDirs = [];
  const localProps = join(ROOT, 'local.properties');
  if (existsSync(localProps)) {
    const m = /sdk\.dir\s*=\s*(.+)/.exec(readFileSync(localProps, 'utf8'));
    if (m) sdkDirs.push(m[1].trim().replace(/\\\\:/g, ':').replace(/\\\\\\\\/g, '\\\\'));
  }
  if (process.env.LOCALAPPDATA) sdkDirs.push(join(process.env.LOCALAPPDATA, 'Android', 'Sdk'));
  if (process.env.HOME) sdkDirs.push(join(process.env.HOME, 'Android', 'Sdk'));
  for (const sdk of sdkDirs) {
    const ndkRoot = join(sdk, 'ndk');
    if (!existsSync(ndkRoot)) continue;
    for (const v of readdirSync(ndkRoot)) candidates.push(join(ndkRoot, v));
  }
  const exe = process.platform === 'win32' ? 'llvm-readelf.exe' : 'llvm-readelf';
  const hostTags = process.platform === 'win32' ? ['windows'] : process.platform === 'darwin' ? ['darwin'] : ['linux'];
  for (const c of candidates) {
    const prebuilt = join(c, 'toolchains', 'llvm', 'prebuilt');
    if (!existsSync(prebuilt)) continue;
    for (const tag of readdirSync(prebuilt)) {
      if (!hostTags.some((h) => tag.startsWith(h))) continue;
      const p = join(prebuilt, tag, 'bin', exe);
      if (existsSync(p)) return p;
    }
  }
  return null;
}

section('① 版本一致性（gradle.properties）');
const gradleProps = readFileSync(join(ROOT, 'gradle.properties'), 'utf8');
const VERSION_NAME = (gradleProps.match(/^VERSION_NAME=(.+)$/m) || [])[1]?.trim();
assert(VERSION_NAME && /^\d+\.\d+\.\d+$/.test(VERSION_NAME), 'VERSION_NAME 是合法 semver', VERSION_NAME);

section('② 本地 publish 验证（publishToMavenLocal）');
gradle(':node-bridge:publishToMavenLocal');
const m2 = (p) => resolve(process.env.USERPROFILE ?? '', '.m2/repository', p);
const bridgeAar = m2(`io/github/fellow99/node-bridge/${VERSION_NAME}/node-bridge-${VERSION_NAME}.aar`);
assert(existsSync(bridgeAar), 'node-bridge AAR 已发布到 m2', bridgeAar);
if (existsSync(bridgeAar)) {
  assert(existsSync(bridgeAar + '.asc'), '签名存在 node-bridge.aar.asc');
}

section('③ AAR 结构断言（node-bridge）');
{
  const list = execFileSync('unzip', ['-l', bridgeAar], { stdio: 'pipe' }).toString();
  const so = list.match(/jni\/arm64-v8a\/[\w.]+\.so/g) ?? [];
  assert(so.length === 2, 'node-bridge 恰含 2 个 .so（bridge+host_client）', so.join(', '));
  for (const js of ['bootstrap.js', 'host.js', 'selfcheck.js']) {
    assert(list.includes(`assets/node-bridge/${js}`), `node-bridge 含 assets/node-bridge/${js}`);
  }
}

section('④ addon DT_NEEDED 门禁（API 26 napi 符号解析前提）');
{
  const readelf = findNdkReadelf();
  assert(readelf !== null, '已定位 llvm-readelf（NDK）', readelf ?? '未找到 NDK（ANDROID_NDK_HOME/local.properties）');
  if (readelf) {
    const tmp = mkdtempSync(join(os.tmpdir(), 'anb-gate-'));
    try {
      execFileSync('unzip', ['-o', '-j', bridgeAar, 'jni/arm64-v8a/libnode_host_client.so', '-d', tmp], { stdio: 'pipe' });
      const addon = join(tmp, 'libnode_host_client.so');
      const dyn = execFileSync(readelf, ['-d', addon], { encoding: 'utf8' });
      const needed = [...dyn.matchAll(/\(NEEDED\)\s+Shared library: \[([^\]]+)\]/g)].map((m) => m[1]);
      assert(needed.includes('libnode.so'), 'addon DT_NEEDED 含 libnode.so', needed.join(', '));
    } finally {
      rmSync(tmp, { recursive: true, force: true });
    }
  }
}

section(failures === 0 ? '✅ 全绿 —— 可进入真实发布' : `❌ ${failures} 项未过`);
process.exit(failures === 0 ? 0 : 2);
