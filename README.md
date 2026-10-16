# V免签监控端 · 定制版（vmq-monitor-custom）

> 本项目基于开源项目 **V免签监控端（[szvone/vmqApk](https://github.com/szvone/vmqApk)）** 二次开发，
> 用于配合 **[szvone/vmqphp](https://github.com/szvone/vmqphp)**（V免签 PHP 服务端）实现个人微信/支付宝收款的通知监听与上报。
>
> **原作者**：szvone（[https://github.com/szvone](https://github.com/szvone)）
> **原项目协议**：MIT License（见 [LICENSE](LICENSE)）。本仓库同样以 **MIT** 发布，并保留原作者的版权声明与许可。
>
> 本项目仅供**个人学习与测试**使用，请勿用于任何非法用途；商用请申请官方商户接口。

---

## 为什么做这个定制版

原版监控端对手机「通知条幅」的解析规则较旧，在**新版微信/支付宝**上容易**漏听收款通知**（表现为：实际已收款，但服务端没有收到推送 → 订单不自动完成，需要人工补单）。常见原因：

1. **支付宝**：原版只检查通知 **content（内容）** 是否含 `通过扫码向你付款` / `成功收款`；新版支付宝常把关键字放在 **title（标题）**，导致直接不进分支 → 漏推。
2. **微信**：原版要求 **title 严格等于** `微信支付` / `微信收款助手` / `微信收款商业版`；标题稍有变化就不匹配 → 漏推。
3. **金额提取**：原版 `getMoney()` 是「取最后一个数字串」，遇到日期/时间/单号混排时可能取错。
4. **无去重 / 无过滤**：同一笔可能重复推送；营销/退款等无关条幅也可能被当成收款。

## 本定制版的改动

- **支付宝**：**title + content 双查**，收款关键字集合扩充。
- **微信**：title 由「全等」改为「**包含**」，关键字集合扩展；内容命中收款关键字亦可。
- **金额提取**：优先解析 `¥/￥` 或 `元` 附近的金额；回退取数字串；统一 **2 位小数**。
- **去重**：同一 `(渠道, 金额)` 在 **15 秒**内只推送一次。
- **无效条幅过滤**：`退款/退回/撤销/失败/转出/支出/账单/优惠/活动/红包` 等一律忽略。
- 构建现代化：**AndroidX + AGP 8.1 + Gradle 8.2**（原项目使用已停服的 jcenter，无法直接构建）。

## 构建

本仓库自带 GitHub Actions 工作流（`.github/workflows/build-apk.yml`），推送到 `main`/`master` 或手动触发即可产出 APK：

```
Actions → Build APK → Run workflow
```

产物：`app/build/outputs/apk/release/app-release.apk`（以 **debug 签名** 打包，可直接安装；如需上架请替换为你自己的签名）。

本地构建（需 JDK 17 + Android SDK）：

```bash
./gradlew assembleRelease
```

## 使用

1. 安装 APK → 打开 → 填写**服务端地址**（你的 V免签服务端域名/IP）与**通讯密钥**（服务端后台「监控端设置」里的那串）。
2. 按提示开启**通知使用权**（NotificationListenerService），并加入系统**省电/内存白名单**、关闭「神隐模式」/「勿扰」。
3. 微信关注「**微信收款助手**」、支付宝开启「**支付助手**」的收款提醒，保证能收到到账条幅。
4. 服务端后台显示「**监控端在线**」即正常。

## 目录结构

```
app/src/main/java/com/vone/vmq/NeNotificationService2.java   通知监听与解析（核心改动）
app/src/main/java/com/vone/vmq/MainActivity.java             配置界面
app/src/main/java/com/google/zxing/...                       扫码（zxing，原样保留）
```

## 许可与致谢

- 原项目：**V免签监控端** © szvone，MIT License。
- 服务端：**[szvone/vmqphp](https://github.com/szvone/vmqphp)** © szvone，MIT License。
- 本定制版由 **rainbow-boy-max** 在原作者基础上做兼容性增强，同样以 MIT 发布；请保留以上声明。

> 免责声明：本项目为个人免签收款方案，资金直接进入个人收款账户，存在合规与风控风险；请自行评估并承担使用后果，切勿用于非法用途。
