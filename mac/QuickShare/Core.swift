import Foundation
import Network

/// Yerelleştirilmiş metin (en / tr).
func L(_ key: String, _ args: CVarArg...) -> String {
    String(format: NSLocalizedString(key, comment: ""), arguments: args)
}

struct QSError: LocalizedError {
    let m: String
    init(_ m: String) { self.m = m }
    var errorDescription: String? { m }
}

/// Cihaz kimliği: ilk açılışta üretilen rastgele kimlik (şifreleme / sertifika yok).
enum Identity {
    static let name: String = Host.current().localizedName ?? ProcessInfo.processInfo.hostName
    static let id: String = {
        let d = UserDefaults.standard
        if let v = d.string(forKey: "deviceId") { return v }
        let v = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        d.set(v, forKey: "deviceId")
        return v
    }()
}

struct Peer: Identifiable {
    let id: String
    let name: String
    let endpoint: NWEndpoint
}

struct Row: Identifiable {
    let id: String
    let name: String
}

/// Uygulama durumu: bulunan cihazlar, ilerleme.
final class Store: ObservableObject {
    static let shared = Store()

    @Published var peers: [String: Peer] = [:]
    @Published var status = ""
    @Published var pct = -1.0
    /// Son kaydedilen dosya; durum yazısına tıklanınca klasörü açmak için.
    @Published var savedURL: URL?

    func post(_ s: String, _ p: Double = -1, saved: URL? = nil) {
        DispatchQueue.main.async { self.status = s; self.pct = p; self.savedURL = saved }
    }

    /// Gelen dosyaların kaydedileceği klasör (varsayılan: ~/Link). Kullanıcı seçerse UserDefaults'ta saklanır.
    static var defaultSaveDir: URL {
        FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Link")
    }

    static var saveDir: URL {
        get {
            if let p = UserDefaults.standard.string(forKey: "saveDir"), !p.isEmpty {
                return URL(fileURLWithPath: p, isDirectory: true)
            }
            return defaultSaveDir
        }
        set { UserDefaults.standard.set(newValue.path, forKey: "saveDir") }
    }

    var rows: [Row] {
        peers.values.sorted { $0.name < $1.name }.map { Row(id: $0.id, name: $0.name) }
    }
}
