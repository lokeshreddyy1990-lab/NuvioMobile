import Darwin
import Foundation
import Network
import Security
import TDLibFramework

private struct RegisteredTelegramFile {
    let size: Int64
    let fileName: String
    let mimeType: String
}

private struct VirtualPart {
    let fileId: Int32
    let size: Int64
    let cumStart: Int64
}

private struct VirtualAsset {
    let parts: [VirtualPart]
    let fileName: String
    let mimeType: String
    let innerOffset: Int64
    let innerSize: Int64
}

private final class TelegramBridgeManager {
    static let shared = TelegramBridgeManager()

    private let receiveQueue = DispatchQueue(label: "com.nuvio.telegram.receive", qos: .userInitiated)
    private let serverQueue = DispatchQueue(label: "com.nuvio.telegram.http", qos: .userInitiated)
    private let lock = NSLock()
    private var clientId: Int32 = 0
    private var receiveLoopStarted = false
    private var waiters: [String: (String) -> Void] = [:]
    private var listener: NWListener?
    private var listenerPort: UInt16?
    private var registeredFiles: [Int32: RegisteredTelegramFile] = [:]
    private var virtualAssets: [Int32: VirtualAsset] = [:]
    private var nextVirtualId: Int32 = 1
    private var apiId: Int32 = 0
    private var apiHash = ""
    private var appVersion = ""
    private var lastCacheOptimizeAt: TimeInterval = 0

    // ponytail: fixed 5 GB cap; raise via settings UI later if users ask
    private let cacheLimitBytes: Int64 = 5 * 1024 * 1024 * 1024
    private let storagePolicyTtl = 2_147_483_647
    private let storagePolicyCount = 2_147_483_647
    private let cacheOptimizeMinInterval: TimeInterval = 60

    private init() {}

    func start(apiId: Int32, apiHash: String, appVersion: String) -> Bool {
        guard apiId > 0, !apiHash.isEmpty else { return false }

        lock.lock()
        if clientId == 0 {
            clientId = td_create_client_id()
        }
        self.apiId = apiId
        self.apiHash = apiHash
        self.appVersion = appVersion
        let shouldStartReceiveLoop = !receiveLoopStarted
        receiveLoopStarted = true
        lock.unlock()

        if shouldStartReceiveLoop {
            receiveQueue.async { [weak self] in self?.receiveLoop() }
        }

        guard let auth = request(["@type": "getAuthorizationState"], timeout: 10),
              let authType = nestedType(auth, key: nil) else {
            return false
        }
        if authType == "authorizationStateWaitTdlibParameters" {
            return setParameters()
        }
        return true
    }

    func request(json: String, timeout: TimeInterval) -> String? {
        guard let data = json.data(using: .utf8),
              var dictionary = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return request(dictionary, timeout: timeout)
    }

    func playbackURL(fileId: Int32, fileSize: Int64, fileName: String, mimeType: String?) -> String? {
        guard fileId > 0, fileSize > 0, let port = ensureServer() else { return nil }
        lock.lock()
        registeredFiles[fileId] = RegisteredTelegramFile(
            size: fileSize,
            fileName: fileName,
            mimeType: mimeType?.isEmpty == false ? mimeType! : "application/octet-stream"
        )
        lock.unlock()
        let encodedName = encodedHeaderValue(fileName)
        return "http://127.0.0.1:\(port)/telegram/\(fileId)/\(encodedName)"
    }

    func virtualPlaybackURL(specJSON: String) -> String? {
        guard let spec = parseVirtualSpec(specJSON), let port = ensureServer() else { return nil }
        lock.lock()
        let virtualId = nextVirtualId
        nextVirtualId += 1
        virtualAssets[virtualId] = spec
        lock.unlock()
        let encodedName = encodedHeaderValue(spec.fileName)
        return "http://127.0.0.1:\(port)/telegram/v/\(virtualId)/\(encodedName)"
    }

    func readConcat(partsJSON: String, offset: Int64, length: Int32) -> Data? {
        guard let parts = parseVirtualParts(partsJSON), length > 0 else { return nil }
        return readConcatRange(parts: parts, start: offset, end: offset + Int64(length) - 1)
    }

