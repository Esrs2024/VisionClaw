package com.morethantiles.inspector

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.core.Wearables

class InspectorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Wearables.initialize(this).onFailure { error, _ ->
                Log.e("MTTInspector", "Meta DAT initialization: ${error.description}")
            }
        } catch (e: Throwable) {
            Log.e("MTTInspector", "Meta DAT failed to initialize; keep app alive", e)
        }
    }
}
