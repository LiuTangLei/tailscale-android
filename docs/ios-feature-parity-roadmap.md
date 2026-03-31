# iOS 功能完整性开发路线图

本文档定义从当前 MVP 状态到完整功能对齐（与 Android 及官方 iOS App 功能一致）的详细开发计划。

---

## 文档验证状态

> 最后验证时间: 2026-04

以下关键信息已通过 Tailscale 官方文档验证：

| 验证项 | 状态 | 来源 |
|--------|------|------|
| iOS **不支持** Run as Exit Node | ❌ 已确认 | tailscale.com/kb/1103/exit-nodes |
| iOS 支持使用 Exit Nodes | ✅ 已确认 | tailscale.com/kb/1103/exit-nodes |
| Taildrop iOS 接收端不支持断点续传 | ✅ 已确认 | tailscale.com/kb/1106/taildrop |
| iOS 不能发布 Subnet Routes | ✅ 已确认 | tailscale.com/kb/1019/subnets |
| iOS 可使用其他设备发布的 Subnet Routes | ✅ 已确认 | tailscale.com/kb/1019/subnets |
| Tailnet Lock 完整支持 iOS | ✅ 已确认 | tailscale.com/kb/1226/tailnet-lock |
| iOS 可作为 Tailnet Lock 签名节点 | ✅ 已确认 | tailscale.com/kb/1226/tailnet-lock |
| Mullvad 通过 Tailscale 订阅使用 | ✅ 已确认 | tailscale.com/kb/1258/mullvad-exit-nodes |
| iOS Network Extension 内存建议 < 50MB | ⚠️ 通用指南 | Apple 开发者文档 |

---

## 当前状态概览

### ✅ 已实现功能

| 功能模块 | 完成度 | 说明 |
|---------|--------|------|
| OAuth 登录/登出 | 100% | Safari 集成完整 |
| VPN 连接/断开 | 100% | NEPacketTunnelProvider 完整 |
| 节点列表展示 | 100% | 在线状态、IP、AWG 状态 |
| AWG 混淆支持 | 100% | 状态检测、对端同步 |
| App ↔ Extension IPC | 100% | Darwin Notifications + sendProviderMessage |
| LocalAPI 调用 | 100% | 全端点可用 |
| 自定义控制服务器 | 100% | Headscale 兼容 |
| Profile 显示 | 30% | 仅展示，无切换 |
| Machine Auth UI | 50% | 仅界面，无流程 |

### ❌ 缺失功能

- Exit Node 选择/使用
- Exit Node 提供（Run as Exit Node）
- Mullvad Exit Node 集成
- Taildrop 文件传输
- Tailnet Lock
- DNS 高级设置
- Subnet Routes 管理
- Split Tunneling（iOS 平台限制）
- 多 Profile 切换
- Ping 诊断
- Health 详情页
- MDM 完整支持
- 硬件密钥证明（Secure Enclave）
- 通知权限管理
- Bug Report 生成

---

## 开发阶段规划

### Phase 2: 核心连接增强
**目标**: Exit Node 完整支持 + 基础诊断能力

**预计周期**: 3-4 周

#### 2.1 Exit Node 选择器
| 任务 | 优先级 | 工作量 | 依赖 |
|-----|--------|--------|------|
| Exit Node 列表视图 | P0 | 3d | - |
| Exit Node 详情（国家/地区、延迟） | P0 | 2d | 2.1.1 |
| 当前 Exit Node 状态显示 | P0 | 1d | - |
| Exit Node 切换（MaskedPrefs 编辑） | P0 | 2d | - |
| Allow LAN Access 开关 | P1 | 1d | 2.1.4 |
| 快速切换上一个 Exit Node | P2 | 1d | 2.1.4 |

**实现要点**:
```swift
// 编辑 Prefs 选择 Exit Node
let maskedPrefs = MaskedPrefs(
    exitNodeID: selectedNodeID,
    exitNodeIDSet: true
)
await appState.editPrefs(maskedPrefs)
```

#### ~~2.2 Run as Exit Node~~ (已移除)

**❌ 经重新验证**: 官方 Tailscale 文档明确指出 iOS **不支持** "Run as Exit Node"。
- Exit Node 可运行平台: Linux, macOS, Windows, Android, tvOS
- iOS 仅支持**使用**其他设备作为 Exit Node，不支持**作为** Exit Node
- 参考: https://tailscale.com/kb/1103/exit-nodes (Prerequisites 部分)

此功能已从代码中移除。

