# Gallery 跨版本语义兼容解析器设计

日期：2026-07-29
状态：已由用户确认

## 背景

ColorOS Gallery 会在更新后重新混淆内部类名。当前模块通过 `erq`、`op80`、`qp80`、`n1g`、`q6q`、`n9q` 等名字寻找 Hook 目标，因此即使业务逻辑没有变化，普通的类名重排也会造成连接失效并要求重新发布模块。

Gallery 16.40.22 的设备验证已经证明这一问题：Token 解密逻辑仍然存在，但活动类名迁移到 `qp80`；没有把该类加入候选表时，prefix fallback 无法安装到真实 Token 解密器。

## 目标

- 新 Gallery 版本仅发生常规混淆改名时，无需发布新模块。
- 已知 Gallery 版本继续使用现有快速路径，不改变已经验证的行为。
- 未知版本通过 DEX 语义特征解析连接核心链路。
- 只有唯一且完全满足约束的目标才允许安装 Hook。
- 扫描结果可缓存，并在 Gallery 更新后自动失效。
- 不记录 Token、设备 ID、NAS 地址或其他认证数据。

## 首期范围

首期自动解析以下连接核心角色：

1. Token prefix 方法。
2. Token 解密方法。
3. Gallery statistic 方法。
4. 图集列表入口。
5. NAS 连接获取方法。
6. 真实图集分页方法。
7. statistic 缓存方法和 statistic DTO 计数字段。

以下功能暂不进行语义自动解析：

- 备份温控。
- 备份暂停状态和提示文本。
- 移动网络备份及设置页开关。
- `ktc0` 私网 TLS 选择器。

这些功能继续使用现有已知映射。`ktc0` Hook 继续要求精确类名和完整方法签名，避免把宽松 TLS 行为错误应用到其他证书判断。

## 方案比较

### 方案 A：已知映射加语义回退

先验证现有类名；找不到或结构不符时，再使用 DexKit 按字符串、类型、参数和调用关系扫描。

优点：

- 已知版本启动速度不变。
- 未知版本能抵抗普通混淆改名。
- 语义扫描失败时仍能按功能独立降级。

缺点：

- 模块会增加 DexKit 原生库体积。
- 未知版本首次启动需要同步扫描 DEX。

### 方案 B：所有版本都进行语义扫描

优点是代码路径统一，缺点是每次新进程都需要依赖缓存或扫描，且已验证版本也承担解析失败风险。

### 方案 C：电脑端生成新版本适配表

模块本身最轻，但 Gallery 更新后仍需要取得 APK、运行分析并发布模块，不能解决频繁发版问题。

## 决策

采用方案 A。已知映射是快速路径和回退基线，语义解析只在已知映射不可用时启动。

实现必须固定一个经过构建和设备验证的 DexKit 发布版本，不使用动态版本号。依赖升级属于单独维护事项。

## 架构

### `ConnectionResolutionBootstrap`

负责选择启动时机和解析来源：

1. 仅处理 `com.coloros.gallery3d`。
2. 分别解析 Token 组和图集组，先调用 `KnownConnectionResolver`。
3. 任一组的已知映射完整且签名验证通过时，立即保留该组结果。
4. 仍有未解析的组时，在 `Application.attach(Context)` 后取得 Context。
5. 对未解析的组尝试读取并验证缓存。
6. 缓存不可用时同步运行 `SemanticDexResolver`，且只查询仍未解析的组。
7. 合并各组结果并交给 `ConnectionHookInstaller`。

`Application.attach` 足够早，可以在 Gallery 业务 `onCreate` 和私有云认证前完成未知版本解析，同时提供安全的 SharedPreferences Context。

### `KnownConnectionResolver`

封装当前所有连接候选表。它不能只检查类是否存在，还必须验证：

- 方法的静态或实例属性。
- 参数顺序和参数类型。
- 返回类型。
- 同一角色需要的关联类型。

验证失败视为未知版本，不允许安装部分结构不匹配的已知 Hook。

### `SemanticDexResolver`

