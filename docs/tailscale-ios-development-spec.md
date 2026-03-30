# Tailscale iOS 自研客户端开发文档

## 1. 文档目标

本文档用于指导一个可独立开发、可逐步上线的 Tailscale iOS 客户端项目。文档目标不是推测官方 iOS GUI 的私有实现，而是在以下公开事实基础上，给出一份可执行的产品规格和技术实现蓝图：

- Tailscale Android 客户端已开源，可作为移动端参考实现。
- Tailscale 的核心 Go node software、`ipn` 类型、LocalAPI、通知流等公开可见。
- Apple 平台 GUI 未开源，因此 iOS 客户端必须自行实现平台壳层。
- Tailscale 官方文档明确给出了 iOS 的平台要求、部分功能支持范围和若干 Apple 平台限制。

本文档当前版本优先定义第一阶段 MVP，覆盖：

- 登录与登出
- VPN 连接与断开
- 节点列表与状态展示

同时，本文档也给出后续阶段的扩展路线，避免 MVP 方案在架构上走入死路。

## 2. 适用范围

本文档适用于以下工作：

- 从零开始设计 iOS 客户端工程结构
- 复用 Tailscale 开源 Go 核心设计 Apple 平台桥接层
- 规划 MVP 上线范围、测试方式和后续增强路线
- 为后续研发、测试、产品和运维提供统一的事实基线

本文档不适用于以下目标：

- 复刻官方 iOS 客户端的私有 GUI 行为
- 自行实现 Tailscale 协调服务器
- 将 iOS 首版做成“全功能桌面级” Tailscale 客户端

## 3. 信息来源与可信度分层

为避免文档出现错误，本文档中的结论按三个等级标注。

### 3.1 官方文档确认

指 Tailscale 官方文档、官方博客、官方知识库、官方 Go package 文档中明确写出的事实。

### 3.2 开源代码确认

指可以从 Android 开源客户端或 `tailscale.com` Go 包中直接看到的结构、类型、接口、行为模式。

### 3.3 工程推断待验证

指基于 Apple 平台机制和 Tailscale 公开代码做出的合理推断。这些内容可以指导设计，但在真正开发前必须通过 spike 或原型验证。

除非另有说明，本文档尽量只把前两类内容写成“确定项”；第三类内容会明确标出。

## 4. 事实基线

### 4.1 平台与分发

以下内容属于官方文档确认：

- Tailscale 官方 iOS 客户端要求 iOS 15.0 或更高版本。
- iOS 客户端支持 iPhone 和 iPad。
- 首次使用需要安装 VPN configuration。
- 官方客户端建议允许 push notifications；其用途之一是提示用户重新认证，例如密钥即将过期时。
- iOS 支持通过 MDM 进行配置部署。

### 4.2 当前代码版本

以下内容属于开源代码确认：

- 本文档参考的 Android 开源仓库使用 `tailscale.com v1.94.2`（见 `go.mod`）。
- Go toolchain 版本由 `go.toolchain.rev` 指定。
- 开发时应以仓库 `go.mod` 中锁定的版本为准，不要假设最新 release 版本号。

### 4.3 开源边界

以下内容属于官方文档确认：

- Tailscale client 的核心 daemon 代码大部分开源。
- Linux 和 Android 的 daemon 与 GUI 都开源。
- 对于 Apple 这类闭源操作系统平台，daemon 开源，但 GUI 不开源。
- Tailscale coordination server 是闭源服务。

因此，本项目的合理路线是：

- 复用开源核心
- 自建 iOS GUI 与平台壳层
- 不尝试猜测或复制官方闭源 UI 细节

### 4.4 Tailscale 的核心工作方式

以下内容属于官方文档确认：

- 数据面基于 WireGuard。
- 控制面负责密钥交换、设备元数据、策略分发和协调，不承载主要业务流量。
- 节点之间优先建立端到端加密的点对点连接。
- NAT 穿透依赖 STUN/ICE 类机制。
- 在无法直连时可回退到 DERP relay。
- 私钥不会离开本地设备。

这意味着 iOS 客户端不需要重写 Tailscale 协议本身，而应尽可能复用现有 Go backend 与其状态模型。

### 4.5 iOS 上已公开确认的功能边界

以下内容属于官方文档确认：

- iOS 可作为普通客户端接入 tailnet。
- iOS 可使用 exit node。
- iOS 会自动接收已批准的 subnet routes。
- Taildrop 支持 iOS，但 iOS 作为接收端时，传输中断后不能断点恢复。
- Tailscale SSH 的 server component 仅在 Linux 和 macOS 开源 CLI 变体上明确可用；任何运行 Tailscale 的设备都可以作为 SSH client 发起连接。
- Funnel 仅在可以运行 Tailscale CLI 的平台上工作。
- Serve 依赖 CLI 和 HTTPS 配置，文档未将 iOS 列为支持平台。

### 4.6 首版必须避免的错误假设

以下内容要明确视为“不要默认支持”：

- 不要把 Android 的前台服务模型搬到 iOS。
- 不要把 Android 的 per-app split tunnel 视为 iOS 等价能力。
- 不要把 iOS 当作 exit node 提供方。
- 不要把 iOS 当作 subnet router 发布方。
- 不要把 Serve、Funnel、Tailscale SSH 服务端纳入 iOS 首版范围。

## 5. 项目目标

### 5.1 第一阶段目标：MVP

第一阶段只做三件事，但每件事必须做扎实：

1. 用户可以完成登录与登出。
2. 用户可以控制 VPN 的连接与断开，并能看到清晰状态。
3. 用户可以查看当前 tailnet 的节点列表与自身节点状态。

### 5.2 第二阶段目标：增强版移动客户端

在 MVP 稳定后，扩展以下方向：

