package com.vone.vmq.util;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

/**
 * 同步管理器：创建系统虚拟账户并注册周期性同步，实现「系统级保活」。
 *
 * 设计参考 shinian-a/Vmq-App（Apache-2.0）。
 */
public class SyncManager {

    private static final String TAG = "SyncManager";

    // 账户类型（必须与 authenticator.xml 一致）
    public static final String ACCOUNT_TYPE = "com.vone.qrcode.account";
    // 账户名称
    public static final String ACCOUNT_NAME = "VmqAppSyncAccount";
    // ContentProvider authority（必须与 syncadapter.xml 一致）
    public static final String AUTHORITY = "com.vone.qrcode.provider";
    // 同步间隔（15 分钟 = 900 秒）
    private static final long SYNC_INTERVAL = 15 * 60;

    private static SyncManager sInstance;

    private final Context mContext;
    private final Account mAccount;
    private final AccountManager mAccountManager;

    private SyncManager(Context context) {
        mContext = context.getApplicationContext();
        mAccountManager = AccountManager.get(mContext);
        mAccount = new Account(ACCOUNT_NAME, ACCOUNT_TYPE);
    }

    public static SyncManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (SyncManager.class) {
                if (sInstance == null) {
                    sInstance = new SyncManager(context);
                }
            }
        }
        return sInstance;
    }

    /** 创建并注册账户到系统 */
    public boolean createAndRegisterAccount() {
        try {
            Account[] accounts = mAccountManager.getAccountsByType(ACCOUNT_TYPE);
            if (accounts.length > 0) {
                Log.d(TAG, "账户已存在，无需重复创建");
                return true;
            }
            if (mAccountManager.addAccountExplicitly(mAccount, null, null)) {
                Log.i(TAG, "账户创建成功：" + ACCOUNT_NAME);
                ContentResolver.setIsSyncable(mAccount, AUTHORITY, 1);
                return true;
            }
            Log.e(TAG, "账户创建失败");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "创建账户时发生异常", e);
            return false;
        }
    }

    /** 启动周期性同步（系统每 15 分钟唤醒一次应用） */
    public void startPeriodicSync() {
        try {
            if (!createAndRegisterAccount()) {
                Log.e(TAG, "账户未创建，无法启动同步");
                return;
            }
            cancelPeriodicSync();

            Bundle extras = new Bundle();
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false);
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false);
            ContentResolver.addPeriodicSync(mAccount, AUTHORITY, extras, SYNC_INTERVAL);
            Log.i(TAG, "周期性同步已启动，间隔：" + SYNC_INTERVAL + "秒");

            requestImmediateSync();
        } catch (Exception e) {
            Log.e(TAG, "启动周期性同步失败", e);
        }
    }

    /** 取消周期性同步 */
    public void cancelPeriodicSync() {
        try {
            if (createAndRegisterAccount()) {
                ContentResolver.removePeriodicSync(mAccount, AUTHORITY, new Bundle());
                Log.d(TAG, "周期性同步已取消");
            }
        } catch (Exception e) {
            Log.e(TAG, "取消周期性同步失败", e);
        }
    }

    /** 请求立即同步 */
    public void requestImmediateSync() {
        try {
            if (createAndRegisterAccount()) {
                Bundle extras = new Bundle();
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY, false);
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, true);
                ContentResolver.requestSync(mAccount, AUTHORITY, extras);
                Log.d(TAG, "已请求立即同步");
            }
        } catch (Exception e) {
            Log.e(TAG, "请求立即同步失败", e);
        }
    }
}
