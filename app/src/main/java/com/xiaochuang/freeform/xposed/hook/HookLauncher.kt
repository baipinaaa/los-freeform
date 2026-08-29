package com.xiaochuang.freeform.xposed.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AndroidAppHelper
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.UserHandle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.init.InitFields.moduleRes
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.findAllMethods
import com.github.kyuubiran.ezxhelper.utils.findConstructor
import com.github.kyuubiran.ezxhelper.utils.findField
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.findMethodOrNull
import com.github.kyuubiran.ezxhelper.utils.getObject
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.hookReplace
import com.github.kyuubiran.ezxhelper.utils.hookReturnConstant
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAuto
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAutoAs
import com.github.kyuubiran.ezxhelper.utils.loadClass
import com.github.kyuubiran.ezxhelper.utils.loadClassOrNull
import com.github.kyuubiran.ezxhelper.utils.newInstance
import com.github.kyuubiran.ezxhelper.utils.paramCount
import com.xiaochuang.freeform.R
import com.xiaochuang.freeform.xposed.services.YAMFManager
import com.xiaochuang.freeform.xposed.utils.log
import com.xiaochuang.freeform.xposed.utils.registerReceiver
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Proxy


class HookLauncher : IXposedHookLoadPackage, IXposedHookZygoteInit {
    companion object {
        const val TAG = "reYAMF_HookLauncher"
        const val ACTION_RECEIVE_LAUNCHER_CONFIG =
            "com.xiaochuang.freeform.ACTION_RECEIVE_LAUNCHER_CONFIG"

        const val EXTRA_HOOK_RECENT = "hookRecent"
        const val EXTRA_HOOK_TASKBAR = "hookTaskbar"
        const val EXTRA_HOOK_POPUP = "hookPopup"
        const val EXTRA_HOOK_TRANSIENT_TASKBAR = "hookTransientTaskbar"

        // cache the shortcut icon so opening the task menu doesn't re-rasterize the
        // vector drawable on the launcher UI thread every time (fixes jank)
        private var cachedShortcutIcon: Bitmap? = null
        private val appIconCache = HashMap<String, Bitmap>()
    }

