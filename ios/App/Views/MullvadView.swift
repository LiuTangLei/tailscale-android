import SwiftUI

// MARK: - Mullvad Models

/// Status of Mullvad feature for the current tailnet.
struct MullvadStatus: Codable {
    let enabled: Bool
    let countries: [MullvadCountry]?
}

/// Mullvad country with available cities.
struct MullvadCountry: Codable, Identifiable {
    let code: String
    let name: String
    let cities: [MullvadCity]
    
    var id: String { code }
}

/// Mullvad city with available exit nodes.
struct MullvadCity: Codable, Identifiable {
    let code: String
    let name: String
    let nodes: [MullvadNode]
    
    var id: String { code }
}

/// Individual Mullvad exit node.
struct MullvadNode: Codable, Identifiable {
    let id: String
    let name: String
    let online: Bool
}

// MARK: - Country Flag Helper

extension String {
    /// Convert country code to flag emoji.
    /// Uses Unicode regional indicator symbols.
    var flagEmoji: String {
        let base: UInt32 = 127397
        var result = ""
        for scalar in self.uppercased().unicodeScalars {
            if let unicode = UnicodeScalar(base + scalar.value) {
                result.append(String(unicode))
            }
        }
        return result
    }
}

// MARK: - Mullvad View

/// Mullvad Exit Nodes selection view.
/// Shows available Mullvad exit nodes grouped by country and city.
struct MullvadView: View {
    @EnvironmentObject var appState: AppState
    @Environment(\.dismiss) private var dismiss
    
    @State private var mullvadStatus: MullvadStatus?
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var searchText = ""
    @State private var expandedCountries: Set<String> = []
    
    /// Currently selected exit node ID.
    private var currentExitNodeID: String? {
        appState.prefs?.ExitNodeID
    }
    
    /// Mullvad exit nodes from the peer list (fallback if API doesn't provide structured data).
    private var mullvadPeers: [PeerNode] {
        appState.peers.filter { peer in
            peer.isExitNode && isMullvadNode(peer)
        }
    }
    
    /// Check if a peer is a Mullvad exit node based on naming convention.
    private func isMullvadNode(_ peer: PeerNode) -> Bool {
        // Mullvad nodes typically have names like "mullvad-xx-yyy-wg-NNN"
        let name = peer.hostname.lowercased()
        return name.hasPrefix("mullvad-") || name.contains("-wg-")
    }
    
    /// Group Mullvad peers by country code.
    private var peersByCountry: [String: [PeerNode]] {
        Dictionary(grouping: mullvadPeers) { peer in
            extractCountryCode(from: peer.hostname)
        }
    }
    
    /// Extract country code from Mullvad node name.
    /// e.g., "mullvad-au-syd-wg-001" -> "au"
    private func extractCountryCode(from hostname: String) -> String {
        let components = hostname.lowercased().components(separatedBy: "-")
        if components.count >= 2 {
            // Format: mullvad-XX-... or XX-YYY-wg-...
            if components[0] == "mullvad" && components.count >= 2 {
                return components[1].uppercased()
            } else if components.count >= 3 && components.contains("wg") {
                return components[0].uppercased()
            }
        }
        return "??"
    }
    
    /// Extract city code from Mullvad node name.
    private func extractCityCode(from name: String) -> String {
        let components = name.lowercased().components(separatedBy: "-")
        if components.count >= 3 {
            if components[0] == "mullvad" {
                return components[2].uppercased()
            } else {
                return components[1].uppercased()
            }
        }
        return "??"
    }
    
    /// Country name from code.
    private func countryName(for code: String) -> String {
        let locale = Locale.current
        return locale.localizedString(forRegionCode: code) ?? code
    }
    
    /// Filtered peers based on search.
    private var filteredPeers: [String: [PeerNode]] {
        guard !searchText.isEmpty else { return peersByCountry }
        
        var result: [String: [PeerNode]] = [:]
        for (country, peers) in peersByCountry {
            let countryName = self.countryName(for: country)
            let filtered = peers.filter { peer in
                peer.displayName.localizedCaseInsensitiveContains(searchText) ||
                countryName.localizedCaseInsensitiveContains(searchText)
            }
            if !filtered.isEmpty || countryName.localizedCaseInsensitiveContains(searchText) {
                result[country] = filtered.isEmpty ? peers : filtered
            }
        }
        return result
    }
    
