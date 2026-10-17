package com.vone.vmq.util;

import android.annotation.TargetApi;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.vone.vmq.service.AliveJobService;

/**
 * JobScheduler 管理类（单例）。
 *
 * 设计参考 shinian-a/Vmq-App（Apache-2.0）。
 */
public class JobSchedulerManager {

    private static final String TAG = "JobSchedulerManager";
    private static final int JOB_ID = 1;
    private static JobSchedulerManager mJobManager;
    private final JobScheduler mJobScheduler;
    private static Context mContext;

    private JobSchedulerManager(Context ctxt) {
        mContext = ctxt;
        mJobScheduler = (JobScheduler) ctxt.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    public static JobSchedulerManager getJobSchedulerInstance(Context ctxt) {
        if (mJobManager == null) {
            mJobManager = new JobSchedulerManager(ctxt);
        }
        return mJobManager;
    }

    @TargetApi(21)
    public void startJobScheduler() {
        if (isBelowLOLLIPOP() || mJobScheduler == null) return;
        if (AliveJobService.isJobServiceAlive()) return;
        try {
            JobInfo.Builder builder = new JobInfo.Builder(JOB_ID,
                    new ComponentName(mContext, AliveJobService.class));
            // 最小周期 15 分钟（系统下限），设备重启后仍然有效
            builder.setPeriodic(15 * 60 * 1000);
            builder.setPersisted(true);
            mJobScheduler.schedule(builder.build());
            Log.d(TAG, "JobScheduler 已启动");
        } catch (Exception e) {
            Log.e(TAG, "startJobScheduler failed", e);
        }
    }

    @TargetApi(21)
    public void stopJobScheduler() {
        if (isBelowLOLLIPOP() || mJobScheduler == null) return;
        mJobScheduler.cancelAll();
    }

    private boolean isBelowLOLLIPOP() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP;
    }
}
