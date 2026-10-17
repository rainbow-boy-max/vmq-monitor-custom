package com.vone.vmq;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * V免签监控端 · 秒囤兔定制版 v2.0（解析规则全面升级）
 *
 * 相对原版（szvone/vmqApk）的改动：
 *  1. 微信：标题由「全等」放宽为「全等或包含」，标题集合扩充（含企业微信）
 *  2. 支付宝：区分「普通收款 / 店员通」，金额 title 优先 → content 回退，并重试一次防掉单
 *  3. getMoney：先定位关键词（收款/付款/到账/转入）→ 正则 → 合法性与金额范围校验
 *  4. 无效条幅过滤（退款/撤销/退回 等）
 *  5. 同 (类型,金额) 15 秒内去重，避免重复推送
 *  6. 推送失败自动补回调；回调成功自动清除对应通知（防通知栏堆积）
 *  7. 实时监听日志面板，便于自检排障
 *
 * 解析与保活设计参考 shinian-a/Vmq-App（Apache-2.0）。
 */
public class NeNotificationService2 extends NotificationListenerService {

    private static final String TAG = "NeNotificationService2";

    private String host = "";
    private String key = "";
    private Thread newThread = null;
    private PowerManager.WakeLock mWakeLock = null;
    private OkHttpClient okHttpClient;
    private Handler mainHandler;

    // 支付平台包名
    private static final String PACKAGE_WECHAT = "com.tencent.mm";
    private static final String PACKAGE_WECHAT_WORK = "com.tencent.wework";
    private static final String PACKAGE_ALIPAY = "com.eg.android.AlipayGphone";
    private static final String PACKAGE_SELF = "com.vone.qrcode";

    // 微信支付标题关键字
    private static final String[] WECHAT_PAY_TITLES = {
            "微信支付", "微信收款助手", "微信收款商业版", "对外收款", "企业微信", "Weixin Cashier Assistant",
            "微信收款", "微信支付收款", "收款到账", "微信收款小账本"
    };

    // 无效条幅关键字（命中即忽略）
    private static final String[] NEGATIVE_KEYS = {
            "退款", "已退回", "退回", "撤销", "交易失败", "已关闭"
    };

    // 金额提取正则（预编译）
    private static final Pattern MONEY_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");

    // 去重
    private static String lastPushKey = "";
    private static long lastPushMs = 0L;

    // ==================== 心跳 ====================

