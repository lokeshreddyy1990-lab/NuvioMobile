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

/// Live playback state for one virtual asset, used to decide which parts may be evicted.
///
/// The player never asks for more than its demuxer window around the play head, so almost
/// all of a large split movie is stale by the time it has been watched. Tracking the head
/// and what the player still needs is what keeps a 23 GB file inside a 4 GB budget.
private struct VirtualPlaybackState {
    var highWaterMark: Int64 = 0
    var lastAccessAt: TimeInterval = 0
    /// Parts a player request is currently being served from. Never evictable.
    var activePartIds: Set<Int32> = []
    /// Bytes served through the loopback server for this asset. Used as the retention
    /// measure, so pinned/transient bytes do not inflate the reported cache size.
    var servedBytes: Int64 = 0
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
    // Playback URLs already handed to the player embed this port, so rebinds reuse it.
    private var preferredPort: NWEndpoint.Port?
    private var registeredFiles: [Int32: RegisteredTelegramFile] = [:]
    private var virtualAssets: [Int32: VirtualAsset] = [:]
    // A virtual asset is described entirely by its parts and byte range, so the same
    // archive always hashes to the same id. Without this, every Telegram search minted
    // a fresh id for the identical file, and the currently playing stream could never
    // be recognised by URL again.
    private var virtualIdsBySignature: [String: Int32] = [:]
    private var nextVirtualId: Int32 = 1
    // Bounds memory: every Telegram search mints a virtual asset, and without a cap this
    // map grows for the lifetime of the process. Insertion order is tracked separately so
    // the oldest entries can be dropped when the cap is exceeded.
    private var virtualAssetOrder: [Int32] = []
    private let maxVirtualAssets = 64
    private var apiId: Int32 = 0
    private var apiHash = ""
    private var appVersion = ""
    private var lastCacheOptimizeAt: TimeInterval = 0

    // Default retained-cache budget. Overridable from the settings UI; the value is
    // persisted by the Kotlin layer and pushed down via setCacheLimitBytes.
    private let defaultCacheLimitBytes: Int64 = 4 * 1024 * 1024 * 1024
    private(set) var cacheLimitBytes: Int64 = 4 * 1024 * 1024 * 1024
    private let storagePolicyTtl = 2_147_483_647
    private let storagePolicyCount = 2_147_483_647
    private let cacheOptimizeMinInterval: TimeInterval = 60
    // Files touched within this window are never touched by the coarse optimizeStorage
    // backstop, so a trim pass cannot race the player for bytes it is about to read.
    private let storageImmunityDelay = 300

    // Bytes served through the loopback server. This mirrors the on-disk cache closely
    // enough to act as a cheap "are we over budget?" signal without paying for a
    // synchronous getStorageStatisticsFast round-trip on every authorization poll.
    private var servedByteCounter: Int64 = 0
    private var lastServedByteReconcileAt: TimeInterval = 0
    private var lastKnownCacheSize: Int64 = 0
    // How long the served-byte counter is considered a usable proxy for on-disk size.
    private let cacheCounterTrustSeconds: TimeInterval = 120