- Exit Node 选择与 Allow LAN Access
- 更多设备详情与健康状态
- Taildrop
- Tailnet Lock
- MDM 能力
- 自定义控制服务器和 Headscale 兼容
- 更完整的诊断与日志导出

### 5.3 非目标

当前文档明确将以下内容排除在首版之外：

- Serve
- Funnel
- Tailscale SSH 服务端
- 作为 exit node 运行
- 作为 subnet router 发布路由
- Android 风格的 app split tunneling

## 6. 能力矩阵

| 能力 | Android 开源实现 | 官方公开 iOS 支持情况 | 自研 iOS MVP | 自研 iOS 长线规划 |
| --- | --- | --- | --- | --- |
| 登录与登出 | 已实现 | 支持 | 支持 | 支持 |
| VPN 连接与断开 | 已实现 | 支持 | 支持 | 支持 |
| 节点列表与状态 | 已实现 | 可推导支持 | 支持 | 支持 |
| Exit Node 作为客户端使用 | 已实现 | 支持 | 不进首版 | 第二阶段 |
| Allow LAN Access | 已实现 | 支持 | 不进首版 | 第二阶段 |
| Taildrop | 已实现 | 支持，有限制 | 不进首版 | 第三阶段 |
| Tailnet Lock | 已实现 | 未直接见到 iOS 完整公开说明 | 不进首版 | 第三阶段 |
| DNS 高级设置 | 已实现 | 部分可行 | 不进首版 | 第三阶段 |
| MDM | 已实现 | 支持 | 不进首版 | 第三阶段 |
| 自定义控制服务器 | 已实现 | 可推导可行 | 不进首版 | 第三阶段 |
| Headscale | Android 可配控制面 | 可推导可行 | 不进首版 | 第三阶段 |
| Serve | Android 侧无核心移动定位 | 未列 iOS 支持 | 排除 | 排除 |
| Funnel | Android 侧无核心移动定位 | 官方说明需 CLI | 排除 | 排除 |
| Tailscale SSH 服务端 | Android 不适合作为服务端 | 官方仅明确 Linux/macOS CLI | 排除 | 排除 |
| 作为 exit node 运行 | Android 可以做但官方也提示性能问题 | 未见 iOS支持 | 排除 | 排除 |
| 发布 subnet routes | Android 已实现 | 未见 iOS支持 | 排除 | 排除 |

说明：

- “可推导支持”表示该能力未在 iOS 安装页直接列出，但从公开模型和平台职责可以合理推导其存在或可实现。
- 任何“可推导”能力在进入开发排期前都需要原型验证。

## 7. 参考实现分析

### 7.1 Android 项目提供了什么

Android 开源项目提供了三个最有价值的参考层：

1. Go 核心装配层
2. 本地控制接口层
3. 响应式状态聚合层

不应该直接照搬的部分是 Android 特有壳层，例如：

- `VpnService`
- foreground service
- WorkManager
- Android share intent
- Android KeyStore 细节
- RestrictionsManager

### 7.2 可直接参考的关键文件

- `libtailscale/interfaces.go`：定义 Android 与 Go 核心之间的最小平台接口，包括 `AppContext`、`IPNService`、`VPNServiceBuilder`、`ShareFileHelper` 等。**iOS 桥接层首先应参考这个文件确定需要实现的接口集合。**
- `libtailscale/backend.go`：`ipnlocal.LocalBackend` 与 `localapi.NewHandler` 的装配方式；同时包含内部 `WatchNotifications` 订阅（使用 `NotifyInitialNetMap | NotifyInitialPrefs | NotifyInitialState`）。
- `libtailscale/localapi.go`：通过 `net.Pipe()` + `http.Request` 实现的进程内 LocalAPI 调用模式，无需网络。提供 `CallLocalAPI`、`CallLocalAPIMultipart` 和 `EditPrefs(ipn.MaskedPrefs)` 等能力。
- `libtailscale/notifier.go`：订阅 `ipn.Notify` 的桥接方式，通过 `backend.WatchNotifications(ctx, ipn.NotifyWatchOpt(mask), ...)` 接收通知并 JSON 序列化传递到前端。
- `libtailscale/keystore.go`：硬件密钥证明（Hardware Attestation Key）实现，Android 使用 StrongBox KeyStore，**iOS 对应 Secure Enclave**。
- `libtailscale/store.go`：状态持久化层，Android 使用 `EncryptedSharedPreferences` 配合 base64 编码。**iOS 对应 Keychain + App Group 共享。**
- `libtailscale/vpnfacade.go`：同时实现 `router.Router` 和 `dns.OSConfigurator` 接口，路由与 DNS 配置变更时触发 TUN 重建。
- `libtailscale/multitun.go`：多 TUN 设备多路复用器，因 Android `VpnService.Builder` 调用 `Establish()` 后不可修改而需要销毁重建 TUN 设备。**iOS 大概率不需要此机制**：iOS `NEPacketTunnelProvider` 支持通过 `setTunnelNetworkSettings(_:completionHandler:)` 原地重配路由和 DNS（配合 `reasserting` 标志），无需销毁重建 TUN。在 Spike 阶段应验证 iOS 原地重配是否稳定可靠；如果确认可行，则不应复用 `multitun.go`，以避免引入不必要的复杂度。
- `libtailscale/net.go`：核心 TUN 构建器（设置 MTU、DNS、路由、地址）与平台网络接口解析。
- `libtailscale/callbacks.go`：跨边界事件全局通道（VPN 请求、断开、DNS 变更、日志、Token、文件分享等）。**重要警告：这些全局 channel（`onVPNRequested`、`onDisconnect`、`onDNSConfigChanged`、`onLog`、`onShareFileHelper`）是进程内全局变量，仅在 Android 单进程模型下可行。iOS 的主 App 与 Network Extension 运行在不同进程中，无法直接共享这些 channel。iOS 必须设计显式的跨进程通信机制（如 App Group `UserDefaults`、Darwin Notifications、或 `NETunnelProviderSession.sendProviderMessage`）来替代这些全局通道。**
- `libtailscale/syspolicy_handler.go`：MDM 策略读取层，实现 `syspolicy.Store` 接口；**iOS 对应 `NSUserDefaults` Managed App Configuration**。
- `libtailscale/fileops.go`：Taildrop 文件操作层，实现 `taildrop.FileOps` 接口；iOS 需基于 `FileManager` + App Group 容器重新实现。
- `libtailscale/log.go`：日志层，Android 用 logcat，远程日志使用 `logtail` + `filch` 缓冲。**iOS 对应 `os.Logger` (Unified Logging)**。
- `android/.../ui/notifier/Notifier.kt`：把通知流转成 `StateFlow` 的实现模式。Kotlin 侧使用的 `NotifyWatchOpt` 标志：`Netmap | Prefs | InitialState | InitialHealthState | RateLimitNetmaps`。
- `android/.../ui/localapi/Client.kt`：MVP 所需 LocalAPI 客户端面。
- `android/.../ui/model/Ipn.kt`、`IpnState.kt`、`NetMap.kt`、`Health.kt`：前端序列化模型参考。

