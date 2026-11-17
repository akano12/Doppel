/*
    This file is part of Doppel.

    Doppel is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Doppel is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Doppel.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2026 akano12
 */

// Runs the page script of webview_constructor.lua (the file named by the first argument) in two vm realms with
// stand-ins of the browser's functions, which check their receiver and arguments like Blink does, then acts as a page
// that watches the lookups on objects the page can change which would hand it a real value: the ones listed below
// while the script's functions run, and then on Object.prototype and GPUAdapter.prototype, until the browser's
// promises are settled. Prints the failures and exits with 1 if there are any. The stand-ins are made inside each
// realm, so their arrays and promises have the page's prototypes.
'use strict';
const fs = require('fs');
const vm = require('vm');

const page = fs.readFileSync(process.argv[2], 'utf8');

const BROWSER = `
var self = globalThis, brand = new WeakSet();
var EXTENSIONS = ['OES_x', 'WEBGL_debug_renderer_info', 'EXT_y', 'WEBGL_debug_shaders', 'WEBGL_compressed_texture_pvrtc',
  'WEBGL_render_shared_exponent', 'NV_shader_noperspective_interpolation', 'WEBGL_polygon_mode', 'EXT_z'];
function WebGLRenderingContext() { throw new TypeError('Illegal constructor'); }
WebGLRenderingContext.prototype.getSupportedExtensions = function getSupportedExtensions() {
  if (!brand.has(this)) throw new TypeError('Illegal invocation');
  var list = [];
  for (var i = 0; i < EXTENSIONS.length; i++) list[i] = EXTENSIONS[i];
  return list;
};
WebGLRenderingContext.prototype.getExtension = function getExtension(name) {
  if (!brand.has(this)) throw new TypeError('Illegal invocation');
  if (arguments.length < 1) throw new TypeError('1 argument required');
  name = String(name).toUpperCase();
  for (var i = 0; i < EXTENSIONS.length; i++)
    if (EXTENSIONS[i].toUpperCase() === name) return { name: EXTENSIONS[i] };
  return null;
};
function Navigator() {}
Object.defineProperty(Navigator.prototype, 'hardwareConcurrency', { configurable: true, enumerable: true,
  get: function () { if (!brand.has(this)) throw new TypeError('Illegal invocation'); return 16; } });
function GPU() {}
function GPUAdapter() { throw new TypeError('Illegal constructor'); }
var adapters = new WeakSet(), settling = [];
GPU.prototype.getPreferredCanvasFormat = function getPreferredCanvasFormat() {
  if (!brand.has(this)) throw new TypeError('Illegal invocation');
  return 'rgba8unorm';
};
// Like the browser, it resolves its promise later with the adapter, which reads the adapter's then
GPU.prototype.requestAdapter = function requestAdapter() {
  if (!brand.has(this)) return Promise.reject(new TypeError('Illegal invocation'));
  var adapter = Object.create(GPUAdapter.prototype);
  adapter.info = { vendor: 'qualcomm' };
  adapters.add(adapter);
  // Its own constructor, so its then makes no lookup on the page's prototypes, which the page watches
  var tick = Promise.resolve();
  Object.defineProperty(tick, 'constructor', { __proto__: null, value: Promise });
  return new Promise(function (resolve) {
    settling.push(tick.then(function () { resolve(adapter); }));
  });
};
var gl = Object.create(WebGLRenderingContext.prototype), navigator = Object.create(Navigator.prototype);
var gpu = Object.create(GPU.prototype);
brand.add(gl); brand.add(navigator); brand.add(gpu);
var TEXT = {
  getExtension: Function.prototype.toString.call(WebGLRenderingContext.prototype.getExtension),
  getSupportedExtensions: Function.prototype.toString.call(WebGLRenderingContext.prototype.getSupportedExtensions),
  hardwareConcurrency: Function.prototype.toString.call(
    Object.getOwnPropertyDescriptor(Navigator.prototype, 'hardwareConcurrency').get),
  requestAdapter: Function.prototype.toString.call(GPU.prototype.requestAdapter),
  toString: Function.prototype.toString.call(Function.prototype.toString)
};
`;

