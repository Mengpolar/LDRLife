package com.mengpolar.ldrlife

import android.app.Application
import com.amap.api.maps.MapsInitializer
import com.mengpolar.ldrlife.core.Updater
import com.mengpolar.ldrlife.session.Session

class LdrApp : Application(), android.app.Application.ActivityLifecycleCallbacks {

    lateinit var session: Session
        private set

    lateinit var updater: Updater
        private set

    /** 前台状态：收到"想你了"时前台直接播放振动，后台走通知 */
    var isForeground: Boolean = false
        private set

    private var started = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
        // 高德 SDK 10.x 强制隐私合规接口：不调用则地图引擎拒绝工作（errorCode 555570，白屏）
        runCatching {
            MapsInitializer.updatePrivacyShow(this, true, true)
            MapsInitializer.updatePrivacyAgree(this, true)
        }
        updater = Updater(this)
        session = Session(this, getSharedPreferences("ldr", MODE_PRIVATE))
        session.bootstrap()
    }

    override fun onActivityStarted(activity: android.app.Activity) {
        started++
        if (started == 1) isForeground = true
    }

    override fun onActivityStopped(activity: android.app.Activity) {
        started--
        if (started == 0) isForeground = false
    }

    override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
    override fun onActivityResumed(a: android.app.Activity) {}
    override fun onActivityPaused(a: android.app.Activity) {}
    override fun onActivitySaveInstanceState(a: android.app.Activity, out: android.os.Bundle) {}
    override fun onActivityDestroyed(a: android.app.Activity) {}
}
