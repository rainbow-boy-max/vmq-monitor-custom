package com.vone.vmq.service;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.SyncResult;
import android.os.Bundle;
import android.util.Log;

/**
 * 同步适配器：同步任务本身不做任何数据同步，仅用于「唤醒应用」实现保活。
 *
 * 设计参考 shinian-a/Vmq-App（Apache-2.0）。
 */
public class SyncAdapter extends AbstractThreadedSyncAdapter {

    private static final String TAG = "SyncAdapter";

    public SyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
        Log.d(TAG, "SyncAdapter 初始化完成");
    }

    @Override
    public void onPerformSync(Account account, Bundle extras, String authority,
                              ContentProviderClient provider, SyncResult syncResult) {
        Log.d(TAG, "执行同步任务 - 账户：" + account.name + ", 授权：" + authority);
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Log.w(TAG, "同步任务被中断", e);
        }
        syncResult.stats.numEntries++;
        Log.d(TAG, "同步任务完成");
    }
}
