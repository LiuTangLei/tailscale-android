import SwiftUI

@main
struct TailscaleApp: App {
    @StateObject private var vpnManager = VPNManager()
    @StateObject private var appState = AppState()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(vpnManager)
                .environmentObject(appState)
                .onAppear {
                    // Wire AppState ↔ VPNManager so user actions can reach the Extension
                    appState.vpnManager = vpnManager
                    // Fetch current profile if already logged in
                    if appState.ipnState == .running || appState.ipnState == .starting {
                        appState.fetchCurrentProfile()
                    }
                }
        }
    }
}