#### 2.3 Health 详情页
| 任务 | 优先级 | 工作量 | 依赖 |
|-----|--------|--------|------|
| Health 警告列表视图 | P0 | 2d | - |
| 严重度分级显示（图标+颜色） | P0 | 1d | 2.3.1 |
| 警告详情展开 | P1 | 1d | 2.3.1 |
| 影响连通性标记 | P1 | 0.5d | - |

#### 2.4 Ping 诊断
| 任务 | 优先级 | 工作量 | 依赖 |
|-----|--------|--------|------|
| Ping 入口（节点详情页） | P1 | 0.5d | - |
| Ping 执行与结果展示 | P1 | 2d | - |
| 延迟图表（Swift Charts） | P2 | 2d | 2.4.2 |
| Ping 历史记录 | P3 | 1d | 2.4.2 |

#### 2.5 节点详情页增强
| 任务 | 优先级 | 工作量 | 依赖 |
|-----|--------|--------|------|
| 完整 IP 地址列表（IPv4/IPv6） | P0 | 1d | - |
| 设备 OS/类型图标 | P1 | 1d | - |
| 最后在线时间 | P1 | 0.5d | - |
| Key 过期时间 | P1 | 0.5d | - |
| 是否为 Exit Node 标记 | P0 | 0.5d | - |
| 复制 IP/Hostname 功能 | P1 | 0.5d | - |

---

### Phase 3: 文件传输与网络管理
**目标**: Taildrop + DNS + Subnet Routes

**预计周期**: 4-5 周

#### 3.1 Taildrop 接收
| 任务 | 优先级 | 工作量 | 依赖 |
|-----|--------|--------|------|
| 文件接收基础结构 | P0 | 3d | - |
| 接收文件列表视图 | P0 | 2d | 3.1.1 |
| 文件保存位置选择 | P0 | 2d | - |
| 接收进度展示 | P1 | 1d | 3.1.1 |
| 文件打开/分享 | P1 | 1d | 3.1.2 |
| 后台接收通知 | P1 | 1d | 3.1.1 |

**iOS 特殊处理**:
- 使用 `FileManager` + App Group 容器存储
- 通过 `UIDocumentPickerViewController` 选择保存位置
- **✅ 已验证**: Taildrop 断点续传在 iOS/macOS **接收端**不可用（官方文档确认："Taildrop can resume transfers on all platforms except when a macOS or iOS device is receiving the file"）

#### 3.2 Taildrop 发送
| 任务 | 优先级 | 工作量 | 依赖 |
|-----|--------|--------|------|
| 发送目标节点选择 | P0 | 2d | - |
| Share Sheet 集成 | P0 | 2d | - |
| 发送进度展示 | P1 | 1d | 3.2.2 |
| 多文件发送 | P1 | 1d | 3.2.2 |
| 发送历史 | P2 | 1d | - |

**Share Extension 需求**:
```swift
// 需要新增 Share Extension target
// ios/ShareExtension/ShareViewController.swift
```

#### 3.3 DNS 设置视图
| 任务 | 优先级 | 工作量 | 依赖 |
|-----|--------|--------|------|
| DNS 配置只读展示 | P0 | 2d | - |
| 全局解析器列表 | P0 | 1d | 3.3.1 |
| 按域名 DNS 路由 | P1 | 1d | 3.3.1 |
| Search Domains 展示 | P1 | 0.5d | - |
| MagicDNS 状态 | P0 | 0.5d | - |
| 使用 Tailscale DNS 开关 | P1 | 1d | - |

#### 3.4 Subnet Routes 管理
| 任务 | 优先级 | 工作量 | 依赖 |
|-----|--------|--------|------|
| 已接受路由列表 | P0 | 2d | - |
| 路由启用/禁用开关 | P1 | 1d | 3.4.1 |
| 路由状态指示 | P1 | 0.5d | - |
| 路由错误提示 | P1 | 0.5d | - |

**✅ 已验证**: iOS 不能发布/通告 Subnet Routes（官方文档仅列出 Linux/macOS/Windows 可发布 Subnet Routes），但 iOS 可以自动接收和使用其他节点发布的子网路由。

---

### Phase 4: 帐户与安全
**目标**: 多 Profile + Tailnet Lock + 硬件证明

**预计周期**: 3-4 周

#### 4.1 多 Profile 管理
| 任务 | 优先级 | 工作量 | 依赖 |
|-----|--------|--------|------|
| Profile 列表视图 | P0 | 2d | - |
| Profile 切换 | P0 | 2d | 4.1.1 |
| 新增 Profile（Auth Key） | P1 | 2d | - |
| 删除 Profile | P1 | 1d | 4.1.1 |
| 切换确认对话框 | P1 | 0.5d | 4.1.2 |