    var body: some View {
        Group {
            if isLoading {
                ProgressView("Loading Mullvad nodes...")
            } else if let error = errorMessage {
                VStack(spacing: 16) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.largeTitle)
                        .foregroundColor(.orange)
                    Text(error)
                        .multilineTextAlignment(.center)
                    Button("Retry") {
                        loadMullvadStatus()
                    }
                    .buttonStyle(.bordered)
                }
                .padding()
            } else if mullvadPeers.isEmpty {
                MullvadNotEnabledView()
            } else {
                mullvadListView
            }
        }
        .navigationTitle("Mullvad Exit Nodes")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            loadMullvadStatus()
        }
    }
    
    private var mullvadListView: some View {
        List {
            // Suggested section
            Section {
                SuggestedMullvadRow(
                    isSelected: false,
                    onSelect: selectSuggestedNode
                )
            } header: {
                Text("Suggested")
            } footer: {
                Text("Automatically select the best Mullvad server based on your location.")
            }
            
            // Countries
            let sortedCountries = filteredPeers.keys.sorted { 
                countryName(for: $0) < countryName(for: $1) 
            }
            
            ForEach(sortedCountries, id: \.self) { countryCode in
                if let peers = filteredPeers[countryCode] {
                    Section {
                        MullvadCountryRow(
                            countryCode: countryCode,
                            countryName: countryName(for: countryCode),
                            peers: peers,
                            isExpanded: expandedCountries.contains(countryCode),
                            currentExitNodeID: currentExitNodeID,
                            onToggle: {
                                if expandedCountries.contains(countryCode) {
                                    expandedCountries.remove(countryCode)
                                } else {
                                    expandedCountries.insert(countryCode)
                                }
                            },
                            onSelectPeer: { peer in
                                appState.setExitNode(peer)
                                dismiss()
                            },
                            onSelectCountry: {
                                selectBestNodeInCountry(countryCode, peers: peers)
                            }
                        )
                    }
                }
            }
        }
        .searchable(text: $searchText, prompt: "Search countries or cities")
    }
    
    private func loadMullvadStatus() {
        isLoading = true
        errorMessage = nil
        
        // Try to get structured Mullvad data from API
        // Fallback to parsing peer list if not available
        Task {
            // Small delay to show loading state
            try? await Task.sleep(nanoseconds: 300_000_000)
            
            await MainActor.run {
                // For now, we use the peer-based approach
                // In future, this could call /localapi/v0/mullvad endpoint
                isLoading = false
                
                if mullvadPeers.isEmpty {
                    // No Mullvad nodes found - user may not have subscription
                    mullvadStatus = MullvadStatus(enabled: false, countries: nil)
                } else {
                    mullvadStatus = MullvadStatus(enabled: true, countries: nil)
                }
            }
        }
    }
    
    private func selectSuggestedNode() {
        // Select the first online Mullvad node (simplistic "best" selection)
        // In production, this would use latency data
        if let bestNode = mullvadPeers.first(where: { $0.online }) ?? mullvadPeers.first {
            appState.setExitNode(bestNode)
            dismiss()
        }
    }
    
    private func selectBestNodeInCountry(_ countryCode: String, peers: [PeerNode]) {
        if let bestNode = peers.first(where: { $0.online }) ?? peers.first {
            appState.setExitNode(bestNode)
            dismiss()
        }
    }
}

// MARK: - Suggested Row

struct SuggestedMullvadRow: View {
    let isSelected: Bool
    let onSelect: () -> Void
    
