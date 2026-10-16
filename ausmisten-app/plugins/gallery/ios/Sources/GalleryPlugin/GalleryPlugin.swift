import Foundation
import Capacitor
import Photos
import PhotosUI
import UIKit
import AVFoundation

/// Zugriff auf die Fotomediathek fuer Ausmisten: auflisten, Vorschauen und Bilder liefern,
/// Videos bereitstellen, loeschen (landet in "Zuletzt geloescht") und Favoriten setzen.
@objc(GalleryPlugin)
public class GalleryPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "GalleryPlugin"
    public let jsName = "Gallery"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "status", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestAccess", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "manageLimited", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "list", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "thumbnail", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "image", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "video", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "delete", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setFavorite", returnType: CAPPluginReturnPromise)
    ]

    private let imageManager = PHCachingImageManager()
    private let workQueue = DispatchQueue(label: "rt.emefka.gallery", qos: .userInitiated)
    private let lock = NSLock()
    private var videoCopies: [URL] = []

    private func statusString(_ s: PHAuthorizationStatus) -> String {
        switch s {
        case .authorized: return "granted"
        case .limited: return "limited"
        case .notDetermined: return "prompt"
        case .denied, .restricted: return "denied"
        @unknown default: return "denied"
        }
    }

    private func findAsset(_ id: String) -> PHAsset? {
        return PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject
    }

    @objc func status(_ call: CAPPluginCall) {
        call.resolve(["status": statusString(PHPhotoLibrary.authorizationStatus(for: .readWrite))])
    }

    @objc func requestAccess(_ call: CAPPluginCall) {
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { s in
            call.resolve(["status": self.statusString(s)])
        }
    }

    @objc func manageLimited(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            guard let vc = self.bridge?.viewController else {
                call.reject("Keine Ansicht verfuegbar")
                return
            }
            PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: vc) { _ in
                call.resolve()
            }
        }
    }

    @objc func openSettings(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url)
            }
            call.resolve()
        }
    }

    @objc func list(_ call: CAPPluginCall) {
        workQueue.async {
            let opts = PHFetchOptions()
            opts.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
            opts.predicate = NSPredicate(format: "mediaType == %d || mediaType == %d",
                                         PHAssetMediaType.image.rawValue, PHAssetMediaType.video.rawValue)
            let result = PHAsset.fetchAssets(with: opts)
            var items: [[String: Any]] = []
            items.reserveCapacity(result.count)
            result.enumerateObjects { asset, _, _ in
                let resources = PHAssetResource.assetResources(for: asset)
                let main = resources.first { $0.type == .photo || $0.type == .video } ?? resources.first
                var size = 0
                for r in resources where r.type == .photo || r.type == .video || r.type == .pairedVideo {
                    size += (r.value(forKey: "fileSize") as? NSNumber)?.intValue ?? 0
                }
                let date = asset.creationDate ?? asset.modificationDate ?? Date()
                items.append([
                    "id": asset.localIdentifier,
                    "name": main?.originalFilename ?? "Foto",
                    "size": size,
                    "date": date.timeIntervalSince1970 * 1000,
                    "video": asset.mediaType == .video,
                    "duration": asset.duration,
                    "width": asset.pixelWidth,
                    "height": asset.pixelHeight,
                    "favorite": asset.isFavorite,
                    "screenshot": asset.mediaSubtypes.contains(.photoScreenshot)
                ])
            }
            call.resolve(["items": items])
        }
    }

    private func resolveJPEG(_ call: CAPPluginCall, side: CGFloat, fill: Bool, quality: CGFloat) {
        guard let id = call.getString("id"), let asset = findAsset(id) else {
            call.reject("Medium nicht gefunden")
            return
        }
        let o = PHImageRequestOptions()
        o.deliveryMode = .highQualityFormat
        o.resizeMode = .fast
        o.isNetworkAccessAllowed = true
        o.isSynchronous = false
        imageManager.requestImage(for: asset,
                                  targetSize: CGSize(width: side, height: side),
                                  contentMode: fill ? .aspectFill : .aspectFit,
                                  options: o) { img, _ in
            guard let img = img, let data = img.jpegData(compressionQuality: quality) else {
                call.reject("Bild nicht verfuegbar")
                return
            }
            call.resolve(["data": "data:image/jpeg;base64," + data.base64EncodedString()])
        }
    }

    @objc func thumbnail(_ call: CAPPluginCall) {
        resolveJPEG(call, side: CGFloat(call.getInt("size") ?? 240), fill: true, quality: 0.75)
    }

    @objc func image(_ call: CAPPluginCall) {
        resolveJPEG(call, side: CGFloat(call.getInt("max") ?? 2048), fill: false, quality: 0.85)
    }

    /// Kopiert das Video in den temporaeren Ordner, damit die Web-Ansicht es abspielen kann.
    @objc func video(_ call: CAPPluginCall) {
        guard let id = call.getString("id"), let asset = findAsset(id) else {
            call.reject("Medium nicht gefunden")
            return
        }
        let o = PHVideoRequestOptions()
        o.isNetworkAccessAllowed = true
        o.deliveryMode = .automatic
        o.version = .current
        imageManager.requestAVAsset(forVideo: asset, options: o) { av, _, _ in
            guard let urlAsset = av as? AVURLAsset else {
                call.reject("Video nicht verfuegbar")
                return
            }
            let ext = urlAsset.url.pathExtension.isEmpty ? "mov" : urlAsset.url.pathExtension
            let dest = FileManager.default.temporaryDirectory
                .appendingPathComponent("video-\(UUID().uuidString).\(ext)")
            do {
                try FileManager.default.copyItem(at: urlAsset.url, to: dest)
            } catch {
                call.reject("Video konnte nicht geladen werden")
                return
            }
            self.lock.lock()
            self.videoCopies.append(dest)
            while self.videoCopies.count > 4 {
                try? FileManager.default.removeItem(at: self.videoCopies.removeFirst())
            }
            self.lock.unlock()
            call.resolve(["path": dest.path])
        }
    }

    /// iOS fragt selbst noch einmal nach. Geloeschtes liegt 30 Tage in "Zuletzt geloescht".
    @objc func delete(_ call: CAPPluginCall) {
        let ids = call.getArray("ids", String.self) ?? []
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil)
        if assets.count == 0 {
            call.resolve(["deleted": [String]()])
            return
        }
        var found: [String] = []
        assets.enumerateObjects { a, _, _ in found.append(a.localIdentifier) }
        PHPhotoLibrary.shared().performChanges({
            PHAssetChangeRequest.deleteAssets(assets)
        }) { ok, err in
            if ok {
                call.resolve(["deleted": found])
            } else {
                call.resolve(["deleted": [String](), "cancelled": true, "error": err?.localizedDescription ?? ""])
            }
        }
    }

    @objc func setFavorite(_ call: CAPPluginCall) {
        let ids = call.getArray("ids", String.self) ?? []
        let value = call.getBool("value") ?? true
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil)
        if assets.count == 0 {
            call.resolve(["ok": true])
            return
        }
        PHPhotoLibrary.shared().performChanges({
            assets.enumerateObjects { a, _, _ in
                PHAssetChangeRequest(for: a).isFavorite = value
            }
        }) { ok, _ in
            call.resolve(["ok": ok])
        }
    }
}
