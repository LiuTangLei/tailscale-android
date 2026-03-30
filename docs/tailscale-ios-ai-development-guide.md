# Tailscale iOS AI 开发指南

## 文档定位

本文档是给 AI 编程助手使用的**可执行开发指南**。

它回答五个问题：

1. iOS MVP 到底做什么
2. 哪些 Go 接口必须在 iOS 侧实现，签名是什么
3. 状态机如何转换，数据如何流动
4. 每个开发阶段的具体产出物和验收标准是什么
5. 哪些 Android 模式必须改写，改成什么

**约束**：凡未标注"已验证"的方案，都只是实现方向。涉及 Network Extension 生命周期、Go↔Swift 桥接、Secure Enclave、IPC 的实现必须先做 Spike 验证。

配套文档：

- 事实基线、能力边界、非目标、风险详述：见 `docs/tailscale-ios-development-spec.md`
- Android 参考实现：见 `libtailscale/` 和 `android/src/main/`

---

## 1. MVP 范围

首版**只做**：登录/登出、VPN 连接/断开、节点列表与状态展示。

首版**不做**：Serve、Funnel、SSH 服务端、exit node 提供方、subnet router、app split tunneling、Taildrop、MDM 完整支持、Headscale UI。

完整能力矩阵见 spec 第 6 节。

## 2. 核心开发原则

1. 以 `ipn.State` / `ipn.Notify` / `ipn.Prefs` / `ipn.MaskedPrefs` 为数据模型，不另造状态体系。
2. 复用 Tailscale 开源 Go backend，不重写协议核心。
3. iOS UI 只做展示和用户动作触发，不承担网络逻辑。
4. **双进程架构**：主 App 与 Packet Tunnel Extension 是两个独立进程，不能沿用 Android 的进程内全局状态假设。
5. 未标注"已验证"的推荐方案，只能作为实现方向。

## 3. iOS 目标工程结构

```text
ios/
├── App/                  # SwiftUI 主界面、登录、状态展示
├── PacketTunnel/         # NEPacketTunnelProvider、VPN 生命周期
├── Shared/               # Swift 数据模型、状态容器、LocalAPI 封装、IPC 协议
├── GoBridge/             # Go backend → Swift 的最小桥接面
└── Tests/                # 模型解码、状态机、LocalAPI、集成测试
```

## 4. 必须实现的 Go 平台接口

以下是从 `libtailscale/interfaces.go` 提取的 `AppContext` 接口完整签名。iOS 必须提供等价实现：

```go
type AppContext interface {
    Log(tag, logLine string)
    EncryptToPref(key, value string) error
    DecryptFromPref(key string) (string, error)
    GetStateStoreKeysJSON() string
    GetOSVersion() (string, error)
    GetDeviceName() (string, error)
    GetInstallSource() string
    ShouldUseGoogleDNSFallback() bool              // iOS: 固定返回 false
    IsChromeOS() (bool, error)                       // iOS: 固定返回 false, nil
    GetInterfacesAsJson() (string, error)
    GetPlatformDNSConfig() string
    GetSyspolicyStringValue(key string) (string, error)
    GetSyspolicyBooleanValue(key string) (bool, error)
    GetSyspolicyStringArrayJSONValue(key string) (string, error)
    HardwareAttestationKeySupported() bool
    HardwareAttestationKeyCreate() (id string, err error)
    HardwareAttestationKeyRelease(id string) error
    HardwareAttestationKeyPublic(id string) (pub []byte, err error)
    HardwareAttestationKeySign(id string, data []byte) (sig []byte, err error)
    HardwareAttestationKeyLoad(id string) error
}
```

**Android → iOS 实现映射表**：

| 接口方法 | Android 实现 | iOS 实现方向 |
|---|---|---|
| `Log` | `android.util.Log` | `os.Logger` (Unified Logging) |
| `EncryptToPref` / `DecryptFromPref` | `EncryptedSharedPreferences` | Keychain (App Group 共享访问组) |
| `GetStateStoreKeysJSON` | SharedPreferences key 遍历 | Keychain `SecItemCopyMatching` 查询 |
| `GetOSVersion` | `Build.VERSION.RELEASE` | `UIDevice.current.systemVersion` |
| `GetDeviceName` | `Settings.Global.DEVICE_NAME` | `UIDevice.current.name` |
| `GetInstallSource` | `PackageManager` | 固定返回 `"appstore"` 或通过 receipt 判断 TestFlight |
| `GetInterfacesAsJson` | `NetworkInterface.getNetworkInterfaces()` | `getifaddrs()` 或 `NWPathMonitor` |
| `GetPlatformDNSConfig` | `LinkProperties.getDnsServers()` | `NWPathMonitor` |
| `GetSyspolicy*` | `RestrictionsManager` | `UserDefaults.standard` (Managed App Config) |
| `HardwareAttestationKey*` | Android KeyStore StrongBox | Secure Enclave `SecKeyCreateRandomKey` |