使用 Gallery 的 `sourceDir` 和全部 `splitSourceDirs` 创建 DexKit 查询桥。它只返回描述符，不直接安装 Hook。

解析完成后立即关闭 DexKit 桥，避免长期持有 DEX/native 资源。

### `ResolvedConnectionHooks`

不可变数据结构，保存：

- 可选的 Token 组：prefix 方法描述符、Token 解密方法描述符和该组解析来源。
- 可选的图集组：statistic、图集入口、连接获取、真实图集、缓存方法、DTO 计数字段描述符和该组解析来源。

两组至少有一组非空；每一组只能整体成功或整体缺失，不能保存半套结果。该对象不包含任何运行时 Token、设备信息或 NAS 信息。

### `ConnectionHookInstaller`

根据描述符通过 Gallery ClassLoader 重新加载反射对象，并再次执行完整签名校验。所有验证通过后才安装现有 Prefix fallback、Token 解密诊断和 Gallery statistic fallback。

安装器不执行 DEX 查询，因此已知映射、缓存和语义结果共享同一套最终安全门。

### `ConnectionResolutionCache`

使用 Gallery 进程中现有的 `coloros_feiniu_bridge` SharedPreferences 文件，增加独立的 resolver 命名空间。

缓存键包含：

- Gallery package name。
- `versionCode`。
- `lastUpdateTime`。
- 主 APK 的路径、长度和最后修改时间。
- 每个 split APK 的路径、长度和最后修改时间。
- resolver schema version。

缓存值仅包含 `ResolvedConnectionHooks` 的描述符和解析器版本。Token 组和图集组分别校验、分别失效；任意缓存键变化会使两组同时失效。描述符加载失败或签名校验失败只删除对应组并重新解析该组。

## Token 解密器匹配规则

候选类必须同时满足全部条件：

1. 存在非静态无参 `String` 返回方法，作为 prefix 方法。
2. 存在非静态 `(String, String) -> String` 方法，作为 Token 解密方法。
3. Token 解密方法调用同一类的 prefix 方法。
4. 该调用链使用精确字符串 `TokenDecryptor`。
5. 该调用链引用 `com.oplus.hardware.cryptoeng.CryptoEngManager`。
6. 解密路径使用 `AES/GCM/NoPadding`。
7. 解密路径使用 SHA-256 派生密钥。

所有约束完成后必须只剩一个类及一组方法。零个或多个结果都返回 `AMBIGUOUS_OR_MISSING`，不得退化为“选择得分最高的候选”。

## 图集链路匹配规则

Provider 角色必须形成一个完整的类型闭环：

1. statistic 方法使用 `getGalleryStat failed for device:` 稳定错误文本。
2. 图集入口是实例方法，签名为 `(int, int, String) -> List`。
3. 连接获取方法是实例方法，签名为 `(String, boolean) -> 非 void`。
4. 真实图集方法是实例方法，第一个参数等于连接获取方法的返回类型，完整签名为 `(connectionType, String, int, int) -> List`。
5. statistic 缓存方法是静态 `(String) -> statisticType`。
6. statistic DTO 是 statistic 方法和缓存方法的共同返回类型。
7. statistic DTO 的 `toString()` 使用 `NasGalleryStatDto(photoCount=` 和 `videoCount=`，并具有两个对应的实例 `int` 字段。

Provider、缓存方法和 DTO 必须作为同一组唯一解析。任何角色缺失或存在第二个完整闭环时，Gallery statistic fallback 不安装，但已经解析成功的 Token Hook 可以继续工作。

## 数据流

1. Gallery 进程加载模块。
2. Token 组和图集组分别完成已知映射结构验证。
3. 已知组生成来源为 `KNOWN` 的组结果。
4. 仍有未解析组时，在 `Application.attach` 后计算 Gallery 指纹。
5. 缓存命中且重新验证成功，生成来源为 `CACHE` 的组结果。
6. 对剩余组同步扫描主 APK 和 split APK。
7. 组内所有角色完成唯一性验证后，生成来源为 `SEMANTIC` 的组结果。
8. 有效组结果分别写入缓存。
9. 合并为 `ResolvedConnectionHooks`。
10. 安装器重新加载并校验反射对象，然后安装对应 Hook。
11. 每个子功能独立报告安装结果。