    private var isRegistered = false

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXHelperInit.initZygote(startupParam)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        EzXHelperInit.initHandleLoadPackage(lpparam)
        loadClassOrNull("com.android.launcher3.Launcher") ?: return
        findMethod("com.android.launcher3.Launcher") {
            name == "onCreate"
        }.hookAfter {
            if (!isRegistered) {
                val activity = it.thisObject as Activity
                val application = activity.application
                application.registerReceiver(ACTION_RECEIVE_LAUNCHER_CONFIG) { _, intent ->
                    val hookRecent = intent.getBooleanExtra(EXTRA_HOOK_RECENT, false)
                    val hookTaskbar = intent.getBooleanExtra(EXTRA_HOOK_TASKBAR, false)
                    val hookPopup = intent.getBooleanExtra(EXTRA_HOOK_POPUP, false)
                    val hookTransientTaskbar =
                        intent.getBooleanExtra(EXTRA_HOOK_TRANSIENT_TASKBAR, false)
                    log(
                        TAG,
                        "receive config hookRecent=$hookRecent hookTaskbar=$hookTaskbar hookPopup=$hookPopup hookTranslucentTaskbar=$hookTransientTaskbar"
                    )
                    if (hookRecent) runCatching { hookRecent(lpparam) }.onFailure { e ->
                        log(TAG, "hook recent failed", e) }
                    if (hookTaskbar) runCatching { hookTaskbar(lpparam) }.onFailure { e ->
                        log(TAG, "hook taskbar failed", e) }
                    if (hookPopup) runCatching { hookPopup(lpparam) }.onFailure { e ->
                        log(TAG, "hook popup failed", e) }
                    if (hookTransientTaskbar) runCatching { hookTransientTaskbar(lpparam) }.onFailure { e ->
                        log(TAG, "hook transient failed", e) }
                    application.unregisterReceiver(this)
                }
                application.sendBroadcast(Intent(YAMFManager.ACTION_GET_LAUNCHER_CONFIG).apply {
                    `package` = "android"
                    putExtra("sender", application.packageName)
                })

                isRegistered = true
            }
        }
    }

    private fun hookRecent(lpparam: XC_LoadPackage.LoadPackageParam) {
        log(TAG, "hooking recent ${lpparam.packageName}")
        XposedBridge.hookAllMethods(
            XposedHelpers.findClass(
                "com.android.quickstep.TaskOverlayFactory",
                lpparam.classLoader
            ), "getEnabledShortcuts", object : XC_MethodHook() {
                @SuppressLint("UseCompatLoadingForDrawables")
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                    val taskView = param.args[0] as View
                    val shortcuts = param.result as? MutableList<Any> ?: run {
                        log(TAG, "getEnabledShortcuts result is not a MutableList, skip")
                        return
                    }
                    if (shortcuts.isEmpty()) {
                        log(TAG, "getEnabledShortcuts returned empty list, skip")
                        return
                    }
                    val itemInfo = XposedHelpers.getObjectField(shortcuts[0], "mItemInfo")

                    var task: Any = Unit

                    runCatching {
                        task = XposedHelpers.callMethod(taskView, "getTask")
                    }.onFailure {
                        val taskContainers = XposedHelpers.getObjectField(taskView, "taskContainers") as List<*>
                        val firstContainer = taskContainers[0]
                        task = XposedHelpers.getObjectField(firstContainer, "task")
                    }

                    val activity = taskView.context
                    val key = XposedHelpers.getObjectField(task, "key")
                    val taskId = XposedHelpers.getIntField(key, "id")

                    val userId = XposedHelpers.getIntField(key, "userId")

                    val classRemoteActionShortcut = XposedHelpers.findClass(
                        "com.android.launcher3.popup.RemoteActionShortcut",
                        lpparam.classLoader
                    )

                    val intent = Intent(YAMFManager.ACTION_OPEN_IN_YAMF).apply {
                        setPackage("android")
                    }

                    // fix: TaskViewItemInfo has no matching constructor on LineageOS 23.x (Android 15/16) launcher,
                    // ezxhelper newInstance() throws NoSuchMethodException -> read target component directly
                    val topComponent = runCatching {
                        XposedHelpers.callMethod(itemInfo, "getTargetComponent") as ComponentName
                    }.getOrElse { e ->
                        log(
                            TAG,
                            "getTargetComponent failed on ${itemInfo.javaClass.name}: ${e.message}, falling back to toString()"
                        )
                        val flattened = extractComponentInfo(itemInfo.toString()).toString()
                        val cn = ComponentName.unflattenFromString(flattened)
                        if (cn == null) {
                            log(TAG, "Cannot resolve target component, skip: $itemInfo")
                            return
                        }
                        cn
                    }
                    intent.putExtra(YAMFManager.EXTRA_COMPONENT_NAME, topComponent)
                    intent.putExtra(YAMFManager.EXTRA_TASK_ID, taskId)
                    intent.putExtra(YAMFManager.EXTRA_USER_ID, userId)
                    intent.putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_RECENT)

                    val action = RemoteAction(
                        Icon.createWithBitmap(
                            appIconCache[topComponent.packageName] ?: run {
                                // AdaptiveIconDrawable 的 intrinsicWidth/Height 为 -1，
                                // toBitmap() 不传尺寸必抛异常 -> 必须指定目标尺寸（48dp）
                                val iconSizePx =
                                    (activity.resources.displayMetrics.density * 48).toInt().coerceAtLeast(64)
                                var icon: Bitmap? = null
                                // 优先用 launcher context 取 app 图标
                                runCatching {
                                    val d = activity.packageManager
                                        .getApplicationIcon(topComponent.packageName)
                                    log(
                                        TAG,
                                        "getApplicationIcon(launcher) ok for ${topComponent.packageName}: " +
                                            "${d.javaClass.simpleName} iw=${d.intrinsicWidth} ih=${d.intrinsicHeight}"
                                    )
                                    icon = d.toBitmap(iconSizePx, iconSizePx)
                                }.onFailure {
                                    log(
                                        TAG,
                                        "getApplicationIcon(launcher) failed for ${topComponent.packageName}: ${it.message}"
                                    )
                                }
                                // 多用户/工作资料场景 launcher 查不到时，用系统 context 再试
                                if (icon == null) {
                                    runCatching {
                                        val d = AndroidAppHelper.currentApplication().packageManager
                                            .getApplicationIcon(topComponent.packageName)
                                        log(
                                            TAG,
                                            "getApplicationIcon(system) ok for ${topComponent.packageName}: " +
                                                "${d.javaClass.simpleName} iw=${d.intrinsicWidth} ih=${d.intrinsicHeight}"
                                        )
                                        icon = d.toBitmap(iconSizePx, iconSizePx)
                                    }.onFailure {
                                        log(
                                            TAG,
                                            "getApplicationIcon(system) failed for ${topComponent.packageName}: ${it.message}"
                                        )
                                    }
                                }
                                if (icon != null) {
                                    // 只有成功才缓存：FALLBACK 沙漏不入缓存，避免同包名永久沙漏
                                    appIconCache[topComponent.packageName] = icon
                                } else {
                                    log(TAG, "recents icon FALLBACK(sandclock) for ${topComponent.packageName}")
                                }
                                icon ?: (cachedShortcutIcon
                                    ?: moduleRes.getDrawable(R.drawable.ic_picture_in_picture_alt_24, null)
                                        .toBitmap()
                                        .also { cachedShortcutIcon = it })
                            }
                        ),
                        moduleRes.getString(R.string.open_with_yamf), // + if (BuildConfig.DEBUG) " ($taskId)" else "",
                        "",
                        PendingIntent.getBroadcast(
                            AndroidAppHelper.currentApplication(),
                            1345,
                            intent,
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    val c = classRemoteActionShortcut.constructors[0]
                    val shortcut = when (c.parameterCount) {
                        4 -> c.newInstance(action, activity, itemInfo, null)
                        3 -> c.newInstance(action, activity, itemInfo)
                        else -> {
                            log(
                                TAG,
                                "unknown RemoteActionShortcut constructor: ${c.toGenericString()}"
                            )
                            null
                        }
                    }

                    if (shortcut != null) {
                        shortcuts.add(shortcut)
                        log(
                            TAG,
                            "recents shortcut added for ${topComponent.packageName} " +
                                "taskId=$taskId iconCache=${appIconCache.size}"
                        )
                    }
                    } catch (e: Throwable) {
                        log(TAG, "hookRecent afterHookedMethod failed: ${e.message}", e)
                    }
                }
            })
    }

    private fun hookTaskbar(lpparam: XC_LoadPackage.LoadPackageParam) {
        log(TAG, "hooking taskbar ${lpparam.packageName}")
        loadClass("com.android.launcher3.taskbar.TaskbarActivityContext").apply {
            findMethodOrNull { name == "startItemInfoActivity" }
                ?.hookReplace {
                    val infoIntent = it.args[0].invokeMethodAutoAs<Intent>("getIntent")!!
                    val intent = Intent(YAMFManager.ACTION_OPEN_IN_YAMF).apply {
                        setPackage("android")
                        putExtra(YAMFManager.EXTRA_COMPONENT_NAME, infoIntent.component)
                        putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_TASKBAR)
                    }
                    AndroidAppHelper.currentApplication().sendBroadcast(intent)
                }
            val classWorkspaceiteminfo =
                loadClass("com.android.launcher3.model.data.WorkspaceItemInfo")
            findMethod { name == "onTaskbarIconClicked" }
                .hookBefore {
                    val tag = it.args[0].invokeMethodAuto("getTag")!!
                    if (classWorkspaceiteminfo.isInstance(tag)) {
                        val infoIntent = tag.invokeMethodAutoAs<Intent>("getIntent")!!
                        val intent = Intent(YAMFManager.ACTION_OPEN_IN_YAMF).apply {
                            setPackage("android")
                            putExtra(YAMFManager.EXTRA_COMPONENT_NAME, infoIntent.component)
                            putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_TASKBAR)
                        }
                        AndroidAppHelper.currentApplication().sendBroadcast(intent)
                        it.result = Unit
                    }
                }
        }

    }

    private var proxyClass: Any? = null

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun handleProxyMethod(param: XC_MethodHook.MethodHookParam) {
        val methodName = param.method.name
        val _this = param.thisObject
        when (methodName) {
            "onClick" -> {
                val mItemInfo = _this.getObject("mItemInfo")
                val componentName = mItemInfo.invokeMethod("getTargetComponent") as ComponentName
                val userId = (mItemInfo.getObject("user") as UserHandle)
                AndroidAppHelper.currentApplication()
                    .sendBroadcast(Intent(YAMFManager.ACTION_OPEN_APP).apply {
                        setPackage("android")
                        putExtra(YAMFManager.EXTRA_COMPONENT_NAME, componentName)
                        putExtra(YAMFManager.EXTRA_USER_ID, userId)
                        putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_POPUP)
                    })
                _this.invokeMethodAuto("dismissTaskMenuView")
                param.result = Unit
            }

            "setIconAndContentDescriptionFor" -> {
                val view = param.args[0] as ImageView
                view.setImageDrawable(
                    moduleRes.getDrawable(
                        R.drawable.ic_picture_in_picture_alt_24,
                        null
                    )
                )
                view.contentDescription = moduleRes.getString(R.string.open_with_yamf)
                param.result = Unit
            }

            "setIconAndLabelFor" -> {
                val iconView = param.args[0] as View
                val labelView = param.args[1] as TextView
                iconView.background =
                    moduleRes.getDrawable(R.drawable.ic_picture_in_picture_alt_24, null)
                labelView.text = moduleRes.getString(R.string.open_with_yamf)
                param.result = Unit
            }
        }
    }

    private fun hookPopup(lpparam: XC_LoadPackage.LoadPackageParam) {
        log(TAG, "hooking popup ${lpparam.packageName}")
//        loadClass("com.android.launcher3.Launcher")
//            .findMethod { name == "getSupportedShortcuts" }
//            .hookAfter {
//                val r = (it.result as Stream<*>).toArray()
//                it.result = Stream.of(*r, getOpenInYAMFSystemShortcutFactory(lpparam.classLoader))
//            }
        loadClass("com.android.launcher3.popup.SystemShortcut")
            .findField { name == "INSTALL" }
            .set(null, getOpenInYAMFSystemShortcutFactory(lpparam.classLoader))
        loadClass("com.android.launcher3.popup.SystemShortcut")
            .findAllMethods { true }
            .hookBefore {
                val thiz = it.thisObject
                if (thiz !== proxyClass) return@hookBefore
                handleProxyMethod(it)
            }
        loadClass("com.android.launcher3.popup.SystemShortcut\$Install")
            .findAllMethods { true }
            .hookBefore {
                val thiz = it.thisObject
                if (thiz !== proxyClass) return@hookBefore
                handleProxyMethod(it)
            }
    }

    private fun getOpenInYAMFSystemShortcutFactory(classLoader: ClassLoader): Any {
        return Proxy.newProxyInstance(
            classLoader, arrayOf(loadClass("com.android.launcher3.popup.SystemShortcut\$Factory"))
        ) { _, method, args ->
            if (method.name != "getShortcut") return@newProxyInstance Unit
            return@newProxyInstance loadClass("com.android.launcher3.popup.SystemShortcut\$Install")
                .findConstructor { paramCount == 3 }
                .newInstance(args[0], args[1], args[2])
                .also { proxyClass = it }
        }
    }

    private fun hookTransientTaskbar(lpparam: XC_LoadPackage.LoadPackageParam) {
        log(TAG, "hook transientTaskbar ${lpparam.packageName}")
        loadClass("com.android.launcher3.util.DisplayController")
            .findMethod { name == "isTransientTaskbar" }
            .hookReturnConstant(true)
    }

    fun extractComponentInfo(input: String): String? {
        val regex = Regex("""ComponentInfo\{[^}]+\}""")
        return regex.find(input)?.value
    }
}