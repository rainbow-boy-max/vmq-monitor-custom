package com.vone.vmq;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import androidx.core.app.NotificationCompat;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * V免签监控端 · 定制版（解析规则增强）
 *
 * 相对原版（szvone/vmqApk）的改动：
 *  1. 支付宝：title + content 双查，关键字扩充（原版只查 content，新版支付宝常把关键字放标题 → 漏单）
 *  2. 微信：title 由「全等」改为「包含」，关键字集合扩展
 *  3. getMoney：优先解析 ￥/¥/元 附近的金额，回退取数字串；统一 2 位小数
 *  4. 去重：同一 (type, 金额) 在 15 秒内只推送一次
 *  5. 无效条幅过滤：退款/撤销/失败/退回 等一律忽略
 */
public class NeNotificationService2 extends NotificationListenerService {
    private String TAG = "NeNotificationService2";
    private String host = "";
    private String key = "";
    private Thread newThread = null;
    private PowerManager.WakeLock mWakeLock = null;

    // 支付宝收款关键字（title 或 content 命中即可）
    private static final String[] ALIPAY_KEYS = {
            "通过扫码向你付款", "成功收款", "收款到账", "向你付款", "收款成功", "到账提醒", "收款"
    };
    // 微信收款标题（包含匹配）
    private static final String[] WX_TITLES = {
            "微信支付", "微信收款助手", "微信收款商业版", "微信收款", "收款到账", "微信支付收款"
    };
    // 微信收款内容关键字
    private static final String[] WX_KEYS = {
            "收款", "到账", "已存入零钱", "已到账"
    };
    // 无效条幅（命中即忽略）
    private static final String[] NEG_KEYS = {
            "退款", "退回", "撤销", "失败", "转出", "支出", "账单", "优惠", "活动", "红包"
    };

    private static long lastPushMs = 0L;
    private static String lastPushKey = "";

