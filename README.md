# ColorOS 飞牛 Bridge

这是一个 LSPosed 模块，用于修复部分 ColorOS 设备上相册与「设备空间」无法连接飞牛 NAS 的问题。问题根因是这两处在本地加载 token 解密 prefix 时都要调用 `cryptoeng` 硬件安全服务，但系统底层根据调用进程做权限检查并拒绝下发密钥，导致 prefix 为空、token 解密失败、NAS 连接流程无法继续。

## 功能

- 作用于 `com.coloros.gallery3d`（相册）与 `com.heytap.mydevices`（设备空间）。
- Hook 目标应用内 token 解密类的 prefix 加载方法（相册为 `e()`，设备空间见下文），也就是本地 prefix 的加载入口。
- 先按已知类名和完整方法结构查找；未确认命中时按结构扫描目标 APK 的 dex，扫描失败后才使用兼容旧版的类名 fallback。
- 优先保留系统原始 `cryptoeng` 路径，只有原方法返回空字符串或 `null` 时才提供 fallback。
- fallback 会优先解析当前安装的目标 APK dex 字符串池，自动提取包含 `GwToken` 的精确 prefix。
- 如果 APK 扫描失败，会使用当前已验证的飞牛 token prefix 作为兜底。
- 基于现代 libxposed API 102 实现。
- 模块内置静态作用域声明，声明的作用域为 `相册 / com.coloros.gallery3d` 与 `设备空间 / com.heytap.mydevices`。

## 实现原理

相册和设备空间都会通过已保存的 access token 和 refresh token 构造飞牛 NAS 的 `ConnectionSetupData`。token 解密使用 AES-GCM，密钥派生方式是：

```text
SHA-256(prefix + deviceId)
```

受影响的系统版本中，两处都会通过 `cryptoeng` 获取 `prefix`（相册走 `cryptoeng cmd 26`，设备空间走反射调用 `CryptoEngManager#cryptoEngCommand`）。但 `cryptoeng` 根据调用进程做权限检查，拒绝了目标进程，导致 `prefix` 为 `null`。随后 token 解密返回 `null`，连接流程无法继续。

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

已在 ColorOS 16 / Android 16 的相册和 ColorOS 17 / Android 17 的设备空间上验证。附近版本理论上也可用，但需要保持以下点不变：

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

两套都使用与相册相同的 33 字符 prefix 和同一套密钥派生方式。17.25.10 的类名没有混淆，可以直接走已知类名快速路径，省掉一次全 APK dex 扫描；17.5.5 那套只能靠结构扫描。

模块按「一个包一组候选契约」匹配，任一组满足即算命中，所以上述两套可以并存。用真实 APK 复验：

```bash
gradle :app:testDebugUnitTest -Dmydevices.dex.path=/path/to/MyDevices.apk
```

参数可以是 APK，也可以是单个 `classes5.dex`。

### 结构定位

类名只作为快速路径。已知类名全部失配或不满足完整方法结构时，模块会扫描目标 APK 的 dex，找出**同时**声明某一组候选契约、且代码里加载了 `TokenDecryptor` 日志 tag 的类 —— 在 16.40.22 和 MyDevices 17.25.10 上，全 APK 都有且仅有一个类满足。只要上面那几点不变，后续改名不再需要发新版。结构扫描仍无结果时，才会按类名兼容缺少解密入口标记的早期版本。

如果 OPPO/OnePlus 后续改了方法名、结构或 token 派生方式，模块仍需跟进适配。

## 构建

GitHub Actions 会在每次 push、pull request 和手动触发时自动构建 APK。主线 push 和手动构建会上传使用项目固定 release key 签名的 APK；外部 pull request 因无法读取仓库 Secrets，只上传 unsigned release APK。

也可以用 Android Studio 打开本仓库，然后运行 `app` 构建任务。

本地有 Gradle 时可以执行：

```bash
gradle :app:assembleRelease
```

如果发布前需要仓库内置 Gradle Wrapper，可以在有 Gradle 的机器上执行一次：

```bash
gradle wrapper --gradle-version 8.7
```

本地构建产物位置：

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

