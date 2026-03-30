import Foundation

// MARK: - GoBridge: Swift ↔ Go Backend Bridge
//
// When the Libtailscale.xcframework is built (via ios/build_go.sh),
// the real Go backend is used. Otherwise, stubs are compiled so the
// project still builds for UI development.

#if canImport(Libtailscale)
import Libtailscale

enum GoBridge {
    /// The running Go Application instance, set after start().
    private(set) static var application: (any LibtailscaleApplicationProtocol)?
    /// Retain the AppContext so it's not deallocated while Go holds a reference.
    private static var appContext: GoAppContext?

    /// Start the Go backend. Must be called from the Extension process.
    ///
    /// - Parameters:
    ///   - dataDir: Writable directory for backend state.
    ///   - directFileRoot: Directory for Taildrop files (can be empty for MVP).
    ///   - hwAttestation: Whether hardware attestation is enabled.
    /// - Returns: true if the backend started successfully.
    static func start(dataDir: String, directFileRoot: String, hwAttestation: Bool) -> Bool {
        let appCtx = GoAppContext()
        let app = LibtailscaleStart(dataDir, directFileRoot, hwAttestation, appCtx)
        if app != nil {
            application = app
            appContext = appCtx
            return true
        }
        return false
    }

    /// Subscribe to ipn.Notify events from the Go backend.
    ///
    /// - Parameters:
    ///   - mask: Bitmask of NotifyWatchOpt values.
    ///   - callback: Called with JSON-serialized ipn.Notify on each event.
    /// - Returns: A handle to stop the subscription; nil if backend not started.
    static func watchNotifications(mask: Int, callback: @escaping (Data) -> Void) -> NotificationHandle? {
        guard let app = application else { return nil }
        let cb = GoNotificationCallback(callback)
        let manager = app.watchNotifications(mask, cb: cb)
        let handle = NotificationHandle()
        handle.goManager = manager
        return handle
    }

    /// Call a LocalAPI endpoint via the in-process bridge (net.Pipe pattern).
    ///
    /// - Parameters:
    ///   - timeoutMillis: Timeout in milliseconds.
    ///   - method: HTTP method (GET, POST, PATCH, etc.).
    ///   - endpoint: LocalAPI endpoint path (e.g. "/localapi/v0/status").
    ///   - body: Optional request body.
    /// - Returns: The LocalAPI response.
    static func callLocalAPI(
        timeoutMillis: Int,
        method: String,
        endpoint: String,
        body: Data? = nil
    ) async throws -> LocalAPIResponse {
        guard let app = application else { throw GoBridgeError.startFailed }

        let inputStream: (any LibtailscaleInputStreamProtocol)? = body.map { DataInputStream($0) }

        let goResp = try app.callLocalAPI(timeoutMillis, method: method, endpoint: endpoint, body: inputStream)

        let statusCode = goResp.statusCode()
        let bodyData = try goResp.bodyBytes()

        return LocalAPIResponse(statusCode: statusCode, body: bodyData)
    }

    /// Stop watching notifications.
    static func stopNotifications(_ handle: NotificationHandle) {
        handle.goManager?.stop()
        handle.goManager = nil
    }
}

#else
// MARK: - Stub Implementation (no Libtailscale.xcframework)

enum GoBridge {
    static var application: AnyObject? { nil }

    static func start(dataDir: String, directFileRoot: String, hwAttestation: Bool) -> Bool {
        NSLog("[GoBridge] start() — stub, Go backend not built. Run ios/build_go.sh first.")
        return false
    }

    static func watchNotifications(mask: Int, callback: @escaping (Data) -> Void) -> NotificationHandle? {
        NSLog("[GoBridge] watchNotifications(mask: \(mask)) — stub")
        return nil
    }

    static func callLocalAPI(
        timeoutMillis: Int,
        method: String,
        endpoint: String,
        body: Data? = nil
    ) async throws -> LocalAPIResponse {
        NSLog("[GoBridge] callLocalAPI(\(method) \(endpoint)) — stub")
        throw GoBridgeError.notImplemented
    }

    static func stopNotifications(_ handle: NotificationHandle) {
        NSLog("[GoBridge] stopNotifications() — stub")
    }
}
#endif

// MARK: - Shared Types (always available)

/// Handle returned by watchNotifications, used to stop the subscription.
class NotificationHandle {
    #if canImport(Libtailscale)
    var goManager: (any LibtailscaleNotificationManagerProtocol)?
    #endif
}

/// Response from a LocalAPI call.
struct LocalAPIResponse {
    let statusCode: Int
    let body: Data
}

enum GoBridgeError: Error, LocalizedError {
    case notImplemented
    case startFailed
    case callFailed(String)

    var errorDescription: String? {
        switch self {
        case .notImplemented: return "Go backend not integrated"
        case .startFailed: return "Go backend failed to start"
        case .callFailed(let msg): return "LocalAPI call failed: \(msg)"
        }
    }
}

// MARK: - NotifyWatchOpt

/// Matches Go's ipn.NotifyWatchOpt constants.
struct NotifyWatchOpt {
    static let engineUpdates       = 1
    static let initialState        = 2
    static let prefs               = 4
    static let netmap              = 8
    static let noPrivateKey        = 16
    static let initialTailFSShares = 32
    static let initialOutgoingFiles = 64
    static let initialHealthState  = 128
    static let rateLimitNetmaps    = 256

    /// Default mask for iOS subscription (matches Android Kotlin default).
    static let defaultMask =
        netmap | prefs | initialState | initialHealthState | rateLimitNetmaps
}
