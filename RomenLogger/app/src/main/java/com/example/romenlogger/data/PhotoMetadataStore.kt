package com.example.romenlogger.data

import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object PhotoMetadataStore {
    @Synchronized
    fun append(recording: Recording, file: File, capturedAtMs: Long, location: Location) {
        val metadataFile = File(recording.directory, "photos.json")
        val photos = if (metadataFile.exists()) {
            runCatching { JSONArray(metadataFile.readText()) }.getOrDefault(JSONArray())
        } else JSONArray()
        photos.put(JSONObject().apply {
            put("file", file.name)
            put("capturedAtMs", capturedAtMs)
            put("lat", location.latitude)
            put("lon", location.longitude)
            put("accuracyM", if (location.hasAccuracy()) location.accuracy else JSONObject.NULL)
        })
        metadataFile.writeText(photos.toString(2))
    }
}
