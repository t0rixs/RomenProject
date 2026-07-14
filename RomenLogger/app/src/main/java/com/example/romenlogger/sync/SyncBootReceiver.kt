package com.example.romenlogger.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SyncBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BluetoothSyncServerService.start(context)
    }
}
