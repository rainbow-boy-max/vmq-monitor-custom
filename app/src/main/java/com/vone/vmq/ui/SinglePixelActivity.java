package com.vone.vmq.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.vone.vmq.AppConstants;
import com.vone.vmq.MainActivity;
import com.vone.vmq.util.SystemUtils;

import java.lang.ref.WeakReference;

/**
 * 1 像素保活 Activity：锁屏时置于前台，抬高进程优先级。
 *
 * 设计参考 shinian-a/Vmq-App（Apache-2.0）。
 */
public class SinglePixelActivity extends AppCompatActivity {

    public static WeakReference<SinglePixelActivity> sInstance;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = new WeakReference<>(this);
        Window mWindow = getWindow();
        mWindow.setGravity(Gravity.LEFT | Gravity.TOP);
        WindowManager.LayoutParams attrParams = mWindow.getAttributes();
        attrParams.x = 0;
        attrParams.y = 0;
        attrParams.height = 1;
        attrParams.width = 1;
        mWindow.setAttributes(attrParams);
    }

    @Override
    protected void onDestroy() {
        if (!SystemUtils.isAPPALive(this, AppConstants.PACKAGE_NAME)) {
            Intent intentAlive = new Intent(this, MainActivity.class);
            intentAlive.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intentAlive);
        }
        super.onDestroy();
    }
}