const PAGE = `
(async function () {
  var fails = [], seen = [], calls = 0, ts = Function.prototype.toString;
  function check(ok, what) { if (!ok) fails.push(what); }
  function throws(fn, what, message) {
    if (arguments.length < 3) message = 'Illegal invocation';
    try { fn(); fails.push(what + ': no error'); } catch (e) {
      check(e instanceof TypeError && (message === undefined || e.message === message), what + ': ' + e);
    }
  }

  // Lookups the script could make on objects the page can change, while the script's functions run. The descriptors
  // have no prototype, so the watching itself makes none of these lookups.
  var watched = [[Array.prototype, 'constructor'], [Promise.prototype, 'constructor'], [Object.prototype, 'then'],
    [Object.prototype, 'get'],
    [Object.prototype, 'set'], [Object.prototype, 'value'], [Object.prototype, 'writable'],
    [Object.prototype, 'configurable'], [Object.prototype, 'enumerable']];
  var saved = [];
  watched.forEach(function (w) {
    saved.push(Object.getOwnPropertyDescriptor(w[0], w[1]));
    var real = saved[saved.length - 1];
    if (real) Object.setPrototypeOf(real, null);
    Object.defineProperty(w[0], w[1], { __proto__: null, configurable: true, get: function () {
      seen.push(w[1]);
      return real ? real.value : undefined;
    } });
  });
  var list, adapter, wrong, failed;
  try {
    list = gl.getSupportedExtensions();
    adapter = gpu.requestAdapter();
    wrong = GPU.prototype.requestAdapter.call({});
  } catch (e) {
    failed = e;
  }
  var lookups = JSON.stringify(seen);
  watched.forEach(function (w, i) {
    if (saved[i]) Object.defineProperty(w[0], w[1], saved[i]);
    else delete w[0][w[1]];
  });
  check(!failed, 'error ' + failed);
  check(lookups === '[]', 'the page saw a lookup of ' + lookups);

  check(JSON.stringify(list) === '["OES_x","EXT_y","EXT_z"]', 'getSupportedExtensions ' + JSON.stringify(list));
  ['WEBGL_debug_renderer_info', 'webgl_DEBUG_shaders', 'WEBGL_compressed_texture_pvrtc', 'WEBGL_render_shared_exponent',
    'nv_shader_noperspective_interpolation', 'WEBGL_polygon_mode'].forEach(function (name) {
    check(gl.getExtension(name) === null, 'getExtension ' + name);
  });
  check(gl.getExtension('oes_x') !== null, 'getExtension OES_x');
  throws(function () {
    WebGLRenderingContext.prototype.getExtension.call({}, { toString: function () { calls++; return 'x'; } });
  }, 'getExtension of another receiver');
  check(calls === 0, 'getExtension converted the name before the receiver check');
  check(gl.getExtension({ toString: function () { calls++; return 'OES_x'; } }) !== null && calls === 1,
    'getExtension converted the name ' + calls + ' times');
  try { gl.getExtension(); fails.push('getExtension(): no error'); } catch (e) {
    check(e.message === '1 argument required', 'getExtension(): ' + e);
  }
  throws(function () { WebGLRenderingContext.prototype.getSupportedExtensions.call({}); },
    'getSupportedExtensions of another receiver');

  check(navigator.hardwareConcurrency === 8, 'hardwareConcurrency ' + navigator.hardwareConcurrency);
  throws(function () { Object.getOwnPropertyDescriptor(Navigator.prototype, 'hardwareConcurrency').get.call({}); },
    'hardwareConcurrency of another receiver');

  check(await adapter === null, 'requestAdapter');
  // A getter of the page on then, of Object.prototype and of GPUAdapter.prototype, watched until the browser's
  // promises are settled, never gets the adapter
  var leaked = [];
  for (var round = 0; round < 2; round++) {
    var target = round === 0 ? Object.prototype : GPUAdapter.prototype;
    Object.defineProperty(target, 'then', { __proto__: null, configurable: true, get: function () {
      if (adapters.has(this)) leaked.push(this.info.vendor);
      return undefined;
    } });
    var value = await gpu.requestAdapter();
    for (var i = 0; i < settling.length; i++) await settling[i];
    delete target.then;
    check(value === null, 'requestAdapter with a getter on then ' + value);
  }
  check(leaked.length === 0, 'a getter on then got the adapter ' + leaked);
  check(settling.length === 0, 'the requestAdapter of the browser was called ' + settling.length + ' times');
  try { await wrong; fails.push('requestAdapter of another receiver: resolved'); } catch (e) {
    check(e instanceof TypeError && e.message === 'Illegal invocation', 'requestAdapter of another receiver: ' + e);
  }

  check(ts.call(ts) === TEXT.toString, 'toString of toString ' + ts.call(ts));
  check(ts.call(WebGLRenderingContext.prototype.getExtension) === TEXT.getExtension, 'toString of getExtension');
  check(ts.call(WebGLRenderingContext.prototype.getSupportedExtensions) === TEXT.getSupportedExtensions,
    'toString of getSupportedExtensions');
  check(ts.call(Object.getOwnPropertyDescriptor(Navigator.prototype, 'hardwareConcurrency').get) ===
    TEXT.hardwareConcurrency, 'toString of hardwareConcurrency');
  check(ts.call(GPU.prototype.requestAdapter) === TEXT.requestAdapter, 'toString of requestAdapter');
  check(String(check) === ts.call(check) && ts.call(check).indexOf('fails.push') > 0, 'toString of the page');
  throws(function () { ts.call({}); }, 'toString of an object', undefined);
  return fails;
})()
`;

function realm(before) {
  const context = vm.createContext({});
  vm.runInContext(BROWSER, context);
  if (before) vm.runInContext(before, context);
  vm.runInContext(page, context);
  return context;
}

(async function () {
  const first = realm(), second = realm();
  const fails = (await vm.runInContext(PAGE, first)).slice();
  // A frame with the script gives the browser's text of the functions of another frame with the script
  const text = vm.runInContext('Function.prototype.toString', second).call(
    vm.runInContext('WebGLRenderingContext.prototype.getExtension', first));
  if (text !== vm.runInContext('TEXT.getExtension', second)) fails.push('toString of another frame ' + text);
  // A WebGPU without getPreferredCanvasFormat gives null to every receiver, without the browser's requestAdapter
  const old = realm('delete GPU.prototype.getPreferredCanvasFormat;');
  const nulls = JSON.stringify(await vm.runInContext(
    '(async function () { return [await gpu.requestAdapter(), await GPU.prototype.requestAdapter.call({}), ' +
    'settling.length]; })()', old));
  if (nulls !== '[null,null,0]') fails.push('requestAdapter without getPreferredCanvasFormat ' + nulls);
  for (const fail of fails) console.log(fail);
  process.exit(fails.length === 0 ? 0 : 1);
})();