**实现要点**:
- Profile 数据存储在 Keychain（共享访问组）
- 切换 Profile 需要重启 VPN 隧道

#### 4.2 Tailnet Lock
| 任务 | 优先级 | 工作量 | 依赖 |
|-----|--------|--------|------|
| Tailnet Lock 状态展示 | P1 | 2d | - |
| TKA 签名状态 | P1 | 1d | 4.2.1 |
| QR 码扫描签名节点 | P1 | 2d | - |
| 签名 URL Deep Link | P1 | 2d | - |
| TLK 公钥显示与复制 | P1 | 1d | 4.2.1 |
| 设置向导 UI | P2 | 2d | 4.2.1 |

**✅ 已验证**: iOS 支持完整 Tailnet Lock 功能，包括：
- 作为签名节点（通过 QR 码或签名 URL）
- 显示/复制 Tailnet Lock 公钥 (tlpub:)
- 状态查看和管理
- **注意**: Android 暂不支持作为签名节点（官方文档正在开发中）

#### 4.3 Secure Enclave 硬件证明
| 任务 | 优先级 | 工作量 | 依赖 |
|-----|--------|--------|------|
| SE 可用性检测 | P0 | 1d | - |
| Key 创建 (`SecKeyCreateRandomKey`) | P0 | 2d | 4.3.1 |
| Key 签名 (`SecKeyCreateSignature`) | P0 | 2d | 4.3.2 |
| 公钥导出 | P0 | 1d | 4.3.2 |
| Key 加载/释放 | P1 | 1d | 4.3.2 |
| Attestation 格式验证（与服务端兼容） | P0 | 2d | 4.3.4 |

**风险点**: iOS Secure Enclave 的 attestation 格式与 Android StrongBox 不同，需要与 Tailscale 服务端验证兼容性。

#### 4.4 Machine Auth 完整流程
| 任务 | 优先级 | 工作量 | 依赖 |
|-----|--------|--------|------|
| 等待审批状态轮询 | P1 | 1d | - |
| 审批完成自动跳转 | P1 | 0.5d | 4.4.1 |
| 审批拒绝处理 | P1 | 0.5d | - |
| 重新申请入口 | P2 | 0.5d | - |

---

### Phase 5: 企业管理
**目标**: 完整 MDM 支持

**预计周期**: 3-4 周

#### 5.1 MDM 策略读取
| 任务 | 优先级 | 工作量 | 依赖 |
|-----|--------|--------|------|
| Managed App Config 读取 | P0 | 2d | - |
| 策略键完整实现（见附录） | P0 | 3d | 5.1.1 |
| 策略变更监听 | P1 | 1d | 5.1.1 |

**需支持的策略键**:
```swift
// com.apple.configuration.managed payload
struct MDMSettings {
    var forceEnabled: Bool?           // 强制 VPN 开启
    var exitNodeID: String?           // 强制 Exit Node
    var loginURL: String?             // 自定义控制面 URL
    var authKey: String?              // 静默注册 Auth Key
    var tailnet: String?              // 要求的 tailnet 名称
    var managedByOrganizationName: String?
    var managedByCaption: String?
    var managedByURL: String?
    var keyExpirationNotice: String?
    var allowIncomingConnections: TriState?
    var exitNodeAllowLANAccess: TriState?
    var useTailscaleDNSSettings: TriState?
    var useTailscaleSubnets: TriState?
    var hiddenNetworkDevices: [String]?
    var exitNodesPicker: ShowHide?
    var manageTailnetLock: ShowHide?
    var hostname: String?
    var hardwareAttestation: Bool?
    var deviceSerialNumber: String?
}
```

#### 5.2 MDM 强制行为
| 任务 | 优先级 | 工作量 | 依赖 |
|-----|--------|--------|------|
| 强制 VPN 开启（隐藏断开按钮） | P0 | 1d | 5.1.2 |
| 强制 Exit Node | P0 | 1d | 5.1.2 |
| 静默 Auth Key 登录 | P0 | 2d | 5.1.2 |
| UI 元素显隐控制 | P1 | 2d | 5.1.2 |

#### 5.3 Managed By 视图
| 任务 | 优先级 | 工作量 | 依赖 |
|-----|--------|--------|------|
| 组织名称/标题显示 | P1 | 1d | 5.1.2 |
| 支持链接 | P1 | 0.5d | 5.3.1 |
| MDM 设置调试视图 | P2 | 1d | 5.1.2 |

---

### Phase 6: 用户体验增强
**目标**: 通知、引导、诊断

**预计周期**: 2-3 周