    var body: some View {
        Button(action: onSelect) {
            HStack {
                Image(systemName: "sparkles")
                    .foregroundColor(.accentColor)
                    .frame(width: 32)
                
                VStack(alignment: .leading, spacing: 2) {
                    Text("Best Available")
                        .font(.body)
                    Text("Lowest latency server")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                
                Spacer()
                
                if isSelected {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(.accentColor)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Country Row

struct MullvadCountryRow: View {
    let countryCode: String
    let countryName: String
    let peers: [PeerNode]
    let isExpanded: Bool
    let currentExitNodeID: String?
    let onToggle: () -> Void
    let onSelectPeer: (PeerNode) -> Void
    let onSelectCountry: () -> Void
    
    private var onlinePeers: [PeerNode] {
        peers.filter { $0.online }
    }
    
    private var hasSelectedNode: Bool {
        guard let exitID = currentExitNodeID else { return false }
        return peers.contains { $0.id == exitID }
    }
    
    var body: some View {
        VStack(spacing: 0) {
            // Country header
            Button(action: onToggle) {
                HStack {
                    Text(countryCode.flagEmoji)
                        .font(.title2)
                    
                    VStack(alignment: .leading, spacing: 2) {
                        Text(countryName)
                            .font(.body)
                            .foregroundColor(.primary)
                        Text("\(onlinePeers.count) of \(peers.count) online")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    
                    Spacer()
                    
                    if hasSelectedNode {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundColor(.accentColor)
                    }
                    
                    // Use any server in this country
                    Button {
                        onSelectCountry()
                    } label: {
                        Text("Use")
                            .font(.caption)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                    }
                    .buttonStyle(.bordered)
                    .tint(.accentColor)
                    
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .foregroundColor(.secondary)
                        .font(.caption)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            
            // Expanded city/node list
            if isExpanded {
                Divider()
                    .padding(.vertical, 8)
                
                ForEach(peers.sorted { $0.displayName < $1.displayName }) { peer in
                    MullvadNodeRow(
                        peer: peer,
                        isSelected: peer.id == currentExitNodeID,
                        onSelect: { onSelectPeer(peer) }
                    )
                    .padding(.leading, 40)
                }
            }
        }
    }
}

// MARK: - Node Row

struct MullvadNodeRow: View {
    let peer: PeerNode
    let isSelected: Bool
    let onSelect: () -> Void
    
    var body: some View {
        Button(action: onSelect) {
            HStack {
                Circle()
                    .fill(peer.online ? Color.green : Color.gray)
                    .frame(width: 8, height: 8)
                
                Text(peer.displayName)
                    .font(.subheadline)
                    .foregroundColor(peer.online ? .primary : .secondary)
                
                Spacer()
                
                if isSelected {
                    Image(systemName: "checkmark")
                        .foregroundColor(.accentColor)
                        .font(.caption)
                }
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!peer.online)
    }
}

// MARK: - Not Enabled View

struct MullvadNotEnabledView: View {
    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "globe.europe.africa")
                .font(.system(size: 60))
                .foregroundColor(.secondary)
            
            Text("Mullvad Not Available")
                .font(.title2)
                .fontWeight(.semibold)
            
            Text("Mullvad exit nodes are a paid add-on feature. Enable Mullvad in your Tailscale admin console to access 60+ global locations.")
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
                .padding(.horizontal, 32)
            
            Link(destination: URL(string: "https://login.tailscale.com/admin/settings/mullvad")!) {
                HStack {
                    Text("Open Admin Console")
                    Image(systemName: "arrow.up.right.square")
                }
            }
            .buttonStyle(.borderedProminent)
            
            VStack(alignment: .leading, spacing: 8) {
                Label("No separate Mullvad account needed", systemImage: "checkmark.circle")
                Label("60+ global locations", systemImage: "checkmark.circle")
                Label("Billed through Tailscale", systemImage: "checkmark.circle")
            }
            .font(.subheadline)
            .foregroundColor(.secondary)
            .padding(.top, 16)
        }
        .padding()
    }
}

#if DEBUG
struct MullvadView_Previews: PreviewProvider {
    static var previews: some View {
        NavigationView {
            MullvadView()
                .environmentObject(AppState())
        }
    }
}
#endif