### 7.3 Android 参考实现揭示的关键结论

以下内容属于开源代码确认：

- Android 并不是直接通过公网请求来控制 backend，而是通过 `net.Pipe()` 进程内桥接调用 LocalAPI（无本地 HTTP 监听）。
- 前端核心状态由 `ipn.Notify` 驱动，包括 `State`、`Prefs`、`NetMap`、Health 等。
- 设置编辑采用 `MaskedPrefs`，不是整包覆盖配置。
- Exit Node、连接状态、profile 信息都建立在同一套 `Prefs` 和 `Notify` 模型之上。
- 认证支持多种方式：浏览器 OAuth/SSO（通过 Chrome Custom Tabs 打开 `BrowseToURL`）、Auth Key 直接登录、QR 码登录（Android TV）、MDM 静默注册。
- 多 profile 管理已在 Android 实现：`profiles`、`currentProfile`、`addProfile`、`deleteProfile`、`switchProfile`。
- 硬件密钥证明通过 Android KeyStore StrongBox 实现，iOS 对应 Secure Enclave。
- 状态持久化通过 `EncryptedSharedPreferences` 实现，iOS 对应 Keychain。

这意味着 iOS 设计时，最重要的是复用这套数据流，而不是复刻 Android 的页面组织。

## 8. iOS 总体架构

### 8.1 架构原则

1. Go 核心尽量保持单一事实来源。
2. iOS UI 只负责展示状态和触发用户动作，不直接承担网络核心逻辑。
3. VPN 生命周期必须遵循 Apple 的 Network Extension 模型。
4. 主 App 与扩展之间的通信必须显式设计，不能靠进程内全局状态假设。

### 8.2 推荐目标结构

推荐拆分为以下 target 或模块：

#### A. iOS 主 App

职责：

- 登录入口
- 主界面与节点列表
- 设置页与基础诊断页
- 发起 VPN 开关操作
- 展示 backend 当前状态

#### B. Packet Tunnel Network Extension

职责：

- 承担 VPN tunnel 生命周期
- 应用路由、DNS、TUN 配置
- 托管与 Go backend 的核心联动
- 在受限生命周期中维护最小可运行状态

#### C. Shared Core 层

职责：

- 定义 Swift 侧展示模型
- 封装 LocalAPI 调用
- 统一解析 `Notify`、`Prefs`、`Status`、`NetMap`
- 负责主 App 与扩展之间共享状态的读写协议

#### D. Go Bridge 层

职责：

- 暴露启动 backend、订阅通知、调用 LocalAPI、读写状态等最小桥接面
- 将 iOS 平台能力适配到 Tailscale backend 需要的接口上

#### E. Test Targets

职责：

- 状态机测试
- LocalAPI 封装测试
- 通知解码测试
- 扩展与主 App 的集成测试

### 8.3 主 App 与扩展的进程关系

以下属于工程推断待验证，但基本应按此设计：

- 主 App 与 Packet Tunnel Extension 运行在不同进程。**这是 iOS 与 Android 最大的架构差异**——Android 的 `VpnService` 与 UI 运行在同一进程，可以共享全局变量和内存对象；iOS 则完全隔离。
- 共享状态需要通过 App Group 容器或明确的系统通道传递。推荐的 IPC 机制包括：
  - **App Group `UserDefaults`**：适合小量状态同步（连接状态、当前 profile 等）。
  - **App Group 共享文件目录**：适合大数据交换（日志、NetMap 缓存等）。
  - **`NETunnelProviderSession.sendProviderMessage`**：主 App 向 Extension 发送请求消息并接收回复，适合主动查询（如获取状态、触发操作）。
  - **Darwin Notifications (`CFNotificationCenterGetDarwinNotifyCenter`)**：轻量级跨进程事件通知（如“状态已变更，请重新读取”），不携带数据。
  - **Keychain（共享访问组）**：适合加密状态持久化。
- 不应默认使用“主 App 单例对象”作为全局事实来源。
- **Go 侧的 `callbacks.go` 使用的全局 channel 在 iOS 双进程模型下完全不可用**，必须用上述 IPC 机制替代。
- Extension 应具备独立初始化 backend 的能力——当主 App 未启动时（如系统自动重连 VPN），Extension 需要能自主完成 backend 启动、状态恢复。

#### 8.3.1 Network Extension 内存限制

**Network Extension 内存限制约 50 MB**，超出后系统会直接终止扩展进程（无警告、无回调）。