    func cacheSize() -> Int64 {
        storageSizeFast() ?? directorySize(url: filesDirectory)
    }

    func clearCache() {
        optimizeStorage(limitBytes: 0)
    }

    func optimizeCacheIfNeeded() {
        let now = Date().timeIntervalSince1970
        guard now - lastCacheOptimizeAt >= cacheOptimizeMinInterval else { return }
        guard cacheSize() > cacheLimitBytes else { return }
        lastCacheOptimizeAt = now
        optimizeStorage(limitBytes: cacheLimitBytes)
    }

    private func storageSizeFast() -> Int64? {
        guard let response = request([
            "@type": "getStorageStatisticsFast",
            "chat_limit": 0,
        ], timeout: 10),
              let data = response.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return jsonInt64(object["size"])
    }

    private func optimizeStorage(limitBytes: Int64) {
        _ = request([
            "@type": "optimizeStorage",
            "size": limitBytes,
            "ttl": storagePolicyTtl,
            "count": storagePolicyCount,
            "immunity_delay": 0,
            "file_types": [
                ["@type": "fileTypeVideo"],
                ["@type": "fileTypeDocument"],
            ],
            "chat_ids": [] as [Int],
            "exclude_chat_ids": [] as [Int],
            "return_deleted_file_statistics": false,
            "chat_limit": 0,
        ], timeout: 120)
    }

    private func setParameters() -> Bool {
        try? FileManager.default.createDirectory(at: databaseDirectory, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: filesDirectory, withIntermediateDirectories: true)
        let response = request([
            "@type": "setTdlibParameters",
            "use_test_dc": false,
            "database_directory": databaseDirectory.path,
            "files_directory": filesDirectory.path,
            "database_encryption_key": databaseEncryptionKey,
            "use_file_database": true,
            "use_chat_info_database": true,
            "use_message_database": true,
            "use_secret_chats": false,
            "api_id": apiId,
            "api_hash": apiHash,
            "system_language_code": Locale.current.identifier,
            "device_model": "Apple device",
            "system_version": ProcessInfo.processInfo.operatingSystemVersionString,
            "application_version": appVersion,
        ], timeout: 20)
        return nestedType(response, key: nil) == "ok"
    }

    private func request(_ source: [String: Any], timeout: TimeInterval) -> String? {
        lock.lock()
        let activeClientId = clientId
        lock.unlock()
        guard activeClientId != 0 else { return nil }

        var dictionary = source
        let token = UUID().uuidString
        dictionary["@extra"] = token
        guard let payload = jsonString(dictionary) else { return nil }

        let semaphore = DispatchSemaphore(value: 0)
        var response: String?
        lock.lock()
        waiters[token] = { value in
            response = value
            semaphore.signal()
        }
        lock.unlock()

        td_send(activeClientId, payload)
        if semaphore.wait(timeout: .now() + timeout) == .timedOut {
            lock.lock()
            waiters.removeValue(forKey: token)
            lock.unlock()
            return nil
        }
        return response
    }

