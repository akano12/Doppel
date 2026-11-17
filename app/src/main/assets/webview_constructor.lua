-- This file is part of Doppel.

-- Doppel is free software: you can redistribute it and/or modify
-- it under the terms of the GNU General Public License as published by
-- the Free Software Foundation, either version 3 of the License, or
-- (at your option) any later version.

-- Doppel is distributed in the hope that it will be useful,
-- but WITHOUT ANY WARRANTY; without even the implied warranty of
-- MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
-- GNU General Public License for more details.

-- You should have received a copy of the GNU General Public License
-- along with Doppel.  If not, see <http://www.gnu.org/licenses/>.

-- Copyright 2017-2019 Marcel Bokhorst (M66B)
-- Copyright 2026 akano12

-- The user agent of a WebView contains the device model and firmware build, like
-- '(Linux; Android 15; Pixel 9 Build/AP4A.250105.002; wv)', which Chromium reads from the properties: they become the
-- fake Build.MODEL and Build.ID, '(Linux; Android 15; unknown Build/unknown; wv)', which Chromium makes of the fake
-- properties too (see generic_unknown_value and systemproperties_get), also for WebSettings.getDefaultUserAgent, which
-- apps send with their own requests. A user agent without the build, like the reduced one of Android 17
-- ('Android 10; K'), stays. Without a model, which Chromium and RuntimeInit (the user agent of Dalvik) leave out for a
-- build that is not a release, none is added.
-- It is set when the WebView is made, because pages see it (navigator.userAgent, the requests);
-- user agents the app sets later with the model and build are changed too, and so is the default
-- the app brings back with null or an empty user agent. Other user agents of the app stay.
-- So do user agents with the model and build the app sends as a header, which it can have stored before,
-- with HttpURLConnection or OkHttp, of which Request.Builder.header and addHeader call Headers.Builder.set and add.

function reduce(ua)
    return (string.gsub(ua, '; ([^;]*)Build/[^;)]*', function(model)
        -- Without a model, which RuntimeInit puts right after the version, like Android Baklava Build/, when no version
        -- came before, as one does before the model of emulators, Android SDK built for x86
        if model == '' or (string.find(model, '^Android ') and not string.find(ua, 'Android [^;]*;')) then
            return '; ' .. model .. 'Build/unknown'
        end
        return '; unknown Build/unknown'
    end, 1))
end

local HEADERS = { set = true, add = true, addUnsafeNonAscii = true, setRequestProperty = true,
    addRequestProperty = true }

-- The value of a User-Agent header, the name is not case sensitive
function before(h, param)
    if not HEADERS[h:getMethodName()] then
        return false
    end
    local name = param:getArgument(0)
    local ua = param:getArgument(1)
    if name == nil or ua == nil or string.lower(name) ~= 'user-agent' then
        return false
    end
    local fake = reduce(ua)
    if fake == ua then
        return false
    end
    param:setArgument(1, fake)
    return true, ua, fake
end

-- The parameter is not named hook, the function to hook a method is
function after(h, param)
    -- Headers are done before, their objects have no settings
    if HEADERS[h:getMethodName()] then
        return false
    end
    if h:getMethodName() == 'getDefaultUserAgent' then
        local result = param:getResult()
        if result == nil then
            return false
        end
        local fake = reduce(result)
        if fake == result then
            return false
        end
        param:setResult(fake)
        return true, result, fake
    end

    local this = param:getThis()
    -- The hooked constructor is the one all others call, so this runs once per WebView
    if this == nil then
        return false
    end

    local settings = this:getSettings()
    if settings == nil then
        return false
    end
    hook(settings, 'setUserAgentString', setUserAgentString)
    hints(settings, param)
    pagescript(this, param)

    local ua = settings:getUserAgentString()
    if ua == nil then
        return false
    end
    local fake = reduce(ua)
    if fake == ua then
        return false
    end
    settings:setUserAgentString(fake)
    return true, ua, fake
end