    @SuppressLint("InvalidWakeLockTag")
    public void acquireWakeLock(Context context) {
        if (null == mWakeLock) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
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

    public void initAppHeart() {
        Log.d(TAG, "开始启动心跳线程");
        if (newThread != null) {
            return;
        }
        acquireWakeLock(this);
        if (okHttpClient == null) {
            okHttpClient = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();
        }

        newThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "心跳线程启动！");
                while (true) {
                    SharedPreferences read = getSharedPreferences(AppConstants.SP_NAME_CONFIG, MODE_PRIVATE);
                    host = read.getString(AppConstants.SP_KEY_HOST, "");
                    key = read.getString(AppConstants.SP_KEY_KEY, "");

                    String t = String.valueOf(new Date().getTime());
                    String sign = md5(t + key);
                    Request request = new Request.Builder()
                            .url("http://" + host + "/appHeart?t=" + t + "&sign=" + sign)
                            .method("GET", null)
                            .build();
                    Call call = okHttpClient.newCall(request);
                    call.enqueue(new Callback() {
                        @Override
                        public void onFailure(Call call, IOException e) {
                            final String error = e.getMessage();
                            if (mainHandler != null) {
                                mainHandler.post(new Runnable() {
                                    public void run() {
                                        if (MainActivity.LogsTextView != null
                                                && !MainActivity.LogsTextView.getText().toString().contains("心跳状态错误")) {
                                            sendMonitorLogs(timeStamp() + "\r\r\r\r" + "心跳状态错误，请重新配置或切换网络环境! 错误：" + error);
                                        }
                                    }
                                });
                            }
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

    // ==================== 通知回调 ====================

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        SharedPreferences read = getSharedPreferences(AppConstants.SP_NAME_CONFIG, MODE_PRIVATE);
        host = read.getString(AppConstants.SP_KEY_HOST, "");
        key = read.getString(AppConstants.SP_KEY_KEY, "");

        Notification notification = sbn.getNotification();
        String pkg = sbn.getPackageName();
        if (notification == null) {
            return;
        }
        Bundle extras = notification.extras;
        if (extras == null) {
            return;
        }

        String title = extras.getString(NotificationCompat.EXTRA_TITLE, "");
        String content = extras.getString(NotificationCompat.EXTRA_TEXT, "");
        Log.d(TAG, "包名: " + pkg + " | 标题: " + title + " | 内容: " + content);

        // 无效条幅过滤
        String all = (title == null ? "" : title) + " " + (content == null ? "" : content);
        if (containsAny(all, NEGATIVE_KEYS)) {
            Log.d(TAG, "忽略：命中无效条幅关键字");
            return;
        }

        if (PACKAGE_WECHAT.equals(pkg) || PACKAGE_WECHAT_WORK.equals(pkg)) {
            handleWechatNotification(title, content);
        } else if (PACKAGE_ALIPAY.equals(pkg)) {
            handleAlipayNotification(title, content);
        } else if (PACKAGE_SELF.equals(pkg)) {
            handleSelfTestNotification(content);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
    }

    // ==================== 微信解析 ====================

    private void handleWechatNotification(String title, String content) {
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(content)) return;

        // 标题命中（全等 或 包含）
        boolean isPayTitle = false;
        for (String payTitle : WECHAT_PAY_TITLES) {
            if (payTitle.equals(title) || (!TextUtils.isEmpty(title) && title.contains(payTitle))) {
                isPayTitle = true;
                break;
            }
        }
        if (!isPayTitle) return;

        // 必须含「收款」，且排除「已支付」等无效
        if (!content.contains("收款") || content.contains("已支付")) return;

        String money = getMoney(content);
        if (money == null || money.isEmpty()) {
            money = getMoney(title);
        }

        if (money != null && !money.isEmpty()) {
            try {
                double amount = Double.parseDouble(money);
                toast("匹配成功：微信到账" + money + "元");
                Log.d(TAG, "匹配成功：微信到账 " + money + "元");
                appPush(AppConstants.PAY_TYPE_WECHAT, amount);
            } catch (NumberFormatException e) {
                Log.e(TAG, "解析微信金额失败：" + money, e);
                showMoneyParseErrorToast("微信");
            }
        } else {
            showMoneyParseErrorToast("微信");
        }
    }

    // ==================== 支付宝解析 ====================

    private void handleAlipayNotification(String title, String content) {
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(content)) return;

        String money;
        String platform;
        int platformType = AppConstants.PAY_TYPE_ALIPAY;

        boolean isNormalPay = (title.contains("成功收款") && content.contains("已转入余额"))
                || content.contains("通过扫码向你付款");
        boolean isStaffPay = title.contains("店员通") || content.contains("支付宝成功收款");

        if (isNormalPay) {
            platform = "支付宝";
            money = getMoney(title);
            if (money == null || money.isEmpty()) {
                money = getMoney(content);
            }
        } else if (isStaffPay) {
            platform = "支付宝店员";
            money = getMoney(content);
            if (money == null || money.isEmpty()) {
                money = getMoney(title);
            }
        } else {
            return; // 非收款类型
        }

        // 重试一次防掉单
        if (money == null || money.isEmpty()) {
            money = isNormalPay ? getMoney(content) : getMoney(title);
        }

        if (money != null && !money.isEmpty()) {
            try {
                double amount = Double.parseDouble(money);
                toast("匹配成功：" + platform + "到账" + money + "元");
                Log.d(TAG, "匹配成功：" + platform + "到账 " + money + "元");
                appPush(platformType, amount);
            } catch (NumberFormatException e) {
                Log.e(TAG, "解析" + platform + "金额失败：" + money, e);
                showMoneyParseErrorToast(platform);
            }
        } else {
            showMoneyParseErrorToast(platform);
        }
    }

    // ==================== 自检 ====================

    private void handleSelfTestNotification(String content) {
        if (content != null && (content.contains("如果程序正常") || content.contains("测试推送"))) {
            sendMonitorLogs(timeStamp() + "\r\r\r\r" + "测试收款监听权限正常！");
            toast("监听正常，如无法正常回调请联系管理员反馈！");
        }
    }

    private void showMoneyParseErrorToast(String platform) {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        sendMonitorLogs(timeStamp() + "\r\r\r\r" + "监听到" + platform + "收款消息但未匹配到金额！");
        mainHandler.post(new Runnable() {
            public void run() {
                Toast.makeText(getApplicationContext(), "监听到收款消息但未匹配到金额！", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ==================== 推送 ====================

    public void appPush(final int type, final double price) {
        SharedPreferences read = getSharedPreferences(AppConstants.SP_NAME_CONFIG, MODE_PRIVATE);
        host = read.getString(AppConstants.SP_KEY_HOST, "");
        key = read.getString(AppConstants.SP_KEY_KEY, "");

        final String priceStr = String.format(java.util.Locale.US, "%.2f", price);

        // 去重：同一 (type, 金额) 15 秒内只推一次
        String dedupKey = type + ":" + priceStr;
        long now = System.currentTimeMillis();
        if (dedupKey.equals(lastPushKey) && (now - lastPushMs) < AppConstants.DEDUP_WINDOW_MS) {
            Log.d(TAG, "去重：跳过重复推送 " + dedupKey);
            return;
        }
        lastPushKey = dedupKey;
        lastPushMs = now;

        Log.d(TAG, "appPush: 开始 - 类型:" + type + ", 金额:" + priceStr);

        final String t = String.valueOf(new Date().getTime());
        final String sign = md5(type + priceStr + t + key);
        final String url = buildPushUrl(host, type, priceStr, t, sign);
        Log.d(TAG, "appPush: URL:" + url);

        if (okHttpClient == null) {
            okHttpClient = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();
        }

        Request request = new Request.Builder().url(url).method("GET", null).build();
        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                String error = e.getMessage();
                Log.e(TAG, "appPush: 请求失败 - " + error);
                sendMonitorLogs(timeStamp() + "\r\r\r\r" + "通知回调失败：" + error);
                scheduleRetryCallback(type, price, priceStr);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String str = response.body().string();
                    JSONObject result = new JSONObject(str);
                    String msg = result.getString("msg");
                    logPushResult(type, priceStr, msg, str, false);
                    if ("成功".equals(msg)) {
                        cancelNotification(type);
                    }
                } catch (Exception e) {
                    String error = e.getMessage();
                    Log.e(TAG, "appPush: 响应解析失败 - " + error);
                    sendMonitorLogs(timeStamp() + "\r\r\r\r" + "通知回调失败：" + error);
                    scheduleRetryCallback(type, price, priceStr);
                }
            }
        });
    }

    /** 清除对应支付应用的通知，防止通知栏堆积 */
    private void cancelNotification(int type) {
        try {
            StatusBarNotification[] activeNotifications = getActiveNotifications();
            if (activeNotifications == null || activeNotifications.length == 0) {
                return;
            }
            String targetPackage = (type == AppConstants.PAY_TYPE_WECHAT) ? PACKAGE_WECHAT : PACKAGE_ALIPAY;
            for (StatusBarNotification sbn : activeNotifications) {
                if (sbn != null && targetPackage.equals(sbn.getPackageName())) {
                    cancelNotification(sbn.getKey());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "cancelNotification 失败 - " + e.getMessage());
        }
    }

    private String buildPushUrl(String host, int type, String priceStr, String t, String sign) {
        return "http://" + host + "/appPush?t=" + t + "&type=" + type + "&price=" + priceStr + "&sign=" + sign;
    }

    private void logPushResult(int type, String priceStr, String msg, String data, boolean isRetry) {
        String prefix = isRetry ? "自动补回调：" : "";
        String payType = (type == AppConstants.PAY_TYPE_WECHAT) ? "微信支付" : "支付宝";
        String logContent = timeStamp() + "\r\r\r\r"
                + prefix + "监听到" + payType + "收款" + priceStr + "元"
                + "\t通知回调状态：" + msg
                + "\n通知回调信息：" + data;
        sendMonitorLogs(logContent);
    }

    /** 推送失败后延迟 1 秒自动补回调 */
    private void scheduleRetryCallback(final int type, final double price, final String priceStr) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    String t = String.valueOf(new Date().getTime());
                    String sign = md5(type + priceStr + t + key);
                    String url = buildPushUrl(host, type, priceStr, t, sign);
                    String data = getHtml(url);
                    JSONObject jsonObject = new JSONObject(data);
                    int code = jsonObject.getInt("code");
                    String message = jsonObject.getString("msg");
                    if (code == 1 && "成功".equals(message)) {
                        logPushResult(type, priceStr, message, data, true);
                        toast("补通知回调成功：" + data);
                    } else {
                        Log.w(TAG, "补回调失败 - code:" + code + ", msg:" + message);
                    }
                } catch (Exception e) {
                    String error = e.getMessage();
                    Log.e(TAG, "补回调异常 - " + error);
                    toast("自动补单回调失败！错误详情：" + error);
                }
            }
        }, AppConstants.CALLBACK_RETRY_DELAY);
    }