    private func receiveLoop() {
        while true {
            autoreleasepool {
                guard let pointer = td_receive(1.0) else { return }
                let response = String(cString: pointer)
                guard let data = response.data(using: .utf8),
                      let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                    return
                }
                guard let token = object["@extra"] as? String else {
                    self.handleUpdate(object)
                    return
                }
                lock.lock()
                let waiter = waiters.removeValue(forKey: token)
                lock.unlock()
                waiter?(response)
            }
        }
    }

    private func handleUpdate(_ object: [String: Any]) {
        guard object["@type"] as? String == "updateAuthorizationState",
              let state = object["authorization_state"] as? [String: Any],
              state["@type"] as? String == "authorizationStateClosed" else { return }

        lock.lock()
        clientId = td_create_client_id()
        lock.unlock()
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            _ = self?.setParameters()
        }
    }

    private func ensureServer() -> UInt16? {
        lock.lock()
        if let listenerPort {
            lock.unlock()
            return listenerPort
        }
        lock.unlock()

        let ready = DispatchSemaphore(value: 0)
        do {
            let parameters = NWParameters.tcp
            parameters.allowLocalEndpointReuse = true
            parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
            let newListener = try NWListener(using: parameters, on: .any)
            newListener.newConnectionHandler = { [weak self] connection in
                self?.handle(connection: connection)
            }
            newListener.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    self?.lock.lock()
                    self?.listenerPort = newListener.port?.rawValue
                    self?.lock.unlock()
                    ready.signal()
                case .failed, .cancelled:
                    ready.signal()
                default:
                    break
                }
            }
            lock.lock()
            listener = newListener
            lock.unlock()
            newListener.start(queue: serverQueue)
        } catch {
            return nil
        }

        _ = ready.wait(timeout: .now() + 5)
        lock.lock()
        let port = listenerPort
        lock.unlock()
        return port
    }

    private func handle(connection: NWConnection) {
        connection.start(queue: serverQueue)
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] data, _, _, error in
            guard let self, error == nil, let data,
                  let request = String(data: data, encoding: .utf8) else {
                connection.cancel()
                return
            }
            self.respond(to: request, on: connection)
        }
    }

    private func respond(to request: String, on connection: NWConnection) {
        let lines = request.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else {
            sendError(400, on: connection)
            return
        }
        let requestParts = requestLine.split(separator: " ")
        guard requestParts.count >= 2 else {
            sendError(400, on: connection)
            return
        }
        let method = requestParts[0].uppercased()
        guard method == "GET" || method == "HEAD" else {
            sendError(405, on: connection)
            return
        }
        let pathSegments = requestParts[1].split(separator: "/")
        if pathSegments.count >= 4, pathSegments[1] == "v", let virtualId = Int32(pathSegments[2]) {
            lock.lock()
            let asset = virtualAssets[virtualId]
            lock.unlock()
            guard let asset, asset.innerSize > 0 else {
                sendError(404, on: connection)
                return
            }
            serve(
                requestLines: lines,
                method: method,
                totalSize: asset.innerSize,
                fileName: asset.fileName,
                mimeType: asset.mimeType,
                on: connection
            ) { [weak self] range in
                self?.streamConcat(
                    parts: asset.parts,
                    offset: asset.innerOffset + range.lowerBound,
                    end: asset.innerOffset + range.upperBound,
                    on: connection
                )
            }
            return
        }
        guard pathSegments.count >= 3, let fileId = Int32(pathSegments[1]) else {
            sendError(404, on: connection)
            return
        }
        lock.lock()
        let registeredFile = registeredFiles[fileId]
        lock.unlock()
        guard let registeredFile, registeredFile.size > 0 else {
            sendError(404, on: connection)
            return
        }
        serve(
            requestLines: lines,
            method: method,
            totalSize: registeredFile.size,
            fileName: registeredFile.fileName,
            mimeType: registeredFile.mimeType,
            on: connection
        ) { [weak self] range in
            self?.stream(fileId: fileId, offset: range.lowerBound, end: range.upperBound, on: connection)
        }
    }

    private func serve(
        requestLines lines: [String],
        method: String,
        totalSize: Int64,
        fileName: String,
        mimeType: String,
        on connection: NWConnection,
        startBody: @escaping (ClosedRange<Int64>) -> Void
    ) {
        let rangeHeader = lines.first { $0.lowercased().hasPrefix("range:") }
        let range = parseRange(rangeHeader, totalSize: totalSize)
        let isPartial = range.lowerBound > 0 || range.upperBound < totalSize - 1
        let contentLength = range.upperBound - range.lowerBound + 1
        var headers = isPartial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n"
        headers += "Content-Type: \(mimeType)\r\n"
        headers += "Content-Disposition: inline; filename*=UTF-8''\(encodedHeaderValue(fileName))\r\n"
        headers += "Accept-Ranges: bytes\r\n"
        headers += "Content-Length: \(contentLength)\r\n"
        if isPartial {
            headers += "Content-Range: bytes \(range.lowerBound)-\(range.upperBound)/\(totalSize)\r\n"
        }
        headers += "Connection: close\r\n\r\n"
        let headerData = Data(headers.utf8)
        connection.send(content: headerData, completion: .contentProcessed { error in
            guard error == nil else {
                connection.cancel()
                return
            }
            guard method == "GET" else {
                connection.send(content: nil, isComplete: true, completion: .contentProcessed { _ in
                    connection.cancel()
                })
                return
            }
            startBody(range)
        })
    }

    private func stream(fileId: Int32, offset: Int64, end: Int64, on connection: NWConnection) {
        streamConcat(
            parts: [VirtualPart(fileId: fileId, size: end + 1, cumStart: 0)],
            offset: offset,
            end: end,
            on: connection
        )
    }

    private func streamConcat(parts: [VirtualPart], offset: Int64, end: Int64, on connection: NWConnection) {
        guard offset <= end else {
            connection.send(content: nil, isComplete: true, completion: .contentProcessed { _ in connection.cancel() })
            return
        }
        guard let mapped = mapConcatOffset(parts: parts, offset: offset) else {
            connection.cancel()
            return
        }
        let partEnd = mapped.part.cumStart + mapped.part.size - 1
        let chunkEnd = min(end, partEnd)
        let chunkSize = min(Int64(1_048_576), chunkEnd - offset + 1)
        serverQueue.async { [weak self] in
            guard let self,
                  let data = self.downloadSlice(fileId: mapped.part.fileId, offset: mapped.localOffset, length: chunkSize),
                  !data.isEmpty else {
                connection.cancel()
                return
            }
            connection.send(content: data, completion: .contentProcessed { [weak self] error in
                guard error == nil else {
                    connection.cancel()
                    return
                }
                self?.streamConcat(parts: parts, offset: offset + Int64(data.count), end: end, on: connection)
            })
        }
    }

    private func readConcatRange(parts: [VirtualPart], start: Int64, end: Int64) -> Data? {
        guard start <= end else { return Data() }
        var collected = Data()
        var pos = start
        while pos <= end {
            guard let mapped = mapConcatOffset(parts: parts, offset: pos) else { return nil }
            let partEnd = mapped.part.cumStart + mapped.part.size - 1
            let chunkEnd = min(end, partEnd)
            let length = chunkEnd - pos + 1
            guard let data = downloadSlice(fileId: mapped.part.fileId, offset: mapped.localOffset, length: length) else {
                return nil
            }
            collected.append(data)
            pos += Int64(data.count)
            if data.isEmpty { break }
        }
        return collected
    }

    private func mapConcatOffset(parts: [VirtualPart], offset: Int64) -> (part: VirtualPart, localOffset: Int64)? {
        for part in parts {
            let partEnd = part.cumStart + part.size
            if offset >= part.cumStart && offset < partEnd {
                return (part, offset - part.cumStart)
            }
        }
        return nil
    }

    private func downloadSlice(fileId: Int32, offset: Int64, length: Int64) -> Data? {
        guard length > 0,
              let response = request([
                "@type": "downloadFile",
                "file_id": fileId,
                "priority": 32,
                "offset": offset,
                "limit": length,
                "synchronous": true,
              ], timeout: 90),
              let path = localFilePath(response),
              let handle = try? FileHandle(forReadingFrom: URL(fileURLWithPath: path)) else {
            return nil
        }
        defer { try? handle.close() }
        do {
            try handle.seek(toOffset: UInt64(offset))
            return try handle.read(upToCount: Int(length))
        } catch {
            return nil
        }
    }

    private func parseVirtualSpec(_ json: String) -> VirtualAsset? {
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let parts = parseVirtualParts(object["parts"]) else {
            return nil
        }
        let fileName = (object["fileName"] as? String)?.takeIfNotEmpty ?? "video.mkv"
        let mimeType = (object["mimeType"] as? String)?.takeIfNotEmpty ?? "application/octet-stream"
        let innerOffset = jsonInt64(object["innerOffset"]) ?? 0
        var innerSize = jsonInt64(object["innerSize"]) ?? 0
        let concatSize = parts.last.map { $0.cumStart + $0.size } ?? 0
        if innerSize <= 0 {
            innerSize = concatSize
        }
        guard concatSize > 0, innerOffset >= 0, innerOffset + innerSize <= concatSize else { return nil }
        return VirtualAsset(
            parts: parts,
            fileName: fileName,
            mimeType: mimeType,
            innerOffset: innerOffset,
            innerSize: innerSize
        )
    }

    private func parseVirtualParts(_ json: String) -> [VirtualPart]? {
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) else {
            return nil
        }
        return parseVirtualParts(object)
    }

    private func parseVirtualParts(_ object: Any?) -> [VirtualPart]? {
        guard let items = object as? [[String: Any]], !items.isEmpty else { return nil }
        var parts: [VirtualPart] = []
        var cum: Int64 = 0
        for item in items {
            let fileId = Int32(jsonInt64(item["fileId"]) ?? 0)
            let size = jsonInt64(item["size"]) ?? 0
            guard fileId > 0, size > 0 else { return nil }
            parts.append(VirtualPart(fileId: fileId, size: size, cumStart: cum))
            cum += size
        }
        return parts
    }

    private func jsonInt64(_ value: Any?) -> Int64? {
        if let number = value as? NSNumber { return number.int64Value }
        if let number = value as? Int64 { return number }
        if let number = value as? Int { return Int64(number) }
        if let number = value as? Double { return Int64(number) }
        return nil
    }

    private func localFilePath(_ response: String) -> String? {
        guard let data = response.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              object["@type"] as? String == "file",
              let local = object["local"] as? [String: Any] else { return nil }
        return local["path"] as? String
    }

    private func sendError(_ status: Int, on connection: NWConnection) {
        let reason: String
        switch status {
        case 404: reason = "Not Found"
        case 405: reason = "Method Not Allowed"
        case 415: reason = "Unsupported Media Type"
        default: reason = "Bad Request"
        }
        let payload = Data("HTTP/1.1 \(status) \(reason)\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8)
        connection.send(content: payload, isComplete: true, completion: .contentProcessed { _ in connection.cancel() })
    }

    private func parseRange(_ header: String?, totalSize: Int64) -> ClosedRange<Int64> {
        guard let header,
              let value = header.split(separator: ":", maxSplits: 1).last?.trimmingCharacters(in: .whitespaces),
              value.lowercased().hasPrefix("bytes=") else {
            return 0...(totalSize - 1)
        }
        let components = value.dropFirst(6).split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false)
        let start = Int64(components.first ?? "") ?? 0
        let end = components.count > 1 ? (Int64(components[1]) ?? totalSize - 1) : totalSize - 1
        let safeStart = min(max(0, start), totalSize - 1)
        let safeEnd = min(max(safeStart, end), totalSize - 1)
        return safeStart...safeEnd
    }

    private var databaseDirectory: URL {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        return root.appendingPathComponent("Telegram/Database", isDirectory: true)
    }

    private var filesDirectory: URL {
        let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        return root.appendingPathComponent("Telegram/Files", isDirectory: true)
    }

    private var databaseEncryptionKey: String {
        let service = "com.nuvio.enhanced.telegram"
        let account = "database-encryption-key"
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        if SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
           let data = item as? Data,
           let stored = String(data: data, encoding: .utf8),
           !stored.isEmpty {
            return stored
        }

        let legacyKey = "NuvioTelegramDatabaseEncryptionKey"
        let generated = UserDefaults.standard.string(forKey: legacyKey)?.takeIfNotEmpty
            ?? secureRandomKey()
        let value = Data(generated.utf8)
        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: value,
        ]
        SecItemAdd(addQuery as CFDictionary, nil)
        UserDefaults.standard.removeObject(forKey: legacyKey)
        return generated
    }

    private func directorySize(url: URL) -> Int64 {
        guard let enumerator = FileManager.default.enumerator(
            at: url,
            includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey]
        ) else { return 0 }
        var total: Int64 = 0
        for case let fileURL as URL in enumerator {
            let values = try? fileURL.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
            if values?.isRegularFile == true { total += Int64(values?.fileSize ?? 0) }
        }
        return total
    }
}

