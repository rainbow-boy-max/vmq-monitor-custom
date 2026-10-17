package com.vone.vmq.util;

import android.app.ActivityManager;
import android.content.Context;

import java.util.List;

/**
 * 工具类：判断应用进程是否存活。
 *
 * 设计参考 shinian-a/Vmq-App（Apache-2.0）。
 */
public class SystemUtils {

    public static boolean isAPPALive(Context mContext, String packageName) {
        boolean isAPPRunning = false;
        ActivityManager activityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) return false;
        List<ActivityManager.RunningAppProcessInfo> appProcessInfoList = activityManager.getRunningAppProcesses();
        if (appProcessInfoList == null) return false;
        for (ActivityManager.RunningAppProcessInfo appInfo : appProcessInfoList) {
            if (packageName.equals(appInfo.processName)) {
                isAPPRunning = true;
                break;
            }
        }
        return isAPPRunning;
    }
}
