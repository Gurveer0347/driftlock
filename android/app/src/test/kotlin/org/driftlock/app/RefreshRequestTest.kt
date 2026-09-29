package org.driftlock.app

import android.app.Activity
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RefreshRequestTest {
    @Test fun modernRequestDoesNotOverrideTheSurfaceRateWithADisplayMode() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val host=View(activity.get())
            requestSmoothRefreshRate(activity.get().window,host,120f,3)
            assertEquals(120f,activity.get().window.attributes.preferredRefreshRate,.001f)
            assertEquals("A display mode would make Android ignore the rate hint",0,activity.get().window.attributes.preferredDisplayModeId)
            assertEquals("The active drawing view must vote for smooth animation",120f,host.requestedFrameRate,.001f)
        } finally { activity.pause().stop().destroy() }
    }
    @Test @Config(sdk=[33]) fun legacyDeviceUsesItsSupportedSameResolutionMode() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            requestSmoothRefreshRate(activity.get().window,View(activity.get()),90f,2)
            assertEquals(90f,activity.get().window.attributes.preferredRefreshRate,.001f)
            assertEquals(2,activity.get().window.attributes.preferredDisplayModeId)
        } finally { activity.pause().stop().destroy() }
    }
}