private func jsonString(_ dictionary: [String: Any]) -> String? {
    guard JSONSerialization.isValidJSONObject(dictionary),
          let data = try? JSONSerialization.data(withJSONObject: dictionary) else { return nil }
    return String(data: data, encoding: .utf8)
}

private func secureRandomKey() -> String {
    var bytes = [UInt8](repeating: 0, count: 32)
    if SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess {
        return Data(bytes).base64EncodedString()
    }
    return UUID().uuidString + UUID().uuidString
}

private func encodedHeaderValue(_ value: String) -> String {
    let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
    return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? "video"
}

private extension String {
    var takeIfNotEmpty: String? { isEmpty ? nil : self }
}

private func nestedType(_ json: String?, key: String?) -> String? {
    guard let json, let data = json.data(using: .utf8),
          let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
    if let key { return (object[key] as? [String: Any])?["@type"] as? String }
    return object["@type"] as? String
}

@_cdecl("NuvioTelegramStart")
func NuvioTelegramStart(_ apiId: Int32, _ apiHash: UnsafePointer<CChar>?, _ appVersion: UnsafePointer<CChar>?) -> Int32 {
    guard let apiHash, let appVersion else { return 0 }
    return TelegramBridgeManager.shared.start(
        apiId: apiId,
        apiHash: String(cString: apiHash),
        appVersion: String(cString: appVersion)
    ) ? 1 : 0
}

