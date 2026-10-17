package com.vone.vmq.service;

import android.annotation.TargetApi;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.vone.vmq.AppConstants;
import com.vone.vmq.MainActivity;
import com.vone.vmq.util.SystemUtils;

/**
 * JobService：5.0+ 即便被 forcestop 也能被系统重新拉起。
 *
 * 设计参考 shinian-a/Vmq-App（Apache-2.0）。
 */
@TargetApi(21)
public class AliveJobService extends JobService {

    private static final String TAG = "AliveJobService";
    private volatile static JobService mKeepAliveService = null;

    public static boolean isJobServiceAlive() {
        return mKeepAliveService != null;
    }

    private static final int MESSAGE_ID_TASK = 0x01;

    private final Handler mHandler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            if (SystemUtils.isAPPALive(getApplicationContext(), AppConstants.PACKAGE_NAME)) {
                Log.d(TAG, "APP 存活");
            } else {
                Intent intent = new Intent(getApplicationContext(), MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Log.e(TAG, "APP 被杀死，重启...");
            }
            jobFinished((JobParameters) msg.obj, false);
            return true;
        }
    });

    @Override
    public boolean onStartJob(JobParameters params) {
        mKeepAliveService = this;
        Message msg = Message.obtain(mHandler, MESSAGE_ID_TASK, params);
        mHandler.sendMessage(msg);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        mHandler.removeMessages(MESSAGE_ID_TASK);
        return false;
    }
}
