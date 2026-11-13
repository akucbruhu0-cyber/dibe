// Run: node site/assets/js/download.test.mjs
import assert from 'node:assert/strict';
import { classify, pickAssets, FALLBACK, mb } from './download.js';

const UA = {
  macSafari: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15',
  win: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36',
  android: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36',
  iphone: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1',
  linux: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36',
};

const key = (o) => classify(o).key;
assert.equal(key({ ua: UA.macSafari }), 'arm64', 'Safari on Mac defaults to Apple Silicon');
assert.equal(classify({ ua: UA.macSafari }).sure, false, 'no CPU hint means unsure');
assert.equal(key({ ua: UA.macSafari, platform: 'macOS', arch: 'x86' }), 'intel', 'Chrome on Intel Mac');
assert.deepEqual(classify({ ua: UA.macSafari, platform: 'macOS', arch: 'arm' }), { os: 'mac', key: 'arm64', sure: true });
assert.equal(key({ ua: UA.win }), 'win');
assert.equal(key({ ua: UA.win, platform: 'Windows', arch: 'arm' }), 'win', 'Windows on ARM still gets x64');
assert.equal(key({ ua: UA.android, platform: 'Android' }), 'apk');
assert.equal(key({ ua: UA.linux }), null, 'Linux gets no single pick');
assert.equal(classify({ ua: UA.iphone }).os, 'ios');
assert.equal(key({ ua: UA.iphone }), null, 'iOS gets no file');
assert.equal(classify({ ua: UA.macSafari, touch: 5 }).os, 'ios', 'iPadOS pretends to be a Mac');

const release = {
  tag_name: 'v1.5.0',
  assets: [
    { name: 'DIBE-1.5.0-macos-arm64.zip', browser_download_url: 'u/arm', size: 40e6 },
    { name: 'DIBE-1.5.0-macos-intel.zip', browser_download_url: 'u/intel', size: 44e6 },
    { name: 'DIBE-1.5.0-windows-x64-setup.exe', browser_download_url: 'u/win', size: 45e6 },
    { name: 'DIBE-1.5.0-windows-x64-setup.exe.sig', browser_download_url: 'u/sig', size: 1 },
    { name: 'dibe-android-arm64.apk', browser_download_url: 'u/apk', size: 12e6 },
  ],
};
const picked = pickAssets(release);
assert.equal(picked.version, '1.5.0');
assert.equal(picked.assets.arm64.url, 'u/arm');
assert.equal(picked.assets.intel.url, 'u/intel');
assert.equal(picked.assets.win.url, 'u/win', 'the .sig file must not match');
assert.equal(picked.assets.apk.url, 'u/apk');

const partial = pickAssets({ tag_name: 'v1.5.0', assets: [release.assets[0]] });
assert.equal(partial.assets.win.url, FALLBACK.assets.win.url, 'missing assets keep the fallback');
assert.deepEqual(pickAssets(null), { version: '1.4.0', assets: FALLBACK.assets });
assert.deepEqual(pickAssets({ message: 'API rate limit exceeded' }).assets, FALLBACK.assets);
assert.equal(mb(40055603), '38 MB', 'the real 1.4.0 arm64 zip reads as 38 MB');
assert.equal(mb(FALLBACK.assets.apk.size), '12 MB');

console.log('download.js: all checks passed');
