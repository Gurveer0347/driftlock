package org.driftlock.app

import android.os.Build
import android.view.View
import android.view.Window

/** A window-local preference. Android still owns thermal/battery/display policy. */
internal fun preferredSmoothRefreshRate(supported:FloatArray):Float =
    supported.filter{it.isFinite() && it>0 && it<=120.1f}.maxOrNull() ?: 0f

/** Window mode selection overrides the surface-rate hint on modern Android. */
internal fun requestSmoothRefreshRate(window:Window,host:View,rate:Float,legacyModeId:Int) {
    window.attributes=window.attributes.apply {
        preferredRefreshRate=rate
        preferredDisplayModeId=if(Build.VERSION.SDK_INT>=34)0 else legacyModeId
    }
    if(Build.VERSION.SDK_INT>=35)host.requestedFrameRate=rate
}
