package com.example.romenlogger.data

/** 画面プレビュー用の1サンプル（記録CSVと同じ単位: m/s²） */
data class AccelSample(
    val timestampMs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float
)
