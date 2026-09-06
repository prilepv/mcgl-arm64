import Foundation

/// Explicit opt-in smoke test for a public nickname. Never accepts a password.
/// Not invoked by verify-source.sh or bundled in the app.
@main
struct AccountInfoProbe {
    static func main() {
        precondition(CommandLine.arguments.count == 2, "Usage: account-info-probe NICKNAME")
        let service = MCGLAccountService()
        var completed = false
        var passed = false
        service.fetch(nickname: CommandLine.arguments[1]) { result in
            switch result {
            case .success(let info):
                print("ACCOUNT_INFO_HTTPS_PASS profession=\(info.professionTitle) construction=\(info.construction) destruction=\(info.destruction) inlineAvatar=\(info.avatar != nil)")
                passed = true
            case .failure(let error): print("ACCOUNT_INFO_FAIL \(error.localizedDescription)")
            }
            completed = true
        }
        let deadline = Date().addingTimeInterval(20)
        while !completed && Date() < deadline { RunLoop.current.run(until: Date().addingTimeInterval(0.02)) }
        if !passed { exit(1) }
    }
}