-- Pages can also read the model by User-Agent Client Hints (navigator.userAgentData, Sec-CH-UA-Model), which Chromium
-- makes of the same property as the user agent. WebView is believed to send none for a user agent without the default
-- one in it, like the changed one; with androidx.webkit in the app the model also becomes the fake one, also the
-- default one (null), an empty one stays. Apps without it, or with its classes renamed by R8, are left as they are.
-- shortcut: a default user agent without the build, like the reduced one of Android 17, stays the default one, so in
-- apps without androidx.webkit the client hints are believed to keep the real model; upgrade by setting the metadata
-- through the support library glue of the WebView provider, which needs no androidx in the app.
function hints(settings, param)
    pcall(function()
        if not param:findClass('androidx.webkit.WebViewFeature'):isFeatureSupported('USER_AGENT_METADATA') then
            return
        end
        local compat = param:findClass('androidx.webkit.WebSettingsCompat')
        local metadata = compat:getUserAgentMetadata(settings)
        local model = metadata:getModel()
        if model == '' or model == 'unknown' then
            return
        end
        local builder = luajava.new(param:findClass('androidx.webkit.UserAgentMetadata$Builder'), metadata)
        compat:setUserAgentMetadata(settings, builder:setModel('unknown'):build())
    end)
end

-- Pages also read the graphics chip by the WebGL extension WEBGL_debug_renderer_info (its unmasked renderer and
-- vendor, like Adreno (TM) 740 and Qualcomm), by WEBGL_debug_shaders (the shaders as translated with the workarounds
-- Chromium has for the driver of a chip, like the ones of Adreno and PowerVR), by the WebGL extensions Chromium has
-- only with a GL extension of a vendor in the driver, which gles_getstring leaves out
-- (WEBGL_compressed_texture_pvrtc with one of IMG, WEBGL_render_shared_exponent with one of QCOM,
-- NV_shader_noperspective_interpolation with one of NV and WEBGL_polygon_mode, which ANGLE has on an OpenGL ES driver
-- only with GL_NV_polygon_mode), by the adapter of WebGPU (its vendor and architecture), and the number of processors by
-- navigator.hardwareConcurrency, which Chromium gets in native code and in its own renderer process, out of reach of
-- the hooks of glGetString and availableProcessors. With androidx.webkit in the app, this script runs in every frame
-- when its document starts to load, before the page's own: the extensions are not there, like in a WebGL without
-- them, getExtension gives null (for the names without regard to case, like Chromium) and getSupportedExtensions
-- leaves them out, so getParameter gives null for the renderer and vendor with INVALID_ENUM, like Chromium does for an
-- extension not enabled; requestAdapter gives null at once, like Chromium does when WebGPU is off in the WebView,
-- without asking the browser, which would resolve its promise with the real adapter and so hand it to a getter of the
-- page on then; and there are 8 processors, like cpu_count gives. The functions are methods, like the ones of the
-- browser, and call the ones of the browser by Reflect.apply, taken before the page can change it, so a receiver of
-- another type and, but for requestAdapter, wrong arguments throw or reject like in the browser, the receiver checked
-- before the arguments are converted; requestAdapter checks it by getPreferredCanvasFormat, which has no effects, and
-- calls the browser's only for a receiver of another type, to reject like it (a WebGPU without
-- getPreferredCanvasFormat, older than any Chromium shipped it in, gives null to every receiver). They make no lookup
-- the page can change: the list is filtered
-- in place, and the promise of requestAdapter comes from the Promise.resolve taken at the start, resolved with null,
-- which has no then. Function.prototype.toString gives the browser's text of the replaced functions and of itself,
-- also in the other frames with the script, since it knows them by their source. Comments stay out of the script,
-- which a frame without it can read. The other extensions stay, and so do the limits and precisions of WebGL, which
-- tell the chip only by comparison.
-- shortcut: workers, frames of about:blank which the page makes itself and apps without androidx.webkit (or with its
-- classes renamed by R8) read the real values, toString of such a frame shows the source of the functions, and
-- stacks captured while the functions run, also by the page's own code they call, like the toString of a name, show
-- a frame of the script; upgrade when the script can be added by the WebView provider itself (the support library
-- glue) and before workers start.
-- shortcut: requestAdapter does not read its options, so the page's getters on them are not called and invalid ones
-- give null instead of a TypeError; convert them like WebIDL if pages are seen to check that.
local PAGE = [[(function () {
  try {
    var apply = Reflect.apply, string = String, upper = String.prototype.toUpperCase, promise = Promise;
    var source = Function.prototype.toString, describe = Object.getOwnPropertyDescriptor, resolve = Promise.resolve;
    var HIDDEN = ['WEBGL_DEBUG_RENDERER_INFO', 'WEBGL_DEBUG_SHADERS', 'WEBGL_COMPRESSED_TEXTURE_PVRTC',
      'WEBGL_RENDER_SHARED_EXPONENT', 'NV_SHADER_NOPERSPECTIVE_INTERPOLATION', 'WEBGL_POLYGON_MODE'], masks = [];
    function mask(fake, real) {
      masks.push(apply(source, fake, []), apply(source, real, []));
      return fake;
    }
    function hidden(name) {
      name = apply(upper, name, []);
      for (var i = 0; i < HIDDEN.length; i++)
        if (HIDDEN[i] === name) return true;
      return false;
    }
    Function.prototype.toString = mask({
      toString() {
        var text = apply(source, this, arguments);
        for (var i = 0; i < masks.length; i += 2)
          if (masks[i] === text) return masks[i + 1];
        return text;
      }
    }.toString, source);
    [self.WebGLRenderingContext, self.WebGL2RenderingContext].forEach(function (context) {
      if (!context) return;
      var proto = context.prototype, getExtension = proto.getExtension;
      var getSupportedExtensions = proto.getSupportedExtensions;
      proto.getExtension = mask({
        getExtension(name) {
          if (arguments.length === 0 || typeof name === 'symbol') return apply(getExtension, this, arguments);
          apply(getSupportedExtensions, this, []);
          name = string(name);
          if (hidden(name)) return null;
          return apply(getExtension, this, [name]);
        }
      }.getExtension, getExtension);
      proto.getSupportedExtensions = mask({
        getSupportedExtensions() {
          var list = apply(getSupportedExtensions, this, arguments), j = 0;
          if (!list) return list;
          for (var i = 0; i < list.length; i++)
            if (!hidden(list[i])) list[j++] = list[i];
          list.length = j;
          return list;
        }
      }.getSupportedExtensions, getSupportedExtensions);
    });
    var concurrency = self.Navigator && describe(Navigator.prototype, 'hardwareConcurrency');
    if (concurrency && concurrency.get) {
      var processors = concurrency.get;
      concurrency.get = mask(describe({
        get hardwareConcurrency() {
          apply(processors, this, []);
          return 8;
        }
      }, 'hardwareConcurrency').get, processors);
      Object.defineProperty(Navigator.prototype, 'hardwareConcurrency', concurrency);
    }
    if (self.GPU) {
      var requestAdapter = GPU.prototype.requestAdapter, format = GPU.prototype.getPreferredCanvasFormat;
      GPU.prototype.requestAdapter = mask({
        requestAdapter() {
          try {
            if (format) apply(format, this, []);
          } catch (e) {
            return apply(requestAdapter, this, arguments);
          }
          return apply(resolve, promise, [null]);
        }
      }.requestAdapter, requestAdapter);
    }
  } catch (e) {
  }
})();]]

function pagescript(webview, param)
    pcall(function()
        if not param:findClass('androidx.webkit.WebViewFeature'):isFeatureSupported('DOCUMENT_START_SCRIPT') then
            return
        end
        -- Every origin, so every frame, also of other sites
        param:findClass('androidx.webkit.WebViewCompat'):addDocumentStartJavaScript(webview, PAGE,
            luajava.bindClass('java.util.Collections'):singleton('*'))
    end)
end

function setUserAgentString(when, param)
    if when ~= 'before' then
        return
    end
    local ua = param:getArgument(0)
    if ua == nil or ua == '' then
        local webSettings = luajava.bindClass('android.webkit.WebSettings')
        -- Not the hooked call, which the app did not make
        ua = param:unhooked(function() return webSettings:getDefaultUserAgent(param:getApplicationContext()) end)
        if ua == nil then
            return
        end
    end
    local fake = reduce(ua)
    if fake ~= param:getArgument(0) then
        param:setArgument(0, fake)
    end
end