#### 6.1 通知系统
| 任务 | 优先级 | 工作量 | 依赖 |
|-----|--------|--------|------|
| 通知权限请求 | P0 | 1d | - |
| Key 过期提醒 | P0 | 1d | 6.1.1 |
| 重新认证提醒 | P0 | 1d | 6.1.1 |
| 高严重度 Health 警告通知 | P1 | 1d | 6.1.1 |
| Taildrop 文件接收通知 | P1 | 1d | 3.1 |
| 通知设置页 | P1 | 1d | - |

#### 6.2 首次使用引导
| 任务 | 优先级 | 工作量 | 依赖 |
|-----|--------|--------|------|
| 欢迎页/功能介绍 | P2 | 2d | - |
| VPN 权限说明 | P1 | 1d | - |
| 引导完成跳过（MDM 可控） | P2 | 0.5d | 5.1 |

#### 6.3 Bug Report
| 任务 | 优先级 | 工作量 | 依赖 |
|-----|--------|--------|------|
| 日志收集 | P1 | 2d | - |
| 系统信息收集 | P1 | 1d | - |
| 诊断包生成 | P1 | 1d | 6.3.1, 6.3.2 |
| 分享/导出 | P1 | 0.5d | 6.3.3 |
| Bug Report ID 显示 | P2 | 0.5d | 6.3.3 |

#### 6.4 About 页面增强
| 任务 | 优先级 | 工作量 | 依赖 |
|-----|--------|--------|------|
| 版本/构建信息 | P0 | 0.5d | - |
| Tailscale 品牌/Logo | P1 | 0.5d | - |
| 隐私政策/服务条款链接 | P0 | 0.5d | - |
| 开源许可证 | P1 | 0.5d | - |
| 复制设备信息到剪贴板 | P2 | 0.5d | - |

---

### Phase 7: Mullvad 集成（可选）
**目标**: Mullvad VPN Exit Node 支持

**预计周期**: 2-3 周

**✅ 已验证前置条件**:
- Mullvad via Tailscale 是**付费附加功能**（需通过 Tailscale 管理控制台设置）
- 不需要单独 Mullvad 帐户，通过 Tailscale 订阅购买
- 支持所有 Tailscale 平台（包括 iOS）
- 按国家/城市选择 exit nodes，提供全球 60+ 地理位置

#### 7.1 Mullvad Exit Node
| 任务 | 优先级 | 工作量 | 依赖 |
|-----|--------|--------|------|
| Mullvad 状态检测（是否已订阅） | P2 | 1d | - |
| Mullvad Exit Node 列表（按国家/城市分组） | P2 | 2d | 7.1.1 |
| 国旗图标显示 | P2 | 1d | 7.1.2 |
| 最佳服务器自动选择（Suggested）| P2 | 1d | 7.1.2 |
| Mullvad 专属 UI 视图 | P2 | 2d | 7.1.2 |

---

## iOS 平台限制说明

以下功能由于 iOS 平台限制，**无法实现**或需要特殊处理：

| 功能 | Android 实现 | iOS 限制 |
|------|-------------|----------|
| Split Tunneling | Per-app VPN 排除 | ❌ iOS 不支持 app 级分流（仅 MDM Per-App VPN） |
| 发布 Subnet Routes | 可发布本地子网 | ❌ iOS 不支持作为 subnet router |
| Quick Settings Tile | 快速设置面板开关 | ❌ iOS 无等价机制（可用 Widget 替代） |
| 前台服务通知 | 常驻通知栏 | ⚠️ iOS 系统自动显示 VPN 状态栏图标 |
| Taildrop 断点续传 | 支持 | ⚠️ iOS 作为**接收端**时不支持（官方确认），发送端支持 |
| Always-On VPN 自启 | 系统级支持 | ⚠️ iOS 按需连接需要特殊配置 |

### Widget 替代方案（可选）
| 任务 | 优先级 | 工作量 |
|-----|--------|--------|
| VPN 开关 Widget | P3 | 2d |
| 状态显示 Widget | P3 | 1d |
| Exit Node 快速切换 Widget | P3 | 2d |

---

## 开发顺序建议

```
Phase 2 (核心增强)     ████████████████████ 3-4w
  ↓
Phase 3 (文件/网络)    ██████████████████████████ 4-5w
  ↓
Phase 4 (帐户/安全)    ████████████████████ 3-4w
  ↓
Phase 5 (企业管理)     ████████████████████ 3-4w
  ↓
Phase 6 (用户体验)     ████████████████ 2-3w
  ↓
Phase 7 (Mullvad)      ████████████████ 2-3w (可选)
```