Go runtime + Tailscale backend + WireGuard engine 的组合可能逼近此限制。应对策略包括：

1. **Spike 阶段必须做内存 baseline 测试**：在真机上运行 Extension，用 Instruments 监测稳态内存、NetMap 更新峰值内存、大型 tailnet（数百节点）下的内存占用。
2. **启用 `NotifyRateLimitNetmaps`**：减少 NetMap 更新频率，降低 JSON 序列化/反序列化的瞬时内存压力。
3. **考虑裁剪 Go 编译**：使用 `-ldflags="-s -w"` 减小二进制体积，评估 `GOGC` 调优降低 GC 内存开销。
4. **Extension 内仅保留最小功能集**：将日志查看、诊断数据收集等非核心功能放在主 App 侧，Extension 只做 VPN tunnel 维护。
5. **设计优雅降级**：如果内存压力大，Extension 应能主动释放非关键缓存（如完整 NetMap），保住核心隧道功能。

### 8.4 iOS 平台约束清单

以下平台差异会直接影响架构或功能设计：

| Android 特性 | iOS 等价机制 | 影响 |
| --- | --- | --- |
| `VpnService` 前台服务 | `NEPacketTunnelProvider` 扩展 | 完全不同的生命周期模型 |
| `EncryptedSharedPreferences` | Keychain + App Group | 状态持久化方式不同，需共享访问组 |
| Android KeyStore StrongBox | Secure Enclave (`SecKeyCreateRandomKey`) | 硬件密钥证明实现方式不同 |
| `RestrictionsManager` (MDM) | `NSUserDefaults` Managed App Config | MDM 策略读取接口不同 |
| Chrome Custom Tabs | `ASWebAuthenticationSession` | 登录浏览器跳转方式不同 |
| Per-app split tunnel (`addDisallowedApplication`) | 不支持（仅 MDM Per-App VPN） | iOS 无法做应用级分流 |
| `android.util.Log` / logcat | `os.Logger` (Unified Logging) | 日志系统不同 |
| Notification Channels | `UNNotificationCategory` | 通知分组机制不同 |
| `VpnService.Builder` 重建 TUN | `NEPacketTunnelProvider.packetFlow` + `setTunnelNetworkSettings` | TUN 设备管理方式不同，iOS 支持原地重配，**`multitun.go` 大概率不适用** |
| `SupportsSplitDNS() = false` | **iOS 可能支持 Split DNS**（通过 `NEDNSSettings.matchDomains`）（⚠️ 工程推断待验证） | iOS DNS 能力可能更强，但需验证 Go backend 在 `SupportsSplitDNS()` 返回 `true` 时的行为是否正确 |
| WorkManager | `BGTaskScheduler`（受限） | 后台任务调度能力差异大 |
| Intent / Deep Link | URL Scheme / Universal Links | 深链接机制不同 |
| VPN 状态通知栏常驻 | iOS 状态栏 VPN 图标（系统自动） | 不需要手动管理前台通知 |
## 9. 核心集成策略

### 9.1 设计原则

本项目复用的核心不是 Android 平台层，而是 Tailscale backend 的统一逻辑。iOS 需要自建平台适配层，使其满足 backend 的最低运行条件。

### 9.2 iOS 必须实现的平台接口

以下属于开源代码确认，来自 `libtailscale/interfaces.go`。**这是 iOS 桥接层最重要的参考文件。**

Android 的 `AppContext` 接口定义了 Go 核心运行所需的全部平台回调，iOS 必须提供等价实现：

| 接口方法 | 作用 | iOS 实现方向 |
| --- | --- | --- |
| `Log(tag, logLine)` | 平台日志 | `os.Logger` |
| `EncryptToPref(key, value)` | 加密写入状态 | Keychain (App Group 共享) |
| `DecryptFromPref(key)` | 解密读取状态 | Keychain (App Group 共享) |
| `GetStateStoreKeysJSON()` | 列出所有状态键 | Keychain 查询 |
| `GetOSVersion()` | 系统版本 | `UIDevice.current.systemVersion` |
| `GetDeviceName()` | 设备名 | `UIDevice.current.name` |
| `GetInstallSource()` | 安装来源 | 固定返回 `"appstore"` 或 `"testflight"` |
| `GetInterfacesAsJson()` | 网络接口信息 | `NWPathMonitor` / `getifaddrs` |
| `GetPlatformDNSConfig()` | 平台 DNS 配置 | `NWPathMonitor` |
| `ShouldUseGoogleDNSFallback()` | Google DNS 回退 | 返回 `false`（非 ChromeOS） |
| `IsChromeOS()` | 是否 ChromeOS | 返回 `false` |
| `GetSyspolicyStringValue(key)` | MDM 字符串策略 | `UserDefaults.standard` |
| `GetSyspolicyBooleanValue(key)` | MDM 布尔策略 | `UserDefaults.standard` |
| `GetSyspolicyStringArrayJSONValue(key)` | MDM 字符串数组策略 | `UserDefaults.standard` |
| `HardwareAttestationKeySupported()` | 硬件密钥支持检测 | Secure Enclave 可用性检查 |
| `HardwareAttestationKeyCreate()` | 创建硬件密钥，**返回 `id string`** | `SecKeyCreateRandomKey` + Secure Enclave，返回生成的 key id |
| `HardwareAttestationKeySign(id, data)` | 使用指定 id 的密钥对 data 签名 | `SecKeyCreateSignature` |
| `HardwareAttestationKeyPublic(id)` | 导出指定 id 密钥的公钥 | `SecKeyCopyExternalRepresentation` |
| `HardwareAttestationKeyLoad(id)` | 加载指定 id 的密钥 | `SecItemCopyMatching` |
| `HardwareAttestationKeyRelease(id)` | 释放指定 id 的密钥 | `SecItemDelete` |

