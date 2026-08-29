package com.xiaochuang.freeform.xposed.ui.window

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.ITaskStackListenerProxy
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Context.DISPLAY_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.IPackageManagerHidden
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.IRotationWatcher
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManagerHidden
import android.widget.ImageButton
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.dynamicanimation.animation.FlingAnimation
import androidx.dynamicanimation.animation.flingAnimationOf
import androidx.wear.widget.RoundedDrawable
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.getObject
import com.github.kyuubiran.ezxhelper.utils.getObjectAs
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.google.android.material.color.MaterialColors
import com.xiaochuang.freeform.common.getAttr
import com.xiaochuang.freeform.common.gson
import com.xiaochuang.freeform.common.onException
import com.xiaochuang.freeform.common.runMain
import com.xiaochuang.freeform.databinding.LeftBackGestureOverlayBinding
import com.xiaochuang.freeform.databinding.RightBackGestureOverlayBinding
import de.robv.android.xposed.XposedHelpers
import com.xiaochuang.freeform.databinding.WindowAppBinding
import kotlinx.coroutines.withContext
import com.xiaochuang.freeform.xposed.services.YAMFManager
import com.xiaochuang.freeform.xposed.services.YAMFManager.config
import com.xiaochuang.freeform.xposed.utils.Instances
import com.xiaochuang.freeform.xposed.utils.RunMainThreadQueue
import com.xiaochuang.freeform.xposed.utils.TipUtil
import com.xiaochuang.freeform.xposed.utils.animateResize
import com.xiaochuang.freeform.xposed.utils.animateScaleThenResize
import com.xiaochuang.freeform.xposed.utils.dpToPx
import com.xiaochuang.freeform.xposed.utils.getActivityInfoCompat
import com.xiaochuang.freeform.xposed.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable


