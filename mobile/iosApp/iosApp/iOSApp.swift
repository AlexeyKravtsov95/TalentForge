import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                // The Compose view covers the whole window so the TalentForge
                // dark background paints through the system safe regions
                // (the common screen insets the content itself via
                // WindowInsets.safeDrawing — applied exactly once). The app
                // palette is fixed dark, so the status bar renders in light
                // content.
                .ignoresSafeArea()
                .preferredColorScheme(.dark)
        }
    }
}