@_cdecl("NuvioTelegramRequest")
func NuvioTelegramRequest(_ json: UnsafePointer<CChar>?, _ timeoutSeconds: Double) -> UnsafeMutablePointer<CChar>? {
    guard let json,
          let response = TelegramBridgeManager.shared.request(json: String(cString: json), timeout: timeoutSeconds) else {
        return nil
    }
    return strdup(response)
}

@_cdecl("NuvioTelegramPlaybackURL")
func NuvioTelegramPlaybackURL(
    _ fileId: Int32,
    _ fileSize: Int64,
    _ fileName: UnsafePointer<CChar>?,
    _ mimeType: UnsafePointer<CChar>?
) -> UnsafeMutablePointer<CChar>? {
    guard let fileName,
          let url = TelegramBridgeManager.shared.playbackURL(
            fileId: fileId,
            fileSize: fileSize,
            fileName: String(cString: fileName),
            mimeType: mimeType.map { String(cString: $0) }
          ) else { return nil }
    return strdup(url)
}

@_cdecl("NuvioTelegramVirtualPlaybackURL")
func NuvioTelegramVirtualPlaybackURL(_ specJSON: UnsafePointer<CChar>?) -> UnsafeMutablePointer<CChar>? {
    guard let specJSON,
          let url = TelegramBridgeManager.shared.virtualPlaybackURL(specJSON: String(cString: specJSON)) else {
        return nil
    }
    return strdup(url)
}

