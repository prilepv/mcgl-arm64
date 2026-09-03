import Cocoa
import Darwin

final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate, NSTextFieldDelegate {
    private let preferences: MCGLLauncherPreferences

    init(preferences: MCGLLauncherPreferences = MCGLLauncherPreferences()) {
        self.preferences = preferences
        super.init()
    }
    private let supportedMemoryMB = [1024, 2048, 4096, 6144, 8192]
    private lazy var resourcesRoot: URL = {
        guard let resourceURL = Bundle.main.resourceURL else {
            fatalError("Application resources are unavailable")
        }
        return resourceURL
    }()
    private lazy var portSupportURL = resourcesRoot
        .appendingPathComponent("PortSupport", isDirectory: true)
    private lazy var patchToolsURL = resourcesRoot
        .appendingPathComponent("PatchTools", isDirectory: true)
    private lazy var supportRootURL: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                            in: .userDomainMask).first!
        return base.appendingPathComponent("Minecraft Galaxy ARM64", isDirectory: true)
    }()
    private lazy var gameDirectoryURL = supportRootURL
        .appendingPathComponent("mclient-arm64", isDirectory: true)
    private lazy var gameDirectory = gameDirectoryURL.path
    private lazy var javaVMPath = resourcesRoot
        .appendingPathComponent("java8-arm64", isDirectory: true)
        .appendingPathComponent("Home/jre/lib/server/libjvm.dylib").path
    private lazy var javaExecutableURL = resourcesRoot
        .appendingPathComponent("java8-arm64/Home/bin/java")
    private lazy var jarExecutableURL = resourcesRoot
        .appendingPathComponent("java8-arm64/Home/bin/jar")
    private lazy var gameRuntimePath = resourcesRoot
        .appendingPathComponent("MCGL ARM64 Runtime.app", isDirectory: true).path
    private lazy var logPath: String = {
        let base = FileManager.default.urls(for: .libraryDirectory,
                                            in: .userDomainMask).first!
        let directory = base
            .appendingPathComponent("Logs", isDirectory: true)
            .appendingPathComponent("Minecraft Galaxy ARM64", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory,
                                                 withIntermediateDirectories: true)
        return directory.appendingPathComponent("launcher.log").path
    }()

    private var window: NSWindow!
    private var loginField: NSTextField!
    private var passwordField: NSSecureTextField!
    private var rememberLoginButton: NSButton!
    private var fpsPopUp: NSPopUpButton!
    private var launchButton: NSButton!
    private var stopButton: NSButton!
    private var memoryPopUp: NSPopUpButton!
    private var preallocateMemoryButton: NSButton!
    private var multicoreButton: NSButton!
    private var graphicsDiagnosticsButton: NSButton!
    private var chunkVboButton: NSButton!
    private var statusLabel: NSTextField!
    private var logView: NSTextView!
    private var gameApplication: NSRunningApplication?
    private var installer: MCGLInstaller?
    private var logChannel: FileHandle?
    private var passwordChannel: FileHandle?
    private var transportPaths: [String] = []

    func applicationDidFinishLaunching(_ notification: Notification) {
        buildWindow()
        appendLog("Minecraft Galaxy ARM64 Bootstrap 1.6.4 готов к запуску.")
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }

    func applicationWillTerminate(_ notification: Notification) {
        if let gameApplication, !gameApplication.isTerminated {
            gameApplication.terminate()
        }
        cleanupTransport()
    }

    func buildWindow() {
        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 720, height: 703),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false
        )
        window.title = "Minecraft Galaxy — ARM64"
        window.center()
        window.delegate = self
        window.isReleasedWhenClosed = false

        let content = NSView()
        content.translatesAutoresizingMaskIntoConstraints = false
        window.contentView = content

        let title = NSTextField(labelWithString: "Minecraft Galaxy")
        title.font = .systemFont(ofSize: 25, weight: .semibold)

        let subtitle = NSTextField(labelWithString: "Полностью нативный клиент Apple Silicon · Java 8 + LWJGL 2 ARM64")
        subtitle.textColor = .secondaryLabelColor

        let loginLabel = NSTextField(labelWithString: "Логин")
        loginField = NSTextField()
        loginField.placeholderString = "Логин MCGL"
        loginField.bezelStyle = .roundedBezel
        loginField.identifier = NSUserInterfaceItemIdentifier("login")
        loginField.stringValue = preferences.savedLogin
        loginField.delegate = self

        let passwordLabel = NSTextField(labelWithString: "Пароль")
        passwordField = NSSecureTextField()
        passwordField.placeholderString = "Пароль не сохраняется"
        passwordField.bezelStyle = .roundedBezel
        passwordField.target = self
        passwordField.action = #selector(launchGame)
        passwordField.identifier = NSUserInterfaceItemIdentifier("password")

        rememberLoginButton = NSButton(checkboxWithTitle: "Сохранять логин",
                                      target: self, action: #selector(rememberLoginChanged))
        rememberLoginButton.identifier = NSUserInterfaceItemIdentifier("rememberLogin")
        rememberLoginButton.state = preferences.remembersLogin ? .on : .off
        rememberLoginButton.toolTip = "Сохраняется только логин на этом Mac. При снятии галочки он удаляется из настроек. Пароль не сохраняется."

        launchButton = NSButton(title: "Запустить", target: self, action: #selector(launchGame))
        launchButton.bezelStyle = .rounded
        launchButton.keyEquivalent = "\r"

        stopButton = NSButton(title: "Остановить", target: self, action: #selector(stopGame))
        stopButton.bezelStyle = .rounded
        stopButton.isEnabled = false

        let memoryLabel = NSTextField(labelWithString: "Память")
        memoryPopUp = NSPopUpButton(frame: .zero, pullsDown: false)
        memoryPopUp.addItems(withTitles: supportedMemoryMB.map { memory in
            "\(memory) МБ (\(memory / 1024) ГБ)"
        })
        let savedMemory = UserDefaults.standard.integer(forKey: "MCGLMemoryLimitMB")
        let selectedMemory = supportedMemoryMB.contains(savedMemory) ? savedMemory : 2048
        memoryPopUp.selectItem(at: supportedMemoryMB.firstIndex(of: selectedMemory) ?? 1)

        let fpsLabel = NSTextField(labelWithString: "Лимит FPS")
        fpsPopUp = NSPopUpButton(frame: .zero, pullsDown: false)
        fpsPopUp.identifier = NSUserInterfaceItemIdentifier("fpsLimit")
        fpsPopUp.addItems(withTitles: MCGLLauncherPreferences.fpsLimits.map {
            $0 == 0 ? "Без ограничения" : "\($0) FPS"
        })
        fpsPopUp.selectItem(at: MCGLLauncherPreferences.fpsLimits.firstIndex(of: preferences.fpsLimit) ?? 0)
        fpsPopUp.target = self
        fpsPopUp.action = #selector(fpsLimitChanged)
        fpsPopUp.toolTip = "Верхний предел FPS со следующего запуска игры. VSync и настройки клиента могут ограничивать FPS сильнее. «Без ограничения» не добавляет лимит лаунчера."

        preallocateMemoryButton = NSButton(
            checkboxWithTitle: "Заранее выделять весь выбранный объём памяти",
            target: nil,
            action: nil)
        preallocateMemoryButton.state = UserDefaults.standard.bool(
            forKey: "MCGLPreallocateMemory") ? .on : .off

        multicoreButton = NSButton(
            checkboxWithTitle: "Многопоточная оптимизация памяти (экспериментально)",
            target: nil,
            action: nil)
        let savedMulticoreSetting = UserDefaults.standard.object(
            forKey: "MCGLMulticoreMemoryProfile") as? Bool
        multicoreButton.state = (savedMulticoreSetting ?? true) ? .on : .off

        graphicsDiagnosticsButton = NSButton(
            checkboxWithTitle: "Диагностика графики (не изменяет качество изображения)",
            target: nil,
            action: nil)
        let savedGraphicsDiagnostics = UserDefaults.standard.object(
            forKey: "MCGLGraphicsDiagnostics") as? Bool
        graphicsDiagnosticsButton.state = (savedGraphicsDiagnostics ?? true) ? .on : .off

        chunkVboButton = NSButton(checkboxWithTitle: "VBO-отрисовка чанков (экспериментально)",
                                  target: self, action: #selector(chunkVboChanged))
        chunkVboButton.identifier = NSUserInterfaceItemIdentifier("chunkVbo")
        chunkVboButton.state = preferences.chunkVbo ? .on : .off
        chunkVboButton.toolTip = "Отправка геометрии без изменения качества. В 1.6 формат вершин дополнительно кешируется через VAO, если драйвер поддерживает. Применяется при следующем запуске игры. Сними галочку для возврата к исходному рендеру. До 256 МБ дополнительных буферов вершин."

        statusLabel = NSTextField(labelWithString: "Готов к запуску")
        statusLabel.textColor = .secondaryLabelColor

        let scrollView = NSScrollView()
        scrollView.hasVerticalScroller = true
        scrollView.borderType = .bezelBorder
        logView = NSTextView(frame: NSRect(x: 0, y: 0, width: 640, height: 220))
        logView.isEditable = false
        logView.isSelectable = true
        logView.isVerticallyResizable = true
        logView.isHorizontallyResizable = false
        logView.autoresizingMask = [.width]
        logView.minSize = NSSize(width: 0, height: 220)
        logView.maxSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
        logView.textContainer?.containerSize = NSSize(width: 640, height: CGFloat.greatestFiniteMagnitude)
        logView.textContainer?.widthTracksTextView = true
        logView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        logView.backgroundColor = NSColor(calibratedWhite: 0.08, alpha: 1)
        logView.textColor = NSColor(calibratedWhite: 0.9, alpha: 1)
        scrollView.documentView = logView

        let hint = NSTextField(labelWithString: "Оставь это окно открытым, пока работает игра. Пароль не сохраняется: он передаётся через одноразовый защищённый канал.")
        hint.textColor = .secondaryLabelColor
        hint.font = .systemFont(ofSize: 11)
        hint.maximumNumberOfLines = 2

        let views: [NSView] = [title, subtitle, loginLabel, loginField, passwordLabel,
                               passwordField, rememberLoginButton, launchButton, stopButton, statusLabel,
                               fpsLabel, fpsPopUp,
                               memoryLabel, memoryPopUp, preallocateMemoryButton, multicoreButton,
                               graphicsDiagnosticsButton, chunkVboButton, scrollView, hint]
        for view in views {
            view.translatesAutoresizingMaskIntoConstraints = false
            content.addSubview(view)
        }

        NSLayoutConstraint.activate([
            title.topAnchor.constraint(equalTo: content.topAnchor, constant: 22),
            title.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 24),

            subtitle.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 3),
            subtitle.leadingAnchor.constraint(equalTo: title.leadingAnchor),

            loginLabel.topAnchor.constraint(equalTo: subtitle.bottomAnchor, constant: 22),
            loginLabel.leadingAnchor.constraint(equalTo: title.leadingAnchor),
            loginField.centerYAnchor.constraint(equalTo: loginLabel.centerYAnchor),
            loginField.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 105),
            loginField.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -24),

            passwordLabel.topAnchor.constraint(equalTo: loginLabel.bottomAnchor, constant: 18),
            passwordLabel.leadingAnchor.constraint(equalTo: title.leadingAnchor),
            passwordField.centerYAnchor.constraint(equalTo: passwordLabel.centerYAnchor),
            passwordField.leadingAnchor.constraint(equalTo: loginField.leadingAnchor),
            passwordField.trailingAnchor.constraint(equalTo: loginField.trailingAnchor),

            rememberLoginButton.topAnchor.constraint(equalTo: passwordField.bottomAnchor, constant: 8),
            rememberLoginButton.leadingAnchor.constraint(equalTo: loginField.leadingAnchor),
            rememberLoginButton.trailingAnchor.constraint(lessThanOrEqualTo: loginField.trailingAnchor),

            launchButton.topAnchor.constraint(equalTo: rememberLoginButton.bottomAnchor, constant: 10),
            launchButton.leadingAnchor.constraint(equalTo: loginField.leadingAnchor),
            launchButton.widthAnchor.constraint(equalToConstant: 125),
            stopButton.centerYAnchor.constraint(equalTo: launchButton.centerYAnchor),
            stopButton.leadingAnchor.constraint(equalTo: launchButton.trailingAnchor, constant: 10),
            stopButton.widthAnchor.constraint(equalToConstant: 125),
            statusLabel.centerYAnchor.constraint(equalTo: launchButton.centerYAnchor),
            statusLabel.leadingAnchor.constraint(equalTo: stopButton.trailingAnchor, constant: 15),

            memoryLabel.topAnchor.constraint(equalTo: launchButton.bottomAnchor, constant: 15),
            memoryLabel.leadingAnchor.constraint(equalTo: title.leadingAnchor),
            memoryPopUp.centerYAnchor.constraint(equalTo: memoryLabel.centerYAnchor),
            memoryPopUp.leadingAnchor.constraint(equalTo: loginField.leadingAnchor),
            memoryPopUp.widthAnchor.constraint(equalToConstant: 190),

            fpsLabel.centerYAnchor.constraint(equalTo: memoryLabel.centerYAnchor),
            fpsLabel.leadingAnchor.constraint(equalTo: memoryPopUp.trailingAnchor, constant: 30),
            fpsPopUp.centerYAnchor.constraint(equalTo: memoryPopUp.centerYAnchor),
            fpsPopUp.leadingAnchor.constraint(equalTo: fpsLabel.trailingAnchor, constant: 10),
            fpsPopUp.widthAnchor.constraint(equalToConstant: 175),
            fpsPopUp.trailingAnchor.constraint(lessThanOrEqualTo: loginField.trailingAnchor),

            preallocateMemoryButton.topAnchor.constraint(equalTo: memoryPopUp.bottomAnchor, constant: 8),
            preallocateMemoryButton.leadingAnchor.constraint(equalTo: loginField.leadingAnchor),

            multicoreButton.topAnchor.constraint(equalTo: preallocateMemoryButton.bottomAnchor, constant: 8),
            multicoreButton.leadingAnchor.constraint(equalTo: loginField.leadingAnchor),

            graphicsDiagnosticsButton.topAnchor.constraint(equalTo: multicoreButton.bottomAnchor, constant: 8),
            graphicsDiagnosticsButton.leadingAnchor.constraint(equalTo: loginField.leadingAnchor),

            chunkVboButton.topAnchor.constraint(equalTo: graphicsDiagnosticsButton.bottomAnchor, constant: 8),
            chunkVboButton.leadingAnchor.constraint(equalTo: loginField.leadingAnchor),
            chunkVboButton.trailingAnchor.constraint(lessThanOrEqualTo: loginField.trailingAnchor),

            scrollView.topAnchor.constraint(equalTo: chunkVboButton.bottomAnchor, constant: 12),
            scrollView.leadingAnchor.constraint(equalTo: title.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: loginField.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: hint.topAnchor, constant: -10),

            hint.leadingAnchor.constraint(equalTo: title.leadingAnchor),
            hint.trailingAnchor.constraint(equalTo: loginField.trailingAnchor),
            hint.bottomAnchor.constraint(equalTo: content.bottomAnchor, constant: -16)
        ])

        window.initialFirstResponder = loginField.stringValue.isEmpty ? loginField : passwordField
    }

    @objc private func rememberLoginChanged() {
        preferences.rememberLogin(rememberLoginButton.state == .on,
                                  login: loginField.stringValue)
    }

    func controlTextDidChange(_ notification: Notification) {
        if notification.object as? NSTextField === loginField {
            preferences.updateLogin(loginField.stringValue)
        }
    }

    @objc private func fpsLimitChanged() {
        let index = fpsPopUp.indexOfSelectedItem
        preferences.fpsLimit = MCGLLauncherPreferences.fpsLimits.indices.contains(index)
            ? MCGLLauncherPreferences.fpsLimits[index] : 0
    }

    @objc private func chunkVboChanged() {
        preferences.chunkVbo = chunkVboButton.state == .on
    }

    @objc private func launchGame() {
        guard gameApplication == nil else {
            appendLog("Клиент уже запущен.")
            return
        }

        let login = loginField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        preferences.updateLogin(login)
        let password = passwordField.stringValue
        guard !login.isEmpty, !password.isEmpty else {
            showError("Введи логин и пароль MCGL.")
            return
        }

        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: javaVMPath) else {
            showError("Не найдена подготовленная ARM64 Java 8.")
            return
        }

        prepareOfficialClientAndLaunch(login: login, password: password)
    }

    private func prepareOfficialClientAndLaunch(login: String, password: String) {
        guard installer == nil else {
            appendLog("Установка клиента уже выполняется.")
            return
        }

        let profileExists = FileManager.default.fileExists(atPath: gameDirectory)
        appendLog(profileExists
            ? "Проверка обновлений официального клиента MCGL…"
            : "Первый запуск: оригинальные файлы будут загружены с официального зеркала MCGL.")
        statusLabel.stringValue = profileExists ? "Проверка обновлений…" : "Подготовка загрузки…"
        launchButton.isEnabled = false
        stopButton.isEnabled = false

        let installer = MCGLInstaller(
            supportRootURL: supportRootURL,
            gameDirectoryURL: gameDirectoryURL,
            portSupportURL: portSupportURL,
            patchToolsURL: patchToolsURL,
            javaExecutableURL: javaExecutableURL,
            jarExecutableURL: jarExecutableURL)
        self.installer = installer
        installer.install(progress: { [weak self] message in
            DispatchQueue.main.async {
                guard let self else { return }
                self.statusLabel.stringValue = message
                self.appendLog(message)
            }
        }, completion: { [weak self] result in
            guard let self else { return }
            self.installer = nil
            switch result {
            case .success:
                self.appendLog("Официальные файлы проверены; ARM64-патчи готовы.")
                self.launchPreparedGame(login: login, password: password)
            case .failure(let error):
                self.resetLaunchControls(status: "Ошибка обновления")
                self.showError("Не удалось подготовить официальный клиент: \(error.localizedDescription)")
            }
        })
    }

    private func launchPreparedGame(login: String, password: String) {
        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: gameDirectory + "/Minecraft.jar") else {
            showError("Не найден скачанный Minecraft.jar.")
            return
        }
        guard fileManager.fileExists(atPath: gameDirectory + "/mcgl-nativewindow-patch.jar") else {
            showError("Не найден патч отдельного игрового окна.")
            return
        }
        guard fileManager.fileExists(atPath: gameRuntimePath + "/Contents/Info.plist"),
              fileManager.isExecutableFile(atPath: gameRuntimePath + "/Contents/MacOS/MCGL ARM64 Runtime") else {
            showError("Не найдено macOS-приложение игрового клиента.")
            return
        }

        stopStaleClients()

        let token = UUID().uuidString.lowercased()
        let passwordFIFO = "/private/tmp/mcgl-\(token)-password.fifo"
        let logFIFO = "/private/tmp/mcgl-\(token)-log.fifo"
        do {
            try prepareTransport(password: password, passwordPath: passwordFIFO, logPath: logFIFO)
        } catch {
            showError("Не удалось создать защищённый канал: \(error.localizedDescription)")
            return
        }

        appendLog("Запуск Minecraft Galaxy в полностью нативном ARM64-режиме…")
        statusLabel.stringValue = "Запуск клиента…"
        launchButton.isEnabled = false
        stopButton.isEnabled = true

        let configuration = NSWorkspace.OpenConfiguration()
        configuration.arguments = [login, passwordFIFO, logFIFO, gameDirectory]
        let memoryIndex = max(memoryPopUp.indexOfSelectedItem, 0)
        let memoryMB = supportedMemoryMB[memoryIndex]
        let preallocateMemory = preallocateMemoryButton.state == .on
        let multicoreEnabled = multicoreButton.state == .on
        let graphicsDiagnosticsEnabled = graphicsDiagnosticsButton.state == .on
        let fpsLimit = preferences.fpsLimit
        UserDefaults.standard.set(memoryMB, forKey: "MCGLMemoryLimitMB")
        UserDefaults.standard.set(preallocateMemory, forKey: "MCGLPreallocateMemory")
        UserDefaults.standard.set(multicoreEnabled, forKey: "MCGLMulticoreMemoryProfile")
        UserDefaults.standard.set(graphicsDiagnosticsEnabled, forKey: "MCGLGraphicsDiagnostics")
        var runtimeEnvironment = ProcessInfo.processInfo.environment
        runtimeEnvironment["MCGL_MEMORY_MB"] = String(memoryMB)
        runtimeEnvironment["MCGL_PREALLOCATE_MEMORY"] = preallocateMemory ? "1" : "0"
        runtimeEnvironment["MCGL_MULTICORE_MEMORY"] = multicoreEnabled ? "1" : "0"
        runtimeEnvironment["MCGL_GRAPHICS_DIAGNOSTICS"] = graphicsDiagnosticsEnabled ? "1" : "0"
        runtimeEnvironment["MCGL_FPS_LIMIT"] = String(fpsLimit)
        runtimeEnvironment["MCGL_CHUNK_VBO"] = preferences.chunkVbo ? "1" : "0"
        configuration.environment = runtimeEnvironment
        configuration.activates = true
        configuration.createsNewApplicationInstance = true
        configuration.addsToRecentItems = false
        memoryPopUp.isEnabled = false
        preallocateMemoryButton.isEnabled = false
        multicoreButton.isEnabled = false
        graphicsDiagnosticsButton.isEnabled = false
        chunkVboButton.isEnabled = false
        fpsPopUp.isEnabled = false
        appendLog(fpsLimit == 0 ? "Дополнительный лимит FPS выключен."
            : "Лимит FPS: \(fpsLimit). VSync и настройки игры могут ограничивать FPS сильнее.")
        appendLog(preallocateMemory
            ? "Память Java: \(memoryMB) МБ будут выделены при запуске."
            : "Максимальная память Java: \(memoryMB) МБ; начальный резерв: 512 МБ.")
        appendLog(multicoreEnabled
            ? "Экспериментальный профиль памяти включён: G1GC использует фоновые ядра."
            : "Экспериментальный профиль памяти выключен: используется стандартный ParallelGC.")
        appendLog(graphicsDiagnosticsEnabled
            ? "Диагностика графики включена: статистика кадра будет выводиться каждые 5 секунд."
            : "Диагностика графики выключена.")
        appendLog(preferences.chunkVbo
            ? "VBO-отрисовка включена: ускорение VAO включится при поддержке драйвером; запасные пути сохранены."
            : "Используется прежняя отрисовка чанков (VBO выключен).")

        NSWorkspace.shared.openApplication(
            at: URL(fileURLWithPath: gameRuntimePath),
            configuration: configuration
        ) { [weak self] application, error in
            DispatchQueue.main.async {
                guard let self else { return }
                guard let application, error == nil else {
                    self.cleanupTransport()
                    self.resetLaunchControls(status: "Ошибка запуска")
                    self.showError("Не удалось открыть игровое macOS-приложение: \(error?.localizedDescription ?? "неизвестная ошибка")")
                    return
                }

                guard application.processIdentifier > 0, !application.isTerminated else {
                    self.cleanupTransport()
                    self.resetLaunchControls(status: "Ошибка запуска")
                    self.showError("Игровой runtime завершился до получения PID.")
                    return
                }

                self.gameApplication = application
                self.passwordField.stringValue = ""
                self.appendLog("Игровое приложение запущено, PID: \(application.processIdentifier).")
                self.appendLog("Пароль передан по одноразовому каналу; в журнал он не записан.")
                self.releasePasswordChannel(afterRuntimeOpens: passwordFIFO)
                self.monitorTermination(of: application)

                DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self, weak application] in
                    guard let self, let application,
                          self.gameApplication?.processIdentifier == application.processIdentifier,
                          !application.isTerminated else { return }
                    NSApp.deactivate()
                    self.appendLog("Лаунчер освободил фокус для игрового окна.")
                }

                DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak self, weak application] in
                    guard let self, let application,
                          self.gameApplication?.processIdentifier == application.processIdentifier,
                          !application.isTerminated else { return }
                    let activated = application.activate(options: [.activateAllWindows])
                    self.appendLog(activated
                        ? "Окна игрового macOS-приложения выведены на передний план."
                        : "macOS не подтвердила вывод игровых окон на передний план.")
                }
            }
        }
    }

    @objc private func stopGame() {
        guard let application = gameApplication, !application.isTerminated else { return }
        appendLog("Остановка клиента…")
        application.terminate()
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak application] in
            guard let application, !application.isTerminated else { return }
            application.forceTerminate()
        }
    }

    private func prepareTransport(password: String, passwordPath: String, logPath: String) throws {
        cleanupTransport()

        let permissions = mode_t(S_IRUSR | S_IWUSR)
        var createdPaths: [String] = []
        for path in [passwordPath, logPath] {
            guard Darwin.mkfifo(path, permissions) == 0 else {
                for createdPath in createdPaths { Darwin.unlink(createdPath) }
                throw posixError()
            }
            createdPaths.append(path)
        }

        let logDescriptor = Darwin.open(logPath, O_RDWR | O_NONBLOCK | O_NOFOLLOW)
        guard logDescriptor >= 0 else {
            for path in createdPaths { Darwin.unlink(path) }
            throw posixError()
        }

        let passwordDescriptor = Darwin.open(passwordPath, O_RDWR | O_NONBLOCK | O_NOFOLLOW)
        guard passwordDescriptor >= 0 else {
            Darwin.close(logDescriptor)
            for path in createdPaths { Darwin.unlink(path) }
            throw posixError()
        }

        transportPaths = createdPaths
        let logHandle = FileHandle(fileDescriptor: logDescriptor, closeOnDealloc: true)
        let passwordHandle = FileHandle(fileDescriptor: passwordDescriptor, closeOnDealloc: true)
        logChannel = logHandle
        passwordChannel = passwordHandle

        logHandle.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            guard !data.isEmpty, let text = String(data: data, encoding: .utf8) else { return }
            DispatchQueue.main.async {
                self?.appendLog(text.trimmingCharacters(in: .newlines))
            }
        }

        do {
            try passwordHandle.write(contentsOf: Data((password + "\n").utf8))
        } catch {
            cleanupTransport()
            throw error
        }
    }

    private func releasePasswordChannel(afterRuntimeOpens path: String) {
        DispatchQueue.global(qos: .utility).async { [weak self] in
            for _ in 0..<100 {
                if Darwin.access(path, F_OK) != 0 { break }
                usleep(100_000)
            }
            DispatchQueue.main.async {
                guard let self, self.transportPaths.contains(path) else { return }
                try? self.passwordChannel?.close()
                self.passwordChannel = nil
            }
        }
    }

    private func monitorTermination(of application: NSRunningApplication) {
        DispatchQueue.global(qos: .utility).async { [weak self, weak application] in
            guard let application else { return }
            while !application.isTerminated {
                usleep(200_000)
            }
            DispatchQueue.main.async {
                guard let self,
                      self.gameApplication?.processIdentifier == application.processIdentifier else { return }
                self.appendLog("Игровой процесс завершён.")
                self.cleanupTransport()
                self.gameApplication = nil
                self.resetLaunchControls(status: "Клиент остановлен")
            }
        }
    }

    private func cleanupTransport() {
        logChannel?.readabilityHandler = nil
        try? logChannel?.close()
        try? passwordChannel?.close()
        logChannel = nil
        passwordChannel = nil
        for path in transportPaths { Darwin.unlink(path) }
        transportPaths.removeAll()
    }

    private func resetLaunchControls(status: String) {
        statusLabel.stringValue = status
        launchButton.isEnabled = true
        stopButton.isEnabled = false
        memoryPopUp.isEnabled = true
        preallocateMemoryButton.isEnabled = true
        multicoreButton.isEnabled = true
        graphicsDiagnosticsButton.isEnabled = true
        chunkVboButton.isEnabled = true
        fpsPopUp.isEnabled = true
    }

    private func posixError() -> NSError {
        NSError(domain: NSPOSIXErrorDomain, code: Int(errno), userInfo: nil)
    }

    private func stopStaleClients() {
        let staleApplications = NSRunningApplication.runningApplications(
            withBundleIdentifier: "community.mcgl.arm64-port.runtime")
        guard !staleApplications.isEmpty else { return }

        for application in staleApplications where !application.isTerminated {
            application.terminate()
        }
        for _ in 0..<20 where staleApplications.contains(where: { !$0.isTerminated }) {
            usleep(100_000)
        }
        for application in staleApplications where !application.isTerminated {
            application.forceTerminate()
        }
        appendLog("Остановлен зависший процесс из предыдущего запуска.")
    }

    private func showError(_ message: String) {
        statusLabel.stringValue = message
        appendLog("ОШИБКА: " + message)
        NSSound.beep()
    }

    private func appendLog(_ message: String) {
        guard !message.isEmpty else { return }
        let line = message + "\n"
        logView.textStorage?.append(NSAttributedString(
            string: line,
            attributes: [.foregroundColor: NSColor(calibratedWhite: 0.9, alpha: 1),
                         .font: NSFont.monospacedSystemFont(ofSize: 12, weight: .regular)]
        ))
        logView.scrollToEndOfDocument(nil)

        let timestamp = ISO8601DateFormatter().string(from: Date())
        let record = Data((timestamp + " " + line).utf8)
        if !FileManager.default.fileExists(atPath: logPath) {
            FileManager.default.createFile(atPath: logPath, contents: nil)
        }
        if let handle = try? FileHandle(forWritingTo: URL(fileURLWithPath: logPath)) {
            do {
                try handle.seekToEnd()
                try handle.write(contentsOf: record)
                try handle.close()
            } catch {
                try? handle.close()
            }
        }
    }
}

#if !MCGL_LAUNCHER_TEST
@main
struct MCGLLauncherMain {
    static func main() {
        let application = NSApplication.shared
        let delegate = AppDelegate()
        application.delegate = delegate
        application.setActivationPolicy(.regular)
        application.run()
    }
}
#endif
