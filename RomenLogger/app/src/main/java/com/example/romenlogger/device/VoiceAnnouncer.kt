package com.example.romenlogger.device

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.example.romenlogger.R

/** THINKLETにTTSエンジンがなくても動作する、同梱済み日本語音声の再生器。 */
class VoiceAnnouncer(private val context: Context) {
    private var player: MediaPlayer? = null

    fun speak(message: String) {
        val resource = when (message) {
            "記録を開始します" -> R.raw.start
            "記録を終了します" -> R.raw.stop
            "記録を一時停止します" -> R.raw.pause
            "記録を再開します" -> R.raw.resume
            "撮影しました" -> R.raw.photo
            "撮影に失敗しました" -> R.raw.photo_failed
            "記録は開始されていません" -> R.raw.not_started
            "カメラを使用できません" -> R.raw.camera_unavailable
            "記録中のみ撮影できます" -> R.raw.recording_only
            "位置情報を取得できません" -> R.raw.location_unavailable
            else -> return
        }
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            context.resources.openRawResourceFd(resource).use { afd ->
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            setVolume(1f, 1f)
            setOnCompletionListener { it.release(); if (player === it) player = null }
            prepare()
            start()
        }
    }

    fun shutdown() {
        player?.release()
        player = null
    }
}
