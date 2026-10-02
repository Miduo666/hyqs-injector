// 注入器在线更新发布脚本：把补丁 JS / 图标 / APK 上传到 Cloudflare KV（qimeng.app/hyqs/*），并更新签名过的 manifest.json。
//
// 用法（在注入器工程根目录执行）：
//   node tools/publish.mjs keygen                 生成 ECDSA P-256 签名密钥（私钥 keystore/update_ec_p256.pem，公钥写进 app assets）
//   node tools/publish.mjs patch [--notes "说明"] [--min-apk N]
//                                                 发布 app/src/main/assets/patched_encrypt.js + EXTRA_ASSETS 图标，序号自动 +1；
//                                                 注入器打开时拿到新补丁会弹窗（显示 notes）问要不要立即注入
//                                                 （同时把新序号写回 assets/patch_serial.txt，之后打的 APK 内置的就是这一版）
//   node tools/publish.mjs apk [--notes "说明"] [--force]   发布 app/build/outputs/apk/release/app-release.apk（versionCode 读 build.gradle）
//   node tools/publish.mjs show                   拉线上 manifest 并验签
//
// manifest.json = {"payload": "<JSON 字符串>", "sig": "<base64 DER ECDSA-SHA256>"}，注入器只信签名对得上的 payload。
// payload = {ts, patch:{serial, file, sha256, size, minApk, assets:{名字:{file, sha256, size}}}, apk:{versionCode, versionName, file, sha256, size, notes, force}}
import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, sign, verify } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const ASSETS = join(ROOT, 'app', 'src', 'main', 'assets');
const PRIV = join(ROOT, 'keystore', 'update_ec_p256.pem');
const PUB_ASSET = join(ASSETS, 'update_pubkey.b64');
const SERIAL_ASSET = join(ASSETS, 'patch_serial.txt');
const APK = join(ROOT, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');
const SITE = 'C:\\Users\\31297\\Desktop\\学英语';           // qimeng.app 的 Pages 项目（wrangler 装在这里，已登录）
const WRANGLER = join(SITE, 'node_modules', 'wrangler', 'bin', 'wrangler.js');
const KV_ID = '7764619b14b34db48f702e1392829b9f';           // KV 命名空间 hyqs-update，绑定名 HYQS
const BASE = 'https://qimeng.app/hyqs/';

const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');
const arg = (name, def) => { const i = process.argv.indexOf(name); return i > 0 ? process.argv[i + 1] : def; };
const flag = (name) => process.argv.includes(name);

// 单 key 上传（kv key put）大文件偶尔连续报 7009 Upstream service unavailable（2026-10-02，~2MB 补丁时好时坏，小文件正常），
// 失败时改走批量写入端点（kv bulk put，base64 编码，二进制也安全），实测同一时段能成功、读回 sha256 一致
function kvPut(key, file, ct) {
  try {
    execFileSync(process.execPath, [WRANGLER, 'kv', 'key', 'put', key, '--path', file, '--namespace-id', KV_ID, '--remote',
      '--metadata', JSON.stringify({ ct })], { cwd: SITE, stdio: ['ignore', 'pipe', 'inherit'] });
  } catch (e) {
    console.log('  单 key 上传失败，改用 bulk put 重试：', key);
    const tmp = join(tmpdir(), `kvbulk-${Date.now()}.json`);
    writeFileSync(tmp, JSON.stringify([{ key, value: readFileSync(file).toString('base64'), base64: true, metadata: { ct } }]));
    try {
      execFileSync(process.execPath, [WRANGLER, 'kv', 'bulk', 'put', tmp, '--namespace-id', KV_ID, '--remote'],
        { cwd: SITE, stdio: ['ignore', 'pipe', 'inherit'] });
    } finally { rmSync(tmp, { force: true }); }
  }
  console.log('  上传', key, statSync(file).size, '字节');
}

async function fetchManifest() {
  const r = await fetch(BASE + 'manifest.json?t=' + Date.now(), { cache: 'no-store' });
  if (r.status === 404) return null;
  const m = await r.json();
  const pub = createPublicKey({ key: Buffer.from(readFileSync(PUB_ASSET, 'utf8').trim(), 'base64'), format: 'der', type: 'spki' });
  if (!verify('sha256', Buffer.from(m.payload, 'utf8'), pub, Buffer.from(m.sig, 'base64'))) throw new Error('线上 manifest 验签失败');
  return JSON.parse(m.payload);
}

function writeManifest(payload) {
  payload.ts = Math.floor(Date.now() / 1000);
  const text = JSON.stringify(payload);
  const sig = sign('sha256', Buffer.from(text, 'utf8'), createPrivateKey(readFileSync(PRIV))).toString('base64');
  const tmp = join(ROOT, 'app', 'build', 'manifest.json');
  mkdirSync(dirname(tmp), { recursive: true });
  writeFileSync(tmp, JSON.stringify({ payload: text, sig }));
  kvPut('manifest.json', tmp, 'application/json');
}

async function verifyOnline(check) {
  // KV 全球同步最多约 60 秒，这里最多等 90 秒
  for (let i = 0; i < 18; i++) {
    try {
      const p = await fetchManifest();
      if (p && check(p)) { console.log('线上已生效并验签通过'); return; }
    } catch (e) { console.log('  ', e.message); }
    await new Promise((r) => setTimeout(r, 5000));
  }
  console.log('90 秒内还没在线上看到新版本（KV 同步慢），稍后用 show 再确认');
}

// 更新日志：assets/changelog.json（新的在前，{patch, apk, date, items}），随签名 manifest 下发，注入器【更新日志】按钮显示。
// 这一版还没有条目时用 --notes 补一条（按"；"或换行拆成多项）；发 APK 时如果最新条目是同一批的补丁就把 apk 版本挂上去
function syncChangelog(payload, { patch, apk, notes }) {
  const f = join(ASSETS, 'changelog.json');
  const log = existsSync(f) ? JSON.parse(readFileSync(f, 'utf8')) : [];
  const date = new Date().toISOString().slice(0, 10);
  const items = (notes || '').split(/[；;\n]/).map((s) => s.trim()).filter(Boolean);
  if (patch && !log.some((e) => e.patch === patch) && items.length) log.unshift({ patch, date, items });
  if (apk && !log.some((e) => e.apk === apk)) {
    if (log[0] && !log[0].apk && log[0].patch === parseInt(readFileSync(SERIAL_ASSET, 'utf8'))) log[0].apk = apk;
    else if (items.length) log.unshift({ apk, date, items });
  }
  writeFileSync(f, JSON.stringify(log, null, 2) + '\n');
  payload.changelog = log;
}

function extraAssets() {
  const src = readFileSync(join(ROOT, 'app', 'src', 'main', 'java', 'com', 'hyqs', 'injector', 'HotfixServer.java'), 'utf8');
  const m = src.match(/EXTRA_ASSETS\s*=\s*\{([^}]*)\}/);
  return m ? [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1]) : [];
}

