package com.vone.vmq.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

/**
 * 动态监听 锁屏 / 解锁 / 亮屏 广播，用于驱动 1 像素保活。
 *
 * 设计参考 shinian-a/Vmq-App（Apache-2.0）。
 */
public class ScreenReceiverUtil {

    private static final String TAG = "ScreenReceiverUtil";
    private final Context mContext;
    private SreenBroadcastReceiver mScreenReceiver;
    private SreenStateListener mStateReceiverListener;

    public ScreenReceiverUtil(Context mContext) {
        this.mContext = mContext;
    }

    public void setScreenReceiverListener(SreenStateListener mStateReceiverListener) {
        this.mStateReceiverListener = mStateReceiverListener;
        this.mScreenReceiver = new SreenBroadcastReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        mContext.registerReceiver(mScreenReceiver, filter);
    }

    public void stopScreenReceiverListener() {
        if (mScreenReceiver != null) {
            try {
                mContext.unregisterReceiver(mScreenReceiver);
            } catch (Exception e) {
                Log.e(TAG, "unregister failed", e);
            }
        }
    }

    public class SreenBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (mStateReceiverListener == null) return;
            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                mStateReceiverListener.onSreenOn();
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                mStateReceiverListener.onSreenOff();
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                mStateReceiverListener.onUserPresent();
            }
        }
    }

    public interface SreenStateListener {
        void onSreenOn();

        void onSreenOff();

        void onUserPresent();
    }
}
