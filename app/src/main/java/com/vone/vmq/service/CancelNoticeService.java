package com.vone.vmq.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.vone.qrcode.R;

/**
 * 移除 DaemonService 的常驻通知（选择性使用）。
 *
 * 设计参考 shinian-a/Vmq-App（Apache-2.0）。
 */
public class CancelNoticeService extends Service {

    private static final String CHANNEL_ID = "cancel_notice_channel";
    private static final String CHANNEL_NAME = "临时通知";

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN_MR2) {
            createNotificationChannel();
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(DaemonService.NOTICE_ID, buildNotification(),
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    startForeground(DaemonService.NOTICE_ID, buildNotification());
                }
            } catch (Exception e) {
                // ignore
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    SystemClock.sleep(1000);
                    stopForeground(true);
                    NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (manager != null) {
                        manager.cancel(DaemonService.NOTICE_ID);
                    }
                    stopSelf();
                }
            }).start();
        }
        return super.onStartCommand(intent, flags, startId);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("用于临时移除前台通知");
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
        return builder.setSmallIcon(R.mipmap.ic_launcher).build();
    }
}