注意：硬件密钥相关方法的参数是 `id string`（由 `Create()` 返回），不是 `name`。iOS 实现时需要保存 `Create()` 返回的 id，后续操作均以此 id 为索引。此外，需要验证 Tailscale 服务端是否对 attestation 证书链有特定格式要求——Android 的 StrongBox attestation 格式与 iOS Secure Enclave 的 attestation 格式可能不同，如果服务端做了格式校验，可能导致验证失败。

此外还需实现 `IPNService`（VPN 生命周期）和 `VPNServiceBuilder`（TUN 配置）的等价接口，这些在 iOS 上对应 `NEPacketTunnelProvider` 及其 `packetFlow`。

### 9.3 应复用的核心概念

以下内容属于开源代码确认或官方 Go 文档确认：

- `ipn.State`
- `ipn.Notify`
- `ipn.Prefs`
- `ipn.MaskedPrefs`
- `ipn.LoginProfile`
- `ipn.ServeConfig`
- `ipn.NotifyWatchOpt`

虽然 MVP 不会暴露 `ServeConfig` 能力，但模型本身来自公开核心，后续文档要知道它存在，避免未来扩展时重新设计配置存储格式。

### 9.4 NotifyWatchOpt 标志

以下属于开源代码确认。订阅 `WatchNotifications` 时需要指定监听标志：

| 标志 | 值 | 用途 |
| --- | --- | --- |
| `NotifyInitialState` | 2 | 订阅时立即发送当前 State |
| `NotifyInitialPrefs` | 4 | 订阅时立即发送当前 Prefs |
| `NotifyInitialNetMap` | 8 | 订阅时立即发送当前 NetMap |
| `NotifyInitialHealthState` | 128 | 订阅时立即发送当前 Health |
| `NotifyRateLimitNetmaps` | 256 | 限制 NetMap 更新频率，避免高频刷新 |

Android Kotlin 侧默认使用 `Netmap | Prefs | InitialState | InitialHealthState | RateLimitNetmaps`。Go 侧内部订阅使用 `NotifyInitialNetMap | NotifyInitialPrefs | NotifyInitialState`。

iOS 侧建议与 Android Kotlin 保持一致的默认标志组合。

### 9.5 LocalAPI 策略

推荐方向：优先做进程内桥接，而不是本地 loopback HTTP。

理由：

- Android 开源实现已经证明进程内桥接 LocalAPI 是可行的。
- iOS 对本地端口监听、跨进程通信和沙箱行为更加敏感。
- 进程内桥接更容易控制权限边界和生命周期。

这属于工程推断待验证，必须做原型确认。

### 9.6 Go Bridge 方案

推荐先调研两条路线：

#### 方案 A：gomobile / Objective-C bridge

优点：

- 与现有移动方向一致
- 上层 Swift 调用路径直观

风险：

- 调试复杂度较高
- 与 extension 环境结合时可能带来额外封装成本

#### 方案 B：静态库 + C wrapper + Swift 封装

优点：

- 边界更清楚
- 更适合精确定义 ABI 和线程边界

风险：

- 初期接线成本更高
- 需要更严格管理内存与回调接口

当前建议：先做最小 Spike，同时验证以下五件事：

- backend 启动是否稳定
- `Notify` 订阅是否稳定
- LocalAPI 调用是否可用
- Network Extension 生命周期内是否能正确持有 backend
- **Network Extension 内 Go backend 的稳态内存占用是否在 50 MB 限制内**

## 10. 状态模型设计

### 10.1 MVP 状态机

MVP 至少需要支持以下状态：

- `NoState`
- `NeedsLogin`
- `NeedsMachineAuth`
- `Stopped`
- `Starting`
- `Running`
- 错误态

说明：

- 这些状态来自公开的 `ipn.State`。
- UI 不应自行发明另一套不兼容状态机。

### 10.2 前端应持有的核心状态

主状态容器至少应包含：

- 当前 IPN 状态
- 当前用户 profile
- 登录态与浏览器引导态
- 当前节点自身信息
- 节点列表
- 最近一次 `Prefs`
- 最近一次 Health 信息
- 最近一次错误信息

### 10.3 状态更新来源

状态来源应明确分成两类：

1. 主动查询
   - 例如首次加载 `status`、`prefs`、profiles
2. 被动订阅
   - 例如 `WatchNotifications` 推来的 `Notify`

策略上应以订阅为主，以查询为补偿。

## 11. 认证与会话流程

### 11.1 登录流程

MVP 登录流程建议如下：

1. 用户打开 App。
2. App 检查本地是否已有可恢复 profile 与当前状态。
3. 若为 `NeedsLogin`，调用 backend 触发登录。实际分三步：`EditPrefs(WantRunning=true)` → `Start(options)` → `StartLoginInteractive()`。
4. backend 通过 `BrowseToURL` 回调要求前端打开浏览器。
5. iOS 使用 `SFSafariViewController` 或 `ASWebAuthenticationSession` 打开登录页。注意：Tailscale 登录完成信号来自 backend 的 `Notify.LoginFinished`，而非浏览器 URL 回调，因此不需要注册自定义 URL scheme。使用 `SFSafariViewController` 时在收到 `LoginFinished` 后主动 dismiss 即可。
6. 用户在浏览器完成 OAuth/SSO 登录。
7. App 接收回调或通过 `Notify.LoginFinished` 获知登录完成，关闭认证界面。
8. 状态进入 `Stopped` 或 `Running`，取决于当前 `WantRunning`。

#### 其他登录方式（后续阶段）

Android 还支持以下登录方式，iOS 可按需引入：

