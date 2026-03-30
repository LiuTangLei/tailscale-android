import Foundation

/// App-wide state container driven by ipn.Notify events.
///
/// In the dual-process architecture:
/// - Extension receives Notify from Go backend → writes to App Group UserDefaults
/// - Extension posts Darwin notification ("state changed")
/// - App's AppState observes Darwin notification → reads from App Group UserDefaults
///
/// This replaces Android's in-process global state pattern.
/// All updates must happen on @MainActor since SwiftUI observes this.
@MainActor
class AppState: ObservableObject {
    // MARK: - Published State

    @Published var ipnState: IpnState = .noState
    @Published var currentProfile: LoginProfile?
    @Published var prefs: IpnPrefs?
    @Published var selfNode: PeerNode?
    @Published var peers: [PeerNode] = []
    @Published var health: HealthState?
    @Published var lastError: String?
    @Published var isLoggingIn: Bool = false
    @Published var browseToURL: String?

    // MARK: - AWG State

    /// Per-peer AWG config status: normalizedHostname → hasAwgConfig
    @Published var awgPeersStatus: [String: Bool] = [:]
    /// Per-peer AWG config data: normalizedHostname → AwgPeerResult
    @Published var awgPeersData: [String: AwgPeerResult] = [:]
    /// Whether the local machine has non-default AWG config
    @Published var localAwgStatus: Bool = false
    /// Toast-style status message for AWG operations
    @Published var awgStatusMessage: String?
    /// Hostname of peer currently being synced (nil if no sync in progress)
    @Published var awgSyncInProgress: String?
    /// Whether AWG peers have been loaded (prevent duplicate requests)
    private var awgPeersLoaded = false

    /// Reference to VPNManager for IPC. Set by TailscaleApp at launch.
    weak var vpnManager: VPNManager?

