import NetworkExtension
import os

/// Packet Tunnel Provider — hosts the Go backend and manages the VPN tunnel.
///
/// This runs in a separate process from the main App.
/// Do NOT use UIApplication, shared global state, or any App-process APIs here.
class PacketTunnelProvider: NEPacketTunnelProvider {

    private let logger = Logger(subsystem: "com.tailscale.ipn.ios", category: "tunnel")
    private var notifyHandle: NotificationHandle?

    // MARK: - Lifecycle

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        logger.log("startTunnel: beginning")

        // 1. Determine data directory inside App Group container
        guard let containerURL = sharedContainerURL else {
            let err = NSError(domain: "PacketTunnel", code: 1,
                              userInfo: [NSLocalizedDescriptionKey: "No App Group container"])
            completionHandler(err)
            return
        }

        let dataDir = containerURL.appendingPathComponent("tailscale", isDirectory: true).path
        let directFileRoot = containerURL.appendingPathComponent("taildrop", isDirectory: true).path

        // Ensure directories exist
        do {
            try FileManager.default.createDirectory(atPath: dataDir, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(atPath: directFileRoot, withIntermediateDirectories: true)
        } catch {
            logger.error("startTunnel: failed to create directories: \(error.localizedDescription)")
            let err = NSError(domain: "PacketTunnel", code: 3,
                              userInfo: [NSLocalizedDescriptionKey: "Failed to create data directories: \(error.localizedDescription)"])
            completionHandler(err)
            return
        }

        // 2. Start Go backend
        let started = GoBridge.start(dataDir: dataDir, directFileRoot: directFileRoot, hwAttestation: false)
        if !started {
            logger.error("startTunnel: Go backend failed to start")
            let err = NSError(domain: "PacketTunnel", code: 2,
                              userInfo: [NSLocalizedDescriptionKey: "Go backend start failed"])
            completionHandler(err)
            return
        }
        logger.log("startTunnel: Go backend started")

        // 3. Register tunnel config callback so Go backend can push route/DNS changes
        #if canImport(Libtailscale)
        if let app = GoBridge.application {
            let configCb = GoTunnelConfigCallback { [weak self] configJSON in
                self?.handleTunnelConfigUpdate(configJSON)
            }
            LibtailscaleSetTunnelConfigCallback(app, configCb)
        }
        #endif

        // 4. Subscribe to WatchNotifications to track state
        notifyHandle = GoBridge.watchNotifications(mask: NotifyWatchOpt.defaultMask) { [weak self] data in
            self?.handleNotification(data)
        }
        logger.log("startTunnel: watching notifications")

        // 5. Configure initial tunnel settings
        //    Real settings update from Go backend via TunnelConfigCallback.
        let settings = createInitialTunnelSettings()
        setTunnelNetworkSettings(settings) { [weak self] error in
            if let error = error {
                self?.logger.error("startTunnel: setTunnelNetworkSettings failed: \(error.localizedDescription)")
                completionHandler(error)
                return
            }
            self?.logger.log("startTunnel: tunnel settings applied")
            completionHandler(nil)
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        logger.log("stopTunnel: reason=\(String(describing: reason))")

        // Stop notifications
        if let handle = notifyHandle {
            GoBridge.stopNotifications(handle)
            notifyHandle = nil
        }

        // Write disconnected state to shared defaults
        writeSharedState(ipnState: 3) // IpnState.stopped

        completionHandler()
    }

    // MARK: - Tunnel Config from Go Backend

    /// Called by Go when router.Config or dns.OSConfig changes.
    private func handleTunnelConfigUpdate(_ configJSON: Data) {
        do {
            let config = try JSONDecoder().decode(TunnelConfigFromGo.self, from: configJSON)
            let settings = buildTunnelSettings(from: config)
            updateTunnelSettings(settings)
            logger.log("tunnel config updated: \(config.localAddresses.count) addrs, \(config.routes.count) routes, \(config.dnsServers.count) DNS")
        } catch {
            logger.error("handleTunnelConfigUpdate: decode failed: \(error.localizedDescription)")
            sharedDefaults?.set("Tunnel config error: \(error.localizedDescription)",
                               forKey: IPCConstants.keyLastError)
            postDarwinNotification(IPCConstants.notifyStateChanged)
        }
    }

    /// Build NEPacketTunnelNetworkSettings from Go's TunnelConfig JSON.
    private func buildTunnelSettings(from config: TunnelConfigFromGo) -> NEPacketTunnelNetworkSettings {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "100.100.100.100")

        var ipv4Addrs: [String] = []
        var ipv4Masks: [String] = []
        var ipv6Addrs: [String] = []
        var ipv6PrefixLens: [NSNumber] = []

        // Parse local addresses (CIDR notation)
        for addrStr in config.localAddresses {
            let parts = addrStr.split(separator: "/")
            guard parts.count == 2 else { continue }
            let ip = String(parts[0])
            let prefixLen = Int(parts[1]) ?? 32

            if ip.contains(":") {
                ipv6Addrs.append(ip)
                ipv6PrefixLens.append(NSNumber(value: prefixLen))
            } else {
                ipv4Addrs.append(ip)
                ipv4Masks.append(prefixLenToMask(prefixLen))
            }
        }

        // IPv4
        if !ipv4Addrs.isEmpty {
            let ipv4 = NEIPv4Settings(addresses: ipv4Addrs, subnetMasks: ipv4Masks)
            var routes: [NEIPv4Route] = []
            for routeStr in config.routes {
                if routeStr.contains(":") { continue } // skip IPv6
                let rParts = routeStr.split(separator: "/")
                guard rParts.count == 2 else { continue }
                routes.append(NEIPv4Route(destinationAddress: String(rParts[0]),
                                          subnetMask: prefixLenToMask(Int(rParts[1]) ?? 32)))
            }
            if routes.isEmpty {
                routes = [NEIPv4Route.default()]
            }
            ipv4.includedRoutes = routes
            settings.ipv4Settings = ipv4
        }

        // IPv6
        if !ipv6Addrs.isEmpty {
            let ipv6 = NEIPv6Settings(addresses: ipv6Addrs, networkPrefixLengths: ipv6PrefixLens)
            var routes: [NEIPv6Route] = []
            for routeStr in config.routes {
                if !routeStr.contains(":") { continue } // skip IPv4
                let rParts = routeStr.split(separator: "/")
                guard rParts.count == 2 else { continue }
                routes.append(NEIPv6Route(destinationAddress: String(rParts[0]),
                                          networkPrefixLength: NSNumber(value: Int(rParts[1]) ?? 128)))
            }
            if routes.isEmpty {
                routes = [NEIPv6Route.default()]
            }
            ipv6.includedRoutes = routes
            settings.ipv6Settings = ipv6
        }

        // DNS
        if !config.dnsServers.isEmpty {
            let dns = NEDNSSettings(servers: config.dnsServers)
            if config.dnsDomains.isEmpty {
                dns.matchDomains = [""] // Route all DNS through tunnel
            } else {
                dns.matchDomains = [""] // Still route all DNS for MagicDNS
                dns.searchDomains = config.dnsDomains
            }
            settings.dnsSettings = dns
        }

        settings.mtu = NSNumber(value: config.mtu > 0 ? config.mtu : 1280)

        return settings
    }