## 错误处理和安全边界

- DexKit native library 加载失败：记录一次 `resolver unavailable stage=native-load`，继续其他已知 Hook。
- DEX 查询抛错：关闭查询桥，记录异常类型，不记录查询到的业务数据。
- Token 角色不唯一：不安装 Prefix fallback 或 Token 诊断。
- 图集角色不完整：不安装 statistic fallback，不影响已成功解析的 Token Hook。
- 缓存数据损坏：删除 resolver 缓存并执行一次重新扫描。
- 反射对象二次校验失败：拒绝安装并使缓存失效。
- 所有 resolver 日志设置总量上限，避免循环刷屏。
- 日志只包含解析来源、阶段、候选数量、类/方法描述符和耗时。
- 不允许对 `TrustManager`、`HostnameVerifier`、`Cipher` 或 `CryptoEngManager` 安装全局替换 Hook。
- 不允许在候选不唯一时使用宽松反射规则继续执行。

## 性能要求

- 已知映射路径不得进行 DEX 扫描。
- 未知版本冷扫描目标为 5 秒以内。
- 缓存命中和二次验证目标为 100 毫秒以内。
- 同一进程只允许一个 resolver 扫描任务；其他调用等待同一结果。
- 同一 Gallery 指纹的失败结果在当前进程内缓存，避免重复扫描。

## 测试策略

### 单元测试

- Gallery 指纹在 `versionCode`、`lastUpdateTime`、主 APK 或 split APK 信息变化时失效。
- 缓存描述符能正确序列化、反序列化和拒绝 schema 不匹配。
- 方法和字段签名验证覆盖静态属性、参数顺序、返回类型和关联类型。
- 候选为零、一个和多个时分别返回缺失、成功和歧义。
- 日志格式不允许出现 Token、设备 ID 或 NAS 地址字段。

### 合成 DEX 集成测试

- 构造具有 Token 稳定特征的测试类并验证成功解析。
- 将所有测试类和方法重命名，解析结果保持不变。
- 添加第二个完整 Token 候选，解析器必须拒绝。
- 构造完整 Provider/Cache/DTO 类型闭环并验证成功。
- 破坏连接返回类型或真实图集第一个参数，Provider 解析必须失败。

### 真实 Gallery 离线验证

使用本地、忽略提交的 Gallery 16.40.8、16.40.13 和 16.40.22 APK/DEX 运行兼容验证。厂商 APK 和反编译完整源码不进入版本库。

验证输出必须包含每个角色的最终描述符、解析来源、候选数量和耗时，不包含业务数据。

### 设备验收

- 已知版本日志显示 `resolver source=known`。
- 删除或禁用已知候选的测试构建在 16.40.22 上显示 `resolver source=semantic`，并解析到实际 Token 解密器。
- 第二次启动显示 `resolver source=cache`，不再次扫描。
- Token 诊断显示非空解密结果，但不打印内容。
- 私有云图集能够实际打开并显示照片。
- 仅出现 Hook 安装日志不视为连接成功。

## 发布和回退

- 首个版本保留全部现有类名映射。
- 语义解析仅作为已知路径失败后的回退，不改变已知版本行为。
- 如果 DexKit 不支持设备 ABI 或加载失败，模块按现有已知映射运行。
- 如果未知版本解析失败，用户可提供 Gallery APK 和有限诊断日志用于补充新的稳定特征；不得仅把新混淆类名继续无限追加到主 Hook 文件。
- 后续将备份温控、状态展示和移动网络迁移到语义解析时，必须分别编写新设计并独立验收。

## 完成标准

- 已知 Gallery 版本通过快速路径。
- 合成类完全重命名后仍能解析连接核心角色。
- 歧义候选严格失败关闭。
- 缓存在 Gallery 更新后自动失效。
- 所有测试、兼容脚本和 Android 构建通过。
- 最终 APK 通过 v2 签名验证并包含所选 DexKit ABI。
- Gallery 16.40.22 设备测试能够显示真实私有云照片。
