package com.vone.vmq;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.zxing.activity.CaptureActivity;
import com.vone.qrcode.R;
import com.vone.vmq.service.DaemonService;
import com.vone.vmq.service.ForeService;
import com.vone.vmq.service.NativeDaemonService;
import com.vone.vmq.util.Constant;
import com.vone.vmq.util.JobSchedulerManager;
import com.vone.vmq.util.SyncManager;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 主界面（秒囤兔定制版 · v2.0）
 *
 * 相对原版（szvone/vmqApk）的定制：
 *  - 启动即创建通知渠道（否则系统「允许通知」开关为灰）
 *  - 扫码 / 手动配置保存后立即视为「已配置」（原版把 isOk 写在心跳回调里，心跳失败即永远未配置）
 *  - 新增「开启通知使用权」「应用权限设置」引导按钮
 *  - 新增「一键保活」：拉起前台服务 / 守护进程 / JobScheduler / 账号同步 + 申请电池优化白名单 + 自启动引导
 *  - 新增实时监听日志面板，便于自检排障
 *
 * 保活与解析设计参考 shinian-a/Vmq-App（Apache-2.0）。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private TextView txthost;
    private TextView txtkey;
    private TextView txtLogs;

    /** 监听日志面板（供 NeNotificationService2 跨进程/跨组件写入） */
    public static TextView LogsTextView = null;
    public static Handler monitorLogHandler = null;

    private boolean isOk = false;

    private static String host;
    private static String key;

    int id = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txthost = findViewById(R.id.txt_host);
        txtkey = findViewById(R.id.txt_key);
        txtLogs = findViewById(R.id.txt_logs);
        LogsTextView = txtLogs;

        initLogHandler();

        // 检测通知使用权是否启用
        if (!isNotificationListenersEnabled()) {
            gotoNotificationAccessSetting();
        }
        // 重启监听服务
        toggleNotificationListenerService(this);

        // 读取保存的配置数据并显示
        SharedPreferences read = getSharedPreferences(AppConstants.SP_NAME_CONFIG, MODE_PRIVATE);
        host = read.getString(AppConstants.SP_KEY_HOST, "");
        key = read.getString(AppConstants.SP_KEY_KEY, "");
        if (!TextUtils.isEmpty(host) && !TextUtils.isEmpty(key)) {
            txthost.setText(" 通知地址：" + host);
            txtkey.setText(" 通讯密钥：" + key);
            isOk = true;
        }

        Toast.makeText(MainActivity.this, "V免签开源免费免签系统 · 秒囤兔定制版 v2.0", Toast.LENGTH_SHORT).show();

        createChannel();          // 启动即创建通知渠道
        startKeepAlive();         // 启动保活体系
    }

    /** 初始化日志面板 Handler（接收监听服务发来的日志） */
    private void initLogHandler() {
        monitorLogHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                super.handleMessage(msg);
                Bundle bundle = msg.getData();
                if (bundle != null && txtLogs != null) {
                    txtLogs.setText(bundle.getString(AppConstants.SP_KEY_LOGS_STR, ""));
                }
            }
        };
        // 首次进入时回显历史日志
        SharedPreferences read = getSharedPreferences(AppConstants.SP_NAME_LOGS, MODE_PRIVATE);
        String logs = read.getString(AppConstants.SP_KEY_LOGS_STR, "");
        if (!TextUtils.isEmpty(logs) && txtLogs != null) {
            txtLogs.setText(logs);
        }
    }

    /** 启动全部保活组件 */
    private void startKeepAlive() {
        try {
            Intent fore = new Intent(this, ForeService.class);
            Intent daemon = new Intent(this, DaemonService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(fore);
                startForegroundService(daemon);
            } else {
                startService(fore);
                startService(daemon);
            }
            startService(new Intent(this, NativeDaemonService.class));
        } catch (Exception e) {
            Log.e(TAG, "启动保活服务失败", e);
        }
        try {
            JobSchedulerManager.getJobSchedulerInstance(this).startJobScheduler();
        } catch (Exception e) {
            Log.e(TAG, "启动 JobScheduler 失败", e);
        }
        try {
            SyncManager.getInstance(this).startPeriodicSync();
        } catch (Exception e) {
            Log.e(TAG, "启动账号同步保活失败", e);
        }
    }

    // ==================== 配置：扫码 / 手动 ====================

    /** 扫码配置 */
    public void startQrCode(View v) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA}, Constant.REQ_PERM_CAMERA);
            return;
        }
        Intent intent = new Intent(MainActivity.this, CaptureActivity.class);
        startActivityForResult(intent, Constant.REQ_QR_CODE);
    }

    /** 手动配置 */
    public void doInput(View v) {
        final EditText inputServer = new EditText(this);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("请输入配置数据").setView(inputServer)
                .setNegativeButton("取消", null);
        builder.setPositiveButton("确认", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                String scanResult = inputServer.getText().toString();
                applyConfig(scanResult);
            }
        });
        builder.show();
    }

    /** 解析并保存配置（host/key 形如 xxx.com/通讯密钥） */
    private void applyConfig(String scanResult) {
        String[] tmp = scanResult.split("/");
        if (tmp.length != 2) {
            Toast.makeText(MainActivity.this, "数据错误，请您输入网站上显示的配置数据!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (tmp[0].indexOf("localhost") >= 0) {
            Toast.makeText(MainActivity.this, "配置信息错误，本机调试请访问 本机局域网IP:8080 获取配置信息进行配置!", Toast.LENGTH_LONG).show();
            return;
        }

        final String cfgHost = tmp[0];
        final String cfgKey = tmp[1];

        String t = String.valueOf(new Date().getTime());
        String sign = md5(t + cfgKey);
        OkHttpClient okHttpClient = new OkHttpClient();
        Request request = new Request.Builder().url("http://" + cfgHost + "/appHeart?t=" + t + "&sign=" + sign).method("GET", null).build();
        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.d(TAG, "配置心跳失败：" + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Log.d(TAG, "配置心跳返回：" + response.body().string());
            }
        });

        txthost.setText(" 通知地址：" + cfgHost);
        txtkey.setText(" 通讯密钥：" + cfgKey);
        host = cfgHost;
        key = cfgKey;

        SharedPreferences.Editor editor = getSharedPreferences(AppConstants.SP_NAME_CONFIG, MODE_PRIVATE).edit();
        editor.putString(AppConstants.SP_KEY_HOST, host);
        editor.putString(AppConstants.SP_KEY_KEY, key);
        editor.commit();
        isOk = true; // [定制] 保存即视为已配置
        Toast.makeText(MainActivity.this, "配置已保存", Toast.LENGTH_SHORT).show();
    }

    // ==================== 权限引导 ====================

    /** 开启通知使用权 */
    public void openNotifyAccess(View v) {
        if (gotoNotificationAccessSetting()) {
            Toast.makeText(MainActivity.this, "请在列表中找到「V免签监控端」并打开", Toast.LENGTH_LONG).show();
        }
    }

    /** 打开本应用权限设置页 */
    public void openAppSettings(View v) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(MainActivity.this, "请手动到：设置-应用-V免签监控端-权限", Toast.LENGTH_LONG).show();
        }
    }

    /** 一键保活：拉起服务 + 申请电池优化白名单 + 自启动引导 */
    public void openKeepAlive(View v) {
        startKeepAlive();
        requestIgnoreBatteryOptimizations();
        new AlertDialog.Builder(this)
                .setTitle("后台保活设置")
                .setMessage("为保证锁屏后仍能实时监听收款，请完成以下设置：\n\n"
                        + "1. 电池优化：已为您申请「忽略电池优化」，请点击允许\n"
                        + "2. 自启动/后台运行：请在手机管家中允许本应用「自启动」与「后台运行」\n"
                        + "3. 锁定后台：在多任务界面下拉本应用卡片加锁，避免被一键清理\n\n"
                        + "是否现在前往「自启动」设置页？")
                .setPositiveButton("前往", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        gotoAutoStartSettings();
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    /** 申请忽略电池优化 */
    @SuppressLint("BatteryLife")
    private void requestIgnoreBatteryOptimizations() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    return; // 已加入白名单
                }
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "申请电池优化白名单失败", e);
        }
    }

    /** 跳转自启动设置（各厂商页面不同，逐个尝试） */
    private void gotoAutoStartSettings() {
        String[][] intents = {
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
                {"com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"},
        };
        for (String[] pair : intents) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(pair[0], pair[1]));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            } catch (Exception ignored) {
                // 继续尝试下一个
            }
        }
        // 全部失败：跳系统应用详情页，并给出文字指引
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            // ignore
        }
        Toast.makeText(this, "未找到自启动页，请手动到手机管家-自启动 中允许本应用", Toast.LENGTH_LONG).show();
    }

    /** 创建通知渠道（否则系统里「允许通知」开关为灰） */
    private void createChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm == null) return;
                NotificationChannel channel = new NotificationChannel(AppConstants.NOTIFICATION_CHANNEL_ID, "收款通知", NotificationManager.IMPORTANCE_HIGH);
                channel.enableLights(true);
                channel.setLightColor(Color.GREEN);
                channel.setShowBadge(true);
                nm.createNotificationChannel(channel);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ==================== 自检 ====================

    /** 检测心跳 */
    public void doStart(View view) {
        if (!isOk) {
            Toast.makeText(MainActivity.this, "请您先配置!", Toast.LENGTH_SHORT).show();
            return;
        }
        String t = String.valueOf(new Date().getTime());
        String sign = md5(t + key);
        OkHttpClient okHttpClient = new OkHttpClient();
        Request request = new Request.Builder().url("http://" + host + "/appHeart?t=" + t + "&sign=" + sign).method("GET", null).build();
        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(MainActivity.this, "心跳状态错误，请检查配置是否正确!", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                final String body = response.body().string();
                runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(MainActivity.this, "心跳返回：" + body, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    /** 检测监听：发送一条测试通知，若监听正常会回调提示 */
    public void checkPush(View v) {
        Notification mNotification;
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (mNotificationManager == null) return;

        final String testContent = "这是一条测试推送信息，如果程序正常，则会提示监听权限正常";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(AppConstants.NOTIFICATION_CHANNEL_ID,
                    "收款通知", NotificationManager.IMPORTANCE_DEFAULT);
            channel.enableLights(true);
            channel.setLightColor(Color.GREEN);
            channel.setShowBadge(true);
            mNotificationManager.createNotificationChannel(channel);
            Notification.Builder builder = new Notification.Builder(this, AppConstants.NOTIFICATION_CHANNEL_ID);
            mNotification = builder
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setTicker(testContent)
                    .setContentTitle("V免签测试推送")
                    .setContentText(testContent)
                    .build();
        } else {
            mNotification = new Notification.Builder(MainActivity.this)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setTicker(testContent)
                    .setContentTitle("V免签测试推送")
                    .setContentText(testContent)
                    .build();
        }
        mNotificationManager.notify(id++, mNotification);
    }

    // ==================== 通知使用权判断 ====================

    private void toggleNotificationListenerService(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            pm.setComponentEnabledSetting(new ComponentName(context, NeNotificationService2.class),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(new ComponentName(context, NeNotificationService2.class),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            Toast.makeText(MainActivity.this, "监听服务启动中...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "重启监听服务失败", e);
        }
    }

    public boolean isNotificationListenersEnabled() {
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (String name : names) {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null && TextUtils.equals(pkgName, cn.getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }

    protected boolean gotoNotificationAccessSetting() {
        try {
            Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            try {
                Intent intent = new Intent();
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ComponentName cn = new ComponentName("com.android.settings",
                        "com.android.settings.Settings$NotificationAccessSettingsActivity");
                intent.setComponent(cn);
                intent.putExtra(":settings:show_fragment", "NotificationAccessSettings");
                startActivity(intent);
                return true;
            } catch (Exception e1) {
                e1.printStackTrace();
            }
            Toast.makeText(this, "对不起，您的手机暂不支持", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    // ==================== 工具 ====================

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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Constant.REQ_QR_CODE && resultCode == RESULT_OK) {
            Bundle bundle = data.getExtras();
            String scanResult = bundle.getString(Constant.INTENT_EXTRA_KEY_QR_SCAN);
            applyConfig(scanResult);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case Constant.REQ_PERM_CAMERA:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startQrCode(null);
                } else {
                    Toast.makeText(MainActivity.this, "请至权限中心打开本应用的相机访问权限", Toast.LENGTH_LONG).show();
                }
                break;
            case Constant.REQ_PERM_EXTERNAL_STORAGE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startQrCode(null);
                } else {
                    Toast.makeText(MainActivity.this, "请至权限中心打开本应用的文件读写权限", Toast.LENGTH_LONG).show();
                }
                break;
        }
    }
}