    @SuppressLint("InvalidWakeLockTag")
    public void acquireWakeLock(Context context) {
        if (null == mWakeLock) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "WakeLock");
            if (null != mWakeLock) {
                mWakeLock.acquire();
            }
        }
    }

    public void releaseWakeLock() {
        if (null != mWakeLock) {
            mWakeLock.release();
            mWakeLock = null;
        }
    }

    // 心跳进程
    public void initAppHeart() {
        Log.d(TAG, "开始启动心跳线程");
        if (newThread != null) {
            return;
        }
        acquireWakeLock(this);
        newThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "心跳线程启动！");
                while (true) {
                    SharedPreferences read = getSharedPreferences("vone", MODE_PRIVATE);
                    host = read.getString("host", "");
                    key = read.getString("key", "");

                    String t = String.valueOf(new Date().getTime());
                    String sign = md5(t + key);

                    OkHttpClient okHttpClient = new OkHttpClient();
                    Request request = new Request.Builder().url("http://" + host + "/appHeart?t=" + t + "&sign=" + sign).method("GET", null).build();
                    Call call = okHttpClient.newCall(request);
                    call.enqueue(new Callback() {
                        @Override
                        public void onFailure(Call call, IOException e) {
                            final String error = e.getMessage();
                            Handler handlerThree = new Handler(Looper.getMainLooper());
                            handlerThree.post(new Runnable() {
                                public void run() {
                                    Toast.makeText(getApplicationContext(), "心跳状态错误，请检查配置是否正确!" + error, Toast.LENGTH_LONG).show();
                                }
                            });
                        }

                        @Override
                        public void onResponse(Call call, Response response) throws IOException {
                            Log.d(TAG, "onResponse heard: " + response.body().string());
                        }
                    });
                    try {
                        Thread.sleep(30 * 1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        newThread.start();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Log.d(TAG, "接受到通知消息");
        SharedPreferences read = getSharedPreferences("vone", MODE_PRIVATE);
        host = read.getString("host", "");
        key = read.getString("key", "");

        Notification notification = sbn.getNotification();
        String pkg = sbn.getPackageName();
        if (notification != null) {
            Bundle extras = notification.extras;
            if (extras != null) {
                String title = extras.getString(NotificationCompat.EXTRA_TITLE, "");
                String content = extras.getString(NotificationCompat.EXTRA_TEXT, "");
                String all = (title == null ? "" : title) + " " + (content == null ? "" : content);
                Log.d(TAG, "**********************");
                Log.d(TAG, "包名:" + pkg);
                Log.d(TAG, "标题:" + title);
                Log.d(TAG, "内容:" + content);
                Log.d(TAG, "**********************");

                // 无效条幅过滤
                if (containsAny(all, NEG_KEYS)) {
                    Log.d(TAG, "忽略：命中无效关键字");
                    return;
                }

                if (pkg.equals("com.eg.android.AlipayGphone")) {
                    // 支付宝：title + content 双查
                    if (containsAny(all, ALIPAY_KEYS)) {
                        String money = getMoney(content, title);
                        if (money != null) {
                            Log.d(TAG, "匹配成功： 支付宝 到账 " + money);
                            appPush(2, Double.valueOf(money));
                        } else {
                            toast("监听到支付宝消息但未匹配到金额！");
                        }
                    }
                } else if (pkg.equals("com.tencent.mm")) {
                    // 微信：title 包含匹配（扩展），或内容命中收款关键字
                    boolean titleHit = containsAny(title, WX_TITLES);
                    if (titleHit || containsAny(all, WX_KEYS)) {
                        String money = getMoney(content, title);
                        if (money != null) {
                            Log.d(TAG, "匹配成功： 微信到账 " + money);
                            appPush(1, Double.valueOf(money));
                        } else {
                            toast("监听到微信消息但未匹配到金额！");
                        }
                    }
                } else if (pkg.equals("com.vone.qrcode")) {
                    if ("这是一条测试推送信息，如果程序正常，则会提示监听权限正常".equals(content)) {
                        toast("监听正常，如无法正常回调请联系作者反馈！");
                    }
                }
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
    }

    @Override
    public void onListenerConnected() {
        initAppHeart();
        toast("监听服务开启成功！");
    }

    private void toast(final String msg) {
        Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            public void run() {
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static boolean containsAny(String s, String[] keys) {
        if (s == null) return false;
        for (String k : keys) {
            if (k != null && !k.isEmpty() && s.contains(k)) return true;
        }
        return false;
    }

    public void appPush(int type, double price) {
        SharedPreferences read = getSharedPreferences("vone", MODE_PRIVATE);
        host = read.getString("host", "");
        key = read.getString("key", "");

        // 去重：同一 (type, 金额) 15 秒内只推一次
        String dedupKey = type + ":" + price;
        long now = System.currentTimeMillis();
        if (dedupKey.equals(lastPushKey) && (now - lastPushMs) < 15000) {
            Log.d(TAG, "去重：跳过重复推送 " + dedupKey);
            return;
        }
        lastPushKey = dedupKey;
        lastPushMs = now;

        Log.d(TAG, "onResponse  push: 开始:" + type + "  " + price);
        String t = String.valueOf(new Date().getTime());
        String sign = md5(type + "" + price + t + key);
        String url = "http://" + host + "/appPush?t=" + t + "&type=" + type + "&price=" + price + "&sign=" + sign;
        Log.d(TAG, "onResponse  push: 开始:" + url);

        OkHttpClient okHttpClient = new OkHttpClient();
        Request request = new Request.Builder().url(url).method("GET", null).build();
        Call call = okHttpClient.newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.d(TAG, "onResponse  push: 请求失败");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Log.d(TAG, "onResponse  push: " + response.body().string());
            }
        });
    }

    /**
     * 提取金额：优先取 ￥/¥/元 附近的数字；回退取最后一个数字串。
     * 返回统一 2 位小数字符串，取不到返回 null。
     */
    public static String getMoney(String content, String title) {
        String s = ((title == null ? "" : title) + " " + (content == null ? "" : content)).replace("，", ",").replace("￥", "¥");
        // 1) 优先：¥ 后 或 数字+元 前
        Matcher m = Pattern.compile("¥\\s*([0-9]+(?:\\.[0-9]{1,2})?)|([0-9]+(?:\\.[0-9]{1,2})?)\\s*元").matcher(s);
        String best = null;
        while (m.find()) {
            best = (m.group(1) != null) ? m.group(1) : m.group(2);
        }
        if (best != null) return normalize(best);
        // 2) 回退：取最后一个数字串
        List<String> ss = new ArrayList<String>();
        for (String sss : s.replaceAll("[^0-9.]", ",").split(",")) {
            if (sss.length() > 0) ss.add(sss);
        }
        if (ss.size() < 1) return null;
        return normalize(ss.get(ss.size() - 1));
    }

    private static String normalize(String num) {
        try {
            double d = Double.parseDouble(num);
            return String.format(java.util.Locale.US, "%.2f", d);
        } catch (Exception e) {
            return null;
        }
    }

    public static String md5(String string) {
        if (TextUtils.isEmpty(string)) {
            return "";
        }
        MessageDigest md5 = null;
        try {
            md5 = MessageDigest.getInstance("MD5");
            byte[] bytes = md5.digest(string.getBytes());
            String result = "";
            for (byte b : bytes) {
                String temp = Integer.toHexString(b & 0xff);
                if (temp.length() == 1) {
                    temp = "0" + temp;
                }
                result += temp;
            }
            return result;
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return "";
    }
}
