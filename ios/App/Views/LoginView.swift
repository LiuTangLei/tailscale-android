import SwiftUI

/// Login view displayed when ipn.State == NeedsLogin.
struct LoginView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            Image(systemName: "network")
                .font(.system(size: 64))
                .foregroundColor(.accentColor)

            Text("Tailscale")
                .font(.largeTitle)
                .fontWeight(.bold)

            Text("Sign in to connect to your tailnet")
                .font(.body)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)

            Button(action: {
                appState.startLogin()
            }) {
                Text("Sign In")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color.accentColor)
                    .foregroundColor(.white)
                    .cornerRadius(12)
            }
            .padding(.horizontal, 32)

            if appState.isLoggingIn {
                ProgressView("Signing in...")
            }

            if let error = appState.lastError {
                Text(error)
                    .font(.caption)
                    .foregroundColor(.red)
                    .padding(.horizontal, 32)
            }

            Spacer()
        }
    }
}
