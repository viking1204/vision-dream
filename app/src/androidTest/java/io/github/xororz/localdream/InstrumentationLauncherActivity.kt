package io.github.xororz.localdream

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * 为 OEM 安装器提供可识别的测试包入口。
 *
 * 该 Activity 仅存在于 androidTest APK，不会打入 Vision Dream 正式应用。
 *
 * @author likaixuan
 * @since 2026-07-30
 */
class InstrumentationLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "Vision Dream instrumentation tests"
            },
        )
    }
}
