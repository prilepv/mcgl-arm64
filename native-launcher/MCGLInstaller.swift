import CryptoKit
import Darwin
import Foundation

final class MCGLInstaller {
    private struct ManifestEntry {
        let md5: String
        let path: String
    }

    private let fileManager = FileManager.default
    private let supportRootURL: URL
    private let gameDirectoryURL: URL
    private let portSupportURL: URL
    private let patchToolsURL: URL
    private let javaExecutableURL: URL
    private let jarExecutableURL: URL
    private let resolverLock = NSLock()
    private var resolvedAddressCache: [String: [String]] = [:]
    private let portMarker = "Minecraft Galaxy ARM64 bootstrap 1.6.4\n"
    private let mirrors = [
        URL(string: "http://f1.mcgl.ru/mclient/")!,
        URL(string: "http://f3.mcgl.ru/mclient/")!,
        URL(string: "http://f2.mcgl.ru/mclient/")!
    ]

    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 180
        configuration.httpMaximumConnectionsPerHost = 6
        configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        return URLSession(configuration: configuration)
    }()

    init(supportRootURL: URL,
         gameDirectoryURL: URL,
         portSupportURL: URL,
         patchToolsURL: URL,
         javaExecutableURL: URL,
         jarExecutableURL: URL) {
        self.supportRootURL = supportRootURL
        self.gameDirectoryURL = gameDirectoryURL
        self.portSupportURL = portSupportURL
        self.patchToolsURL = patchToolsURL
        self.javaExecutableURL = javaExecutableURL
        self.jarExecutableURL = jarExecutableURL
    }

    func install(progress: @escaping (String) -> Void,
                 completion: @escaping (Result<Void, Error>) -> Void) {
        DispatchQueue.global(qos: .utility).async { [self] in
            let result: Result<Void, Error>
            do {
                try performInstall(progress: progress)
                result = .success(())
            } catch {
                result = .failure(error)
            }
            DispatchQueue.main.async {
                completion(result)
            }
        }
    }

    private func performInstall(progress: @escaping (String) -> Void) throws {
        guard fileManager.fileExists(atPath: portSupportURL.path) else {
            throw installerError("в приложении отсутствуют компоненты ARM64-порта")
        }
        guard fileManager.isExecutableFile(atPath: javaExecutableURL.path),
              fileManager.isExecutableFile(atPath: jarExecutableURL.path) else {
            throw installerError("в приложении отсутствуют инструменты Java 8")
        }

        try fileManager.createDirectory(at: supportRootURL,
                                        withIntermediateDirectories: true)
        let temporaryURL = supportRootURL
            .appendingPathComponent(".updating-\(UUID().uuidString)", isDirectory: true)
        try fileManager.createDirectory(at: temporaryURL,
                                        withIntermediateDirectories: true)
        defer { try? fileManager.removeItem(at: temporaryURL) }

        progress("Получение официального списка файлов MCGL…")
        let (manifestData, versionData, preferredMirrors) = try fetchVerifiedManifest()
        let entries = try parseManifest(manifestData)
            .filter { $0.path != "version.md5" }
        guard !entries.isEmpty else {
            throw installerError("официальный список файлов пуст")
        }

        let profileExists = fileManager.fileExists(atPath: gameDirectoryURL.path)
        let previousManifestURL = gameDirectoryURL.appendingPathComponent("update_f.lst")
        let previousManifestData = try? Data(contentsOf: previousManifestURL)
        let previousEntries = previousManifestData
            .flatMap { try? parseManifest($0) } ?? []
        let previousByPath = Dictionary(
            uniqueKeysWithValues: previousEntries.map { ($0.path, $0.md5) })
        let currentByPath = Dictionary(
            uniqueKeysWithValues: entries.map { ($0.path, $0.md5) })

        let markerURL = gameDirectoryURL.appendingPathComponent(".arm64-port-version")
        let installedMarker = try? String(contentsOf: markerURL, encoding: .utf8)
        let needsPortRefresh = installedMarker != portMarker
        let portOwnedPaths = try portSupportPaths()

        progress(profileExists
            ? "Проверка установленных файлов MCGL…"
            : "Подготовка новой установки MCGL…")

        var entriesToDownload: [ManifestEntry] = []
        for (index, entry) in entries.enumerated() {
            let destination = gameDirectoryURL.appendingPathComponent(entry.path)
            let exists = fileManager.fileExists(atPath: destination.path)
            let previousHash = previousByPath[entry.path]
            let requiresDownload: Bool

            if !exists {
                requiresDownload = true
            } else if entry.path == "bin/mcgl.jar" {
                // The installed jar contains our fullscreen bytecode patch, so
                // its checksum intentionally differs from the official file.
                requiresDownload = needsPortRefresh || previousHash != entry.md5
            } else if portOwnedPaths.contains(entry.path) {
                // ARM64 replacements intentionally differ from the official
                // Intel files.  Reinstall them from PortSupport below.
                requiresDownload = false
            } else {
                requiresDownload = (try? md5(ofFileAt: destination)) != entry.md5
            }

            if requiresDownload { entriesToDownload.append(entry) }
            if index % 100 == 0 || index + 1 == entries.count {
                let percent = Int((Double(index + 1) / Double(entries.count)) * 100.0)
                progress("Проверка установленных файлов MCGL: \(percent)%")
            }
        }

        let removedPaths = Set(previousByPath.keys)
            .subtracting(currentByPath.keys)
            .subtracting(portOwnedPaths)
            .filter { $0 != "version.md5" }
        let localVersionData = try? Data(
            contentsOf: gameDirectoryURL.appendingPathComponent("version.md5"))
        let metadataChanged = previousManifestData != manifestData ||
            localVersionData != versionData || needsPortRefresh

        if entriesToDownload.isEmpty && removedPaths.isEmpty && !metadataChanged {
            progress("Клиент MCGL обновлён не требуется.")
            return
        }

        if !entriesToDownload.isEmpty {
            progress("Загрузка обновлений MCGL: 0%")
            try download(entries: entriesToDownload,
                         to: temporaryURL,
                         mirrors: preferredMirrors,
                         progress: progress)
        }

        try manifestData.write(to: temporaryURL.appendingPathComponent("update_f.lst"),
                               options: .atomic)
        try versionData.write(to: temporaryURL.appendingPathComponent("version.md5"),
                              options: .atomic)

        progress("Установка нативных компонентов Apple Silicon…")
        try overlayPortSupport(from: portSupportURL, to: temporaryURL)

        let stagedGameJar = temporaryURL.appendingPathComponent("bin/mcgl.jar")
        if fileManager.fileExists(atPath: stagedGameJar.path) {
            progress("Применение ARM64, оконных патчей и оптимизации эффектов…")
            try patchClient(in: temporaryURL)
        }

        try Data(portMarker.utf8).write(
            to: temporaryURL.appendingPathComponent(".arm64-port-version"),
            options: .atomic)

        if profileExists {
            progress("Установка проверенных обновлений MCGL…")
            try applyStagedFiles(from: temporaryURL, to: gameDirectoryURL)
            for path in removedPaths.sorted() {
                try validate(relativePath: path)
                let obsolete = gameDirectoryURL.appendingPathComponent(path)
                if fileManager.fileExists(atPath: obsolete.path) {
                    try fileManager.removeItem(at: obsolete)
                }
            }
            progress(entriesToDownload.isEmpty
                ? "ARM64-компоненты клиента обновлены."
                : "Обновление MCGL установлено: \(entriesToDownload.count) файл(ов).")
        } else {
            try fileManager.moveItem(at: temporaryURL, to: gameDirectoryURL)
            progress("Официальный клиент загружен; ARM64-порт установлен.")
        }
    }

    private func portSupportPaths() throws -> Set<String> {
        guard let enumerator = fileManager.enumerator(
            at: portSupportURL,
            includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey],
            options: [.skipsHiddenFiles]) else {
            throw installerError("не удалось прочитать компоненты ARM64-порта")
        }

        let prefix = portSupportURL.standardizedFileURL.path + "/"
        var paths = Set<String>()
        for case let source as URL in enumerator {
            let values = try source.resourceValues(
                forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
            guard values.isSymbolicLink != true else {
                throw installerError("символические ссылки в ARM64-компонентах запрещены")
            }
            guard values.isRegularFile == true else { continue }
            let sourcePath = source.standardizedFileURL.path
            guard sourcePath.hasPrefix(prefix) else {
                throw installerError("компонент ARM64 вышел за пределы каталога порта")
            }
            let relativePath = String(sourcePath.dropFirst(prefix.count))
            try validate(relativePath: relativePath)
            paths.insert(relativePath)
        }
        return paths
    }

    private func md5(ofFileAt url: URL) throws -> String {
        let data = try Data(contentsOf: url, options: .mappedIfSafe)
        return md5(data)
    }

    private func applyStagedFiles(from stagingRoot: URL,
                                  to destinationRoot: URL) throws {
        guard let enumerator = fileManager.enumerator(
            at: stagingRoot,
            includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey],
            options: []) else {
            throw installerError("не удалось прочитать подготовленное обновление")
        }

        let prefix = stagingRoot.standardizedFileURL.path + "/"
        var files: [(URL, String)] = []
        for case let source as URL in enumerator {
            let values = try source.resourceValues(
                forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
            guard values.isSymbolicLink != true else {
                throw installerError("символическая ссылка обнаружена в обновлении")
            }
            guard values.isRegularFile == true else { continue }
            let sourcePath = source.standardizedFileURL.path
            guard sourcePath.hasPrefix(prefix) else {
                throw installerError("файл обновления вышел за пределы временного каталога")
            }
            let relativePath = String(sourcePath.dropFirst(prefix.count))
            try validate(relativePath: relativePath)
            files.append((source, relativePath))
        }

        for (source, relativePath) in files {
            let destination = destinationRoot.appendingPathComponent(relativePath)
            try fileManager.createDirectory(
                at: destination.deletingLastPathComponent(),
                withIntermediateDirectories: true)
            if fileManager.fileExists(atPath: destination.path) {
                _ = try fileManager.replaceItemAt(
                    destination,
                    withItemAt: source,
                    backupItemName: nil,
                    options: [.usingNewMetadataOnly])
            } else {
                try fileManager.moveItem(at: source, to: destination)
            }
        }
    }

    private func fetchVerifiedManifest() throws -> (Data, Data, [URL]) {
        var lastError: Error?
        for (index, mirror) in mirrors.enumerated() {
            do {
                let versionData = try fetch(relativePath: "version.md5", from: mirror)
                let manifestData = try fetch(relativePath: "update.lst", from: mirror)
                guard let versionText = String(data: versionData, encoding: .utf8),
                      let expectedMD5 = firstMD5(in: versionText) else {
                    throw installerError("зеркало вернуло некорректный version.md5")
                }
                guard md5(manifestData) == expectedMD5 else {
                    throw installerError("контрольная сумма официального update.lst не совпала")
                }
                var preferred = [mirror]
                preferred.append(contentsOf: mirrors.enumerated()
                    .filter { $0.offset != index }
                    .map { $0.element })
                return (manifestData, versionData, preferred)
            } catch {
                lastError = error
            }
        }
        throw lastError ?? installerError("официальные зеркала MCGL недоступны")
    }

    private func parseManifest(_ data: Data) throws -> [ManifestEntry] {
        guard let text = String(data: data, encoding: .utf8) else {
            throw installerError("официальный update.lst имеет неизвестную кодировку")
        }

        var entries: [ManifestEntry] = []
        var paths = Set<String>()
        for rawLine in text.split(whereSeparator: { $0.isNewline }) {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty { continue }
            let fields = line.split(maxSplits: 1, whereSeparator: { $0.isWhitespace })
            guard fields.count == 2 else {
                throw installerError("повреждённая строка в официальном update.lst")
            }
            let hash = String(fields[0]).lowercased()
            guard hash.count == 32,
                  hash.allSatisfy({ $0.isHexDigit }) else {
                throw installerError("некорректная контрольная сумма в update.lst")
            }

            var path = String(fields[1]).trimmingCharacters(in: .whitespaces)
            while path.hasPrefix("./") { path.removeFirst(2) }
            try validate(relativePath: path)
            guard paths.insert(path).inserted else {
                throw installerError("повторяющийся путь в update.lst: \(path)")
            }
            entries.append(ManifestEntry(md5: hash, path: path))
        }
        return entries
    }

    private func download(entries: [ManifestEntry],
                          to rootURL: URL,
                          mirrors: [URL],
                          progress: @escaping (String) -> Void) throws {
        let queue = OperationQueue()
        queue.name = "community.mcgl.arm64-port.downloads"
        queue.qualityOfService = .utility
        queue.maxConcurrentOperationCount = 6

        let stateLock = NSLock()
        var firstError: Error?
        var completed = 0
        var lastReportedDecile = 0

        for entry in entries {
            queue.addOperation { [self] in
                stateLock.lock()
                let shouldStop = firstError != nil
                stateLock.unlock()
                if shouldStop { return }

                do {
                    let data = try fetch(relativePath: entry.path, from: mirrors)
                    guard md5(data) == entry.md5 else {
                        throw installerError("не совпала контрольная сумма файла \(entry.path)")
                    }
                    let destination = rootURL.appendingPathComponent(entry.path)
                    try fileManager.createDirectory(
                        at: destination.deletingLastPathComponent(),
                        withIntermediateDirectories: true)
                    try data.write(to: destination, options: .atomic)

                    stateLock.lock()
                    completed += 1
                    let decile = Int((Double(completed) / Double(entries.count)) * 10.0)
                    let shouldReport = decile > lastReportedDecile || completed == entries.count
                    if shouldReport { lastReportedDecile = decile }
                    stateLock.unlock()
                    if shouldReport {
                        progress("Загрузка официального клиента MCGL: \(min(decile * 10, 100))%")
                    }
                } catch {
                    stateLock.lock()
                    if firstError == nil { firstError = error }
                    stateLock.unlock()
                }
            }
        }

        queue.waitUntilAllOperationsAreFinished()
        if let firstError { throw firstError }
    }

    private func fetch(relativePath: String, from mirrors: [URL]) throws -> Data {
        var lastError: Error?
        for mirror in mirrors {
            do {
                return try fetch(relativePath: relativePath, from: mirror)
            } catch {
                lastError = error
            }
        }
        throw lastError ?? installerError("не удалось скачать \(relativePath)")
    }

    private func fetch(relativePath: String, from mirror: URL) throws -> Data {
        try validate(relativePath: relativePath)
        var url = mirror
        for component in relativePath.split(separator: "/") {
            url.appendPathComponent(String(component))
        }

        guard let host = mirror.host else {
            throw installerError("в адресе зеркала отсутствует имя сервера")
        }
        var requests: [URLRequest] = []
        for address in resolvedAddresses(for: host) {
            guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
                continue
            }
            components.host = address
            guard let numericURL = components.url else { continue }
            var request = URLRequest(url: numericURL)
            request.setValue(host, forHTTPHeaderField: "Host")
            requests.append(request)
        }
        requests.append(URLRequest(url: url))

        var lastError: Error?
        for request in requests {
            do {
                return try fetch(request: request, displayPath: relativePath)
            } catch {
                lastError = error
            }
        }
        throw lastError ?? installerError("не удалось скачать \(relativePath)")
    }

    private func fetch(request: URLRequest, displayPath: String) throws -> Data {
        let semaphore = DispatchSemaphore(value: 0)
        var result: Result<Data, Error>?
        let task = session.dataTask(with: request) { data, response, error in
            defer { semaphore.signal() }
            if let error {
                result = .failure(error)
                return
            }
            guard let response = response as? HTTPURLResponse,
                  response.statusCode == 200,
                  let data else {
                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                result = .failure(self.installerError(
                    "зеркало вернуло HTTP \(code) для \(displayPath)"))
                return
            }
            result = .success(data)
        }
        task.resume()
        semaphore.wait()
        guard let result else {
            throw installerError("загрузка \(displayPath) завершилась без результата")
        }
        return try result.get()
    }

    private func resolvedAddresses(for host: String) -> [String] {
        resolverLock.lock()
        if let cached = resolvedAddressCache[host] {
            resolverLock.unlock()
            return cached
        }
        resolverLock.unlock()

        var head: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, nil, nil, &head) == 0, let first = head else {
            resolverLock.lock()
            resolvedAddressCache[host] = []
            resolverLock.unlock()
            return []
        }
        defer { freeaddrinfo(first) }

        var addresses: [String] = []
        var current: UnsafeMutablePointer<addrinfo>? = first
        while let info = current {
            if info.pointee.ai_family == AF_INET || info.pointee.ai_family == AF_INET6 {
                var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(info.pointee.ai_addr,
                               info.pointee.ai_addrlen,
                               &buffer,
                               socklen_t(buffer.count),
                               nil,
                               0,
                               NI_NUMERICHOST) == 0 {
                    let address = String(cString: buffer)
                    if !addresses.contains(address) { addresses.append(address) }
                }
            }
            current = info.pointee.ai_next
        }

        resolverLock.lock()
        resolvedAddressCache[host] = addresses
        resolverLock.unlock()
        return addresses
    }

    private func overlayPortSupport(from sourceRoot: URL, to destinationRoot: URL) throws {
        guard let enumerator = fileManager.enumerator(
            at: sourceRoot,
            includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey],
            options: [.skipsHiddenFiles]) else {
            throw installerError("не удалось прочитать компоненты ARM64-порта")
        }

        let sourcePrefix = sourceRoot.standardizedFileURL.path + "/"
        for case let source as URL in enumerator {
            let values = try source.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            guard values.isSymbolicLink != true else {
                throw installerError("символические ссылки в ARM64-компонентах запрещены")
            }
            let sourcePath = source.standardizedFileURL.path
            guard sourcePath.hasPrefix(sourcePrefix) else {
                throw installerError("компонент ARM64 вышел за пределы каталога порта")
            }
            let relativePath = String(sourcePath.dropFirst(sourcePrefix.count))
            try validate(relativePath: relativePath)
            let destination = destinationRoot.appendingPathComponent(relativePath)
            if values.isDirectory == true {
                try fileManager.createDirectory(at: destination,
                                                withIntermediateDirectories: true)
            } else {
                try fileManager.createDirectory(
                    at: destination.deletingLastPathComponent(),
                    withIntermediateDirectories: true)
                if fileManager.fileExists(atPath: destination.path) {
                    try fileManager.removeItem(at: destination)
                }
                try fileManager.copyItem(at: source, to: destination)
            }
        }
    }

    private func patchClient(in gameRoot: URL) throws {
        let gameJar = gameRoot.appendingPathComponent("bin/mcgl.jar")
        let arm64PatchClass = patchToolsURL.appendingPathComponent("ClassBytePatch.class")
        let fullscreenPatchClass = patchToolsURL.appendingPathComponent("PatchMCGLFullscreen.class")
        let performancePatchClass = patchToolsURL.appendingPathComponent("PatchMCGLPerformance.class")
        let asmJar = patchToolsURL.appendingPathComponent("asm-debug-all.jar")
        guard fileManager.fileExists(atPath: gameJar.path),
              fileManager.fileExists(atPath: arm64PatchClass.path),
              fileManager.fileExists(atPath: fullscreenPatchClass.path),
              fileManager.fileExists(atPath: performancePatchClass.path),
              fileManager.fileExists(atPath: asmJar.path) else {
            throw installerError("не найдены файлы ARM64/fullscreen-патчей")
        }

        let workURL = supportRootURL
            .appendingPathComponent(".patching-\(UUID().uuidString)", isDirectory: true)
        let inputClass = workURL.appendingPathComponent("Minecraft.input.class")
        let stagedRoot = workURL.appendingPathComponent("staged", isDirectory: true)
        let outputClass = stagedRoot
            .appendingPathComponent("net/minecraft/client/Minecraft.class")
        try fileManager.createDirectory(at: outputClass.deletingLastPathComponent(),
                                        withIntermediateDirectories: true)
        defer { try? fileManager.removeItem(at: workURL) }

        let classData = try runProcess(
            executable: URL(fileURLWithPath: "/usr/bin/unzip"),
            arguments: ["-p", gameJar.path, "net/minecraft/client/Minecraft.class"],
            captureStandardOutput: true)
        guard !classData.isEmpty else {
            throw installerError("класс Minecraft не найден в официальном mcgl.jar")
        }
        try classData.write(to: inputClass, options: .atomic)

        // The direct Cocoa launcher from 1.0.0 requires this base patch: it
        // removes the legacy AWT loading-screen calls and prevents the game
        // from replacing the already-created standalone LWJGL Display.  The
        // fullscreen transform is intentionally applied only after it.
        _ = try runProcess(
            executable: javaExecutableURL,
            arguments: [
                "-cp", patchToolsURL.path,
                "ClassBytePatch", inputClass.path
            ])
        _ = try runProcess(
            executable: javaExecutableURL,
            arguments: [
                "-cp", patchToolsURL.path + ":" + asmJar.path,
                "PatchMCGLFullscreen", inputClass.path, outputClass.path
            ])
        _ = try runProcess(
            executable: jarExecutableURL,
            arguments: [
                "uf", gameJar.path,
                "-C", stagedRoot.path,
                "net/minecraft/client/Minecraft.class"
            ])
        let optimizedJar = gameRoot.appendingPathComponent("bin/mcgl.optimized.jar")
        _ = try runProcess(
            executable: javaExecutableURL,
            arguments: [
                "-Djava.awt.headless=true", "-cp",
                patchToolsURL.path + ":" + asmJar.path + ":" +
                    gameRoot.appendingPathComponent("bin/*").path + ":" +
                    gameDirectoryURL.appendingPathComponent("bin/*").path,
                "PatchMCGLPerformance", gameJar.path, optimizedJar.path
            ])
        try fileManager.removeItem(at: gameJar)
        try fileManager.moveItem(at: optimizedJar, to: gameJar)
    }

    @discardableResult
    private func runProcess(executable: URL,
                            arguments: [String],
                            captureStandardOutput: Bool = false) throws -> Data {
        let process = Process()
        process.executableURL = executable
        process.arguments = arguments
        let output = Pipe()
        let errors = Pipe()
        process.standardOutput = output
        process.standardError = errors
        try process.run()
        let outputData = output.fileHandleForReading.readDataToEndOfFile()
        let errorData = errors.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            let detail = String(data: errorData + outputData, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            throw installerError(detail?.isEmpty == false
                ? detail!
                : "вспомогательный процесс завершился с кодом \(process.terminationStatus)")
        }
        return captureStandardOutput ? outputData : Data()
    }

    private func validate(relativePath: String) throws {
        guard !relativePath.isEmpty,
              !relativePath.hasPrefix("/"),
              !relativePath.contains("\\") else {
            throw installerError("небезопасный путь в manifest: \(relativePath)")
        }
        let components = relativePath.split(separator: "/", omittingEmptySubsequences: false)
        guard !components.contains(where: { $0.isEmpty || $0 == "." || $0 == ".." }) else {
            throw installerError("небезопасный путь в manifest: \(relativePath)")
        }
    }

    private func firstMD5(in text: String) -> String? {
        for token in text.split(whereSeparator: { $0.isWhitespace }) {
            let value = String(token).lowercased()
            if value.count == 32 && value.allSatisfy({ $0.isHexDigit }) {
                return value
            }
        }
        return nil
    }

    private func md5(_ data: Data) -> String {
        Insecure.MD5.hash(data: data)
            .map { String(format: "%02x", $0) }
            .joined()
    }

    private func installerError(_ description: String) -> NSError {
        NSError(domain: "MCGLBootstrapInstaller", code: 1,
                userInfo: [NSLocalizedDescriptionKey: description])
    }
}