@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class AppWindow(
    val context: Context,
    private val flags: Int,
    private val appComponent: ComponentName?,
    private val onVirtualDisplayCreated: (Int) -> Unit
) :
    TextureView.SurfaceTextureListener, SurfaceHolder.Callback {
    companion object {
        const val TAG = "reYAMF_AppWindow"
        const val ACTION_RESET_ALL_WINDOW = "com.xiaochuang.freeform.ui.window.action.ACTION_RESET_ALL_WINDOW"
    }

    lateinit var binding: WindowAppBinding
    lateinit var bindingLeftBackGesture: LeftBackGestureOverlayBinding
    lateinit var bindingRightBackGesture: RightBackGestureOverlayBinding
    private lateinit var virtualDisplay: VirtualDisplay
    private val taskStackListener =
        ITaskStackListenerProxy.newInstance(context.classLoader) { args, method ->
            when (method.name) {
                "onTaskMovedToFront" -> {
                    onTaskMovedToFront(args[0] as ActivityManager.RunningTaskInfo)
                }
                "onTaskDescriptionChanged" -> {
                    onTaskDescriptionChanged(args[0] as ActivityManager.RunningTaskInfo)
                }
                "onTaskRemovalStarted" -> {
                    // 全局 taskStackListener 每个小窗都注册，移除任何 task 都会回调；
                    // 必须校验归属，否则关 A 小窗会把其他小窗也一起销毁
                    val removedTaskId = (args[0] as? Int) ?: -1
                    if (removedTaskId == currentTaskId) {
                        onDestroy()
                    } else {
                        log(
                            TAG,
                            "onTaskRemovalStarted ignored: removed=$removedTaskId " +
                                "current=$currentTaskId display=$displayId"
                        )
                    }
                }
            }
        }
    private val rotationWatcher = RotationWatcher()
    private val surfaceOnTouchListener = SurfaceOnTouchListener()
    private val surfaceOnGenericMotionListener = SurfaceOnGenericMotionListener()
    var displayId = -1
    var rotateLock = false
    var isMini = false
    var isCollapsed = false
    private var halfWidth = 0
    private var halfHeight = 0
    lateinit var surfaceView: View
    private var newDpi = calculateDpi(
        config.defaultWindowWidth, config.defaultWindowHeight,
        calculateScreenInches(config.defaultWindowWidth, config.defaultWindowHeight)
    ) - config.reduceDPI
    private var originalWidth: Int = 0
    private var originalHeight: Int = 0
    private var isResize: Boolean = true
    private var orientation = 0
    private var params = WindowManager.LayoutParams()
    private var paramsBg = WindowManager.LayoutParams()
    private var backGestureJob: Job? = null
    private var isSuperShown = false
    private var isLoadingShowing = true
    private var currentTaskId = -1
    private var isCollapseAnimating = false

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_RESET_ALL_WINDOW) {
                val lp = binding.root.layoutParams as WindowManager.LayoutParams
                lp.apply {
                    x = 0
                    y = 0
                }
                Instances.windowManager.updateViewLayout(binding.root, lp)
                val width = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200F, context.resources.displayMetrics).toInt()
                val height = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 300F, context.resources.displayMetrics).toInt()
                binding.vSizePreviewer.updateLayoutParams {
                    this.width = width
                    this.height = height
                }
                surfaceView.updateLayoutParams {
                    this.width = width
                    this.height = height
                }
            }
        }
    }

    init {
        runCatching {
            binding = WindowAppBinding.inflate(LayoutInflater.from(context))
            bindingLeftBackGesture = LeftBackGestureOverlayBinding.inflate(LayoutInflater.from(context))
            bindingRightBackGesture = RightBackGestureOverlayBinding.inflate(LayoutInflater.from(context))
        }.onException { e ->
            Log.e(TAG, "Failed to create new window, did you reboot?", e)
            TipUtil.showToast("Failed to create new window, did you reboot?")
        }.onSuccess {
            doInit()
        }
    }

    private fun doInit() {
        when(config.surfaceView) {
            0 -> {
                surfaceView = binding.viewSurface
                binding.viewTexture.visibility = View.GONE
            }
            1 -> {
                surfaceView = binding.viewTexture
                binding.viewSurface.visibility = View.GONE
            }
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        )

        val displayManager = context.getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display?.rotation ?: Surface.ROTATION_0
        params.y = when (rotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                orientation = 0
                config.portraitY
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                orientation = 1
                config.landscapeY
            }
            else -> 0
        }

        params.apply {
            if (orientation == 0) {
                gravity = Gravity.CENTER
                y = -80.dpToPx().toInt()
            } else {
                gravity = Gravity.TOP or Gravity.START
                y = 0
            }
            x = 0
//            this as WindowLayoutParamsHidden
//            privateFlags = privateFlags or WindowLayoutParamsHidden.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY
        }

        paramsBg = WindowManager.LayoutParams(
            20.dpToPx().toInt(),
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        paramsBg.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

        bindingLeftBackGesture.root.let {
            paramsBg.gravity = Gravity.START or Gravity.TOP
            Instances.windowManager.addView(bindingLeftBackGesture.root, paramsBg)
        }

        bindingRightBackGesture.root.let {
            val paramsBgR = WindowManager.LayoutParams(
                20.dpToPx().toInt(),
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            paramsBgR.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            paramsBgR.gravity = Gravity.END or Gravity.TOP
            Instances.windowManager.addView(bindingRightBackGesture.root, paramsBgR)
        }

        binding.root.let { layout ->
            Instances.windowManager.addView(layout, params)
        }

        binding.rootClickMask.setOnTouchListener { _, event ->
            moveGestureDetector.onTouchEvent(event)
            moveToTopIfNeed(event)
            true
        }

        // 提前固定内容区尺寸：首帧就有正确大小，避免窗口初始透明露出壁纸
        val width = config.defaultWindowWidth.dpToPx().toInt()
        val height = config.defaultWindowHeight.dpToPx().toInt()
        surfaceView.updateLayoutParams {
            this.width = width
            this.height = height
        }
        binding.vSizePreviewer.updateLayoutParams {
            this.width = width
            this.height = height
        }

        // 加载占位图标：直接用包名取 app 图标（不用等 updateTask）
        appComponent?.let { cn ->
            runCatching {
                val icon = context.packageManager.getApplicationIcon(cn.packageName)
                binding.ivLoading.setImageDrawable(icon)
                log(TAG, "ivLoading icon set from ${cn.packageName}")
            }.onFailure { t ->
                log(TAG, "ivLoading icon failed: ${t.message}", t)
            }
        }

        binding.ibSuper.setOnClickListener {
            log(TAG, "ibSuper: show menu")
            showSuperMenu()
        }

        binding.cvTopBarClickMask.setOnTouchListener { _, event ->
            moveGestureDetector.onTouchEvent(event)
            moveToTopIfNeed(event)
            true
        }

        rightResize(binding.ibResize)

        surfaceView.setOnTouchListener(surfaceOnTouchListener)
        surfaceView.setOnGenericMotionListener(surfaceOnGenericMotionListener)

        binding.ibClose.setOnClickListener {
            log(TAG, "ibClose clicked")
            closeWindowAndTask()
        }

        binding.ibFullscreen.setOnClickListener {
            log(TAG, "ibFullscreen clicked")
            hideSuperMenu()
            getTopRootTask()?.runCatching {
                Instances.activityTaskManager.moveRootTaskToDisplay(taskId, 0)
            }?.onFailure { t ->
                if (t is Error) throw t
                TipUtil.showToast("${t.message}")
            }?.onSuccess {
                binding.ibClose.callOnClick()
            }
        }

        binding.ibMinimize.setOnClickListener {
            log(TAG, "ibMinimize clicked")
            hideSuperMenu()
            changeMini()
        }

        binding.ibCollapse.setOnClickListener {
            log(TAG, "ibCollapse clicked")
            hideSuperMenu()
            changeCollapsed()
            true
        }

        binding.ibSuperClose.setOnClickListener {
            log(TAG, "ibSuperClose clicked")
            hideSuperMenu()
        }

        virtualDisplay = Instances.displayManager.createVirtualDisplay(
            "yamf${System.currentTimeMillis()}",
            config.defaultWindowWidth.dpToPx().toInt(),
            config.defaultWindowHeight.dpToPx().toInt(),
            newDpi-config.reduceDPI, null, flags
        )
        displayId = virtualDisplay.display.displayId
        log(TAG, "VirtualDisplay created: displayId=$displayId size=${config.defaultWindowWidth}x${config.defaultWindowHeight}dp (${config.defaultWindowWidth.dpToPx().toInt()}x${config.defaultWindowHeight.dpToPx().toInt()}px) dpi=$newDpi showImeInWindow=${config.showImeInWindow}")
        try {
            val imePolicy = if (config.showImeInWindow) WindowManagerHidden.DISPLAY_IME_POLICY_LOCAL else WindowManagerHidden.DISPLAY_IME_POLICY_FALLBACK_DISPLAY
            (Instances.windowManager as WindowManagerHidden).setDisplayImePolicy(displayId, imePolicy)
            log(TAG, "setDisplayImePolicy displayId=$displayId policy=$imePolicy")
        } catch (e: Throwable) {
            log(TAG, "setDisplayImePolicy failed: ${e.message}", e)
        }
        Instances.activityTaskManager.registerTaskStackListener(taskStackListener)
        (surfaceView as? TextureView)?.surfaceTextureListener = this
        (surfaceView as? SurfaceView)?.holder?.addCallback(this)
        var failCount = 0
        fun watchRotation() {
            runCatching {
                Instances.iWindowManager.watchRotation(rotationWatcher, displayId)
            }.onFailure {
                failCount++
                Log.d(TAG, "watchRotation: fail $failCount")
                watchRotation()
            }
        }
        watchRotation()
        context.registerReceiver(broadcastReceiver, IntentFilter(ACTION_RESET_ALL_WINDOW), Context.RECEIVER_EXPORTED)
        onVirtualDisplayCreated(displayId)

        isResize = false
        binding.cvBackground.post {
            originalWidth = binding.cvBackground.width
            originalHeight = binding.cvBackground.height
            binding.cvBackground.visibility = View.VISIBLE

            binding.cvBackground.radius = config.windowRoundedCorner.dpToPx()
            binding.cvappIcon.radius = config.windowRoundedCorner.dpToPx()


            binding.cvParent.radius = (config.windowRoundedCorner+2).dpToPx()
            originalWidth = binding.cvParent.width
            originalHeight = binding.cvParent.height
            binding.cvParent.visibility = View.VISIBLE

            // 直接显示（深色背景 + app 图标占位），不做 0→1 缩放动画，避免窗口透明期露出壁纸闪烁
            setBackgroundWrapContent()

            // 诊断日志：用窗口绝对坐标确认三个点按钮距窗口底边的真实距离
            binding.ibSuper.post {
                val loc = IntArray(2)
                binding.ibSuper.getLocationInWindow(loc)
                log(
                    TAG,
                    "layout check: window=${binding.root.width}x${binding.root.height} " +
                        "cvParent=${binding.cvParent.width}x${binding.cvParent.height} " +
                        "background=${binding.background.width}x${binding.background.height} " +
                        "cvBackground=${binding.cvBackground.width}x${binding.cvBackground.height} " +
                        "ibSuper windowBottom=${loc[1] + binding.ibSuper.height} " +
                        "rootBottom=${binding.root.height} " +
                        "surfaceView=${surfaceView.width}x${surfaceView.height}"
                )
            }

            CoroutineScope(Dispatchers.Main).launch {
                delay(200)

                binding.cvParent.strokeWidth = 2.dpToPx().toInt()
            }

            isResize = true
        }

        //TODO: Find me a better alternative for less resource usage instead of polling
        backGestureJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                if (isMini || isCollapsed) {
                    withContext(Dispatchers.Main) {
                        bindingLeftBackGesture.root.visibility = View.GONE
                        bindingRightBackGesture.root.visibility = View.GONE
                    }
                } else if (displayId == YAMFManager.currentDisplayId) {
                    withContext(Dispatchers.Main) {
                        bindingLeftBackGesture.root.visibility = View.VISIBLE
                        bindingRightBackGesture.root.visibility = View.VISIBLE
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        bindingLeftBackGesture.root.visibility = View.GONE
                        bindingRightBackGesture.root.visibility = View.GONE
                    }
                }

                delay(500)
            }
        }
    }

    private fun closeWindowAndTask() {
        log(TAG, "closeWindowAndTask: closing window on display $displayId")
        isResize = false
        backGestureJob?.cancel()
        backGestureJob = null
        binding.cvappIcon.visibility = View.INVISIBLE

        // remove the task from the virtual display so the app is closed instead of
        // being moved back to the main display (fullscreen) when the VD is released
        //
        // 注意：android-stub / rikka hidden stub 里都没有 removeTask / removeRootTask 的
        // 声明，且 AOSP main（Android 16）IActivityTaskManager 接口中 removeRootTask(int)
        // 已移除、removeTask(int) 存在，因此只能反射调用运行时真实 Binder 代理上的
        // removeTask(int)。必须显式传 int 参数类型：装箱 Integer 匹配不到 int 签名。
        runCatching {
            val taskId = getTopRootTask()?.taskId ?: 0
            if (taskId > 0) {
                XposedHelpers.callMethod(
                    Instances.activityTaskManager,
                    "removeTask",
                    arrayOf<Class<*>>(Integer.TYPE),
                    taskId
                )
                log(TAG, "closeWindowAndTask: removeTask $taskId ok")
            } else {
                log(TAG, "closeWindowAndTask: no visible root task on display $displayId, closing window only")
            }
        }.onFailure { t ->
            log(TAG, "closeWindowAndTask: removeTask failed: ${t.message}", t)
        }

        CoroutineScope(Dispatchers.IO).launch {
            delay(200)

            withContext(Dispatchers.Main) {
                animateScaleThenResize(
                    binding.cvParent,
                    1F, 1F,
                    0F, 0F,
                    0.5F, 0.5F,
                    0, 0,
                    context
                ) {
                    onDestroy()
                }
            }
        }
    }

    private fun onDestroy() {
        log(TAG, "onDestroy: displayId=$displayId isMini=$isMini isCollapsed=$isCollapsed")
        context.unregisterReceiver(broadcastReceiver)
        Instances.iWindowManager.removeRotationWatcher(rotationWatcher)
        Instances.activityTaskManager.unregisterTaskStackListener(taskStackListener)
        YAMFManager.removeWindow(displayId)
        virtualDisplay.release()
        Instances.windowManager.removeView(binding.root)
        Instances.windowManager.removeView(bindingLeftBackGesture.root)
        Instances.windowManager.removeView(bindingRightBackGesture.root)
    }

    private fun getTopRootTask(): ActivityTaskManager.RootTaskInfo? {
        Instances.activityTaskManager.getAllRootTaskInfosOnDisplay(displayId).forEach { task ->
            if (task.visible)
                return task
        }
        return null
    }

    private fun moveToTop() {
        Instances.windowManager.removeView(bindingLeftBackGesture.root)
        Instances.windowManager.removeView(bindingRightBackGesture.root)
        Instances.windowManager.addView(bindingLeftBackGesture.root, bindingLeftBackGesture.root.layoutParams)
        Instances.windowManager.addView(bindingRightBackGesture.root, bindingRightBackGesture.root.layoutParams)

        Instances.windowManager.removeView(binding.root)
        Instances.windowManager.addView(binding.root, binding.root.layoutParams)
        YAMFManager.moveToTop(displayId)
    }

    private fun showSuperMenu() {
        isSuperShown = true
        binding.ibSuper.visibility = View.GONE
        binding.clSuperLayout.apply {
            alpha = 1f
            visibility = View.VISIBLE
        }
    }

    private fun hideSuperMenu() {
        isSuperShown = false
        binding.clSuperLayout.visibility = View.GONE
        binding.ibSuper.visibility = View.VISIBLE
    }

    private fun moveToTopIfNeed(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_UP && YAMFManager.isTop(displayId).not()) {
            moveToTop()
        }
    }

    private fun updateTask(taskInfo: ActivityManager.RunningTaskInfo) {
        currentTaskId = taskInfo.taskId
        RunMainThreadQueue.add {
            if (taskInfo.isVisible.not()) {
                delay(500) // fixme: use a method that directly determines visibility
            }

            var backgroundColor = 0
            var statusBarColor = 0
            var navigationBarColor = 0
            var taskDescription: ActivityManager.TaskDescription?

            if (Build.VERSION.SDK_INT < 35) {
                val topActivity = taskInfo.topActivity ?: return@add
                taskDescription = Instances.activityTaskManager.getTaskDescription(taskInfo.taskId) ?: return@add
                val activityInfo = (Instances.iPackageManager as IPackageManagerHidden).getActivityInfoCompat(topActivity, 0, taskInfo.getObjectAs("userId"))

                backgroundColor = taskDescription.backgroundColor
                statusBarColor = taskDescription.backgroundColor
                navigationBarColor = taskDescription.backgroundColor
                binding.appIcon.setImageDrawable(RoundedDrawable().apply {
                    drawable = runCatching { taskDescription.icon }.getOrNull()?.let { BitmapDrawable(it) } ?: activityInfo.loadIcon(Instances.packageManager)
                    isClipEnabled = true
                    radius = 100
                })
                binding.ivLoading.setImageDrawable(binding.appIcon.drawable)
            } else {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val runningTasks = activityManager.getRunningTasks(5)

                for (task in runningTasks) {
                    if (task.taskId == taskInfo.taskId) {
                        val packageName = task.baseActivity?.packageName
                        try {
                            val packageManager = context.packageManager
                            backgroundColor = task.taskDescription!!.backgroundColor
                            statusBarColor = task.taskDescription!!.backgroundColor
                            navigationBarColor = task.taskDescription!!.backgroundColor
                            binding.appIcon.setImageDrawable(packageManager.getApplicationIcon(
                                packageName!!
                            ))
                            binding.ivLoading.setImageDrawable(binding.appIcon.drawable)
                        } catch (e: PackageManager.NameNotFoundException) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            if (config.coloredController) {
                val onStateBar = if (MaterialColors.isColorLight(ColorUtils.compositeColors(statusBarColor, backgroundColor)) xor ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)) {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimaryContainer).data
                } else {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimary).data
                }

                binding.ibClose.imageTintList = ColorStateList.valueOf(onStateBar)
                binding.background.setBackgroundColor(navigationBarColor)

                val onNavigationBar = if (MaterialColors.isColorLight(ColorUtils.compositeColors(navigationBarColor, backgroundColor)) xor ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)) {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimaryContainer).data
                } else {
                    context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimary).data
                }

                binding.ibMinimize.imageTintList = ColorStateList.valueOf(onNavigationBar)
                binding.ibFullscreen.imageTintList = ColorStateList.valueOf(onNavigationBar)
                binding.ibResize.imageTintList = ColorStateList.valueOf(onNavigationBar)
                binding.ibSuper.imageTintList = ColorStateList.valueOf(onStateBar)
            }
        }
    }

    fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
        if (taskInfo.getObject("displayId") == displayId) {
            updateTask(taskInfo)
        }
    }

    fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo) {
        if (taskInfo.getObject("displayId") == displayId) {
            if(!taskInfo.isVisible){
                return
            }
            updateTask(taskInfo)
        }
    }

    inner class RotationWatcher : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            runMain {
                if (rotateLock.not())
                    rotate(rotation)
            }
        }
    }

    fun rotate(rotation: Int) {
        if (rotation == 1 || rotation == 3) {
            val t = halfHeight
            halfHeight = halfWidth
            halfWidth = t
            val surfaceWidth = surfaceView.width
            val surfaceHeight = surfaceView.height
            binding.vSizePreviewer.updateLayoutParams {
                width = surfaceHeight
                height = surfaceWidth
            }
            surfaceView.updateLayoutParams {
                width = surfaceHeight
                height = surfaceWidth
            }
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        log(TAG, "onSurfaceTextureAvailable: $width x $height isMini=$isMini isCollapsed=$isCollapsed isResize=$isResize")
        if (isMini.not() && isCollapsed.not()) {
            newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
            virtualDisplay.resize(width, height, newDpi)
            surface.setDefaultBufferSize(width, height)
            halfWidth = width % 2
            halfHeight = height % 2
        } else {
            newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
            virtualDisplay.resize(width * 2 + halfWidth, height * 2 + halfHeight, newDpi)
            surface.setDefaultBufferSize(width * 2 + halfWidth, height * 2 + halfHeight)
        }
        virtualDisplay.surface = Surface(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        log(TAG, "onSurfaceTextureSizeChanged: $width x $height isResize=$isResize")
        if (isResize) {
            if (isMini.not()) {
                newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
                virtualDisplay.resize(width, height, newDpi)
                surface.setDefaultBufferSize(width, height)
                halfWidth = width % 2
                halfHeight = height % 2
            } else {
                newDpi = calculateDpi(width, height, calculateScreenInches(width, height)) - config.reduceDPI
                virtualDisplay.resize(width * 2 + halfWidth, height * 2 + halfHeight, newDpi)
                surface.setDefaultBufferSize(width * 2 + halfWidth, height * 2 + halfHeight)
            }
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // first frame rendered -> hide the loading placeholder (app icon)
        if (isLoadingShowing) {
            isLoadingShowing = false
            binding.ivLoading.visibility = View.GONE
            log(TAG, "first frame rendered, loading placeholder hidden")
        }
    }

    // minimizes the floating window a bar-less only-content floating window
    private fun changeMini() {
        log(TAG, "changeMini: entered isMini=$isMini isCollapsed=$isCollapsed")
        isCollapsed = false
        isResize = false

        if (isMini) {
            isMini = false
            isResize = true
            binding.rootClickMask.visibility = View.GONE

            if (surfaceView is SurfaceView) {
                binding.cvBackground.updateLayoutParams {
                    width = originalWidth
                    height = originalHeight
                }
                setBackgroundWrapContent()
                setParrentWrapContent()
            } else {
                binding.cvBackground.updateLayoutParams {
                    width = originalWidth
                    height = originalHeight
                }
                animateScaleThenResize(
                    binding.cvBackground,
                    0.5F, 0.5F,
                    1F, 1F,
                    0F, 0F,
                    originalWidth, originalHeight,
                    context
                ){
                    setBackgroundWrapContent()
                    setParrentWrapContent()
                    bindingLeftBackGesture.root.visibility = View.VISIBLE
                    bindingRightBackGesture.root.visibility = View.VISIBLE
                }
            }

            surfaceView.visibility = View.VISIBLE
            surfaceView.setOnTouchListener(surfaceOnTouchListener)
            surfaceView.setOnGenericMotionListener(surfaceOnGenericMotionListener)

            return
        }
        else if (!isMini) {
            binding.rootClickMask.visibility = View.VISIBLE
            isMini = true

            if (config.surfaceView == 1) {
                binding.cvBackground.updateLayoutParams {
                    width = originalWidth/2
                    height = originalHeight/2
                }
            } else {
                animateResize(
                    binding.cvBackground,
                    originalWidth, originalWidth/2,
                    originalHeight, originalHeight/2,
                    context
                ){
                    isResize = true
                    bindingLeftBackGesture.root.visibility = View.GONE
                    bindingRightBackGesture.root.visibility = View.GONE
                }
            }

            surfaceView.setOnTouchListener(null)
            surfaceView.setOnGenericMotionListener(null)

            return
        }
    }

    private fun setBackgroundWrapContent() {
        val layoutParams = binding.cvBackground.layoutParams
        layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        binding.cvBackground.layoutParams = layoutParams
    }

    private fun setParrentWrapContent() {
        val layoutParams = binding.cvParent.layoutParams
        layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        binding.cvParent.layoutParams = layoutParams
    }

    private fun changeCollapsed() {
        log(TAG, "changeCollapsed: entered isCollapsed=$isCollapsed")
        if (isCollapseAnimating) {
            // 收起/展开动画进行中，忽略切换请求，避免展开与收起动画竞争导致状态卡死
            log(TAG, "changeCollapsed ignored: collapse animation in progress")
            return
        }
        isResize = false
        if (isCollapsed) {
            binding.rootClickMask.visibility = View.GONE
            expandWindow()
            bindingLeftBackGesture.root.visibility = View.VISIBLE
            bindingRightBackGesture.root.visibility = View.VISIBLE
        } else {
            binding.rootClickMask.visibility = View.VISIBLE
            collapseWindow()
            bindingLeftBackGesture.root.visibility = View.GONE
            bindingRightBackGesture.root.visibility = View.GONE
        }
    }

    private fun expandWindow() {
        log(TAG, "expandWindow")
        isCollapsed = false
        isCollapseAnimating = true
        binding.background.visibility = View.VISIBLE
        binding.ibSuper.visibility = View.VISIBLE

        animateResize(
            binding.appIcon, 40.dpToPx().toInt(), 0, 40.dpToPx().toInt(), 0, context) {
            binding.cvappIcon.visibility = View.GONE
            animateResize(binding.cvBackground, 0, originalWidth, 0, originalHeight, context) {
                setBackgroundWrapContent()
                setParrentWrapContent()
                binding.cvappIcon.visibility = View.VISIBLE

                binding.cvappIcon.visibility = View.GONE
                isResize = true
                isCollapseAnimating = false
            }
        }
    }

    private fun collapseWindow() {
        log(TAG, "collapseWindow")
        isCollapsed = true
        isCollapseAnimating = true

        CoroutineScope(Dispatchers.Main).launch {
            delay(200)

            animateResize(binding.cvBackground, binding.cvBackground.width, 0, binding.cvBackground.height, 0, context) {
                binding.cvappIcon.visibility = View.VISIBLE
                binding.background.visibility = View.GONE
                binding.ibSuper.visibility = View.GONE
                animateResize(binding.appIcon, 0, 40.dpToPx().toInt(), 0, 40.dpToPx().toInt(), context)

                isResize = true
                isCollapseAnimating = false
            }
        }
    }

    private fun calculateScreenInches(width: Int, height: Int): Float {
        val x = (width / context.resources.displayMetrics.xdpi).pow(2)
        val y = (height / context.resources.displayMetrics.ydpi).pow(2)

        return sqrt(x + y)
    }

    private fun calculateDpi(width: Int, height: Int, screenSizeInInches: Float): Int {
        val widthSqr = width.toFloat().pow(2)
        val heightSqr = height.toFloat().pow(2)
        val diagonalPixels = sqrt(widthSqr + heightSqr)

        return floor(diagonalPixels / screenSizeInInches).toInt()
    }

    private fun rightResize(ibResize: ImageButton) {
        ibResize.setOnTouchListener(object : View.OnTouchListener {
            var beginX = 0F
            var beginY = 0F
            var beginWidth = 0
            var beginHeight = 0
            var beginRootX = 0
            var beginRootY = 0
            var minW = 0
            var minH = 0

            var offsetX = 0F
            var offsetY = 0F

            // 保持窗口左上角固定（默认窗口左上角 = 屏幕坐标 (x, y)）
            // 注意：直接改原对象再 updateViewLayout 强制重排（updateViewLayout 不检查
            // 引用是否变化，一定触发 relayout）。之前用 layoutParams=lp 赋回同一个
            // 对象，ViewRootImpl 认为没变不重排，导致 CENTER 模式下窗口以中心扩展、
            // 上下镜像扩大。不能复制 LayoutParams：其拷贝构造在 hidden stub 中被遮蔽
            fun keepTopLeftOrigin(newWidth: Int, newHeight: Int) {
                val lp = binding.root.layoutParams as WindowManager.LayoutParams
                if (orientation == 0) {
                    // 竖屏 gravity=CENTER：x/y 是相对屏幕中心的偏移，宽度/高度变化时
                    // 补偿一半，使左上角（中心偏移量算出的角点）保持不动
                    lp.x = beginRootX + (newWidth - beginWidth) / 2
                    lp.y = beginRootY + (newHeight - beginHeight) / 2
                } else {
                    // 横屏 gravity=TOP|START：x/y 即左上角坐标，直接不动
                    lp.x = beginRootX
                    lp.y = beginRootY
                }
                Instances.windowManager.updateViewLayout(binding.root, lp)
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when(event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        log(TAG, "menu resize DOWN at (${event.rawX.toInt()}, ${event.rawY.toInt()})")
                        // hide the super menu visually (keep layout so the touch stream continues)
                        isSuperShown = false
                        binding.clSuperLayout.alpha = 0f
                        beginX = event.rawX
                        beginY = event.rawY
                        beginWidth = binding.cvBackground.width
                        beginHeight = binding.cvBackground.height
                        val lp = binding.root.layoutParams as WindowManager.LayoutParams
                        beginRootX = lp.x
                        beginRootY = lp.y
                        minW = (config.defaultWindowWidth * 0.4).toInt().dpToPx().toInt()
                        minH = (config.defaultWindowHeight * 0.4).toInt().dpToPx().toInt()
                        binding.vSizePreviewer.updateLayoutParams {
                            width = beginWidth
                            height = beginHeight
                        }
                        binding.vSizePreviewer.visibility = View.VISIBLE
                        binding.cvParent.strokeWidth = 0
                    }
                    MotionEvent.ACTION_MOVE -> {
                        offsetX = event.rawX - beginX
                        offsetY = event.rawY - beginY
                        val targetWidth = (beginWidth + offsetX).toInt().coerceAtLeast(minW)
                        val targetHeight = (beginHeight + offsetY).toInt().coerceAtLeast(minH)
                        binding.vSizePreviewer.updateLayoutParams {
                            width = targetWidth
                            height = targetHeight
                        }
                        // MOVE 期间只移动预览框，不 updateViewLayout 窗口：
                        // 窗口本身保持原地，避免缩放过程中左上角漂移（预览框已显示目标尺寸）
                    }
                    MotionEvent.ACTION_UP -> {
                        log(TAG, "menu resize UP target=${binding.vSizePreviewer.width}x${binding.vSizePreviewer.height}")
                        val w = binding.vSizePreviewer.width
                        val h = binding.vSizePreviewer.height
                        binding.vSizePreviewer.visibility = View.GONE
                        binding.clSuperLayout.visibility = View.GONE
                        binding.clSuperLayout.alpha = 1f
                        binding.cvParent.strokeWidth = 2.dpToPx().toInt()
                        // resize 从菜单展开开始（菜单展开时 ibSuper 已隐藏），结束必须恢复
                        binding.ibSuper.visibility = View.VISIBLE

                        surfaceView.updateLayoutParams {
                            width = w
                            height = h
                        }
                        keepTopLeftOrigin(w, h)

                        // persist new default size (px -> dp)
                        runCatching {
                            val density = context.resources.displayMetrics.density
                            config.defaultWindowWidth = (w / density).roundToInt()
                            config.defaultWindowHeight = (h / density).roundToInt()
                            YAMFManager.updateConfig(gson.toJson(config))
                            log(TAG, "persisted window size: ${config.defaultWindowWidth}x${config.defaultWindowHeight}dp")
                        }.onFailure { t ->
                            log(TAG, "failed to persist size: ${t.message}", t)
                        }
                        moveToTopIfNeed(event)
                    }
                }
                return true
            }
        })
    }

    fun forwardMotionEvent(event: MotionEvent) {
        if (!isSuperShown) {
            val newEvent = MotionEvent.obtain(event)
            newEvent.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
            Instances.inputManager.injectInputEvent(newEvent, 0)
            newEvent.recycle()
        }
    }

    inner class SurfaceOnTouchListener : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            bindingLeftBackGesture.root.visibility = View.VISIBLE
            bindingRightBackGesture.root.visibility = View.VISIBLE
            forwardMotionEvent(event)
            moveToTopIfNeed(event)
            return true
        }
    }

    inner class SurfaceOnGenericMotionListener : View.OnGenericMotionListener {
        override fun onGenericMotion(v: View, event: MotionEvent): Boolean {
            bindingLeftBackGesture.root.visibility = View.VISIBLE
            bindingRightBackGesture.root.visibility = View.VISIBLE
            forwardMotionEvent(event)
            return true
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        virtualDisplay.surface = holder.surface
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        log(TAG, "surfaceChanged: $width x $height")
        newDpi = calculateDpi(width, height, calculateScreenInches(width, height )) - config.reduceDPI
        virtualDisplay.resize(width, height, newDpi)
        // SurfaceView has no per-frame callback: hide the loading placeholder shortly after the surface is ready
        if (isLoadingShowing) {
            binding.root.postDelayed({
                if (isLoadingShowing) {
                    isLoadingShowing = false
                    binding.ivLoading.visibility = View.GONE
                    log(TAG, "surface ready, loading placeholder hidden")
                }
            }, 600)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        virtualDisplay.surface = null
    }

    private val moveGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        var startX = 0
        var startY = 0
        var xAnimation: FlingAnimation? = null
        var yAnimation: FlingAnimation? = null
        var lastX = 0F
        var lastY = 0F
        var last2X = 0F
        var last2Y = 0F

        override fun onDown(e: MotionEvent): Boolean {
            xAnimation?.cancel()
            yAnimation?.cancel()
            val params = binding.root.layoutParams as WindowManager.LayoutParams
            startX = params.x
            startY = params.y
            log(TAG, "move onDown at (${params.x}, ${params.y})")
            return true
        }


        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            e1 ?: return false
            val params = binding.root.layoutParams as WindowManager.LayoutParams
            params.x = (startX + (e2.rawX - e1.rawX)).toInt()
            params.y = (startY + (e2.rawY - e1.rawY)).toInt()
            Instances.windowManager.updateViewLayout(binding.root, params)
            last2X = lastX
            last2Y = lastY
            lastX = e2.rawX
            lastY = e2.rawY
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            e1 ?: return false
            if (e1.source == InputDevice.SOURCE_MOUSE) return false
            log(TAG, "move onFling velocity=($velocityX, $velocityY)")
            val params = binding.root.layoutParams as WindowManager.LayoutParams

            runCatching {
                if (sign(velocityX) != sign(e2.rawX - last2X)) return@runCatching
                xAnimation = flingAnimationOf({
                    params.x = it.toInt()
                    Instances.windowManager.updateViewLayout(binding.root, params)
                }, {
                    params.x.toFloat()
                })
                    .setStartVelocity(velocityX)
                    .setMinValue(0F)
                    .setMaxValue(context.display.width.toFloat() - binding.root.width)
                xAnimation?.start()
            }
            runCatching {
                if (sign(velocityY) != sign(e2.rawY - last2Y)) return@runCatching
                yAnimation = flingAnimationOf({
                    params.y = it.toInt()
                    Instances.windowManager.updateViewLayout(binding.root, params)
                }, {
                    params.y.toFloat()
                })
                    .setStartVelocity(velocityY)
                    .setMinValue(0F)
                    .setMaxValue(context.display.height.toFloat() - binding.root.height)
                yAnimation?.start()
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (isMini && !isCollapsed) changeMini()
            else if (!isMini && isCollapsed) changeCollapsed()
            return true
        }
    })
}
