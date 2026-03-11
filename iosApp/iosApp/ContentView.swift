import UIKit
import SwiftUI
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // Register MPV player bridge before Compose initializes
        NuvioPlayerRegistration.register()
        
        let controller = MainViewControllerKt.MainViewController()
        controller.view.backgroundColor = UIColor(red: 0.008, green: 0.016, blue: 0.016, alpha: 1.0)
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}