    var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.0.1"
    }

    // MARK: - Initialization

    init() {
        // Load initial state from App Group
        loadSharedState()

        // Observe Darwin notifications from Extension
        observeDarwinNotification(IPCConstants.notifyStateChanged) { [weak self] in
            Task { @MainActor in
                self?.loadSharedState()
            }
        }
    }

    // MARK: - Shared State Reading (from App Group UserDefaults)

    /// Read state written by the Packet Tunnel Extension.
    func loadSharedState() {
        guard let defaults = sharedDefaults else { return }

        // ipn.State
        let stateRaw = defaults.integer(forKey: IPCConstants.keyIPNState)
        if let state = IpnState(rawValue: stateRaw) {
            ipnState = state
        }

        // Prefs
        if let prefsStr = defaults.string(forKey: IPCConstants.keyPrefsJSON),
           let prefsData = prefsStr.data(using: .utf8) {
            prefs = try? JSONDecoder().decode(IpnPrefs.self, from: prefsData)
        }

        // NetMap
        if let netMapStr = defaults.string(forKey: IPCConstants.keyNetMapJSON),
           let netMapData = netMapStr.data(using: .utf8) {
            if let netMap = try? JSONDecoder().decode(NetworkMap.self, from: netMapData) {
                updatePeers(from: netMap)
            }
        }

        // BrowseToURL (login)
        let newBrowseURL = defaults.string(forKey: IPCConstants.keyBrowseToURL)
        if newBrowseURL != browseToURL {
            browseToURL = newBrowseURL
        }

        // LoginFinished
        if defaults.bool(forKey: IPCConstants.keyLoginFinished) {
            isLoggingIn = false
            browseToURL = nil
            defaults.removeObject(forKey: IPCConstants.keyLoginFinished)
            fetchCurrentProfile()
        }

        // Health
        if let healthStr = defaults.string(forKey: IPCConstants.keyHealthJSON),
           let healthData = healthStr.data(using: .utf8) {
            health = try? JSONDecoder().decode(HealthState.self, from: healthData)
        }

        // Last error
        lastError = defaults.string(forKey: IPCConstants.keyLastError)
    }

    // MARK: - Notify Processing (direct, for Extension-side use)

    /// Process an ipn.Notify JSON payload from Go backend.
    func handleNotify(_ data: Data) {
        do {
            let notify = try JSONDecoder().decode(IpnNotify.self, from: data)
            applyNotify(notify)
        } catch {
            lastError = "Failed to decode notification: \(error.localizedDescription)"
        }
    }

    private func applyNotify(_ notify: IpnNotify) {
        if let stateInt = notify.State, let state = IpnState(rawValue: stateInt) {
            ipnState = state
        }

        if let prefs = notify.Prefs {
            self.prefs = prefs
        }

        if let netMap = notify.NetMap {
            updatePeers(from: netMap)
        }

        if let url = notify.BrowseToURL {
            browseToURL = url
        }

        if notify.LoginFinished != nil {
            isLoggingIn = false
            browseToURL = nil
            fetchCurrentProfile()
        }

        if let health = notify.Health {
            self.health = health
        }
    }

    private func updatePeers(from netMap: NetworkMap) {
        var allPeers: [PeerNode] = []

        // Self node
        if let selfData = netMap.SelfNode {
            let userProfile = selfData.UserID.flatMap { uid in
                netMap.UserProfiles?[String(uid)]
            }
            let self_ = PeerNode(from: selfData, isSelf: true, userProfile: userProfile)
            selfNode = self_
            allPeers.append(self_)
        }

        // Peer nodes
        if let peerNodes = netMap.Peers {
            for peerData in peerNodes {
                let userProfile = peerData.UserID.flatMap { uid in
                    netMap.UserProfiles?[String(uid)]
                }
                allPeers.append(PeerNode(from: peerData, isSelf: false, userProfile: userProfile))
            }
        }

        peers = allPeers
    }

    // MARK: - User Actions (via VPNManager IPC)

    /// Start interactive login flow.
    /// 1. Ensure Extension is running (connect VPN if needed)
    /// 2. If controlURL is set, PATCH prefs to use custom server
    /// 3. Send startLoginInteractive IPC
    /// 4. Extension sends BrowseToURL via Notify → App opens browser
    func startLogin(controlURL: String = "") {
        isLoggingIn = true
        lastError = nil

        guard let vpn = vpnManager else {
            lastError = "VPN manager not available"
            isLoggingIn = false
            return
        }

        Task {
            // Ensure tunnel is started so Go backend is running
            vpn.connect()

            // Wait for tunnel to connect (up to 10 seconds)
            var connected = false
            for _ in 0..<100 {
                try? await Task.sleep(nanoseconds: 100_000_000) // 100ms
                if vpn.vpnStatus == .connected {
                    connected = true
                    break
                }
            }

            guard connected else {
                lastError = "VPN connection timed out"
                isLoggingIn = false
                return
            }

            // If a custom control URL is provided, set it before login
            if !controlURL.isEmpty {
                do {
                    let prefs = MaskedPrefs.setControlURL(controlURL)
                    let body = try JSONEncoder().encode(prefs)
                    let _ = try await vpn.callLocalAPI(method: "PATCH", endpoint: "/localapi/v0/prefs", body: body)
                } catch {
                    lastError = "Failed to set control server: \(error.localizedDescription)"
                    isLoggingIn = false
                    return
                }
            }

            do {
                let _ = try await vpn.startLoginInteractive()
            } catch {
                lastError = "Login request failed: \(error.localizedDescription)"
                isLoggingIn = false
            }
        }
    }

    /// Log out and clear all state.
    func logout() {
        if let vpn = vpnManager {
            Task {
                do {
                    let _ = try await vpn.callLocalAPI(method: "POST", endpoint: "/localapi/v0/logout")
                } catch {
                    lastError = "Logout failed: \(error.localizedDescription)"
                }
            }
        }

        ipnState = .needsLogin
        currentProfile = nil
        prefs = nil
        selfNode = nil
        peers = []
        browseToURL = nil
        isLoggingIn = false
    }

    /// Toggle VPN on/off via prefs edit.
    func setWantRunning(_ wantRunning: Bool) {
        guard let vpn = vpnManager else { return }

        if wantRunning {
            vpn.connect()
        } else {
            vpn.disconnect()
        }

        // Also tell Go backend about the preference change
        Task {
            do {
                let prefs = MaskedPrefs.setWantRunning(wantRunning)
                let body = try JSONEncoder().encode(prefs)
                let _ = try await vpn.callLocalAPI(method: "PATCH", endpoint: "/localapi/v0/prefs", body: body)
            } catch {
                lastError = "Failed to update preferences: \(error.localizedDescription)"
            }
        }
    }

    /// Fetch the current login profile from the backend.
    func fetchCurrentProfile() {
        guard let vpn = vpnManager else { return }

        Task {
            do {
                let resp = try await vpn.callLocalAPI(method: "GET", endpoint: "/localapi/v0/profiles/current")
                if resp.statusCode == 200,
                   let bodyB64 = resp.bodyBase64,
                   let bodyData = Data(base64Encoded: bodyB64) {
                    let profile = try JSONDecoder().decode(LoginProfile.self, from: bodyData)
                    currentProfile = profile
                }
            } catch {
                // Profile fetch is best-effort; don't show error to user
            }
        }
    }

    // MARK: - AWG Sync

    /// Load AWG config status for all peers via awg-sync-peers endpoint.
    func loadAwgPeersStatus() {
        guard let vpn = vpnManager else { return }

        Task {
            do {
                let resp = try await vpn.callLocalAPI(method: "GET", endpoint: "/localapi/v0/awg-sync-peers")
                guard resp.statusCode == 200,
                      let bodyB64 = resp.bodyBase64,
                      let bodyData = Data(base64Encoded: bodyB64) else {
                    awgStatusMessage = "Failed to get AWG peer info"
                    return
                }

                let awgPeers = try JSONDecoder().decode([AwgPeerResult].self, from: bodyData)

                var statusMap: [String: Bool] = [:]
                var dataMap: [String: AwgPeerResult] = [:]

                for peer in awgPeers {
                    for key in peerKeyCandidates(peer.hostname) {
                        statusMap[key] = peer.hasAwgConfig
                        if dataMap[key] == nil {
                            dataMap[key] = peer
                        }
                    }
                }

                awgPeersStatus = statusMap
                awgPeersData = dataMap

                let awgCount = awgPeers.filter(\.hasAwgConfig).count
                let total = awgPeers.count
                if total > 0 {
                    awgStatusMessage = awgCount > 0
                        ? "Found \(awgCount)/\(total) peers with AWG config"
                        : "Checked \(total) peers, no AWG config found"
                } else {
                    awgStatusMessage = "No peers found"
                }
            } catch {
                awgStatusMessage = "Failed to get AWG config info: \(error.localizedDescription)"
            }
        }
    }

    /// Load local machine AWG configuration status from prefs.
    func loadLocalAwgStatus() {
        guard let vpn = vpnManager else { return }

        Task {
            do {
                let resp = try await vpn.callLocalAPI(method: "GET", endpoint: "/localapi/v0/prefs")
                guard resp.statusCode == 200,
                      let bodyB64 = resp.bodyBase64,
                      let bodyData = Data(base64Encoded: bodyB64) else {
                    localAwgStatus = false
                    return
                }

                let prefs = try JSONDecoder().decode(LocalPrefs.self, from: bodyData)
                localAwgStatus = prefs.AmneziaWG?.hasNonDefaultValues == true
            } catch {
                localAwgStatus = false
            }
        }
    }

    /// Load AWG status once per session when the network map is available.
    func loadAwgStatusIfNeeded() {
        guard !awgPeersLoaded else { return }
        awgPeersLoaded = true
        loadAwgPeersStatus()
        loadLocalAwgStatus()
    }

    /// Sync AWG config from a remote peer to the local machine.
    func syncAwgConfigFromPeer(_ peer: PeerNode, timeout: Int = 10) {
        guard let vpn = vpnManager else { return }

        let hostname = peer.displayName

        // Verify peer has AWG config
        let normalizedKey = peer.normalizedHostname
        let peerData = peerKeyCandidates(hostname).lazy.compactMap({ self.awgPeersData[$0] }).first

        guard let peerData = peerData, peerData.hasAwgConfig else {
            awgStatusMessage = "Peer \(hostname) has no AWG config"
            return
        }

        // Find full node key from peer data or PeerNode
        let fullNodeKey: String? = peer.nodeKey ?? {
            // Fallback: search peers list for matching hostname
            let targetKey = normalizedKey
            return peers.first(where: {
                $0.normalizedHostname == targetKey
            })?.nodeKey
        }()

        guard let nodeKey = fullNodeKey, !nodeKey.isEmpty else {
            awgStatusMessage = "Cannot find nodeKey for peer \(hostname)"
            return
        }

        awgSyncInProgress = hostname

        Task {
            do {
                let request = AwgSyncApplyRequest(nodeKey: nodeKey, timeout: timeout)
                let body = try JSONEncoder().encode(request)
                let resp = try await vpn.callLocalAPI(method: "POST", endpoint: "/localapi/v0/awg-sync-apply", body: body)

                awgSyncInProgress = nil

                guard resp.statusCode == 200,
                      let bodyB64 = resp.bodyBase64,
                      let bodyData = Data(base64Encoded: bodyB64) else {
                    let errMsg = resp.error ?? "Unknown error (status \(resp.statusCode))"
                    awgStatusMessage = parseAwgApplyError(errMsg, hostname: hostname)
                    return
                }

                let _ = try JSONDecoder().decode(AmneziaWGPrefs.self, from: bodyData)
                awgStatusMessage = "AWG config from \(hostname) applied successfully"
                autoReconnectForAwgConfig()
            } catch {
                awgSyncInProgress = nil
                awgStatusMessage = parseAwgApplyError(error.localizedDescription, hostname: hostname)
            }
        }
    }

    func clearAwgStatusMessage() {
        awgStatusMessage = nil
    }

    // MARK: - AWG Helpers

    private func autoReconnectForAwgConfig() {
        guard let vpn = vpnManager else { return }
        Task {
            vpn.disconnect()
            try? await Task.sleep(nanoseconds: 2_000_000_000) // 2 seconds
            vpn.connect()
        }
    }

    private func peerKeyCandidates(_ value: String) -> [String] {
        let trimmed = value.trimmingCharacters(in: .whitespaces)
            .trimmingCharacters(in: CharacterSet(charactersIn: "."))
        let short = trimmed.components(separatedBy: ".").first ?? trimmed
        return Array(Set([
            trimmed,
            trimmed.lowercased(),
            short,
            short.lowercased(),
        ]))
    }

    private func parseAwgApplyError(_ message: String, hostname: String) -> String {
        if message.contains("405") || message.contains("only POST allowed") {
            return "Request method error"
        } else if message.contains("403") || message.contains("access denied") {
            return "Access denied"
        } else if message.contains("404") || message.contains("peer not found") {
            return "Peer \(hostname) not found or offline"
        } else if message.contains("409") || message.contains("no Amnezia-WG config") {
            return "Peer \(hostname) has no AWG config"
        } else if message.contains("500") {
            if message.contains("no netmap available") {
                return "Network map unavailable"
            } else if message.contains("failed to fetch config") {
                return "Cannot fetch config from peer"
            } else if message.contains("failed to apply config") {
                return "Config apply failed"
            }
            return "Server error: \(message)"
        } else if message.contains("timeout") || message.contains("Timeout") {
            return "Operation timeout, please retry"
        }
        return "AWG config apply failed: \(message)"
    }

    // MARK: - Exit Node

    /// Whether this device is configured to run as an exit node.
    var isRunningAsExitNode: Bool {
        // This would be determined by the backend prefs
        // For now, return false as we need to check AdvertiseRoutes
        return false
    }

    /// Set the exit node to use for routing traffic.
    func setExitNode(_ peer: PeerNode) {
        guard let vpn = vpnManager else {
            lastError = "VPN manager not available"
            return
        }

        Task {
            do {
                var maskedPrefs = MaskedPrefs()
                maskedPrefs.ExitNodeID = peer.id
                maskedPrefs.ExitNodeIDSet = true
                let body = try JSONEncoder().encode(maskedPrefs)
                let _ = try await vpn.callLocalAPI(method: "PATCH", endpoint: "/localapi/v0/prefs", body: body)
            } catch {
                lastError = "Failed to set exit node: \(error.localizedDescription)"
            }
        }
    }

    /// Clear the current exit node (stop using any exit node).
    func clearExitNode() {
        guard let vpn = vpnManager else {
            lastError = "VPN manager not available"
            return
        }

        Task {
            do {
                var maskedPrefs = MaskedPrefs()
                maskedPrefs.ExitNodeID = ""
                maskedPrefs.ExitNodeIDSet = true
                let body = try JSONEncoder().encode(maskedPrefs)
                let _ = try await vpn.callLocalAPI(method: "PATCH", endpoint: "/localapi/v0/prefs", body: body)
            } catch {
                lastError = "Failed to clear exit node: \(error.localizedDescription)"
            }
        }
    }

    /// Set allow LAN access when using exit node.
    func setExitNodeAllowLANAccess(_ allow: Bool) {
        guard let vpn = vpnManager else {
            lastError = "VPN manager not available"
            return
        }

        Task {
            do {
                var maskedPrefs = MaskedPrefs()
                maskedPrefs.ExitNodeAllowLANAccess = allow
                maskedPrefs.ExitNodeAllowLANAccessSet = true
                let body = try JSONEncoder().encode(maskedPrefs)
                let _ = try await vpn.callLocalAPI(method: "PATCH", endpoint: "/localapi/v0/prefs", body: body)
            } catch {
                lastError = "Failed to update LAN access setting: \(error.localizedDescription)"
            }
        }
    }

    /// Configure this device to run as an exit node.
    func setRunAsExitNode(_ enabled: Bool) {
        guard let vpn = vpnManager else {
            lastError = "VPN manager not available"
            return
        }

        Task {
            do {
                // Use the advertise-exit-node endpoint
                let endpoint = enabled
                    ? "/localapi/v0/set-advertise-exit-node?enabled=true"
                    : "/localapi/v0/set-advertise-exit-node?enabled=false"
                let _ = try await vpn.callLocalAPI(method: "POST", endpoint: endpoint)
            } catch {
                lastError = "Failed to \(enabled ? "enable" : "disable") exit node: \(error.localizedDescription)"
            }
        }
    }
}
