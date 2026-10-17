package com.vone.vmq.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.vone.qrcode.R;

/**
 * 守护前台服务（独立进程 :daemon_service）。
 *
 * 逻辑：startForeground 后立刻启动 CancelNoticeService 把常驻通知移除，
 * 但服务本身仍处于前台态，从而在不打扰用户的前提下抬高 oom_adj（降低被杀概率）。
 *
 * 设计参考 shinian-a/Vmq-App（Apache-2.0）。
 */
public class DaemonService extends Service {

    private static final String TAG = "DaemonService";
    public static final int NOTICE_ID = 100;
    private static final String CHANNEL_ID = "daemon_service_channel";
    private static final String CHANNEL_NAME = "守护服务";

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            createNotificationChannel();
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTICE_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    startForeground(NOTICE_ID, buildNotification());
                }
            } catch (Exception e) {
                Log.e(TAG, "startForeground failed", e);
            }
            // 移除常驻通知（保留前台态）
            try {
                startService(new Intent(this, CancelNoticeService.class));
            } catch (Exception e) {
                Log.e(TAG, "start CancelNoticeService failed", e);
            }
        } else {
            try {
                startForeground(NOTICE_ID, new Notification());
            } catch (Exception e) {
                Log.e(TAG, "legacy startForeground failed", e);
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("用于保持应用后台运行");
            channel.enableLights(false);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID).setPriority(Notification.PRIORITY_MIN);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("KeepAppAlive")
                .setContentText("DaemonService is running...")
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            NotificationManager mManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (mManager != null) {
                mManager.cancel(NOTICE_ID);
            }
        }
        // 被杀死后立刻重启自己
        try {
            Intent intent = new Intent(getApplicationContext(), DaemonService.class);
            startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "restart self failed", e);
        }
    }
}