- **Auth Key 登录**：直接提供 auth key 完成注册，跳过浏览器流程。适用于 MDM 静默部署和无人值守设备。
- **QR 码登录**：生成登录 URL 的 QR 码供另一设备扫描。Android TV 使用此方式，Apple TV 可参考。
- **MDM 静默注册**：通过 MDM 策略键 `AuthKey` 推送 auth key，设备自动注册无需用户操作。

### 11.2 多 Profile 管理

以下属于开源代码确认：

Android 已实现完整的多 profile 管理，iOS 应在 MVP 保留基础结构以便后续扩展：

- `profiles` — 获取所有 profile
- `currentProfile` — 获取当前活跃 profile
- `addProfile` — 新增 profile
- `switchProfile(id)` — 切换到指定 profile
- `deleteProfile(id)` — 删除 profile

`LoginProfile` 模型包含：`ID`、`Name`、`Key`、`UserProfile`、`NetworkProfile`、`LocalUserID`、`ControlURL`。

MVP 阶段：至少实现 `currentProfile` 读取与展示，支持单 profile 登录/登出。  
Phase 2+：支持多 profile 切换与管理 UI。

### 11.3 登出流程

MVP 登出流程建议如下：

1. 用户在设置页点击登出。
2. App 调用 LocalAPI 对应 logout 能力。
3. backend 清理当前登录态与相关运行态。
4. UI 回到未登录视图。
5. 若本地仍保留 profile 信息，展示为可重新登录或重新连接的状态，而不是保留过期 UI。

### 11.4 需要单独设计的失败态

- 浏览器未完成登录
- 登录回调丢失
- 机器授权未通过
- VPN permission / configuration 未安装成功
- 登录成功但 tunnel 未能启动
- 登录成功后配置恢复失败

## 12. MVP 功能规格

### 12.1 功能 A：登录与登出

#### 用户故事

- 用户可以使用支持的 SSO 登录到 tailnet。
- 用户可以主动登出。
- 用户在重新打开 App 时可以看到正确登录状态。

#### 必需能力

- 登录入口页
- 浏览器跳转
- 登录中状态展示
- 失败提示
- 登出入口
- 本地状态恢复

#### 首版不做

- 多控制面选择 UI
- Headscale 配置 UI
- 多账户复杂切换 UI

### 12.2 功能 B：VPN 连接与断开

#### 用户故事

- 用户可以点击连接。
- 用户可以点击断开。
- 用户可以看到当前状态是未登录、已停止、启动中还是运行中。

#### 必需能力

- 连接按钮
- 断开按钮
- 首次安装 VPN configuration 引导
- 连接中状态
- 失败态重试提示

#### 状态文案必须明确区分

- 未登录
- 需要机器授权
- 已停止
- 连接中
- 已连接
- 发生错误

### 12.3 功能 C：节点列表与状态

#### 用户故事

- 用户可以看到自己当前设备的基本信息。
- 用户可以看到 tailnet 中可见节点列表。
- 用户可以看到节点是否在线、设备名、用户信息、Tailscale IP 等基础信息。

#### 必需字段

- 设备显示名
- 在线状态
- 用户显示名或登录名
- 主地址或 Tailscale 地址
- 是否为当前设备

#### 首版可选字段

- OS 类型
- 最后在线时间
- 是否为 exit node
- key expiry

### 12.4 基础设置与诊断

MVP 至少保留以下最小设置面：

- 版本信息
- 当前控制面信息只读展示
- 日志或诊断入口占位
- 登出

## 13. 后续增强路线

### 13.1 Phase 2

- Exit Node 选择
- Allow LAN Access
- 更详细的 self status
- 节点详情页
- Health 状态展示
- 多 Profile 切换

#### Exit Node 实现要点

以下属于开源代码确认：

- 选择 Exit Node 通过设置 `Prefs.ExitNodeID` (`MaskedPrefs.ExitNodeIDSet`) 实现。
- 允许局域网访问通过 `Prefs.ExitNodeAllowLANAccess` 控制。
- Android 提供了 `InternalExitNodePrior` 字段，用于"快速切换"上一个使用的 Exit Node。
- 还提供了 `set-use-exit-node-enabled?enabled=true/false` LocalAPI 端点，用于开/关当前 Exit Node 而不重新选择。
- Android 有 Mullvad 出口节点集成（商业 VPN 接入），iOS 阶段性评估是否需要。
- MDM 可通过 `ExitNodeID` 策略键强制指定 Exit Node。

#### Health 数据模型

以下属于开源代码确认：

```
Health.State {
    Warnings: Map<WarnableCode, UnhealthyState?>
}

Health.UnhealthyState {
    WarnableCode: String        // 告警标识码
    Severity: low | medium | high
    Title: String               // 告警标题
    Text: String                // 告警详情
    BrokenSince: String?        // 故障起始时间
    Args: Map<String, String>?  // 上下文参数
    ImpactsConnectivity: Boolean?  // 是否影响网络连通性
    DependsOn: List<String>?    // 依赖链
}
```

iOS 实现注意：
- Android 将高严重度健康告警推送为系统通知（`IMPORTANCE_HIGH` channel），iOS 应使用 `UNUserNotificationCenter`。
- `ImpactsConnectivity` 标志应在 UI 中醒目展示。

### 13.2 Phase 3

- Taildrop
- Tailnet Lock
- MDM 配置
- 自定义控制服务器
- Headscale 兼容
- 日志导出、bug report、诊断包

### 13.3 暂不建议纳入移动端目标

- Serve
- Funnel
- SSH 服务端
- iOS 作为 exit node
- iOS 发布 subnet routes

这些能力即使理论上未来存在实现空间，也不应在当前项目的移动端路线中优先投入。

## 14. 工程结构建议

推荐目录形态如下：

```text
ios/
  App/
  PacketTunnel/
  Shared/
  GoBridge/
  Tests/
  UITests/
```

