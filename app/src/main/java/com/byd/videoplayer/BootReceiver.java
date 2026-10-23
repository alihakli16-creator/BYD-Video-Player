package com.byd.videoplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        SharedPreferences prefs = context.getSharedPreferences("BYDPlayerPrefs", Context.MODE_PRIVATE);
        boolean autoStart = prefs.getBoolean("auto_start_boot", true);

        if (autoStart) {
            Intent launchIntent = new Intent(context, MainActivity.class);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            launchIntent.putExtra("from_boot", true);
            context.startActivity(launchIntent);
        }
    }
}