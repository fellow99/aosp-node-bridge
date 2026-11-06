#!/usr/bin/env node
/*
 * tools/build-bridge.mjs —— 交叉编译引导件与 NAPI addon（模块 002 的构建步骤）
 *
 * 为什么不用 CMake：本机没有 CMake/ninja，而 NDK 自带 clang 足够完成这两个极小的 .so；
 *   直调 clang 让「构建 = 一条可复现命令」，且不引入 AGP 的 externalNativeBuild 依赖。
 *   需要 CMake 的场合用 node-bridge/src/main/cpp/CMakeLists.txt（等价语义）。
 *
 * 用法：
 *   node tools/build-bridge.mjs --out <dir> [--abi arm64-v8a] [--api 24] [--force] [--libnode <libnode.so|dir>] [--node-headers <dir>]
 *
 * 退出码：0 成功（或可跳过的缺件）· 1 硬失败
 */

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const args = process.argv.slice(2);
function argOf(name, fallback) {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
}
const OUT = path.resolve(argOf('--out', path.join(ROOT, 'node-bridge/build/generated/jniLibs/arm64-v8a')));
const ABI = argOf('--abi', 'arm64-v8a');
const API = argOf('--api', '24');

const ABI_TRIPLE = { 'arm64-v8a': 'aarch64-linux-android', 'armeabi-v7a': 'armv7a-linux-androideabi' };

const LIBNODE = argOf('--libnode', null);

function log(msg) {
  process.stdout.write(`[build-bridge] ${msg}\n`);
}