### 14.1 `App/`

- 登录 UI
- 主页面
- 节点列表页
- 设置页
- 状态绑定层

### 14.2 `PacketTunnel/`

- `NEPacketTunnelProvider` 实现
- TUN、路由、DNS 应用逻辑
- 扩展内 backend 生命周期控制

### 14.3 `Shared/`

- 状态容器
- 展示模型
- App Group 共享读写封装
- LocalAPI client 抽象

### 14.4 `GoBridge/`

- Swift 对 Go 导出能力的包装
- 回调与线程边界封装
- 错误码与日志统一

## 15. 数据模型与接口清单

### 15.1 MVP 必需模型

- `ipn.State`
- `ipn.Notify`（含 `BrowseToURL`、`LoginFinished`、`State`、`Prefs`、`NetMap`、`Health` 等字段）
- `ipn.Prefs`
- `ipn.MaskedPrefs`
- `ipn.LoginProfile`（即 `IpnLocal.LoginProfile`，含 `ID`、`Name`、`Key`、`UserProfile`、`NetworkProfile`、`LocalUserID`、`ControlURL`）
- `netmap.NetworkMap`（含 `SelfNode`、`Peers`、`Domain`、`UserProfiles`、`TKAEnabled`、`DNS`、`AllCaps`）
- `Health.State` 与 `Health.UnhealthyState`（MVP 阶段只读即可）

### 15.2 MVP 必需接口

最低应打通以下 LocalAPI 或等价 backend 能力：

- `GET /localapi/v0/status` — 当前状态与节点列表
- `GET /localapi/v0/prefs` — 当前偏好
- `PATCH /localapi/v0/prefs` — 编辑偏好（通过 `MaskedPrefs`）
- `POST /localapi/v0/start` — 启动 backend 状态机
- `POST /localapi/v0/logout` — 登出
- `POST /localapi/v0/login-interactive` — 触发交互式登录
- `GET /localapi/v0/profiles/` — 列出所有 profile
- `GET /localapi/v0/profiles/current` — 当前 profile
- `WatchNotifications` — 订阅状态通知流（非 HTTP 端点，通过 Go bridge 直接调用）

### 15.3 接口设计原则

- 所有配置编辑优先走 `MaskedPrefs`
- 所有 UI 状态更新优先订阅 `Notify`
- 所有只读页面优先从统一状态容器读取，而不是各自重复请求 backend

## 16. 测试与验收标准

### 16.1 单元测试

至少覆盖：

- `Notify` 解码
- `Prefs` 与 `MaskedPrefs` 编辑逻辑
- 状态机转换
- 本地缓存恢复

### 16.2 集成测试

至少覆盖：

- 登录到运行态完整链路
- 运行态断开与重连
- 登出
- 主 App 与 Packet Tunnel 的状态同步

### 16.3 真机测试

必须覆盖：

- 首次安装 VPN configuration
- Wi-Fi 与蜂窝切换
- 锁屏后恢复
- 前后台切换
- 重新打开 App 后状态恢复
- 登录过期或需要重新认证

### 16.4 MVP 验收标准

MVP 验收必须满足以下三项：

1. 新用户可完成登录并进入可连接状态。
2. 用户可稳定连接、断开，并在 UI 上看到正确状态。
3. 用户可稳定看到自身节点和节点列表的基础状态信息。

## 17. 风险与待验证问题

### 17.1 高优先级 Spike

在正式开发前，必须先做以下原型验证：

1. Go bridge 在 iOS App 与 Packet Tunnel 中的装配方式（gomobile vs 静态库 + C wrapper）。
2. backend 在 `NEPacketTunnelProvider` 生命周期中的可持续运行性，**尤其关注 50 MB 内存限制**。需要在真机上用 Instruments 测量 Go runtime + backend + WireGuard engine 的稳态内存占用和 NetMap 更新时的峰值内存。
3. `WatchNotifications` 回调在 Apple 平台的稳定性。
4. LocalAPI 采用进程内桥接（`net.Pipe()` 模式）而非 loopback 的可行性。
5. App Group 状态同步策略是否足够稳定（Keychain 共享访问组 + `UserDefaults` suite）。
6. Secure Enclave 硬件密钥创建与签名在 iOS 15+ 上的兼容性，**以及 Tailscale 服务端是否校验 attestation 证书链格式**（Secure Enclave 的 attestation 格式与 Android StrongBox 不同）。
7. `ASWebAuthenticationSession` 与 Tailscale 登录回调的集成验证。
8. **主 App 与 Network Extension 的 IPC 通信验证**：确认 `NETunnelProviderSession.sendProviderMessage` 的请求/响应模式、Darwin Notifications 的跨进程通知、App Group `UserDefaults` 的读写一致性是否满足状态同步需求。
9. **iOS Network Extension TUN 原地重配验证**：确认 `setTunnelNetworkSettings` + `reasserting` 是否能正确应用路由和 DNS 变更而无需重建 TUN，以决定是否需要移植 `multitun.go`。

### 17.2 产品风险

- App Store 审核对 VPN、登录跳转、后台行为的限制
- 扩展生命周期导致的状态不一致
- 日志采集与崩溃诊断能力不足
- 登录完成但 tunnel 启动失败时的用户体验

### 17.3 技术风险

- Go 与 Swift 桥接边界不稳定
- 多进程共享状态设计失误导致 UI 显示陈旧状态
- 过早支持过多功能导致状态机复杂度失控
- **Network Extension 50 MB 内存限制**：Go backend 内存占用可能在大型 tailnet 或频繁 NetMap 更新下超限，导致系统无预警终止扩展
- **Android 全局 channel 模式不可移植**：`callbacks.go` 中的 `onVPNRequested`、`onDisconnect` 等全局 channel 在 iOS 双进程架构下无法使用，需要重新设计 Go 侧的事件传递机制
- **Secure Enclave attestation 格式兼容性**：iOS 与 Android 硬件密钥的 attestation 证书格式不同，可能导致服务端验证失败

