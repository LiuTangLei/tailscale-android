import SwiftUI

/// Displayed when ipn.State == NeedsMachineAuth.
/// Provides status polling and guidance for device approval.
struct MachineAuthView: View {
    @EnvironmentObject var appState: AppState
    @State private var isPolling: Bool = true
    @State private var pollCount: Int = 0
    @State private var showingHelpSheet: Bool = false
    @State private var deviceInfo: DeviceAuthInfo?
    
    private let pollInterval: TimeInterval = 5.0
    private let maxPolls: Int = 120 // 10 minutes max
    
    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            
            // Status icon with animation
            ZStack {
                Circle()
                    .stroke(Color.orange.opacity(0.3), lineWidth: 4)
                    .frame(width: 100, height: 100)
                
                if isPolling {
                    Circle()
                        .trim(from: 0, to: 0.3)
                        .stroke(Color.orange, lineWidth: 4)
                        .frame(width: 100, height: 100)
                        .rotationEffect(.degrees(Double(pollCount) * 30))
                        .animation(.linear(duration: 0.5), value: pollCount)
                }
                
                Image(systemName: "lock.shield")
                    .font(.system(size: 40))
                    .foregroundColor(.orange)
            }
            
            VStack(spacing: 8) {
                Text("Awaiting Approval")
                    .font(.title2)
                    .fontWeight(.semibold)
                
                Text("An admin needs to approve this device before it can connect to the tailnet.")
                    .font(.body)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
            }
            
            // Device info
            if let info = deviceInfo {
                VStack(spacing: 12) {
                    Divider()
                        .padding(.horizontal, 48)
                    
                    VStack(spacing: 8) {
                        InfoItem(label: "Device", value: info.hostname)
                        if let nodeKey = info.nodeKey {
                            InfoItem(label: "Node Key", value: String(nodeKey.prefix(16)) + "...")
                        }
                    }
                    .padding(.horizontal, 32)
                }
            }
            
            // Status indicator
            HStack(spacing: 8) {
                if isPolling {
                    ProgressView()
                        .scaleEffect(0.8)
                    Text("Checking approval status...")
                        .font(.caption)
                        .foregroundColor(.secondary)
                } else {
                    Image(systemName: "pause.circle")
                        .foregroundColor(.secondary)
                    Text("Polling paused")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .padding(.top, 8)
            
            Spacer()
            
            // Action buttons
            VStack(spacing: 12) {
                Button {
                    showingHelpSheet = true
                } label: {
                    HStack {
                        Image(systemName: "questionmark.circle")
                        Text("How to Approve")
                    }
                }
                .buttonStyle(.bordered)
                
                Button {
                    isPolling.toggle()
                    if isPolling {
                        startPolling()
                    }
                } label: {
                    Text(isPolling ? "Pause Checking" : "Resume Checking")
                }
                .buttonStyle(.borderless)
                .foregroundColor(.secondary)
                
                Button(role: .destructive) {
                    appState.logout()
                } label: {
                    Text("Cancel & Sign Out")
                }
                .buttonStyle(.borderless)
            }
            .padding(.bottom, 32)
        }
        .onAppear {
            loadDeviceInfo()
            startPolling()
        }
        .onDisappear {
            isPolling = false
        }
        .sheet(isPresented: $showingHelpSheet) {
            MachineAuthHelpSheet()
        }
    }
    
    private func loadDeviceInfo() {
        guard let vpn = appState.vpnManager else { return }
        
        Task {
            do {
                let resp = try await vpn.callLocalAPI(method: "GET", endpoint: "/localapi/v0/status")
                
                guard resp.statusCode == 200,
                      let bodyB64 = resp.bodyBase64,
                      let bodyData = Data(base64Encoded: bodyB64) else { return }
                
                if let json = try JSONSerialization.jsonObject(with: bodyData) as? [String: Any],
                   let selfStatus = json["Self"] as? [String: Any] {
                    await MainActor.run {
                        deviceInfo = DeviceAuthInfo(
                            hostname: selfStatus["HostName"] as? String ?? "Unknown",
                            nodeKey: selfStatus["PublicKey"] as? String
                        )
                    }
                }
            } catch {
                // Best effort
            }
        }
    }
    
    private func startPolling() {
        Task {
            while isPolling && pollCount < maxPolls {
                pollCount += 1
                
                // Check if state has changed
                appState.loadSharedState()
                
                // If we're no longer in NeedsMachineAuth, stop polling
                if appState.ipnState != .needsMachineAuth {
                    await MainActor.run {
                        isPolling = false
                    }
                    return
                }
                
                try? await Task.sleep(nanoseconds: UInt64(pollInterval * 1_000_000_000))
            }
            
            await MainActor.run {
                isPolling = false
            }
        }
    }
}

/// Info item row.
struct InfoItem: View {
    let label: String
    let value: String
    
    var body: some View {
        HStack {
            Text(label)
                .font(.caption)
                .foregroundColor(.secondary)
            Spacer()
            Text(value)
                .font(.caption)
                .fontWeight(.medium)
        }
    }
}

/// Device auth info.
struct DeviceAuthInfo {
    let hostname: String
    let nodeKey: String?
}

/// Help sheet explaining machine auth.
struct MachineAuthHelpSheet: View {
    @Environment(\.dismiss) private var dismiss
    
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    VStack(alignment: .leading, spacing: 8) {
                        Label("What is Machine Authorization?", systemImage: "questionmark.circle.fill")
                            .font(.headline)
                        Text("Machine authorization is a security feature that requires an admin to approve new devices before they can join your Tailnet.")
                            .font(.body)
                            .foregroundColor(.secondary)
                    }
                    
                    VStack(alignment: .leading, spacing: 12) {
                        Label("How to Approve", systemImage: "checkmark.circle.fill")
                            .font(.headline)
                        
                        StepRow(number: 1, text: "Go to the Tailscale admin console at admin.tailscale.com")
                        StepRow(number: 2, text: "Navigate to the Machines page")
                        StepRow(number: 3, text: "Find this device in the list (it will show as pending)")
                        StepRow(number: 4, text: "Click on the device and select 'Authorize'")
                    }
                    
                    VStack(alignment: .leading, spacing: 8) {
                        Label("Need Help?", systemImage: "lifepreserver.fill")
                            .font(.headline)
                        Text("Contact your Tailnet administrator for assistance with device approval.")
                            .font(.body)
                            .foregroundColor(.secondary)
                        
                        Link(destination: URL(string: "https://tailscale.com/kb/1099/machine-authorization")!) {
                            HStack {
                                Text("Learn more")
                                Image(systemName: "arrow.up.right.square")
                            }
                        }
                        .padding(.top, 4)
                    }
                }
                .padding()
            }
            .navigationTitle("Machine Authorization")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
    }
}

/// Step row for instructions.
struct StepRow: View {
    let number: Int
    let text: String
    
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Circle()
                .fill(Color.accentColor)
                .frame(width: 24, height: 24)
                .overlay {
                    Text("\(number)")
                        .font(.caption)
                        .fontWeight(.bold)
                        .foregroundColor(.white)
                }
            Text(text)
                .font(.body)
        }
    }
}
