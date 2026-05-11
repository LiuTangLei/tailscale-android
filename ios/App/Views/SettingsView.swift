import SwiftUI

/// Minimal settings view for MVP.
struct SettingsView: View {
    @EnvironmentObject var appState: AppState
    @EnvironmentObject var vpnManager: VPNManager

    var body: some View {
        List {
            Section("Account") {
                if let profile = appState.currentProfile {
                    HStack {
                        Text("User")
                        Spacer()
                        Text(profile.name).foregroundColor(.secondary)
                    }
                    if !profile.controlURL.isEmpty && profile.controlURL != "https://controlplane.tailscale.com" {
                        HStack {
                            Text("Control Server")
                            Spacer()
                            Text(profile.controlURL).foregroundColor(.secondary)
                        }
                    }
                }
                
                NavigationLink(destination: ProfilesView()) {
                    HStack {
                        Image(systemName: "person.2")
                            .foregroundColor(.accentColor)
                            .frame(width: 24)
                        Text("Manage Profiles")
                    }
                }
            }

            Section("About") {
                HStack {
                    Text("Version")
                    Spacer()
                    Text(appState.appVersion).foregroundColor(.secondary)
                }
            }

            Section("Network") {
                NavigationLink(destination: DNSSettingsView()) {
                    HStack {
                        Image(systemName: "network")
                            .foregroundColor(.accentColor)
                            .frame(width: 24)
                        Text("DNS Settings")
                    }
                }
                
                NavigationLink(destination: SubnetRoutesView()) {
                    HStack {
                        Image(systemName: "point.3.connected.trianglepath.dotted")
                            .foregroundColor(.accentColor)
                            .frame(width: 24)
                        Text("Subnet Routes")
                    }
                }
            }

            Section("Security") {
                NavigationLink(destination: TailnetLockView()) {
                    HStack {
                        Image(systemName: "lock.shield")
                            .foregroundColor(.accentColor)
                            .frame(width: 24)
                        Text("Tailnet Lock")
                    }
                }
                
                NavigationLink(destination: MDMInfoView()) {
                    HStack {
                        Image(systemName: "building.2")
                            .foregroundColor(.accentColor)
                            .frame(width: 24)
                        Text("Device Management")
                    }
                }
            }

            Section("Diagnostics") {
                HStack {
                    Text("Amnezia-WG")
                    Spacer()
                    if appState.localAwgStatus {
                        HStack(spacing: 4) {
                            Text("\u{2605}")
                                .foregroundColor(Color(red: 1.0, green: 0.84, blue: 0.0))
                            Text("Enabled")
                                .foregroundColor(.green)
                        }
                        .font(.caption)
                    } else {
                        Text("Not configured")
                            .foregroundColor(.secondary)
                            .font(.caption)
                    }
                }

                // AWG refresh button
                Button {
                    appState.refreshAwgStatus()
                } label: {
                    HStack {
                        Image(systemName: "arrow.clockwise")
                        Text("Refresh AWG Status")
                    }
                }
                
                NavigationLink(destination: BugReportView()) {
                    HStack {
                        Image(systemName: "ladybug")
                            .foregroundColor(.accentColor)
                            .frame(width: 24)
                        Text("Bug Report")
                    }
                }

                if let lastError = appState.lastError {
                    HStack {
                        Text("Last Error")
                        Spacer()
                        Text(lastError)
                            .foregroundColor(.red)
                            .font(.caption)
                            .lineLimit(2)
                    }
                }
            }
            
            Section("About") {
                NavigationLink(destination: AboutView()) {
                    HStack {
                        Image(systemName: "info.circle")
                            .foregroundColor(.accentColor)
                            .frame(width: 24)
                        Text("About Tailscale")
                    }
                }
            }

            Section {
                Button(role: .destructive) {
                    appState.logout()
                } label: {
                    HStack {
                        Spacer()
                        Text("Sign Out")
                        Spacer()
                    }
                }
            }
        }
        .navigationTitle("Settings")
    }
}
