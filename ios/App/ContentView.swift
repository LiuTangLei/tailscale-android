import SwiftUI
import SafariServices
import AuthenticationServices

struct ContentView: View {
    @EnvironmentObject var appState: AppState
    @EnvironmentObject var vpnManager: VPNManager
    @State private var showingSafari = false

    var body: some View {
        Group {
            switch appState.ipnState {
            case .noState, .needsLogin:
                LoginView()
            case .needsMachineAuth:
                MachineAuthView()
            default:
                MainView()
            }
        }
        // When Go backend sends BrowseToURL, open Safari for login
        .onChange(of: appState.browseToURL) { url in
            if url != nil {
                showingSafari = true
            }
        }
        // Dismiss Safari when login finishes (LoginFinished clears browseToURL)
        .onChange(of: appState.isLoggingIn) { loggingIn in
            if !loggingIn && showingSafari {
                showingSafari = false
            }
        }
        .sheet(isPresented: $showingSafari, onDismiss: {
            // User closed the browser manually
            if appState.isLoggingIn {
                appState.isLoggingIn = false
            }
        }) {
            if let urlStr = appState.browseToURL, let url = URL(string: urlStr) {
                SafariView(url: url)
            }
        }
    }
}

/// Wraps SFSafariViewController for SwiftUI.
/// Used for the login OAuth flow. Login completion is signaled by
/// Notify.LoginFinished from the Go backend, NOT by a URL callback.
struct SafariView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        let vc = SFSafariViewController(url: url)
        vc.preferredControlTintColor = .systemBlue
        return vc
    }

    func updateUIViewController(_ uiViewController: SFSafariViewController, context: Context) {}
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
            .environmentObject(AppState())
            .environmentObject(VPNManager())
    }
}