release APK 需要自行签名后再分发。

GitHub Actions 主线构建会额外生成：

```text
app/build/outputs/apk/release/app-release-signed.apk
```

固定 release 证书 SHA-256：

```text
37653B3C5DF69F83C3BB16C6BF7ADC7BE25AD0B34EB0EDCFA27A33CD2F3EB1BD
```

从 `0.1.4` 开始，正式安装和后续覆盖升级均使用该证书签名的 release APK。CI debug APK 使用临时 debug key，不作为稳定升级包。

## 安装

1. 安装 GitHub Actions 生成的 signed release APK。
2. 在 LSPosed 中启用模块。
3. 模块声明的作用域包含 `相册 / com.coloros.gallery3d` 与 `设备空间 / com.heytap.mydevices`。
4. 强停相册与设备空间，或重启手机。
5. 打开相册进入飞牛 NAS / 私有云入口，或打开设备空间点击 FnOS 卡片。

## 预期日志

LSPosed 日志中应能看到：

```text
ColorOSFeiniuBridge: installed for com.coloros.gallery3d class=com.oplus.aiunit.vision.qp80 via=known-name
ColorOSFeiniuBridge: installed for com.heytap.mydevices class=com.trim.connectiondemo.utils.TokenDecryptor via=known-name
ColorOSFeiniuBridge: prefix fallback supplied source=apk-dex len=33
```

设备空间会分别在 `com.heytap.mydevices` 和 `com.heytap.mydevices:cards` 两个进程里各注入一次。

`via=` 说明目标类是怎么找到的：

- `known-name`：命中已知类名，且该类同时带该 profile 的解密入口。
- `dex-scan`：已知类名全部失配或不满足完整解密结构，改由 dex 结构定位命中。目标应用刚升级过大版本时属正常。
- `known-name-unconfirmed`：DEX 结构扫描失败后，回退到仅确认 prefix 加载方法的旧版已知类。

随后相册 / 设备空间会继续原有连接流程，并连接飞牛 NAS 服务。

## 排查

- 没有 `ColorOSFeiniuBridge` 日志：模块没有被 LSPosed 加载，检查模块是否启用、作用域是否包含对应包名、是否重启或强停目标应用。
- `prefix fallback unavailable for com.coloros.gallery3d`：模块加载了，但一个可 hook 的目标都没找到 —— 已知类名全部失配，dex 结构定位也没命中。相册的 token 解密结构变了，需要重新适配。
- `prefix fallback unavailable for com.heytap.mydevices`：同上，设备空间的 token 解密结构变了。
- `dex scan did not find a token decryptor class for …`：结构定位扫完全部 dex 无果，同上。
- 没有 `prefix fallback supplied`：原始 `cryptoeng` 可能已经成功，或者没有触发飞牛入口。
- `prefix loader threw …`：目标应用的解密方法自己抛了异常。后面跟着 `prefix fallback supplied` 说明模块已用自己的前缀顶替；只出现它而没有跟随时，异常按原样抛回应用。该日志每进程只记一次，并附带堆栈。
- fallback 后仍无法连接：检查目标应用日志里是否有 `AEADBadTagException`、token 过期、NAS 不可达、账号绑定异常等问题。
- token 解密成功但相册为空：本模块只恢复连接构造，照片索引和同步状态由相册与飞牛 NAS 自身处理。

设备空间侧的排查 tag：`TokenDecryptor`、`FNNetworkConnection`、`FeiNiuScannerImpl`。

常用排查命令：

```bash
adb shell logcat | grep -iE 'ColorOSFeiniuBridge|FeiniuNasSDK|TokenDecryptor|FNNetworkConnection|FeiNiuScannerImpl|NasAlbum|cryptoeng'
adb shell su -c 'ss -tnp | grep 5667'
```

## 安全说明

本模块仅用于用户访问自己拥有并已绑定的飞牛 NAS。模块恢复的是本地 prefix 加载失败的问题，不改变 token 来源、不改变服务端校验、不改变账号或设备绑定。

请不要将本模块用于访问你不拥有或无权访问的设备、账号或 NAS 服务。

## 许可证

MIT