    /// Active virtual assets keyed by the ids the loopback server serves.
    private var virtualPlayback: [Int32: VirtualPlaybackState] = [:]
    /// File ids belonging to any asset that is currently open. These are never evicted,
    /// regardless of position, because the player may re-read header or seek-index bytes.
    private var pinnedFileIds: Set<Int32> = []
    /// Bytes behind the play head retained so a short rewind or A/V resync does not force a
    /// re-download. Sized from the demuxer back buffer plus slack for container re-reads.
    private let backBufferBytes: Int64 = 192 * 1024 * 1024
    /// Bytes ahead of the play head retained to cover the demuxer forward window.
    private let forwardBufferBytes: Int64 = 640 * 1024 * 1024
    /// An asset with no read activity for this long is considered finished, so its pins
    /// are released and its remaining parts become evictable.
    private let idleAssetTimeoutSeconds: TimeInterval = 90

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
        let signature = virtualSignature(spec)
        lock.lock()
        let virtualId: Int32
        if let existing = virtualIdsBySignature[signature] {
            virtualId = existing
        } else {
            virtualId = nextVirtualId
            nextVirtualId += 1
            virtualIdsBySignature[signature] = virtualId
        }
        virtualAssets[virtualId] = spec
        // Pin every part of this asset for as long as it is registered. The player re-reads
        // headers and seek indexes at arbitrary times, so position alone is not enough.
        pinnedFileIds.formUnion(spec.parts.map { $0.fileId })
        virtualPlayback[virtualId] = VirtualPlaybackState(
            highWaterMark: 0,
            lastAccessAt: Date().timeIntervalSince1970,
        )
        touchVirtualAsset(virtualId)
        lock.unlock()
        let encodedName = encodedHeaderValue(spec.fileName)
        return "http://127.0.0.1:\(port)/telegram/v/\(virtualId)/\(encodedName)"
    }

    /// Marks which part is currently being read for a virtual asset and advances the play
    /// head. Called from the serving path, so it reflects what the player actually consumed.
    ///
    /// `globalOffset` is in part-stream coordinates (i.e. including any container inner
    /// offset), which is what the eviction window compares part ranges against.
    private func noteVirtualAccess(virtualId: Int32, globalOffset: Int64, fileId: Int32, bytes: Int) {
        lock.lock()
        var state = virtualPlayback[virtualId] ?? VirtualPlaybackState()
        state.lastAccessAt = Date().timeIntervalSince1970
        state.highWaterMark = max(state.highWaterMark, globalOffset + Int64(bytes))
        state.activePartIds.insert(fileId)
        state.servedBytes += Int64(bytes)
        virtualPlayback[virtualId] = state
        lock.unlock()
    }

    /// Clears the in-flight marker for a part once its serve completes, so the part becomes
    /// evictable again as soon as the head has moved past it.
    private func clearVirtualPartAccess(virtualId: Int32, fileId: Int32) {
        lock.lock()
        virtualPlayback[virtualId]?.activePartIds.remove(fileId)
        lock.unlock()
    }

    /// Releases pins for assets that have not been read from recently.
    ///
    /// Without this, a finished or abandoned playback would pin its parts for the lifetime
    /// of the process, which both leaks disk and prevents the coarse backstop from running.
    private func releaseIdleAssets() {
        let now = Date().timeIntervalSince1970
        lock.lock()
        let idleIds = virtualPlayback.compactMap { id, state in
            state.activePartIds.isEmpty && now - state.lastAccessAt > idleAssetTimeoutSeconds ? id : nil
        }
        for id in idleIds {
            if let dropped = virtualAssets[id] {
                pinnedFileIds.subtract(dropped.parts.map { $0.fileId })
            }
            virtualPlayback[id] = nil
        }
        lock.unlock()
    }

    /// Records access order and trims the oldest entries. Must be called with `lock` held.
    private func touchVirtualAsset(_ virtualId: Int32) {
        if let index = virtualAssetOrder.firstIndex(of: virtualId) {
            virtualAssetOrder.remove(at: index)
        }
        virtualAssetOrder.append(virtualId)
        while virtualAssetOrder.count > maxVirtualAssets {
            let evicted = virtualAssetOrder.removeFirst()
            guard evicted != virtualId else { continue }
            if let dropped = virtualAssets[evicted] {
                pinnedFileIds.subtract(dropped.parts.map { $0.fileId })
            }
            virtualAssets[evicted] = nil
            virtualPlayback[evicted] = nil
            // The signature mapping would otherwise resurrect a stale asset whose parts
            // were already dropped, producing a URL the server cannot serve.
            virtualIdsBySignature = virtualIdsBySignature.filter { $0.value != evicted }
        }
    }

    func readConcat(partsJSON: String, offset: Int64, length: Int32) -> Data? {
        guard let parts = parseVirtualParts(partsJSON), length > 0 else { return nil }
        return readConcatRange(parts: parts, start: offset, end: offset + Int64(length) - 1)
    }

    /// Cheap cached measurement for polling paths. Returns the last reconciled value
    /// without blocking on a TDLib round-trip.
    func cachedCacheSize() -> Int64 {
        lock.lock()
        let value = lastKnownCacheSize
        lock.unlock()
        return value
    }

    /// Authoritative measurement. Only call from user-initiated paths (settings page open,
    /// clear cache, eviction pass) — it performs a synchronous TDLib request.
    func cacheSize() -> Int64 {
        let measured = storageSizeFast() ?? directorySize(url: filesDirectory)
        lock.lock()
        lastKnownCacheSize = measured
        // Reset the served-byte counter: it is now known to be a lower bound only, and
        // leaving it stale would keep inflating the estimate on top of a fresh reading.
        servedByteCounter = measured
        lastServedByteReconcileAt = Date().timeIntervalSince1970
        lock.unlock()
        return measured
    }

    func setCacheLimitBytes(_ limit: Int64) {
        // 0 is TDLib's "delete everything" sentinel, not "no cache" — reject it so a
        // mis-set limit can never be interpreted as a wipe.
        guard limit > 0 else { return }
        lock.lock()
        cacheLimitBytes = limit
        lastCacheOptimizeAt = 0
        lock.unlock()
    }

    func clearCache() {
        optimizeStorage(limitBytes: 0)
        lock.lock()
        lastKnownCacheSize = 0
        servedByteCounter = 0
        lastServedByteReconcileAt = Date().timeIntervalSince1970
        lock.unlock()
    }

    /// Reclaims parts of an in-progress virtual asset that the player can no longer reach.
    ///
    /// The player only ever holds a demuxer window around the play head, so on a large split
    /// movie every part once fully behind the head is dead weight. This is what stops a 23 GB
    /// file from pulling all 23 GB onto disk.
    ///
    /// Safety rules, in order of priority:
    /// 1. Never touch a part with a serve in flight.
    /// 2. Never touch a part still inside the retained window (the head may move backwards).
    /// 3. Never touch the first or last part: containers store header and seek index there.
    /// 4. Delete at most one part per call, and only after the head is a full back-buffer
    ///    past the part's end. This keeps a concurrent `downloadFile` for a neighbouring
    ///    part from being disturbed, since TDLib deletes whole files rather than ranges.
    private func evictStalePartsIfNeeded(virtualId: Int32) {
        lock.lock()
        guard let asset = virtualAssets[virtualId],
              let state = virtualPlayback[virtualId] else {
            lock.unlock()
            return
        }
        // Head offsets are tracked in part-stream coordinates, the same space the part
        // ranges live in, so they compare directly below.
        let head = state.highWaterMark
        let active = state.activePartIds
        let retainBehind = backBufferBytes
        let retainAhead = forwardBufferBytes
        let parts = asset.parts
        let mounted = pinnedFileIds
        let assetStart = asset.innerOffset
        let assetEnd = asset.innerOffset + asset.innerSize
        lock.unlock()

        guard parts.count > 2 else { return }
        let lastIndex = parts.count - 1
        // Head offsets outside the asset (e.g. before playback started) carry no signal.
        guard head >= assetStart else { return }

        for (index, part) in parts.enumerated() {
            // Rules 1 and 3.
            if index == 0 || index == lastIndex { continue }
            if active.contains(part.fileId) { continue }
            guard mounted.contains(part.fileId) else { continue }

            let partStart = part.cumStart
            let partEnd = part.cumStart + part.size
            // Rule 2: keep anything that overlaps the retained window, measured in the same
            // part-stream coordinates the head is tracked in.
            let windowStart = max(0, head - retainBehind)
            let windowEnd = min(assetEnd, head + retainAhead)
            if partEnd > windowStart && partStart < windowEnd { continue }
            // Only reclaim fully-consumed parts.
            guard partEnd <= windowStart else { continue }

            // Reclaim off the serving path: deleteFile is a blocking TDLib round-trip and
            // running it inline would stall the response for the chunk currently being sent.
            // Drop the pin first so this part is not reconsidered on every later chunk.
            lock.lock()
            self.pinnedFileIds.remove(part.fileId)
            lock.unlock()
            let fileId = part.fileId
            DispatchQueue.global(qos: .utility).async { [weak self] in
                self?.deleteCacheFile(fileId: fileId)
            }
            // One reclamation per call: reclaiming in bulk while the player is issuing
            // overlapping reads risks deleting a part that is about to be requested.
            return
        }
    }

    /// Removes one file's downloaded data without disturbing anything else.
    ///
    /// Deliberately not `optimizeStorage`: that call takes a size budget and a file-type
    /// filter, so it cannot exclude the file being played and will evict live bytes.
    private func deleteCacheFile(fileId: Int32) {
        _ = request([
            "@type": "deleteFile",
            "file_id": fileId,
        ], timeout: 30)
    }

    func optimizeCacheIfNeeded() {
        releaseIdleAssets()
        let now = Date().timeIntervalSince1970
        let limit: Int64
        lock.lock()
        // A stream is open: per-part eviction owns reclamation now. The coarse pass cannot
        // exclude the live file and would delete bytes the player is reading.
        guard pinnedFileIds.isEmpty else {
            lock.unlock()
            return
        }
        guard now - lastCacheOptimizeAt >= cacheOptimizeMinInterval else {
            lock.unlock()
            return
        }
        limit = cacheLimitBytes
        lock.unlock()

        // Prefer the cheap counter; only fall back to the blocking measurement when the
        // counter cannot answer the question.
        lock.lock()
        let counterAge = now - lastServedByteReconcileAt
        let counterValue = lastKnownCacheSize
        lock.unlock()
        let candidate = counterAge <= cacheCounterTrustSeconds
            ? counterValue
            : cacheSize()
        guard candidate > limit else {
            // Under budget: remember that the decision was made so the next tick reuses the
            // counter instead of paying for another blocking measurement.
            lock.lock()
            lastServedByteReconcileAt = now
            lock.unlock()
            return
        }

        lock.lock()
        lastCacheOptimizeAt = now
        lock.unlock()
        optimizeStorage(limitBytes: limit)
        // The trim changed the picture; force a re-measure on next demand rather than
        // trusting a stale number.
        lock.lock()
        lastServedByteReconcileAt = 0
        lock.unlock()
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
            "immunity_delay": storageImmunityDelay,
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

    /// Re-checks the loopback server after iOS may have suspended it, rebinding on the same port if dead.
    func restartServerIfStale() {
        lock.lock()
        let port = listenerPort
        lock.unlock()
        guard let port, let endpointPort = NWEndpoint.Port(rawValue: port) else { return }

        let probe = NWConnection(host: "127.0.0.1", port: endpointPort, using: .tcp)
        let probed = DispatchSemaphore(value: 0)
        var reachable = false
        probe.stateUpdateHandler = { state in
            switch state {
            case .ready:
                reachable = true
                probed.signal()
            case .failed, .cancelled:
                probed.signal()
            default:
                break
            }
        }
        probe.start(queue: serverQueue)
        _ = probed.wait(timeout: .now() + 1)
        probe.cancel()
        guard !reachable else { return }

        lock.lock()
        listener?.cancel()
        listener = nil
        listenerPort = nil
        lock.unlock()
        _ = ensureServer()
    }

    private func ensureServer() -> UInt16? {
        lock.lock()
        if let listenerPort {
            lock.unlock()
            return listenerPort
        }
        let port = preferredPort
        lock.unlock()

        if let started = startServer(on: port) {
            return started
        }
        guard port != nil else { return nil }
        // Preferred port could not be rebound; fall back to any free port.
        lock.lock()
        preferredPort = nil
        lock.unlock()
        return startServer(on: nil)
    }

    private func startServer(on port: NWEndpoint.Port?) -> UInt16? {
        let ready = DispatchSemaphore(value: 0)
        do {
            let parameters = NWParameters.tcp
            parameters.allowLocalEndpointReuse = true
            parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: port ?? .any)
            let newListener = try NWListener(using: parameters, on: port ?? .any)
            newListener.newConnectionHandler = { [weak self] connection in
                self?.handle(connection: connection)
            }
            newListener.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    self?.lock.lock()
                    self?.listenerPort = newListener.port?.rawValue
                    self?.preferredPort = newListener.port
                    self?.lock.unlock()
                    ready.signal()
                case .failed, .cancelled:
                    // Drop the dead listener so the next request rebinds instead of reusing its port.
                    self?.lock.lock()
                    if self?.listener === newListener {
                        self?.listener = nil
                        self?.listenerPort = nil
                    }
                    self?.lock.unlock()
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
                    virtualId: virtualId,
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
            virtualId: nil,
            on: connection
        )
    }

    private func streamConcat(
        parts: [VirtualPart],
        offset: Int64,
        end: Int64,
        virtualId: Int32?,
        on connection: NWConnection
    ) {
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
                if let virtualId {
                    self?.clearVirtualPartAccess(virtualId: virtualId, fileId: mapped.part.fileId)
                }
                connection.cancel()
                return
            }
            connection.send(content: data, completion: .contentProcessed { [weak self] error in
                if let virtualId {
                    self?.clearVirtualPartAccess(virtualId: virtualId, fileId: mapped.part.fileId)
                }
                guard error == nil else {
                    connection.cancel()
                    return
                }
                if let virtualId {
                    self?.noteVirtualAccess(
                        virtualId: virtualId,
                        globalOffset: offset,
                        fileId: mapped.part.fileId,
                        bytes: data.count
                    )
                    // Past the head: reclaim parts that can no longer be needed.
                    self?.evictStalePartsIfNeeded(virtualId: virtualId)
                }
                self?.streamConcat(
                    parts: parts,
                    offset: offset + Int64(data.count),
                    end: end,
                    virtualId: virtualId,
                    on: connection
                )
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
            let data = try handle.read(upToCount: Int(length))
            noteServedBytes(data?.count ?? 0)
            return data
        } catch {
            return nil
        }
    }

    /// Tracks bytes handed to the player so eviction decisions do not need a blocking
    /// getStorageStatisticsFast call. The counter only ever grows, so it is a lower bound
    /// on the on-disk cache — safe for "are we over budget?" checks.
    private func noteServedBytes(_ count: Int) {
        guard count > 0 else { return }
        lock.lock()
        servedByteCounter += Int64(count)
        if servedByteCounter > lastKnownCacheSize {
            lastKnownCacheSize = servedByteCounter
        }
        lock.unlock()
    }

    /// Stable identity of a virtual asset: the ordered part list plus the exposed byte
    /// range and name. Part order matters because the parts are concatenated as given.
    private func virtualSignature(_ asset: VirtualAsset) -> String {
        var tokens = asset.parts.map { "\($0.fileId):\($0.size)" }
        tokens.append("offset=\(asset.innerOffset)")
        tokens.append("size=\(asset.innerSize)")
        tokens.append("name=\(asset.fileName)")
        return tokens.joined(separator: "|")
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

@_cdecl("NuvioTelegramCachedCacheSize")
func NuvioTelegramCachedCacheSize() -> Int64 {
    TelegramBridgeManager.shared.cachedCacheSize()
}

@_cdecl("NuvioTelegramSetCacheLimit")
func NuvioTelegramSetCacheLimit(_ limitBytes: Int64) {
    TelegramBridgeManager.shared.setCacheLimitBytes(limitBytes)
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
