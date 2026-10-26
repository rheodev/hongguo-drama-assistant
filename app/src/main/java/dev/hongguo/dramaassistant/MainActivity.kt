package dev.hongguo.dramaassistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var permissionButton: Button
    private lateinit var toggleButton: Button
    private lateinit var records: TextView
    private lateinit var diagnostics: TextView
    private val stateListener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(getColor(R.color.surface))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(32))
        }
        scroll.addView(content)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom,
            )
            insets
        }
        setContentView(scroll)
        scroll.requestApplyInsets()

        content.addView(label("短剧切集助手", 28f, bold = true), row(bottom = 12))
        content.addView(label("出现继续观看提示，自动上滑一次。", 16f, secondary = true), row(bottom = 28))

        status = label("", 20f, bold = true).apply {
            setPadding(dp(16), dp(20), dp(16), dp(20))
            setBackgroundColor(getColor(R.color.status_surface))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status, row(bottom = 20))

        permissionButton = action("开启无障碍权限") { showDisclosure() }
        content.addView(permissionButton, row(bottom = 8))
        toggleButton = action("开始自动切集") { RuntimeState.service?.toggle() }
        content.addView(toggleButton, row(bottom = 8))
        content.addView(action("打开红果短剧", primary = false) { openHongguo() }, row(bottom = 28))

        content.addView(label("如何使用", 20f, bold = true), row(bottom = 12))
        content.addView(label(
            "开启无障碍权限后，点击“开始自动切集”，再打开红果观看。点击屏幕边缘的小把手展开，再点播放或暂停图标控制运行。展开后可拖动换边，三秒无操作后自动收起。\n\n" +
                "同时出现“广告”和“上滑继续观看短剧”才会操作。这个提示可能在广告播放中出现，助手不判断视频是否已完整播放。\n\n" +
                "锁屏或服务重启后需要重新开始。无法识别时不会强行滑动；看广告解锁剧集的独立广告页不在处理范围内。",
            16f,
            secondary = true,
        ), row(bottom = 28))

        content.addView(label("最近操作", 20f, bold = true), row(bottom = 12))
        records = label("暂无操作记录", 14f, secondary = true)
        content.addView(records, row(bottom = 24))

        val diagnosticButton = action("查看性能计数", primary = false) {
            diagnostics.visibility = if (diagnostics.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (diagnostics.visibility == View.VISIBLE) updateDiagnostics()
        }
        content.addView(diagnosticButton, row(bottom = 8))
        diagnostics = label("", 14f, secondary = true).apply { visibility = View.GONE }
        content.addView(diagnostics, row())
    }

    override fun onResume() {
        super.onResume()
        RuntimeState.addListener(stateListener)
        render()
    }

    override fun onPause() {
        RuntimeState.removeListener(stateListener)
        super.onPause()
    }

    private fun render() {
        val connected = RuntimeState.service != null
        status.text = RuntimeState.status
        permissionButton.text = if (connected) "管理无障碍权限" else "开启无障碍权限"
        toggleButton.isEnabled = connected
        toggleButton.text = if (RuntimeState.running) "暂停自动切集" else "开始自动切集"
        records.text = RuntimeState.recentRecords().ifEmpty { "暂无操作记录" }
        if (diagnostics.visibility == View.VISIBLE) updateDiagnostics()
    }

    private fun updateDiagnostics() {
        val count = RuntimeState.inspections
        val average = if (count > 0) RuntimeState.totalInspectMicros / count else 0
        diagnostics.text = getString(
            R.string.diagnostics, RuntimeState.events, count, RuntimeState.gestures,
            average, RuntimeState.maxInspectMicros, RuntimeState.lastResponseMs,
        )
    }

    private fun showDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("开启无障碍服务")
            .setMessage("助手将读取红果短剧当前页面的文字和控件，并在识别到广告及继续观看提示时模拟一次上滑。\n\n" +
                "这些信息仅在手机内处理，不上传、不保存截图。你可以用悬浮按钮暂停，或在系统设置中关闭服务。\n\n" +
                "进入设置后，找到“短剧切集助手”并开启。部分系统需先在应用信息的菜单中允许受限设置。")
            .setNegativeButton("取消", null)
            .setPositiveButton("前往设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .show()
    }

    private fun openHongguo() {
        val intent = packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)
        if (intent != null) {
            startActivity(intent)
        } else {
            AlertDialog.Builder(this)
                .setTitle("未找到红果短剧")
                .setMessage("请先安装红果短剧。当前适配的应用包名为 com.phoenix.read。")
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun label(value: String, size: Float, bold: Boolean = false, secondary: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(if (secondary) R.color.secondary else R.color.foreground))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1.1f)
        if (bold) accessibilityHeadingCompat()
    }

    private fun TextView.accessibilityHeadingCompat() {
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    private fun action(value: String, primary: Boolean = true, click: () -> Unit) = Button(this).apply {
        text = value
        textSize = 16f
        isAllCaps = false
        minHeight = dp(56)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        if (primary) {
            backgroundTintList = ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(getColor(R.color.outline), getColor(R.color.primary)),
            )
            setTextColor(getColor(R.color.on_primary))
        } else {
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.status_surface))
            setTextColor(getColor(R.color.primary))
        }
        setOnClickListener { click() }
    }

    private fun row(bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(bottom) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