const cmd = process.argv[2];
if (cmd === 'keygen') {
  if (existsSync(PRIV) && !flag('--overwrite')) throw new Error('私钥已存在，换密钥会让已装的注入器全部无法验签；确实要换加 --overwrite');
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  mkdirSync(dirname(PRIV), { recursive: true });
  writeFileSync(PRIV, privateKey.export({ type: 'pkcs8', format: 'pem' }));
  writeFileSync(PUB_ASSET, publicKey.export({ type: 'spki', format: 'der' }).toString('base64') + '\n');
  console.log('私钥:', PRIV, '（不要提交、不要丢）\n公钥:', PUB_ASSET);
} else if (cmd === 'patch') {
  const cur = (await fetchManifest()) || {};
  const builtin = parseInt(readFileSync(SERIAL_ASSET, 'utf8')) || 0;
  const serial = Math.max(builtin, (cur.patch && cur.patch.serial) || 0) + 1;
  const js = readFileSync(join(ASSETS, 'patched_encrypt.js'));
  const assets = {};
  for (const name of extraAssets()) {
    const f = join(ASSETS, name), buf = readFileSync(f), h = sha256(buf);
    const key = `assets/${h.slice(0, 12)}-${name}`;
    const old = cur.patch && cur.patch.assets && cur.patch.assets[name];
    if (!old || old.sha256 !== h) kvPut(key, f, name.endsWith('.jpg') ? 'image/jpeg' : 'image/png');
    assets[name] = { file: key, sha256: h, size: buf.length };
  }
  const file = `patch/${serial}.js`;
  kvPut(file, join(ASSETS, 'patched_encrypt.js'), 'application/javascript');
  const minApk = parseInt(arg('--min-apk', (cur.patch && cur.patch.minApk) || 1));
  cur.patch = { serial, file, sha256: sha256(js), size: js.length, minApk, assets, notes: arg('--notes', '') };
  syncChangelog(cur, { patch: serial, notes: arg('--notes', '') });
  writeManifest(cur);
  writeFileSync(SERIAL_ASSET, serial + '\n');
  console.log(`补丁 v${serial} 已发布（minApk ${minApk}），assets/patch_serial.txt 已更新为 ${serial}`);
  await verifyOnline((p) => p.patch && p.patch.serial === serial);
} else if (cmd === 'apk') {
  const gradle = readFileSync(join(ROOT, 'app', 'build.gradle'), 'utf8');
  const versionCode = parseInt(gradle.match(/versionCode\s+(\d+)/)[1]);
  const versionName = gradle.match(/versionName\s+"([^"]+)"/)[1];
  const buf = readFileSync(APK);
  const cur = (await fetchManifest()) || {};
  if (cur.apk && cur.apk.versionCode >= versionCode) throw new Error(`线上已是 versionCode ${cur.apk.versionCode}，先把 build.gradle 的 versionCode 调大再打包`);
  const file = `apk/HyqsInjector-${versionCode}.apk`;
  kvPut(file, APK, 'application/vnd.android.package-archive');
  cur.apk = { versionCode, versionName, file, sha256: sha256(buf), size: buf.length, notes: arg('--notes', ''), force: flag('--force') };
  syncChangelog(cur, { apk: versionName, notes: arg('--notes', '') });
  writeManifest(cur);
  console.log(`注入器 v${versionName}（versionCode ${versionCode}）已发布${cur.apk.force ? '（强制更新）' : ''}`);
  await verifyOnline((p) => p.apk && p.apk.versionCode === versionCode);
} else if (cmd === 'show') {
  console.log(JSON.stringify(await fetchManifest(), null, 2));
} else {
  console.log('用法: node tools/publish.mjs keygen | patch [--min-apk N] | apk [--notes "说明"] [--force] | show');
}
