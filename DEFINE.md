Defining hooks
==============

Introduction
------------

XPrivacyLua allows you to define Xposed hooks without developing an Xposed module.

Defined hooks can be added and updated at run time,
with the big advantage that no reboot is required to test a new or changed hook
(with the exception of persistent system apps, which are clearly marked in XPrivacyLua).
To apply an updated definition an app just needs to be stopped (force closed) and started again.
An easy way to do this is with *Stop* or *Stop automatically* on the screen of the app in XPrivacyLua.

XPrivacyLua allows you to select which apps a definition should be applied to.

You can edit hook definitions in XPrivacyLua itself, on the *Hooks* tab.

Definition
----------

Hook definitions describe where to hook and what to do when the hook executes.

The *where to hook* is described as:

* Which [class](https://developer.android.com/reference/java/lang/Class.html)
* Which [method](https://developer.android.com/reference/java/lang/reflect/Method.html)

In the well documented [Android API](https://developer.android.com/reference/packages.html) you can find class and method names.
For more advanced hooks, see [here](https://github.com/rovo89/XposedBridge/wiki/Development-tutorial#exploring-your-target-and-finding-a-way-to-modify-it).

The *what to do* is described in the form of a [Lua](https://www.lua.org/pil/contents.html) script.

An exported definition in [JSON](https://en.wikipedia.org/wiki/JSON) format looks like this:

```JSON
{
  "collection": "Privacy",
  "group": "Read.Telephony",
  "name": "TelephonyManager.getDeviceId",
  "author": "M66B",
  "version": 1,
  "description": "Let the telephony manager return NULL as device ID",
  "className": "android.telephony.TelephonyManager",
  "methodName": "getDeviceId",
  "parameterTypes": [],
  "returnType": "java.lang.String",
  "minSdk": 1,
  "maxSdk": 999,
  "enabled": true,
  "optional": false,
  "usage": true,
  "notify": false,
  "settings": [
    "setting_name1",
    "setting_name2"
  ],
  "luaScript": "function after(hook, param)\n    local result = param:getResult()\n    if result == nil then\n        return false\n    end\n\n    param:setResult(nil)\n    return true\nend\n"
}
```

<br>

Note that you can conveniently edit hook definitions in XPrivacyLua (*Hooks* tab), so there is no need to edit JSON files.
Definitions can be imported from and exported to JSON files there too.

<br>

* The *collection* and *name* attributes are used to uniquely identify a hook
* For convenience XPrivacyLua applies hooks by *group*
* The *version* is a number which *Downloads* uses to offer updates of an installed definition
* The *description* is optional and is shown in the hook editor and in *Downloads*
* The attributes *minSdk* and *maxSdk* determine for which [Android versions](https://source.android.com/setup/build-numbers) (API level) the hook should be used (*maxSdk* is optional, default 999: all later versions)
* The optional attributes *minApk* and *maxApk* limit the hook to a range of version codes of the app
* The optional attribute *excludePackages* is a comma separated list of [regular expressions](https://developer.android.com/reference/java/util/regex/Pattern) of package names the hook should not be applied to, like `com\.google\..*`
* Setting *enabled* to *false* will switch the hook off (default *true*)
* Setting *optional* to *true* will suppress error messages about the class or method not being found (default *false*)
* Setting *usage* to *false* means that executing the hook will not be reported (default *true*)
* Setting *notify* to *true* will result in showing notifications when the hook is applied (default *false*)
* Settings (custom values) can be set with *Fake values* in the settings (for all apps) or on the screen of an app (for that app),
  and read using *param:getSetting('setting_name1')*; an app setting takes precedence over the setting for all apps.
  List the names of the settings a script reads in *settings*, so that *Fake values* offers a field for each of them

*Collections* in *Settings* selects which *collection*s of hooks XPrivacyLua should use.

Parameter and return types are Java class names, like *java.lang.String*, *android.net.Uri* or *int*,
nested classes with a $ sign, like *android.content.pm.PackageManager$NameNotFoundException*,
and arrays with [] after the type, like *int[]* or *java.lang.String[]*.
The *returnType* has to be the return type of the method, or a subclass of it (the type of the field for a field).

The Lua script from the above definition without the JSON escapes looks like this:

```Lua
function after(hook, param)
    local result = param:getResult()
    if result == nil then
        return false
    end

    param:setResult(nil)
    return true
end
```

There should be a *before* and/or an *after* function, which will be executed before/after the hooked method is/has been executed.
These functions always have exactly two parameters, in the example named *hook* and *param*.
The *hook* parameter can be used to get meta information about the hook.
The *param* parameter can be used to get or set the current arguments and the current result.
The current arguments are typically modified *before* the hooked method is executed.
The result can be set *before* the hooked method is executed,
which will result in the actual method not executing (thus replacing the method),
or can be set *after* the hooked method has been executed.

The most important functions of *hook* are:

* *hook:getName()*: the name of the definition
* *hook:getGroup()*: the group of the definition, like *Read.Telephony*
* *hook:getClassName()*: the name of the hooked class
* *hook:getMethodName()*: the name of the hooked method, on which a script shared by several definitions can branch
* *hook:getParameterTypes()*: the parameter types of the definition as an array of class names
* *hook:getReturnType()*: the return type of the definition as a class name, like to fake a value of the right type
* For other functions, see [here](app/src/main/java/eu/faircode/xlua/XHook.java) for the available public methods

The most important functions of *param* are:

* *param:getApplicationContext()*: see remarks below
* *param:getThis()*: the current object instance or *nil* if the method is static
* *param:getArgument(index)*: get the argument at the specified index (zero based)
* *param:setArgument(index, value)*
* *param:getResult()*: only available after the hooked method has been executed
* *param:setResult(value)*
* *param:findClass(name)*: load a class of the hooked app, see remarks below
* *param:isInstance(object, name)*: check if an object is an instance of the named class or interface
* For other functions, see [here](app/src/main/java/eu/faircode/xlua/XParam.java) for the available public methods

The before/after function **must** return *true* when something was done and **must** return *false* otherwise.
XPrivacyLua will show the last date/time of the last time *true* was returned.
Optionally the function can return the original and the fake value as text too, like *return true, old, fake*,
which the *Activity* tab shows.

Special cases
-------------

You can hook into a constructor by omitting the method name.

You can modify field values in an *after* function by prefixing the method name with a # character, for example:

```JSON
  "methodName": "#SERIAL"
```

Note that *final static* fields (constants) might be optimized 'away' at compile time,
so setting such a field might not work because it doesn't exist at run time anymore.

Another special case is hooking a method of a field using the syntax *[field name]:[method name]*, for example:

```JSON
  "methodName": "CREATOR:createFromParcel"
```

Remarks
-------

A common problem when developing an Xposed module is getting [a context](https://developer.android.com/reference/android/content/Context.html).
With XPrivacyLua you'll never have to worry about this because you can simply get a context like this:

```Lua
    local context = param:getApplicationContext()
```

You can write to the Android logcat using the *log* function:

```Lua
    log('hello world')
    log(some_object) -- will call toString()
```

A log line starts with the word *Log* followed by the package name and uid of the app and the id of the hook (*collection.name*)
and ends with the log text. This way it is always clear which hook/app logging belongs to.

You can hook a method of an object the script gets hold of, like a listener or the settings of a WebView,
with the *hook* function:

```Lua
    hook(settings, 'setUserAgentString', function(when, param)
        if when == 'before' then
            log(param:getArgument(0))
        end
    end)
```

This hooks the methods with that name which the class of the object declares or inherits, once per definition.
The function is called before and after each execution of them with *when* (*before* or *after*),
a *param* like the one of the *before*/*after* functions, and the arguments given to *hook* after the function, if any.
What it returns is not used; an error in it is written to the logcat, and a method which does not exist is only logged too.
Inside a *before*/*after* function whose first parameter is named *hook* the global *hook* is hidden by that parameter,
so name the parameter differently there, like [webview_constructor](app/src/main/assets/webview_constructor.lua) does.

An error in the definition, like class or method not found, or a compile time or run time error of/in the Lua script will result in a status bar notification.
Tapping the error notification opens the screen of the app, where the failed group has an error icon which shows the details of the error.

*luajava.bindClass* and *luajava.newInstance* load classes with the context class loader of the current thread,
which can be a class loader that does not know the classes of the app, like classes of Google Play services.
To use such classes, load them with *param:findClass*:

```Lua
    local cls = param:findClass('com.google.android.gms.location.DetectedActivity')
    local detected = luajava.new(cls, 4, 100)
```

A script is executed once per thread to define its functions, so a script should only define functions.

[LuaJ](http://www.luaj.org/luaj/3.0/README.html) globals are not thread safe.
If you need global caching, you can use something like this:

```Lua
    local scope = param:getApplicationContext()
    param:putValue(name, value, scope)
    local value = param:getValue(name, scope)
```

On the *Hooks* tab you can edit built-in definitions, which will result in making a copy of the definition.
You could for example enable usage notifications or change returned fake values.
Deleting copied definitions will restore the built-in definitions.

With *Downloads* on the *Hooks* tab you can install hook definitions shared by others, which are part of the app (folder *repo*).
To share a definition, export it in the hook editor and offer it as a pull request,
as *repo/hook_N.json* with an entry with the id N, the time of the update (seconds since 1970) and 0 downloads in *repo/list.json*.

You can find example definitions in the [repository](repo), which *Downloads* offers,
and the definitions built into XPrivacyLua [here](app/src/main/assets).

If you have questions, you can ask them in an [issue](https://github.com/akano12/XPrivacyLua/issues).
