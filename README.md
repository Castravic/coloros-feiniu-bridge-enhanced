# ColorOS 飞牛 Bridge Enhanced

面向 ColorOS 16 相册的 LSPosed 增强模块，修复 Root 后飞牛私有云无法连接、自动备份异常等问题。

本项目 Fork 自 [Costben/coloros-feiniu-bridge](https://github.com/Costben/coloros-feiniu-bridge)，保留原项目完整提交历史与 MIT 许可证。在原有连接修复基础上，增加了自动备份温控兼容、暂停原因展示和移动数据备份支持。

> 当前适配环境：ColorOS 16 / Android 16，相册 `16.35.10`、`16.40.8`、`16.40.13`、`16.40.22`。相册内部类名经过混淆，其他版本可能需要重新适配。

## 功能

- 修复相册调用 `cryptoeng cmd 26` 失败后，飞牛 token prefix 为空而无法连接的问题。
- 适配相册 `16.40.22` 的 `qp80` token 解密器和备份状态相关混淆类。
- 修复相册 `16.40.22` 通过 RFC1918 私网 IPv4 访问飞牛 NAS 时的 TLS 分支不兼容。
- 保留相册原始 token、账号和服务端认证流程，不伪造连接状态。
- 在飞牛相册统计接口不可用时，恢复私有云相册列表和云端照片浏览。
- 使用与 ColorOS 官方云备份一致的温控策略：
  - 前台温度高于 45°C 暂停。
  - 后台温度高于 43°C 暂停。
  - 温度降至 41°C 或以下恢复。
- 首页下拉区域显示飞牛私有云备份暂停的具体原因。
- 在相册设置中增加“允许私有云备份使用移动数据”选项。

## 安装

1. 从 [Releases](../../releases) 下载已签名 APK。
2. 安装模块并在 LSPosed 中启用。
3. 作用域只选择 `相册 / com.coloros.gallery3d`。
4. 强行停止相册或重启手机。
5. 打开相册并进入私有云页面。

升级时请直接覆盖安装。不同签名的 APK 无法互相覆盖，本 Fork 的 Release 会持续使用同一签名。

## 暂停原因

模块会根据相册内部的 `PauseReason` 显示对应说明，包括：

- 飞牛私有云未连接
- 私有云存储空间不足
- 相册网络权限未开启
- 没有可用网络
- 设备温度较高
- 电量不足
- 省电模式已开启
- 官方云服务正在同步
- 私有云正在批量下载
- 其他应用正在前台运行

中文环境使用模块内置中文映射，避免相册资源异常回退为英文。

## 移动数据备份

设置路径：

```text
相册 → 设置 → 允许私有云备份使用移动数据
```

该选项默认关闭。开启后，仅当 ColorOS 网络监视器确认当前移动网络已经通过联网验证时，私有云自动备份才会放行。

## 实现概览

模块只作用于 `com.coloros.gallery3d`，主要适配点如下：

- `erq / in80 / op80 / qp80`：为各已适配版本的相册 token 解密器提供 prefix fallback。
- 相册统计服务：接口不可用时从真实相册数据恢复私有云列表。
- `ktc0.k(String)`：仅将 RFC1918 私网 IPv4 导向相册自带的兼容 TLS 分支。
- `bsf / f0q / u0q`：NAS 自动备份条件判断。
- `vwp / l370 / r570 / t570`：仅在 NAS 备份条件检查范围内应用温控策略。
- `stf / o3q / d4q`：首页备份状态和暂停原因。
- `com.oplus.aiunit.vision.jsf`：备份条件即时重算。
- `NetworkMonitor`：在私有云备份条件检查范围内接受已验证的移动网络。
- `SettingsActivity.SettingFragment`：注入移动数据备份开关。

这些候选覆盖相册 `16.35.10`、`16.40.8`、`16.40.13` 和 `16.40.22`，后续版本仍可能因混淆变化而需要适配。

## 安全边界

本模块：

- 不修改相册 APK。
- 不修改账号绑定、NAS 设备记录或数据库。
- 不伪造、不打印、不上传 token。
- 不跳过飞牛服务端认证。
- 不改变官方云服务的网络策略。
- 不绕过温度、电量、省电模式等安全条件。

相册 `16.40.22` 的私网 IPv4 兼容会复用相册内置的宽松 TLS 分支，该分支不验证服务器证书。放宽仅限 `10/8`、`172.16/12` 和 `192.168/16`，但同一局域网内的恶意主机仍可能冒充 NAS；请只在可信局域网中使用。

仅应用于用户自己拥有并已绑定的飞牛 NAS。

## 构建

需要 JDK 17、Android SDK 35 和 Gradle 8.7：

```bash
gradle :app:assembleDebug :app:assembleRelease
```

输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

GitHub Actions 会自动构建测试 APK。正式版本请从 [Releases](../../releases) 下载。

## 排查日志

```bash
adb shell logcat | grep -iE 'ColorOSFeiniuBridge|FeiniuNasSDK|NasBackup'
```

常见模块日志：

```text
ColorOSFeiniuBridge: prefix fallback installed
ColorOSFeiniuBridge: private LAN TLS compatibility installed
ColorOSFeiniuBridge: private LAN TLS compatibility activated
ColorOSFeiniuBridge: backup pause reason text installed
ColorOSFeiniuBridge: mobile data backup compatibility installed
ColorOSFeiniuBridge: mobile data backup condition refresh requested
ColorOSFeiniuBridge: validated mobile network accepted for NAS backup
```

## 致谢

- 原项目及连接修复实现：[Costben/coloros-feiniu-bridge](https://github.com/Costben/coloros-feiniu-bridge)
- 测试、逆向分析与增强功能由本 Fork 持续维护。

## 许可证

[MIT](LICENSE)