    // ==================== 金额解析 ====================

    /**
     * 从文本中提取金额：先定位关键词（收款/付款/到账/转入），
     * 再正则匹配数字，做合法性与金额范围（0.01 ~ 999999.99）校验。
     */
    public static String getMoney(String content) {
        if (content == null || content.isEmpty()) return null;

        int startIndex = -1;
        String[] keywords = {"收款", "付款", "到账", "转入"};
        for (String keyword : keywords) {
            int index = content.indexOf(keyword);
            if (index >= 0) {
                startIndex = index;
                break;
            }
        }
        String searchText = (startIndex >= 0) ? content.substring(startIndex) : content;

        List<String> validAmounts = new ArrayList<>();
        Matcher matcher = MONEY_PATTERN.matcher(searchText);
        while (matcher.find()) {
            String matched = matcher.group();
            if (isValidNumber(matched)) {
                try {
                    double amount = Double.parseDouble(matched);
                    if (amount >= 0.01 && amount <= 999999.99) {
                        validAmounts.add(matched);
                    }
                } catch (NumberFormatException e) {
                    Log.w(TAG, "getMoney: 无效金额 " + matched);
                }
            }
        }
        if (!validAmounts.isEmpty()) return validAmounts.get(0);
        return null;
    }

    private static boolean isValidNumber(String str) {
        if (str == null || str.isEmpty()) return false;
        int dotCount = 0;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '.') {
                dotCount++;
                if (dotCount > 1 || i == 0 || i == str.length() - 1) return false;
            } else if (!Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    // ==================== 监听日志 ====================

    private void sendMonitorLogs(String msgStr) {
        SharedPreferences read = getSharedPreferences(AppConstants.SP_NAME_LOGS, MODE_PRIVATE);
        String logsStr = read.getString(AppConstants.SP_KEY_LOGS_STR, "");

        int lineCount = 0;
        int lastIndex = -1;
        for (int i = 0; i < logsStr.length(); i++) {
            if (logsStr.charAt(i) == '\n') {
                lineCount++;
                if (lineCount >= AppConstants.MAX_LOG_ENTRIES) {
                    lastIndex = i;
                    break;
                }
            }
        }
        if (lastIndex >= 0 && lastIndex < logsStr.length() - 1) {
            logsStr = logsStr.substring(lastIndex + 1);
        }
        logsStr = msgStr + "\n" + logsStr;

        SharedPreferences.Editor editor = getSharedPreferences(AppConstants.SP_NAME_LOGS, MODE_PRIVATE).edit();
        editor.putString(AppConstants.SP_KEY_LOGS_STR, logsStr);
        editor.commit();

        if (MainActivity.monitorLogHandler != null) {
            Message msg = new Message();
            msg.what = 0;
            Bundle bundle = new Bundle();
            bundle.putString(AppConstants.SP_KEY_LOGS_STR, logsStr);
            msg.setData(bundle);
            MainActivity.monitorLogHandler.sendMessage(msg);
        }
    }

    private static String timeStamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new Date());
    }

    // ==================== 生命周期 ====================

    @Override
    public void onListenerConnected() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        initAppHeart();
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                sendMonitorLogs(timeStamp() + "\r\r\r\r" + "监听服务开启成功！");
            }
        }, 1000);
    }

    // ==================== 工具 ====================

    private void toast(final String msg) {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        mainHandler.post(new Runnable() {
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

    public String getHtml(String path) throws Exception {
        HttpURLConnection conn = null;
        InputStream inStream = null;
        try {
            URL url = new URL(path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8 * 1000);
            inStream = conn.getInputStream();
            byte[] data = readInputStream(inStream);
            return new String(data, StandardCharsets.UTF_8);
        } finally {
            if (inStream != null) {
                try {
                    inStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "关闭输入流失败", e);
                }
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public byte[] readInputStream(InputStream inStream) throws Exception {
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = inStream.read(buffer)) != -1) {
            outStream.write(buffer, 0, len);
        }
        return outStream.toByteArray();
    }

    public static String md5(String string) {
        if (TextUtils.isEmpty(string)) {
            return "";
        }
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] bytes = md5.digest(string.getBytes());
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) {
                String temp = Integer.toHexString(b & 0xff);
                if (temp.length() == 1) {
                    temp = "0" + temp;
                }
                result.append(temp);
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return "";
    }
}
