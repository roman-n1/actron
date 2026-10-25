import SwiftUI
import Shared
import UniformTypeIdentifiers

struct ComposeView: UIViewControllerRepresentable {
    func makeCoordinator() -> RecordingFiles { RecordingFiles() }

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = MainViewControllerKt.MainViewController(files: context.coordinator)
        context.coordinator.controller = controller
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView().ignoresSafeArea()
    }
}

// The representable's coordinator retains delegates while UIKit presents a document picker.
final class RecordingFiles: NSObject, IosRecordingFiles, UIDocumentPickerDelegate {
    weak var controller: UIViewController?
    private var picked: ([URL]) -> Void = { _ in }
    private var cancelled: () -> Void = {}

    func open(maxBytes: Int32, opened: @escaping (String, String) -> Void,
              cancelled: @escaping () -> Void, failed: @escaping (String) -> Void) {
        picked = { urls in
            guard let url = urls.first else { cancelled(); return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard size <= Int(maxBytes) else { failed("Recording is too large"); return }
                let data = try Data(contentsOf: url)
                guard data.count <= Int(maxBytes) else { failed("Recording is too large"); return }
                guard let text = String(data: data, encoding: .utf8) else {
                    failed("Recording is not UTF-8"); return
                }
                opened(url.lastPathComponent, text)
            } catch { failed("Cannot read recording: \(error.localizedDescription)") }
        }
        self.cancelled = cancelled
        present(UIDocumentPickerViewController(forOpeningContentTypes: [.json], asCopy: true), failed: failed)
    }

    func save(name: String, text: String, saved: @escaping () -> Void,
              cancelled: @escaping () -> Void, failed: @escaping (String) -> Void) {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let url = folder.appendingPathComponent(name)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try Data(text.utf8).write(to: url)
        } catch { failed("Cannot write recording: \(error.localizedDescription)"); return }
        let cleanup = { try? FileManager.default.removeItem(at: folder) }
        picked = { _ in cleanup(); saved() }
        self.cancelled = { cleanup(); cancelled() }
        present(UIDocumentPickerViewController(forExporting: [url], asCopy: true)) { message in
            cleanup(); failed(message)
        }
    }

    private func present(_ picker: UIDocumentPickerViewController, failed: (String) -> Void) {
        guard let controller else {
            reset()
            failed("Recording window is no longer available")
            return
        }
        picker.delegate = self
        controller.present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        let callback = picked
        reset()
        callback(urls)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        let callback = cancelled
        reset()
        callback()
    }

    private func reset() {
        picked = { _ in }
        cancelled = {}
    }
}