此外还需实现 VPN 生命周期等价接口：

| Android 接口 | iOS 等价 |
|---|---|
| `IPNService` (VPN 服务生命周期) | `NEPacketTunnelProvider` |
| `VPNServiceBuilder` (TUN 构建) | `NEPacketTunnelProvider.setTunnelNetworkSettings` |
| `ParcelFileDescriptor` (TUN fd) | `NEPacketTunnelProvider.packetFlow` |

## 5. 状态机与数据流

### 5.1 ipn.State 状态转换图

```
┌──────────┐
│ NoState  │
└────┬─────┘
     │ backend 启动
     ▼
┌────────────┐    登录成功     ┌─────────┐
│ NeedsLogin │──────────────→│ Stopped │
└────────────┘               └────┬────┘
     ▲                            │ WantRunning=true
     │ 登出 / key 过期            ▼
     │                      ┌──────────┐
     └──────────────────────│ Starting │
                            └────┬─────┘
                                 │ 隧道建立成功
                                 ▼
                            ┌─────────┐
                            │ Running │
                            └────┬────┘
                                 │ WantRunning=false
                                 ▼
                            ┌─────────┐
                            │ Stopped │
                            └─────────┘

特殊分支：
  NeedsLogin ──→ NeedsMachineAuth ──→ Stopped（需管理员批准）
```

### 5.2 登录时序

```
用户点击登录
  → App: EditPrefs(WantRunning=true)
  → App: Start(options)
  → App: StartLoginInteractive()
  → Go backend 返回 Notify { BrowseToURL: "https://..." }
  → App: 用 ASWebAuthenticationSession 或 SFSafariViewController 打开 URL
  → 用户在浏览器完成 OAuth/SSO
  → Go backend 返回 Notify { LoginFinished: {} }
  → App: dismiss 浏览器
  → Go backend 返回 Notify { State: Running }（若 WantRunning=true）
```

**关键**：登录完成信号来自 `Notify.LoginFinished`，不是浏览器 URL 回调。不需要注册自定义 URL scheme。

### 5.3 VPN 连接/断开时序

```
连接：
  → App 通过 IPC 通知 Extension
  → Extension: startTunnel(options:)
  → Extension: 启动 Go backend（如未启动）
  → Extension: setTunnelNetworkSettings(...)
  → Extension: Go backend 进入 Running 状态
  → Extension 通过 IPC 通知 App 状态变更

断开：
  → App: NETunnelProviderManager.connection.stopVPNTunnel()
  → Extension: stopTunnel(with:, completionHandler:)
  → Go backend 停止
```

### 5.4 Notify 订阅模式

从 `libtailscale/notifier.go` 提取的核心模式：

```go
// Go 侧
app.backend.WatchNotifications(ctx, ipn.NotifyWatchOpt(mask), func() {}, func(notify *ipn.Notify) bool {
    b, _ := json.Marshal(notify)
    cb.OnNotify(b)  // 将 JSON 传给 Swift 侧
    return true
})
```

推荐的 mask 组合（与 Android Kotlin 侧一致）：

```
NotifyInitialState(2) | NotifyInitialPrefs(4) | NotifyInitialNetMap(8) |
NotifyInitialHealthState(128) | NotifyRateLimitNetmaps(256)
```

### 5.5 数据流总览

```
┌─────────────────────────────────────────────────────────────┐
│                    Packet Tunnel Extension                  │
│                                                             │
│  Go Backend ──WatchNotifications──→ JSON Notify             │
│      ▲                                   │                  │
│      │ LocalAPI (net.Pipe)               │ IPC              │
│      │                                   ▼                  │
│  ┌───────────┐                   ┌──────────────┐          │
│  │ VPNFacade │                   │ App Group /  │          │
│  │ (Router + │                   │ sendProvider │          │
│  │  DNS)     │                   │ Message      │          │
│  └───────────┘                   └──────┬───────┘          │
└─────────────────────────────────────────┼──────────────────┘
                                          │
┌─────────────────────────────────────────┼──────────────────┐
│                    Main App             │                   │
│                                         ▼                  │
│  ┌──────────────┐    ┌─────────────────────────────┐      │
│  │ SwiftUI View │◄───│ StateContainer              │      │
│  │              │    │ (State, Prefs, NetMap, Health)│      │
│  └──────────────┘    └─────────────────────────────┘      │
└────────────────────────────────────────────────────────────┘
```

## 6. LocalAPI 调用参考

