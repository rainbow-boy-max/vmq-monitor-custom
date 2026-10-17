package com.vone.vmq.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.vone.vmq.service.DaemonService;
import com.vone.vmq.service.ForeService;
import com.vone.vmq.service.NativeDaemonService;

/**
 * 开机自启：设备重启后重新拉起保活服务。
 *
 * 定制版新增（秒囤兔定制版）。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        Log.d(TAG, "收到开机广播，重新拉起保活服务");
        try {
            Intent fore = new Intent(context, ForeService.class);
            Intent daemon = new Intent(context, DaemonService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(fore);
                context.startForegroundService(daemon);
            } else {
                context.startService(fore);
                context.startService(daemon);
            }
            context.startService(new Intent(context, NativeDaemonService.class));
        } catch (Exception e) {
            Log.e(TAG, "启动保活服务失败", e);
        }
    }
}
