# V免签监控端 · 秒囤兔定制版

> 一款基于 [V免签](https://github.com/szvone/vmqphp) 的 Android 收款监听（回调）应用。
> 无需 Root，通过监听微信 / 支付宝的到账通知，把收款金额实时回调给 V免签服务端。

---

## 一、开源协议与署名（Attribution）

本项目为**二次开发（Derivative Work）**，遵循上游开源协议，特此致谢：

| 角色 | 项目 | 协议 | 说明 |
|---|---|---|---|
| **原作者** | [szvone/vmqApk](https://github.com/szvone/vmqApk) | MIT | V免签监控端原始实现（本项目的基础） |
| **保活与解析参考** | [shinian-a/Vmq-App](https://github.com/shinian-a/Vmq-App) | Apache-2.0 | 后台保活体系与通知解析规则的参考实现 |
| **定制方** | 秒囤兔（rainbow-boy-max） | Apache-2.0 | 本仓库的定制改动（见下文「定制内容」） |

- 本项目整体以 **Apache License 2.0** 发布（见 [LICENSE](LICENSE)）。
- 源自 `shinian-a/Vmq-App`（Apache-2.0）的代码部分，已保留其版权与协议声明。
- 源自 `szvone/vmqApk`（MIT）的代码部分，已保留原作者署名。

---

## 二、定制内容（相对原版 szvone/vmqApk）

### 1. 保活体系（根治「锁屏后漏听」）
原版仅有 `WakeLock + 心跳线程`，极易被系统回收，导致锁屏 / 后台一段时间后**漏听通知**。本定制版移植并整合了一整套保活方案：

- **前台常驻服务** `ForeService`
- **双进程守护** `DaemonService`（`:daemon_service`）+ `NativeDaemonService`（`:native_daemon`）
- **JobScheduler** 周期唤醒 `AliveJobService`（15 分钟，重启后仍有效）
- **账号同步保活** `SyncManager` + `SyncAdapter` + `AccountAuthenticator`（系统每 15 分钟唤醒一次）
- **1 像素 Activity** `SinglePixelActivity`（锁屏时抬高进程优先级）
- **开机自启** `BootReceiver`（`BOOT_COMPLETED`）
- **一键保活按钮**：申请「忽略电池优化」白名单 + 跳转各厂商「自启动」设置页

### 2. 通知解析规则升级（减少「漏推 / 取错金额」）
- **微信**：标题由「全等」放宽为「全等或包含」，标题集合扩充（含企业微信）；要求内容含「收款」且非「已支付」。
- **支付宝**：区分「普通收款 / 店员通」两类；金额 **title 优先 → content 回退**，并重试一次防掉单。
- **金额提取**：先定位关键词（收款 / 付款 / 到账 / 转入）→ 正则匹配 → 合法性 + 金额范围（0.01 ~ 999999.99）校验。
- **无效条幅过滤**：退款 / 撤销 / 退回 等一律忽略。
- **去重**：同 (类型, 金额) 15 秒内只推送一次。
- **补回调**：推送失败自动延迟 1 秒重试；回调成功自动清除对应通知（防通知栏堆积）。

### 3. 自检与排障
- 新增**实时监听日志面板**（最近 20 条），心跳状态 / 匹配结果 / 回调状态一目了然。
- 「检测心跳」「检测监听」按钮，快速验证配置与权限。

### 4. 易用性与兼容性
- **启动即创建通知渠道**：否则系统「通知管理」开关为灰色不可点。
- **扫码 / 手动配置保存后立即视为「已配置」**：原版把 `isOk` 写在心跳成功回调里，心跳失败即永远提示「未配置」。
- 新增「**开启通知使用权**」「**应用权限设置**」引导按钮。
- 声明 `usesCleartextTraffic="true"`：V免签服务端通常为 `http://`，Android 9+ 默认禁明文会直接导致推送失败。

---

## 三、使用方法

1. 安装 APK（本页顶部下载）。
2. 打开 App → **扫码配置**（扫描 V免签后台首页的配置二维码）或**手动配置**（粘贴后台「配置数据」，形如 `your-domain.com/通讯密钥`）。
3. 依次点击：
   - **开启通知使用权** → 在系统列表中找到「V免签监控端」并打开；
   - **应用权限设置** → 授予「通知」等权限；
   - **一键保活** → 允许「忽略电池优化」，并在手机管家中允许「自启动 / 后台运行」。
4. 点击「**检测心跳**」验证与服务器的连通性；点击「**检测监听**」验证监听权限。
5. 用真实收款（建议使用唯一金额，如 `0.13`）复测，观察 App 内「监听日志」与服务端订单状态。

---

## 四、构建

本项目通过 **GitHub Actions** 自动构建（`JDK 17 + Android SDK 34`）。
工作流：`.github/workflows/build-apk.yml`，产物为 `app-release.apk`。

签名密钥通过 GitHub Secrets 注入（`ANDROID_KEYSTORE_B64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`），保证**多次构建签名一致**，升级无需卸载。

---

## 五、免责声明

本项目仅供学习与技术研究使用。请遵守当地法律法规及微信 / 支付宝的服务协议，因使用本项目产生的一切后果由使用者自行承担。

---

## 六、致谢

- [szvone](https://github.com/szvone) —— V免签 / vmqApk 原作者
- [shinian-a](https://github.com/shinian-a) —— Vmq-App 保活与解析实现

**License: Apache-2.0**