@_cdecl("NuvioTelegramReadConcat")
func NuvioTelegramReadConcat(
    _ partsJSON: UnsafePointer<CChar>?,
    _ offset: Int64,
    _ length: Int32,
    _ outLength: UnsafeMutablePointer<Int32>?
) -> UnsafeMutablePointer<CChar>? {
    guard let partsJSON,
          let data = TelegramBridgeManager.shared.readConcat(
            partsJSON: String(cString: partsJSON),
            offset: offset,
            length: length
          ),
          !data.isEmpty,
          let raw = malloc(data.count) else {
        return nil
    }
    data.copyBytes(to: raw.assumingMemoryBound(to: UInt8.self), count: data.count)
    outLength?.pointee = Int32(data.count)
    return raw.assumingMemoryBound(to: CChar.self)
}

@_cdecl("NuvioTelegramCacheSize")
func NuvioTelegramCacheSize() -> Int64 {
    TelegramBridgeManager.shared.cacheSize()
}

@_cdecl("NuvioTelegramClearCache")
func NuvioTelegramClearCache() {
    TelegramBridgeManager.shared.clearCache()
}

@_cdecl("NuvioTelegramOptimizeCache")
func NuvioTelegramOptimizeCache() {
    TelegramBridgeManager.shared.optimizeCacheIfNeeded()
}

@_cdecl("NuvioTelegramFree")
func NuvioTelegramFree(_ value: UnsafeMutablePointer<CChar>?) {
    free(value)
}
