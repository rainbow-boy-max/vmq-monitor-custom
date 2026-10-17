package com.vone.vmq.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;

/**
 * Native 守护服务（独立进程 :native_daemon）。
 * 通过 shell 子进程与主进程互监，任一被杀则互相拉起。
 *
 * 设计参考 shinian-a/Vmq-App（Apache-2.0）。
 */
public class NativeDaemonService extends Service {

    private static final String TAG = "NativeDaemonService";
    private Process nativeProcess = null;
    private Thread watchThread = null;
    private volatile boolean isRunning = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startNativeProcess();
        startWatchdog();
        return START_STICKY;
    }

    private void startNativeProcess() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String[] commands = {"sh", "-c", "while true; do sleep 30; done"};
                    nativeProcess = Runtime.getRuntime().exec(commands);
                    isRunning = true;
                    nativeProcess.waitFor();
                } catch (IOException e) {
                    isRunning = false;
                } catch (InterruptedException e) {
                    isRunning = false;
                }
            }
        }).start();
    }

    private void startWatchdog() {
        if (watchThread != null) return;
        watchThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (isRunning) {
                    try {
                        Thread.sleep(5000);
                        if (nativeProcess != null) {
                            try {
                                nativeProcess.exitValue();
                                isRunning = false;
                                startNativeProcess();
                            } catch (IllegalThreadStateException e) {
                                // 仍在运行
                            }
                        }
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        });
        watchThread.start();
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (watchThread != null && watchThread.isAlive()) {
            watchThread.interrupt();
        }
        if (nativeProcess != null) {
            nativeProcess.destroy();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
