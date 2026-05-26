package com.example.romenlogger.log

import android.util.Log

/**
 * 切り分け用ログ（単一タグで [adb] しやすくする）。
 *
 * Mac/Linux の例（このタグだけ流し続ける）:
 * ```
 * adb logcat -s RomenLogger:I
 * ```
 *
 * パッケージで絞る例（Logcat のドロップダウンと同様）:
 * ```
 * adb logcat --pid=$(adb shell pidof -s com.example.romenlogger)
 * ```
 */
object AppLog {
    const val TAG = "RomenLogger"

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun i(message: String, tr: Throwable) {
        Log.i(TAG, message, tr)
    }

    fun w(message: String) {
        Log.w(TAG, message)
    }

    fun w(message: String, tr: Throwable) {
        Log.w(TAG, message, tr)
    }

    fun e(message: String, tr: Throwable? = null) {
        if (tr != null) Log.e(TAG, message, tr) else Log.e(TAG, message)
    }
}
