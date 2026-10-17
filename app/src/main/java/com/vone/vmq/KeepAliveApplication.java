package com.vone.vmq;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;

import com.vone.vmq.receiver.ScreenReceiverUtil;
import com.vone.vmq.ui.SinglePixelActivity;

import java.lang.ref.WeakReference;

/**
 * 全局 Application：负责通知渠道、全局异常兜底、锁屏/解锁监听（1 像素保活驱动）。
 *
 * 保活设计参考自 shinian-a/Vmq-App（Apache-2.0），本项目为「秒囤兔定制版」。
 */
public class KeepAliveApplication extends Application {

    private static final String TAG = "KeepAliveApp";
    private static KeepAliveApplication sInstance;

    private ScreenReceiverUtil screenReceiverUtil;

    public static KeepAliveApplication getInstance() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;

        // 全局异常兜底：任何未捕获异常都不让进程直接崩掉
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                Log.e(TAG, "uncaughtException: " + ex.getMessage(), ex);
            }
        });

        createNotificationChannels();
        registerScreenReceiver();
    }

    /** 统一创建所有通知渠道（Android 8.0+），否则系统「允许通知」开关为灰 */
    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel c1 = new NotificationChannel("1", "收款通知", NotificationManager.IMPORTANCE_HIGH);
        c1.enableLights(true);
        c1.setLightColor(Color.GREEN);
        c1.setShowBadge(true);

        NotificationChannel c2 = new NotificationChannel("vmq_core_service", "核心服务", NotificationManager.IMPORTANCE_LOW);
        c2.setDescription("用于保持应用后台运行");
        c2.enableVibration(false);
        c2.setShowBadge(false);

        NotificationChannel c3 = new NotificationChannel("daemon_service_channel", "守护服务", NotificationManager.IMPORTANCE_MIN);
        c3.enableVibration(false);
        c3.setShowBadge(false);

        NotificationChannel c4 = new NotificationChannel("cancel_notice_channel", "临时通知", NotificationManager.IMPORTANCE_MIN);
        c4.enableVibration(false);
        c4.setShowBadge(false);

        nm.createNotificationChannel(c1);
        nm.createNotificationChannel(c2);
        nm.createNotificationChannel(c3);
        nm.createNotificationChannel(c4);
    }

    /** 锁屏 -> 拉起 1 像素 Activity；亮屏/解锁 -> 结束 1 像素 Activity */
    private void registerScreenReceiver() {
        try {
            screenReceiverUtil = new ScreenReceiverUtil(this);
            screenReceiverUtil.setScreenReceiverListener(new ScreenReceiverUtil.SreenStateListener() {
                @Override
                public void onSreenOn() {
                    finishSinglePixel();
                }

                @Override
                public void onSreenOff() {
                    startSinglePixel();
                }

                @Override
                public void onUserPresent() {
                    finishSinglePixel();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "registerScreenReceiver failed", e);
        }
    }

    private void startSinglePixel() {
        try {
            Intent intent = new Intent(this, SinglePixelActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "startSinglePixel failed", e);
        }
    }

    private void finishSinglePixel() {
        WeakReference<SinglePixelActivity> ref = SinglePixelActivity.sInstance;
        SinglePixelActivity act = (ref == null) ? null : ref.get();
        if (act != null) {
            act.finish();
        }
    }
}