function findNdk() {
  const candidates = [];
  if (process.env.ANDROID_NDK_HOME) candidates.push(process.env.ANDROID_NDK_HOME);
  if (process.env.ANDROID_NDK_ROOT) candidates.push(process.env.ANDROID_NDK_ROOT);

  // 从 local.properties / 默认 SDK 目录里扫 ndk/<version>
  const sdkDirs = [];
  const localProps = path.join(ROOT, 'local.properties');
  if (fs.existsSync(localProps)) {
    const m = /sdk\.dir\s*=\s*(.+)/.exec(fs.readFileSync(localProps, 'utf8'));
    if (m) sdkDirs.push(m[1].trim().replace(/\\:/g, ':').replace(/\\\\/g, '\\'));
  }
  if (process.env.LOCALAPPDATA) sdkDirs.push(path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk'));
  if (process.env.HOME) sdkDirs.push(path.join(process.env.HOME, 'Android', 'Sdk'));

  for (const sdk of sdkDirs) {
    const ndkRoot = path.join(sdk, 'ndk');
    if (!fs.existsSync(ndkRoot)) continue;
    for (const v of fs.readdirSync(ndkRoot)) candidates.push(path.join(ndkRoot, v));
  }
  for (const c of candidates) {
    if (fs.existsSync(path.join(c, 'toolchains', 'llvm', 'prebuilt'))) return c;
  }
  return null;
}

function hostTag(ndk) {
  const prebuilt = path.join(ndk, 'toolchains', 'llvm', 'prebuilt');
  const tags = fs.readdirSync(prebuilt);
  const want = process.platform === 'win32' ? 'windows' : process.platform === 'darwin' ? 'darwin' : 'linux';
  const hit = tags.find((t) => t.startsWith(want));
  if (!hit) throw new Error(`找不到 ${want} 的 prebuilt 工具链：${tags.join(', ')}`);
  return hit;
}

function run(cmd, cmdArgs) {
  log(`${path.basename(cmd)} ${cmdArgs.join(' ')}`);
  // Windows 上 NDK 的交叉 clang 是 .cmd 包装脚本：Node 18+ 禁止直接 spawn .cmd ⇒ 必须经 shell
  // （我们的路径不含空格，故 shell 拼接在这里是安全的）
  const viaShell = process.platform === 'win32' && cmd.toLowerCase().endsWith('.cmd');
  const r = spawnSync(viaShell ? `"${cmd}"` : cmd, cmdArgs, { stdio: 'inherit', shell: viaShell });
  if (r.status !== 0) {
    throw new Error(`退出码 ${r.status}${r.error ? ' (' + r.error.message + ')' : ''}: ${cmd}`);
  }
}


// 定位 libnode.so（addon 的链接输入，用于留下 DT_NEEDED libnode.so）：
//   优先级：--libnode <libnode.so|dir> 显式指定 → 自动探测 gradle 缓存 node-runtime AAR。
function findLibnode() {
  if (LIBNODE) {
    const p = path.resolve(LIBNODE);
    if (fs.existsSync(p) && fs.statSync(p).isDirectory()) {
      const f = path.join(p, 'libnode.so');
      return fs.existsSync(f) ? f : null;
    }
    return fs.existsSync(p) ? p : null;
  }
  const cacheRoot = path.join(os.homedir(), '.gradle', 'caches', 'modules-2', 'files-2.1', 'io.github.fellow99', 'node-runtime');
  if (!fs.existsSync(cacheRoot)) return null;
  // 版本从高到低尝试（多版本缓存时优先新版）
  for (const ver of fs.readdirSync(cacheRoot).sort().reverse()) {
    const verDir = path.join(cacheRoot, ver);
    if (!fs.statSync(verDir).isDirectory()) continue;
    for (const hash of fs.readdirSync(verDir)) {
      const hashDir = path.join(verDir, hash);
      const aar = fs.readdirSync(hashDir).find((f) => f.startsWith('node-runtime-') && f.endsWith('.aar'));
      if (!aar) continue;
      const outDir = path.join(os.tmpdir(), `build-bridge-libnode-${ver}-${hash}`);
      fs.mkdirSync(outDir, { recursive: true });
      const target = path.join(outDir, 'libnode.so');
      if (fs.existsSync(target)) return target;
      const r = spawnSync('unzip', ['-o', '-j', path.join(hashDir, aar), 'jni/arm64-v8a/libnode.so', '-d', outDir], { encoding: 'utf8' });
      if (r.status === 0 && fs.existsSync(target)) {
        log(`libnode.so（node-runtime ${ver}）→ ${target}`);
        return target;
      }
    }
  }
  return null;
}
function main() {
  const ndk = findNdk();
  if (!ndk) {
    log('未找到 Android NDK —— 跳过原生构建（APK 里不会有 libnode_bridge.so，探针会如实报「.so 未加载」）');
    log('修法：sdkmanager "ndk;27.2.12479018" 或 export ANDROID_NDK_HOME=<ndk>');
    process.exit(0);
  }
  log(`NDK = ${ndk}`);

  const triple = ABI_TRIPLE[ABI];
  if (!triple) throw new Error(`不支持的 ABI: ${ABI}`);

  const bin = path.join(ndk, 'toolchains', 'llvm', 'prebuilt', hostTag(ndk), 'bin');
  const clang = path.join(bin, `${triple}${API}-clang${process.platform === 'win32' ? '.cmd' : ''}`);
  if (!fs.existsSync(clang)) throw new Error(`找不到 clang：${clang}`);

  fs.mkdirSync(OUT, { recursive: true });

  // ① 引导件（只链 log/dl；**不**链 libnode，靠 dlopen）
  const bridge = path.join(OUT, 'libnode_bridge.so');
  run(clang, [
    '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-fvisibility=hidden',
    '-Wl,-soname,libnode_bridge.so',
    '-o', bridge,
    path.join(ROOT, 'node-bridge/src/main/cpp/node_bridge.c'),
    '-llog', '-ldl',
  ]);
  log(`✔ ${bridge} (${fs.statSync(bridge).size} B)`);

  // ② NAPI addon。头文件优先取显式 --node-headers（或仓库自带目录，若存在）；次选兄弟仓
  //    aosp-node-runtime 的 include（历史构建即来于此，见 docs/002-真机验证记录.md 行 4）。
  //    链接期导入 libnode.so，使 addon 留下 DT_NEEDED libnode.so：运行期 linker 按依赖图
  //    解析 napi_*（CMakeLists.txt 的设计注释；002 验证记录亦要求 addon DT_NEEDED 含 libnode.so）。
  //    --no-as-needed 防止链接器因「addon 未直接引用 libnode 自身符号」而丢弃该依赖记录。
  let includeDir = null;
  const nodeHeadersArg = argOf('--node-headers', null);
  for (const cand of [
    nodeHeadersArg,
    path.join(ROOT, 'node-bridge/src/main/cpp/include/node'),
    path.join(ROOT, '..', 'aosp-node-runtime', 'node-runtime', 'include', 'node'),
  ]) {
    if (cand && fs.existsSync(path.join(cand, 'node_api.h'))) {
      includeDir = path.resolve(cand);
      break;
    }
  }

  if (!includeDir) {
    log('跳过 node_host_client：未找到 Node NAPI 头（node_api.h）');
    log('修法：node tools/build-bridge.mjs --node-headers <dir>（指向含 node_api.h 的目录，如 aosp-node-runtime/node-runtime/include/node）');
    return;
  }
  log(`NAPI 头 = ${includeDir}`);

  const libnode = findLibnode();
  if (!libnode) {
    log('跳过 node_host_client：未找到 libnode.so（addon 需显式依赖 libnode.so，产物才会带 DT_NEEDED）');
    log('修法：node tools/build-bridge.mjs --libnode <libnode.so|dir>（可先 gradle 依赖 node-runtime 让 AAR 进缓存）');
    return;
  }
  log(`addon 链接输入 libnode.so = ${libnode}`);
  const client = path.join(OUT, 'libnode_host_client.so');
  run(clang, [
    '-shared', '-fPIC', '-O2', '-Wall', '-fvisibility=hidden',
    `-I${includeDir}`,
    '-Wl,-soname,libnode_host_client.so',
    '-Wl,-z,undefs',
    '-Wl,--no-as-needed',
    '-o', client,
    path.join(ROOT, 'node-bridge/src/main/cpp/node_host_client.c'),
    '-L', path.dirname(libnode),
    '-l:libnode.so',
    '-llog',
  ]);
  log(`✔ ${client} (${fs.statSync(client).size} B)`);
}

try {
  main();
} catch (e) {
  process.stderr.write(`[build-bridge] 失败：${e.message}\n`);
  process.exit(1);
}
