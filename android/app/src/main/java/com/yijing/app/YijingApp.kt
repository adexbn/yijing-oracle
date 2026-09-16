package com.yijing.app

import android.app.Application
import com.yijing.app.core.PerfTrace

class YijingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // debug 包里打开性能埋点（release 包是空操作）
        PerfTrace.attach(this)
    }
}