### 6.1 进程内桥接模式

Android 使用 `net.Pipe()` + `http.Request` 实现进程内 LocalAPI，无需 HTTP 监听。核心函数签名（`libtailscale/localapi.go`）：

```go
func (app *App) CallLocalAPI(timeoutMillis int, method, endpoint string, body InputStream) (LocalAPIResponse, error)
func (app *App) EditPrefs(prefs ipn.MaskedPrefs) (LocalAPIResponse, error)
```

### 6.2 MVP 必需端点

| 端点 | 方法 | 用途 | 请求体 | 响应关键字段 |
|---|---|---|---|---|
| `/localapi/v0/status` | GET | 当前状态与节点列表 | 无 | `BackendState`, `Self`, `Peer` |
| `/localapi/v0/prefs` | GET | 当前偏好 | 无 | `WantRunning`, `ExitNodeID` |
| `/localapi/v0/prefs` | PATCH | 编辑偏好 | `MaskedPrefs` JSON | 更新后的 `Prefs` |
| `/localapi/v0/login-interactive` | POST | 触发交互式登录 | 无 | 空（登录结果走 Notify） |
| `/localapi/v0/logout` | POST | 登出 | 无 | 空 |
| `/localapi/v0/profiles/` | GET | 列出所有 profile | 无 | `[]LoginProfile` |
| `/localapi/v0/profiles/current` | GET | 当前 profile | 无 | `LoginProfile` |

`WatchNotifications` 不是 HTTP 端点，通过 Go bridge 直接调用。

### 6.3 MaskedPrefs 编辑示例

只设置想修改的字段，附带对应的 `*Set` 标志：

```json
{
    "WantRunning": true,
    "WantRunningSet": true
}
```

## 7. IPC 方案设计

主 App 与 Extension 之间必须通过显式 IPC 通信。推荐组合：

| 机制 | 用途 | 方向 |
|---|---|---|
| `NETunnelProviderSession.sendProviderMessage` | 主动查询/触发操作 | App → Extension |
| App Group `UserDefaults` | 小量状态同步（连接状态、profile） | 双向 |
| App Group 共享文件目录 | 大数据（日志、NetMap 缓存） | 双向 |
| Darwin Notifications (`CFNotificationCenterGetDarwinNotifyCenter`) | "状态已变更，请重读" | Extension → App |
| Keychain (共享访问组) | 加密状态持久化 | 双向 |

**Android 的 `callbacks.go` 中的全局 channel（`onVPNRequested`、`onDisconnect` 等）在 iOS 双进程下完全不可用**，必须用上述机制替代。

## 8. 线程与并发模型

| 场景 | 线程要求 |
|---|---|
| Go backend 回调（Notify、日志） | 在 Go goroutine 中执行，**不在主线程** |
| Swift UI 更新 | 必须在 `@MainActor` / 主线程 |
| `sendProviderMessage` 回调 | 可能在任意线程 |
| Keychain 操作 | 线程安全但建议串行队列 |
| Extension `startTunnel` / `stopTunnel` | 系统调用，在系统指定线程 |

**规则**：Go 回调 → 解码 JSON → 派发到主线程更新 UI。不要在 Go 回调中直接操作 UI。

## 9. 明确不要照抄 Android 的部分

| Android 模式 | 原因 | iOS 应该怎么做 |
|---|---|---|
| `VpnService` 前台服务 | 完全不同的生命周期 | `NEPacketTunnelProvider` |
| `callbacks.go` 全局 channel | 双进程不可共享 | IPC（见第 7 节） |
| `multitun.go` 多 TUN 重建 | iOS 支持原地重配 | `setTunnelNetworkSettings` + `reasserting` |
| `VPNServiceBuilder.Establish()` | Android 不可修改已建立的 TUN | iOS 用 `packetFlow` 直接读写 |
| `EncryptedSharedPreferences` | Android 专有 | Keychain + App Group |
| Android KeyStore StrongBox | 不同的硬件密钥 API | Secure Enclave |
| 通知栏常驻 | Android 前台服务要求 | iOS 系统自动显示 VPN 状态栏图标 |
| Android UI 页面组织 | Compose Activity/Fragment 模式 | SwiftUI NavigationStack |

## 10. 推荐实现顺序与验收标准

### 阶段 A：最小 Spike

**目标**：确认"能不能做"，不堆 UI。

验证事项：

1. Go backend 能否编译为 iOS 可调用的库（gomobile 或 C wrapper）
2. Extension 内能否启动 backend 并稳定持有状态
3. `WatchNotifications` 能否在 iOS 侧稳定接收 JSON
4. LocalAPI 进程内桥接能否正常工作
5. `setTunnelNetworkSettings` 能否覆盖 MVP 的路由/DNS 更新
6. Extension 稳态内存是否在 **50 MB** 限制内
7. Secure Enclave 是否满足硬件密钥需求

