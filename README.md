# ColorOS 飞牛 Bridge Enhanced

面向 ColorOS 16 / ColorOS 17 的 LSPosed 模块，修复相册（`com.coloros.gallery3d`）与「设备空间」（`com.heytap.mydevices`）在 Root 后无法连接飞牛私有云的问题，并保留本 Enhanced 线一贯的自动备份增强能力。

本仓库 Fork 自 [Costben/coloros-feiniu-bridge](https://github.com/Costben/coloros-feiniu-bridge)，保留原项目完整提交历史与 MIT 许可证。`0.4.0` 起，本仓库 = **Enhanced 线合并上游 ColorOS 17 支持**：以上游的 libxposed API 102 架构（`META-INF/xposed` 描述符 + `XposedModule` 入口 + interceptor 链）为骨架，把增强功能逐项接回同一套入口，并同步上游的设备空间支持与结构定位逻辑。

> 已验证环境：ColorOS 16 / Android 16 的相册（`16.35.10`、`16.40.8`、`16.40.13`、`16.40.22`）与 ColorOS 17 / Android 17 的设备空间。后续版本会先尝试已知映射与结构定位，但仍需真机确认厂商没有改写相关业务流程。

## 功能

- 修复相册 / 设备空间调用 `cryptoeng` 失败后飞牛 token prefix 为空、无法连接的问题。
- 作用于相册 `com.coloros.gallery3d` 与设备空间 `com.heytap.mydevices`。
- 先按已知类名和完整方法结构查找；未确认命中时按结构扫描目标 APK 的 dex，扫描失败后才使用兼容旧版的类名 fallback。
- 优先保留系统原始 `cryptoeng` 路径，只有原方法返回空字符串或 `null` 时才提供 fallback。
- fallback 优先解析当前安装的目标 APK dex 字符串池，自动提取包含 `GwToken` 的精确 prefix；扫描失败时使用已验证的内置 prefix 兜底。
- 相册更新导致混淆类名变化时，按稳定行为特征用 DexKit 语义定位 token 解密与相册统计入口，并按 APK 指纹缓存解析结果。
- 在飞牛相册统计接口不可用时，使用真实相册数据恢复私有云列表与云端照片浏览。
- 修复相册通过 RFC1918 私网 IPv4 访问飞牛 NAS 时的 TLS 分支不兼容。
- 使用与 ColorOS 官方云备份一致的温控策略：前台高于 45°C 暂停、后台高于 43°C 暂停、降至 41°C 或以下恢复。
- 首页下拉区域显示飞牛私有云备份暂停的具体原因。
- 在相册设置中增加「允许私有云备份使用移动数据」选项。
- 基于现代 libxposed API 102 实现；内置静态作用域声明（相册 + 设备空间）。

## 实现原理

相册和设备空间都会通过已保存的 access token 和 refresh token 构造飞牛 NAS 的 `ConnectionSetupData`。token 解密使用 AES-GCM，密钥派生方式是：

```text
SHA-256(prefix + deviceId)
```

受影响的系统版本中，两处都会通过 `cryptoeng` 获取 `prefix`（相册走 `cryptoeng cmd 26`，设备空间走反射调用 `CryptoEngManager#cryptoEngCommand`）。但 `cryptoeng` 根据调用进程做权限检查并拒绝下发密钥，导致 `prefix` 为 `null`，token 解密失败，NAS 连接流程无法继续。

本模块只在原始 prefix 加载方法执行之后检查结果：

- 如果原方法已经成功返回 prefix，模块不做任何修改。
- 如果原方法返回空或 `null`，模块从目标 APK 的 dex 字符串池中解析飞牛 prefix 并返回给调用方。
- 调用方随后继续执行它自己的 token 解密和 NAS 连接流程。

模块不会伪造 token，不会伪造连接对象，也不会跳过飞牛服务端认证。它只恢复原有链路缺失的本地 prefix 值。

## 不做什么

- 不修改相册、设备空间或任何目标 APK。
- 不修改数据库。
- 不修改账号绑定或 NAS 设备记录。
- 不绕过飞牛服务端认证。
- 不打印、不保存、不上传 token。
- 不在目标进程外解密 token。

## 兼容性

LSPosed/Xposed 要求：

- 最低声明 API：`101`，目标 API：`102`
- 作用域配置：`META-INF/xposed/scope.list`（声明 `com.coloros.gallery3d` 与 `com.heytap.mydevices`）
- 模块配置：`META-INF/xposed/module.prop`（`staticScope=true`）
- 入口声明：`META-INF/xposed/java_init.list`（`io.github.colorosfeiniu.bridge.FeiniuBridgeHook`）
- 编译依赖：`io.github.libxposed:api:102.0.0`

附近版本理论上也可用，但需要保持以下点不变：

- 目标包名：`com.coloros.gallery3d`、`com.heytap.mydevices`
- prefix 加载方法：无参、返回 `String`
- token 解密入口：`(String, String)`，返回 `String`
- token 解密类的日志 tag 字面量：`TokenDecryptor`
- token 密钥派生方式：`SHA-256(prefix + deviceId)`

### 相册

相册每次大版本都会重排混淆类名，已核实的对应关系：

| 相册版本 | token 解密类 |
| --- | --- |
| 早期 ColorOS 16 | `com.oplus.aiunit.vision.erq` |
| 早期 ColorOS 16 | `com.oplus.aiunit.vision.in80` |
| 16.40.13 | `com.oplus.aiunit.vision.op80` |
| 16.40.22 | `com.oplus.aiunit.vision.qp80` |

16.40.22 的真实 DEX 与设备 hook 验证记录见 [`docs/validation/gallery-16.40.22.md`](docs/validation/gallery-16.40.22.md)。

相册侧的方法结构一直是 `e()` / `b(String, String)`，只有类名在动 —— 结构扫描另外还命中过 `y190`、`rab0`、`iab0`。

### 设备空间

设备空间不复用相册链路，有自己的连接栈和 token 解密类。已知两套契约：

| MyDevices 版本 | 环境 | token 解密类 | prefix 加载 | 解密入口 |
| --- | --- | --- | --- | --- |
| 17.5.5 | ColorOS 16.1 / Android 16 | 混淆类（`aa.d80`） | `m()` | `c(String, String)` |
| 17.25.10 | ColorOS 17 / Android 17 | `com.trim.connectiondemo.utils.TokenDecryptor` | `obtainPresharedSecretForDecrypt()` | `decrypt(String, String)` |

两套都使用与相册相同的 33 字符 prefix 和同一套密钥派生方式。17.25.10 的类名没有混淆，可以直接走已知类名快速路径；17.5.5 那套只能靠结构扫描。

模块按「一个包一组候选契约」匹配，任一组满足即算命中，所以上述两套可以并存。用真实 APK 复验：

```bash
gradle :app:testDebugUnitTest -Dmydevices.dex.path=/path/to/MyDevices.apk
```

参数可以是 APK，也可以是单个 `classes5.dex`。

### 结构定位

类名只作为快速路径。已知类名全部失配或不满足完整方法结构时，模块会扫描目标 APK 的 dex，找出**同时**声明某一组候选契约、且代码里加载了 `TokenDecryptor` 日志 tag 的类 —— 在 16.40.22 和 MyDevices 17.25.10 上，全 APK 都有且仅有一个类满足。只要上面那点不变，后续改名不再需要发新版。结构扫描仍无结果时，才会按类名兼容缺少解密入口标记的早期版本。

如果 OPPO/OnePlus 后续改了方法名、结构或 token 派生方式，模块仍需跟进适配。

## 增强功能（Enhanced 线）

模块对相册额外启用以下增强；设备空间只使用上游的 prefix 修复路径。

- `erq / in80 / op80 / qp80`：已验证版本直接使用固定映射，为 token 解密器提供 prefix fallback；上游结构定位作为优先路径，DexKit 语义解析作为兜底。
- 未知类名：仅对 token 解密器和相册统计服务启用 DexKit 语义解析；候选必须唯一且通过完整反射签名校验。
- 解析缓存：按相册版本、更新时间和 base/split APK 元数据生成指纹；相册更新后自动失效并重新解析。
- 相册统计服务：接口不可用时从真实相册数据恢复私有云列表。
- `ktc0.k(String)`：仅将 RFC1918 私网 IPv4 导向相册自带的兼容 TLS 分支。
- `bsf / f0q / u0q`：NAS 自动备份条件判断。
- `vwp / l370 / r570 / t570`：仅在 NAS 备份条件检查范围内应用温控策略。
- `stf / o3q / d4q`：首页备份状态和暂停原因。
- `com.oplus.aiunit.vision.jsf`：备份条件即时重算。
- `NetworkMonitor`：在私有云备份条件检查范围内接受已验证的移动网络。
- `SettingsActivity.SettingFragment`：注入移动数据备份开关。

这些固定候选覆盖相册 `16.35.10`、`16.40.8`、`16.40.13` 和 `16.40.22`。后续版本若只改变混淆名，语义解析通常可自行恢复；如果厂商改写了业务流程或稳定特征，仍需更新模块。

首次遇到未知相册版本时会进行一次 DEX 扫描，启动可能略慢，模块 APK 也会因包含 DexKit 原生库而增大。扫描结果会缓存，之后直接复用。解析出多个候选或校验失败时会关闭对应 Hook，而不是猜测目标。

ColorOS 17 版本的相册若尚未验证，温控 / TLS 等精确映射会保持 graceful no-op（不安装对应 hook、不崩溃、仅记录日志），旧版本映射保留。

## 安全边界

本模块：

- 不修改相册 APK。
- 不修改账号绑定、NAS 设备记录或数据库。
- 不伪造、不打印、不上传 token。
- 不跳过飞牛服务端认证。
- 不改变官方云服务的网络策略。
- 不绕过温度、电量、省电模式等安全条件。

相册的私网 IPv4 兼容会复用相册内置的宽松 TLS 分支，该分支不验证服务器证书。放宽仅限 `10/8`、`172.16/12` 和 `192.168/16`，但同一局域网内的恶意主机仍可能冒充 NAS；请只在可信局域网中使用。

仅应用于用户自己拥有并已绑定的飞牛 NAS。

## 构建

需要 JDK 17、Android SDK 35 和 Gradle 8.7：

```bash
gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

GitHub Actions 会在每次 push、pull request 和手动触发时自动构建。主线 push 和手动构建在配置完整签名 Secrets 后上传 signed release APK；未配置完整签名 Secrets 或 pull request 构建时上传 unsigned release APK。unsigned APK 不能直接安装，需要使用项目原有 release key 签名后发布。

如果发布前需要仓库内置 Gradle Wrapper，可以在有 Gradle 的机器上执行一次：

```bash
gradle wrapper --gradle-version 8.7
```

输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

GitHub Actions 主线构建在配置完整签名 Secrets 后会额外生成：

```text
app/build/outputs/apk/release/app-release-signed.apk
```

签名 Secrets 为 `ANDROID_SIGNING_KEYSTORE_BASE64`、`ANDROID_SIGNING_STORE_PASSWORD`、`ANDROID_SIGNING_KEY_PASSWORD` 与 `ANDROID_SIGNING_KEY_ALIAS`。维护者应使用项目原有 release key，以保留覆盖升级能力。合并代码不会改变已发布 APK 的证书；其他 fork 发布的 APK 可能使用不同证书，不能据此认定本项目需要卸载重装。CI debug APK 使用临时 debug key，不作为稳定升级包。

## 安装

1. 从 [Releases](../../releases) 下载并安装项目已签名 APK，或使用配置签名后 GitHub Actions 生成的 signed release APK。
2. 在 LSPosed 中启用模块。
3. 模块声明的作用域包含 `相册 / com.coloros.gallery3d` 与 `设备空间 / com.heytap.mydevices`。
4. 强停相册与设备空间，或重启手机。
5. 打开相册进入飞牛 NAS / 私有云入口；增强功能（温控 / 暂停原因 / 移动数据）作用于相册。打开设备空间点击 FnOS 卡片可验证 prefix 修复。

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

## 排查日志

```bash
adb shell logcat | grep -iE 'ColorOSFeiniuBridge|FeiniuNasSDK|TokenDecryptor|FNNetworkConnection|FeiNiuScannerImpl|NasAlbum|cryptoeng'
adb shell su -c 'ss -tnp | grep 5667'
```

常见模块日志：

```text
ColorOSFeiniuBridge: installed for com.coloros.gallery3d class=com.oplus.aiunit.vision.qp80 via=known-name
ColorOSFeiniuBridge: installed for com.heytap.mydevices class=com.trim.connectiondemo.utils.TokenDecryptor via=known-name
ColorOSFeiniuBridge: prefix fallback supplied source=apk-dex len=33
ColorOSFeiniuBridge: token resolver source=known class=com.oplus.aiunit.vision.qp80
ColorOSFeiniuBridge: private LAN TLS compatibility installed
ColorOSFeiniuBridge: backup pause reason text installed
ColorOSFeiniuBridge: mobile data backup compatibility installed
ColorOSFeiniuBridge: validated mobile network accepted for NAS backup
```

设备空间会分别在 `com.heytap.mydevices` 和 `com.heytap.mydevices:cards` 两个进程里各注入一次。`via=` 说明目标类是怎么找到的：

- `known-name`：命中已知类名，且该类同时带该 profile 的解密入口。
- `dex-scan`：已知类名全部失配或不满足完整解密结构，改由 dex 结构定位命中。
- `known-name-unconfirmed`：DEX 结构扫描失败后，回退到仅确认 prefix 加载方法的旧版已知类。

常见问题：

- 没有 `ColorOSFeiniuBridge` 日志：模块没有被 LSPosed 加载，检查模块是否启用、作用域是否包含对应包名、是否重启或强停目标应用。
- `prefix fallback unavailable for …`：模块加载了，但一个可 hook 的目标都没找到 —— 已知类名全部失配，dex 结构定位也没命中。
- `prefix loader threw …`：目标应用的解密方法自己抛了异常。后面跟着 `prefix fallback supplied` 说明模块已用自己的前缀顶替；只出现它而没有跟随时，异常按原样抛回应用。该日志每进程只记一次，并附带堆栈。
- fallback 后仍无法连接：检查目标应用日志里是否有 `AEADBadTagException`、token 过期、NAS 不可达、账号绑定异常等问题。

设备空间侧的排查 tag：`TokenDecryptor`、`FNNetworkConnection`、`FeiNiuScannerImpl`。

## 致谢

- 原项目及连接修复实现：[Costben/coloros-feiniu-bridge](https://github.com/Costben/coloros-feiniu-bridge)
- ColorOS 17 / libxposed API 102 架构迁移与设备空间支持来自上游。
- 测试、逆向分析与增强功能由本 Fork 持续维护。

## 许可证

[MIT](LICENSE)