    // MARK: - IPC: App → Extension

    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        do {
            let request = try JSONDecoder().decode(IPCRequest.self, from: messageData)

            switch request.command {
            case .callLocalAPI:
                handleLocalAPIRequest(request, completionHandler: completionHandler)

            case .startLoginInteractive:
                handleStartLoginInteractive(completionHandler: completionHandler)
            }
        } catch {
            logger.error("handleAppMessage: decode failed: \(error.localizedDescription)")
            let resp = IPCResponse.failure("Invalid IPC request: \(error.localizedDescription)")
            completionHandler?(try? JSONEncoder().encode(resp))
        }
    }

    // MARK: - IPC Handlers

    private func handleLocalAPIRequest(_ request: IPCRequest, completionHandler: ((Data?) -> Void)?) {
        guard let method = request.method, let endpoint = request.endpoint else {
            let resp = IPCResponse.failure("Missing method or endpoint")
            completionHandler?(try? JSONEncoder().encode(resp))
            return
        }

        let timeout = request.timeoutMillis ?? 30000
        let body: Data? = request.bodyBase64.flatMap { Data(base64Encoded: $0) }

        Task {
            do {
                let apiResp = try await GoBridge.callLocalAPI(
                    timeoutMillis: timeout,
                    method: method,
                    endpoint: endpoint,
                    body: body
                )
                let resp = IPCResponse.success(statusCode: apiResp.statusCode, body: apiResp.body)
                completionHandler?(try? JSONEncoder().encode(resp))
            } catch {
                logger.error("LocalAPI \(method) \(endpoint) failed: \(error.localizedDescription)")
                sharedDefaults?.set(error.localizedDescription, forKey: IPCConstants.keyLastError)
                postDarwinNotification(IPCConstants.notifyStateChanged)
                let resp = IPCResponse.failure(error.localizedDescription)
                completionHandler?(try? JSONEncoder().encode(resp))
            }
        }
    }

    private func handleStartLoginInteractive(completionHandler: ((Data?) -> Void)?) {
        Task {
            do {
                let apiResp = try await GoBridge.callLocalAPI(
                    timeoutMillis: 30000,
                    method: "POST",
                    endpoint: "/localapi/v0/login-interactive"
                )
                let resp = IPCResponse.success(statusCode: apiResp.statusCode, body: apiResp.body)
                completionHandler?(try? JSONEncoder().encode(resp))
            } catch {
                let resp = IPCResponse.failure(error.localizedDescription)
                completionHandler?(try? JSONEncoder().encode(resp))
            }
        }
    }

    // MARK: - Notification Handling

    /// Process ipn.Notify JSON from Go backend.
    /// Writes relevant state to App Group UserDefaults and posts Darwin notification.
    private func handleNotification(_ data: Data) {
        do {
            let notify = try JSONDecoder().decode(IpnNotify.self, from: data)

            if let stateInt = notify.State {
                writeSharedState(ipnState: stateInt)
            }

            if let prefs = notify.Prefs {
                if let prefsData = try? JSONEncoder().encode(prefs) {
                    sharedDefaults?.set(String(data: prefsData, encoding: .utf8), forKey: IPCConstants.keyPrefsJSON)
                }
            }

            if let netMap = notify.NetMap {
                if let netMapData = try? JSONEncoder().encode(netMap) {
                    sharedDefaults?.set(String(data: netMapData, encoding: .utf8), forKey: IPCConstants.keyNetMapJSON)
                }
            }

            if let url = notify.BrowseToURL {
                sharedDefaults?.set(url, forKey: IPCConstants.keyBrowseToURL)
            }

            if notify.LoginFinished != nil {
                sharedDefaults?.set(true, forKey: IPCConstants.keyLoginFinished)
                sharedDefaults?.removeObject(forKey: IPCConstants.keyBrowseToURL)
            }

            if let health = notify.Health {
                if let healthData = try? JSONEncoder().encode(health) {
                    sharedDefaults?.set(String(data: healthData, encoding: .utf8), forKey: IPCConstants.keyHealthJSON)
                }
            }

            // Signal the App to re-read shared state
            postDarwinNotification(IPCConstants.notifyStateChanged)

        } catch {
            logger.error("handleNotification: decode failed: \(error.localizedDescription)")
            sharedDefaults?.set(error.localizedDescription, forKey: IPCConstants.keyLastError)
            postDarwinNotification(IPCConstants.notifyStateChanged)
        }
    }

    // MARK: - Shared State

    private func writeSharedState(ipnState: Int) {
        sharedDefaults?.set(ipnState, forKey: IPCConstants.keyIPNState)
        postDarwinNotification(IPCConstants.notifyStateChanged)
    }

    // MARK: - Tunnel Configuration

    /// Creates initial tunnel network settings before Go provides real config.
    private func createInitialTunnelSettings() -> NEPacketTunnelNetworkSettings {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "100.100.100.100")

        let ipv4 = NEIPv4Settings(addresses: ["100.64.0.1"], subnetMasks: ["255.255.255.255"])
        ipv4.includedRoutes = [NEIPv4Route.default()]
        settings.ipv4Settings = ipv4

        let dns = NEDNSSettings(servers: ["100.100.100.100"])
        dns.matchDomains = [""] // Route all DNS through tunnel
        settings.dnsSettings = dns

        settings.mtu = 1280

        return settings
    }

    /// Re-apply tunnel settings without destroying the TUN device.
    /// iOS supports this via setTunnelNetworkSettings + reasserting flag.
    func updateTunnelSettings(_ settings: NEPacketTunnelNetworkSettings) {
        reasserting = true
        setTunnelNetworkSettings(settings) { [weak self] error in
            self?.reasserting = false
            if let error = error {
                self?.logger.error("updateTunnelSettings failed: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Helpers

    private func prefixLenToMask(_ prefixLen: Int) -> String {
        var mask: UInt32 = 0
        if prefixLen > 0 {
            mask = UInt32.max << (32 - min(prefixLen, 32))
        }
        let b0 = UInt8((mask >> 24) & 0xFF)
        let b1 = UInt8((mask >> 16) & 0xFF)
        let b2 = UInt8((mask >> 8) & 0xFF)
        let b3 = UInt8(mask & 0xFF)
        return "\(b0).\(b1).\(b2).\(b3)"
    }
}

// MARK: - Tunnel Config Model (JSON from Go)

struct TunnelConfigFromGo: Codable {
    let localAddresses: [String]
    let routes: [String]
    let excludeRoutes: [String]?
    let dnsServers: [String]
    let dnsDomains: [String]
    let mtu: Int
}

// MARK: - Go Tunnel Config Callback

#if canImport(Libtailscale)
import Libtailscale

/// Implements Go's TunnelConfigCallback interface.
class GoTunnelConfigCallback: NSObject, LibtailscaleTunnelConfigCallbackProtocol {
    private let handler: (Data) -> Void

    init(_ handler: @escaping (Data) -> Void) {
        self.handler = handler
    }

    func onTunnelConfigUpdate(_ configJSON: Data?) throws {
        guard let configJSON = configJSON else { return }
        handler(configJSON)
    }
}
#endif