**验收标准**：
- [ ] 一个最小 Xcode 工程，Extension 内启动 Go backend 不 crash
- [ ] 调用 `WatchNotifications` 能在 Xcode 控制台看到 JSON 输出
- [ ] 调用 `GET /localapi/v0/status` 能返回合法 JSON
- [ ] Instruments 内存测量报告：稳态 < 40 MB，峰值 < 50 MB
- [ ] 桥接方式结论文档：gomobile 还是 C wrapper
- [ ] IPC 方案结论文档

### 阶段 B：固定桥接边界

**目标**：Swift↔Go 边界收窄到最小。

MVP 最小导出面：

```
Start(dataDir, directFileRoot, hwAttestation, appCtx) → Application
Application.WatchNotifications(mask, callback) → NotificationManager
Application.CallLocalAPI(timeout, method, endpoint, body) → Response
NotificationManager.Stop()
```

**验收标准**：
- [ ] Swift 能调用上述 4 个方法并正确收发数据
- [ ] 桥接层代码不超过 500 行

### 阶段 C：VPN 基础链路

**目标**：隧道能启动、连接状态可见、能断开。

**验收标准**：
- [ ] 首次安装 VPN configuration 对话框正确弹出
- [ ] 隧道启动后 iOS 状态栏出现 VPN 图标
- [ ] `stopVPNTunnel()` 后图标消失
- [ ] 连接/断开循环 10 次无 crash

### 阶段 D：登录与状态流

**目标**：登录后 UI 被 `Notify` 驱动。

**验收标准**：
- [ ] `NeedsLogin` → 浏览器打开 → 登录完成 → 状态变为 `Running`
- [ ] `LoginFinished` 后浏览器自动 dismiss
- [ ] `Prefs`、`NetMap`、`Health` 能正确进入状态容器
- [ ] 登出后 UI 回到 `NeedsLogin` 状态

### 阶段 E：MVP UI

只需以下视图：启动态 / 登录页 / 主页面 / 简单设置页。

主页面保留：连接状态、连接开关、当前设备信息、节点列表、错误提示。

**验收标准**：
- [ ] 新用户首次打开 → 登录 → 连接 → 看到节点列表，全程可完成
- [ ] 节点列表显示：名称、在线状态、Tailscale IP、是否当前设备
- [ ] 杀掉 App 重新打开，状态恢复正确
- [ ] Wi-Fi ↔ 蜂窝切换后连接恢复

## 11. 常见错误清单

以下是 Android → iOS 移植中最常见的错误，AI 实现时应主动检查：

| 错误 | 原因 | 正确做法 |
|---|---|---|
| 在 Extension 中使用 `UIApplication.shared` | Extension 无 UIApplication | 使用编译条件 `#if !EXTENSION` |
| 假设 App 和 Extension 共享内存对象 | 双进程隔离 | 用 IPC + App Group |
| Go 回调中直接更新 SwiftUI @State | 不在主线程 | `DispatchQueue.main.async` 或 `@MainActor` |
| Extension 内存超 50 MB 被系统无预警杀死 | 硬限制 | 启用 `NotifyRateLimitNetmaps`，裁剪 Go 编译 `-ldflags="-s -w"` |
| 用 `UserDefaults.standard` 跨进程 | standard 不共享 | `UserDefaults(suiteName: "group.xxx")` |
| 复制 `multitun.go` 的 TUN 重建逻辑 | iOS 支持原地重配 | `setTunnelNetworkSettings` + `reasserting = true` |
| 注册 URL scheme 接收登录回调 | 登录完成走 Notify 而非 URL | 监听 `Notify.LoginFinished` |
| 用 loopback HTTP 调用 LocalAPI | 沙箱限制 + 不必要 | `net.Pipe()` 进程内桥接 |

## 12. 关键风险摘要

| 风险 | 严重度 | 缓解 |
|---|---|---|
| Go backend 在 Extension 内超 50 MB 内存限制 | 高 | Spike 阶段真机内存测试 |
| Go↔Swift 桥接在 Extension 环境不稳定 | 高 | Spike 阶段验证 |
| `setTunnelNetworkSettings` 原地更新不可靠 | 中 | Spike 阶段验证，备选方案为 TUN 重建 |
| Secure Enclave attestation 格式与服务端不兼容 | 中 | MVP 可降级不做 attestation |
| 双进程状态同步不稳导致 UI 滞后 | 中 | Darwin Notification + sendProviderMessage 组合 |

详细风险分析见 spec 第 17 节。
