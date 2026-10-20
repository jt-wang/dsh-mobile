package dev.dshmobile.app.data

import android.os.SystemClock
import android.util.Log
import dev.dshmobile.app.BuildConfig

/** Debug-only fixed labels and monotonic durations. Never log IDs, URLs or content. */
internal object StartupPerf {
    private var startedAt = 0L
    private val first = mutableSetOf<String>()
    @Synchronized fun start() {
        if (!BuildConfig.DEBUG || startedAt != 0L) return
        startedAt = SystemClock.elapsedRealtime()
        Log.d("DSHPerf", "app_start elapsed_ms=0")
    }
    @Synchronized fun mark(label: String) {
        if (!BuildConfig.DEBUG || startedAt == 0L || !first.add(label)) return
        Log.d("DSHPerf", "$label elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
    }
}