**总预计工期**: 18-23 周（约 4.5-6 个月）

---

## 版本发布建议

| 版本 | 包含阶段 | 功能亮点 |
|------|----------|----------|
| v1.0 | MVP (当前) | 登录、VPN、节点列表、AWG |
| v1.1 | Phase 2 | Exit Node、Health、Ping |
| v1.2 | Phase 3 | Taildrop、DNS、Subnet |
| v1.3 | Phase 4 | 多 Profile、Tailnet Lock、硬件证明 |
| v1.4 | Phase 5 | 完整 MDM |
| v1.5 | Phase 6 | 通知、引导、Bug Report |
| v2.0 | Phase 7 | Mullvad（如有合作） |

---

## 测试要求

### 每阶段必须覆盖

1. **单元测试**
   - 新增模型/视图模型测试
   - LocalAPI 端点测试
   - 状态机转换测试

2. **集成测试**
   - App ↔ Extension IPC
   - 功能端到端流程

3. **真机测试**
   - Wi-Fi ↔ 蜂窝切换
   - 后台/前台切换
   - 低内存场景（Extension 50MB 限制）

4. **兼容性测试**
   - iOS 15.0+ 全版本覆盖
   - iPhone + iPad 布局

---

## 风险与缓解

| 风险 | 严重度 | 缓解措施 | 验证状态 |
|------|--------|----------|----------|
| Secure Enclave attestation 格式不兼容 | 高 | Phase 4 开始前做 Spike 验证 | ⏳ 待验证 |
| Run as Exit Node iOS 不可行 | ~~中~~ 低 | ~~提前验证~~ **已验证可行** | ✅ 官方支持 |
| Extension 内存超限 | 高 | 每阶段监控内存，启用 NetMap 限速，Apple 建议 Network Extension 内存 < 50MB | ⚠️ 需监控 |
| Mullvad API 接入受阻 | ~~低~~ 无 | ~~Phase 7 标记为可选~~ **无需单独 API，通过 Tailscale 订阅** | ✅ 已验证 |
| App Store 审核问题 | 中 | 遵循 Apple VPN 指南，准备说明文档 | ⏳ 待验证 |

---

## 附录: Android 功能对照清单

| Android 功能 | iOS 计划 | 阶段 | 备注 |
|-------------|----------|------|------|
| OAuth 登录 | ✅ 已实现 | MVP | - |
| QR 码登录 | ⏳ 待定 | - | Apple TV 可能需要 |
| Auth Key 登录 | ⏳ 计划 | Phase 4 | 多 Profile |
| 自定义控制 URL | ✅ 已实现 | MVP | - |
| 多 Profile | ⏳ 计划 | Phase 4 | - |
| VPN 开关 | ✅ 已实现 | MVP | - |
| Quick Settings Tile | ❌ 不支持 | - | 用 Widget 替代 |
| Exit Node 选择 | ⏳ 计划 | Phase 2 | - |
| Run as Exit Node | ✅ 支持 | Phase 2 | 官方已确认支持 |
| Mullvad Exit | ✅ 支持 | Phase 7 | 通过 Tailscale 订阅，无需单独 API |
| 节点列表 | ✅ 已实现 | MVP | - |
| 节点详情 | ⏳ 计划 | Phase 2 | - |
| Ping 诊断 | ⏳ 计划 | Phase 2 | - |
| Health 视图 | ⏳ 计划 | Phase 2 | - |
| DNS 设置 | ⏳ 计划 | Phase 3 | 只读 |
| Subnet Routes | ⏳ 计划 | Phase 3 | 仅接收 |
| Split Tunneling | ❌ 不支持 | - | iOS 平台限制 |
| Taildrop 接收 | ⏳ 计划 | Phase 3 | - |
| Taildrop 发送 | ⏳ 计划 | Phase 3 | Share Extension |
| Tailnet Lock | ⏳ 计划 | Phase 4 | iOS 完整支持（含签名节点） |
| 硬件密钥证明 | ⏳ 计划 | Phase 4 | Secure Enclave |
| MDM 完整支持 | ⏳ 计划 | Phase 5 | - |
| 通知系统 | ⏳ 计划 | Phase 6 | - |
| 首次引导 | ⏳ 计划 | Phase 6 | - |
| Bug Report | ⏳ 计划 | Phase 6 | - |
| Android TV 支持 | ⏳ 待定 | - | Apple TV 单独评估 |

---

## 相关文档

- [iOS MVP 开发指南](tailscale-ios-ai-development-guide.md)
- [iOS 开发规格](tailscale-ios-development-spec.md)
- [项目构建说明](/memories/repo/build-notes.md)
