import Foundation
import NetworkExtension

/// Manages the VPN tunnel connection via NEVPNManager.
///
/// This is the main App's interface to control the Packet Tunnel Extension.
/// The Extension runs in a separate process — communication uses:
/// - NEVPNManager for start/stop
/// - NETunnelProviderSession.sendProviderMessage for IPC
/// - App Group UserDefaults for shared state
/// - Darwin Notifications for change signals
@MainActor
class VPNManager: ObservableObject {
    @Published var vpnStatus: NEVPNStatus = .invalid

    private var manager: NETunnelProviderManager?
    private var statusObserver: NSObjectProtocol?

    init() {
        Task {
            await loadManager()
        }
    }

    deinit {
        if let observer = statusObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    // MARK: - Manager Lifecycle

    /// Load or create the VPN configuration.
    func loadManager() async {
        do {
            let managers = try await NETunnelProviderManager.loadAllFromPreferences()
            if let existing = managers.first {
                manager = existing
            } else {
                manager = createManager()
            }
            observeStatus()
            vpnStatus = manager?.connection.status ?? .invalid
        } catch {
            NSLog("Failed to load VPN managers: \(error)")
        }
    }

    private func createManager() -> NETunnelProviderManager {
        let manager = NETunnelProviderManager()
        manager.localizedDescription = "Tailscale"

        let proto = NETunnelProviderProtocol()
        proto.providerBundleIdentifier = "com.tailscale.ipn.ios.network-extension"
        proto.serverAddress = "Tailscale"
        manager.protocolConfiguration = proto

        manager.isEnabled = true
        return manager
    }

    /// Save VPN configuration. This will trigger the system VPN permission dialog on first use.
    func installVPNConfiguration() async throws {
        guard let manager = manager else {
            throw VPNError.noManager
        }
        try await manager.saveToPreferences()
        try await manager.loadFromPreferences()
        observeStatus()
    }

    // MARK: - Connect / Disconnect

    func connect() {
        Task {
            do {
                if manager == nil || vpnStatus == .invalid {
                    await loadManager()
                }
                try await installVPNConfiguration()
                try manager?.connection.startVPNTunnel()
            } catch {
                NSLog("Failed to start VPN: \(error)")
            }
        }
    }

    func disconnect() {
        manager?.connection.stopVPNTunnel()
    }

    // MARK: - IPC: App → Extension

    /// Send a raw message to the Packet Tunnel Extension and receive a response.
    func sendMessage(_ data: Data) async throws -> Data? {
        guard let session = manager?.connection as? NETunnelProviderSession else {
            throw VPNError.noSession
        }
        return try await withCheckedThrowingContinuation { continuation in
            do {
                try session.sendProviderMessage(data) { response in
                    continuation.resume(returning: response)
                }
            } catch {
                continuation.resume(throwing: error)
            }
        }
    }

    /// Send an IPC request to the Extension and decode the response.
    func sendIPCRequest(_ request: IPCRequest) async throws -> IPCResponse {
        let requestData = try JSONEncoder().encode(request)
        guard let responseData = try await sendMessage(requestData) else {
            return IPCResponse.failure("No response from Extension")
        }
        return try JSONDecoder().decode(IPCResponse.self, from: responseData)
    }

    /// Call a LocalAPI endpoint through the Extension.
    func callLocalAPI(method: String, endpoint: String, body: Data? = nil, timeout: Int = 30000) async throws -> IPCResponse {
        let request = IPCRequest(
            command: .callLocalAPI,
            method: method,
            endpoint: endpoint,
            bodyBase64: body?.base64EncodedString(),
            timeoutMillis: timeout
        )
        return try await sendIPCRequest(request)
    }

    /// Trigger interactive login via the Extension.
    func startLoginInteractive() async throws -> IPCResponse {
        let request = IPCRequest(command: .startLoginInteractive)
        return try await sendIPCRequest(request)
    }

    // MARK: - Status Observation

    private func observeStatus() {
        if let observer = statusObserver {
            NotificationCenter.default.removeObserver(observer)
        }
        statusObserver = NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange,
            object: manager?.connection,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.vpnStatus = self?.manager?.connection.status ?? .invalid
            }
        }
    }
}

enum VPNError: Error, LocalizedError {
    case noSession
    case sendFailed
    case noManager

    var errorDescription: String? {
        switch self {
        case .noSession: return "No active VPN session"
        case .sendFailed: return "Failed to send message to Extension"
        case .noManager: return "VPN manager not configured"
        }
    }
}
