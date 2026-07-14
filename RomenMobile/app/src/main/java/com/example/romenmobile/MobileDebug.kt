package com.example.romenmobile

import android.util.Log
import java.io.File

object MobileDebug {
    const val TAG = "RomenMobileDbg"

    fun log(event: String, detail: String = "") {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxMb = runtime.maxMemory() / 1024 / 1024
        Log.i(TAG, "[$event] $detail mem=${usedMb}/${maxMb}MB thread=${Thread.currentThread().name}")
    }

    fun fileInfo(file: File): String =
        "${file.name}=${file.length() / 1024}KB exists=${file.exists()}"

    fun <T> time(event: String, block: () -> T): T {
        val t0 = System.currentTimeMillis()
        log("$event/start")
        return try {
            block().also {
                log("$event/done", "dt=${System.currentTimeMillis() - t0}ms ")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "[$event/fail] dt=${System.currentTimeMillis() - t0}ms", t)
            throw t
        }
    }
}