## 18. 开发顺序建议

推荐按如下顺序推进：

1. 完成最小 bridge spike
2. 打通 backend 启动与 `Notify` 订阅
3. 实现登录流
4. 实现 VPN connect/disconnect
5. 实现节点列表与状态页
6. 完成真机稳定性验证
7. 再进入 Exit Node 等增强能力

## 19. 结论

这个 iOS 项目的正确做法不是“照搬 Android”，而是“复用 Tailscale 开源核心，重建 Apple 平台壳层”。

首版只做登录、VPN 开关、节点状态，是合理且可上线的切入点。只要底层围绕 `ipn`、LocalAPI 和 `Notify` 这套公开模型来搭建，后续扩展 Exit Node、Taildrop、Tailnet Lock 和 MDM 时，就不需要推翻首版架构。

当前版本文档已经足够作为项目立项和第一轮技术 Spike 的依据。下一步应开始补写更细的章节，包括：

- 登录时序图
- App 与 PacketTunnel 的进程通信协议
- LocalAPI 最小接口定义
- MVP 页面与状态机详细规格

## 20. 参考资料

- Tailscale Open Source: <https://tailscale.com/opensource>
- Install Tailscale on iOS: <https://tailscale.com/docs/install/ios>
- How Tailscale works: <https://tailscale.com/blog/how-tailscale-works>
- Exit nodes: <https://tailscale.com/docs/features/exit-nodes>
- Subnet routers: <https://tailscale.com/docs/features/subnet-routers>
- Taildrop: <https://tailscale.com/docs/features/taildrop>
- Tailscale SSH: <https://tailscale.com/docs/features/tailscale-ssh>
- Apple Network Extension: <https://developer.apple.com/documentation/networkextension>
- ASWebAuthenticationSession: <https://developer.apple.com/documentation/authenticationservices/aswebauthenticationsession>

## 附录 A：iOS 项目推荐构建目标

| 项目 | 推荐值 |
| --- | --- |
| 最低部署版本 | iOS 15.0 |
| 推荐 Xcode 版本 | 最新稳定版（16+） |
| Swift 版本 | 5.9+ |
| UI 框架 | SwiftUI（配合 UIKit 兜底） |
| 目标架构 | arm64 |
| 主 App Bundle | 包含 SwiftUI 主界面 + Go bridge |
| Network Extension | Packet Tunnel Provider target |
| App Group | 主 App 与 Extension 共享容器 |
| Keychain Group | 共享 Keychain 访问组 |

Android 参考：`compileSdkVersion 34`、`minSdkVersion 26`（Android 8.0）、`targetSdkVersion 35`、Kotlin 1.9.22、Compose BOM 2024.09.03。

## 附录 B：Android MDM 策略键清单（Phase 3 参考）

以下属于开源代码确认，来自 `MDMSettings.kt`。iOS Phase 3 实现 MDM 时需对齐这些策略键：

| 键名 | 类型 | 用途 |
| --- | --- | --- |
| `ForceEnabled` | Boolean | 强制 VPN 开启 |
| `ExitNodeID` | String | 强制指定 Exit Node |
| `KeyExpirationNotice` | String | 密钥过期告警提前量 |
| `LoginURL` | String | 自定义控制面 URL |
| `ManagedByCaption` | String | "由...管理" 标题 |
| `ManagedByOrganizationName` | String | 管理组织名 |
| `ManagedByURL` | String | 管理方支持链接 |
| `Tailnet` | String | 要求的 tailnet 名称 |
| `HiddenNetworkDevices` | String[] | 隐藏的设备类别 |
| `AllowIncomingConnections` | AlwaysNeverUserDecides | 是否允许入站连接 |
| `ExitNodeAllowLANAccess` | AlwaysNeverUserDecides | Exit Node 时允许局域网 |
| `PostureChecking` | AlwaysNeverUserDecides | 姿态检查 |
| `UseTailscaleDNSSettings` | AlwaysNeverUserDecides | 是否使用 MagicDNS |
| `UseTailscaleSubnets` | AlwaysNeverUserDecides | 是否使用子网路由 |
| `ExitNodesPicker` | ShowHide | Exit Node 选择器显隐 |
| `ManageTailnetLock` | ShowHide | Tailnet Lock 管理入口显隐 |
| `RunExitNode` | ShowHide | 运行 Exit Node 入口显隐 |
| `AllowedSuggestedExitNodes` | String[] | 建议的 Exit Node 列表 |
| `AuthKey` | String | 静默注册 Auth Key |
| `Hostname` | String | 覆盖设备主机名 |
| `OnboardingFlow` | ShowHide | 首次使用引导显隐 |
| `HardwareAttestation` | Boolean | 启用硬件密钥证明 |
| `DeviceSerialNumber` | String | 设备序列号 |

说明：`AlwaysNeverUserDecides` 为三态枚举；`ShowHide` 为二态枚举。iOS 通过 Managed App Configuration（`com.apple.configuration.managed` payload）推送这些键值。

## 附录 C：扩展参考链接

- Tailscale Funnel: <https://tailscale.com/docs/features/tailscale-funnel>
- Tailscale Serve: <https://tailscale.com/docs/features/tailscale-serve>
- Three ways to run Tailscale on macOS: <https://tailscale.com/docs/concepts/macos-variants>
- Tailscaled on macOS: <https://github.com/tailscale/tailscale/wiki/Tailscaled-on-macOS>
- Go package `tailscale.com/ipn`: <https://pkg.go.dev/tailscale.com/ipn>
