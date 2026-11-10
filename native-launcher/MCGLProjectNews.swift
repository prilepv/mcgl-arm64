import Foundation

struct MCGLProjectNews {
    let title: String?
    let date: String?
    let text: String
    let link: URL?
    let symbol: String

    // Public feed used by the Windows launcher. The canonical host supports HTTPS.
    static let feed = URL(string: "https://forum.minecraft-galaxy.ru/mcrunex/?v=2.13")!
    static let allNews = URL(string: "https://forum.minecraft-galaxy.ru/blog/")!
    private static let forum = "forum.minecraft-galaxy.ru"

    static func fetch(completion: @escaping ([MCGLProjectNews]) -> Void) -> URLSessionDataTask {
        var request = URLRequest(url: feed, timeoutInterval: 10)
        request.setValue("Minecraft-Launcher 2.0", forHTTPHeaderField: "User-Agent")
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        configuration.timeoutIntervalForResource = 15
        let session = URLSession(configuration: configuration)
        let task = session.dataTask(with: request) { data, response, _ in
            let http = response as? HTTPURLResponse
            let valid = http?.statusCode == 200 && http?.url?.scheme == "https" && http?.url?.host == forum
            let items = valid ? data.map(parse) ?? [] : []
            DispatchQueue.main.async { completion(items) }
        }
        task.resume()
        session.finishTasksAndInvalidate()
        return task
    }

    static func parse(_ data: Data) -> [MCGLProjectNews] {
        guard data.count <= 128 * 1024, let html = String(data: data, encoding: .utf8) else { return [] }
        let featured = matches(
            #"<div\s+class=['"]blog['"]\s*>\s*<div\s+class=['"]title['"]\s*>\s*<a\s+href=['"]([^'"]+)['"][^>]*>(.*?)</a>\s*</div>\s*<div[^>]*>(.*?)</div>"#,
            in: html).compactMap { fields -> MCGLProjectNews? in
                let title = plainText(fields[1]), text = plainText(fields[2])
                guard !title.isEmpty, let link = forumLink(fields[0]) else { return nil }
                return MCGLProjectNews(title: title, date: nil, text: text, link: link, symbol: "newspaper")
            }
        let updates = matches(
            #"<table\b[^>]*>(.*?)<td\s+class=['"]news-text['"]\s*>\s*<div\s+class=['"]date['"]\s*>\s*([0-9]{4}-[0-9]{2}-[0-9]{2})\s*</div>\s*<div[^>]*>(.*?)</div>\s*(?:</td>\s*)?</tr>\s*</table>"#,
            in: html).compactMap { fields -> MCGLProjectNews? in
                let text = plainText(fields[2])
                guard !text.isEmpty else { return nil }
                let symbol = fields[0].contains("/res/3.png") ? "gift" : "megaphone"
                return MCGLProjectNews(title: nil, date: fields[1], text: text, link: nil, symbol: symbol)
            }
        return Array((featured + updates).prefix(4))
    }

    private static func forumLink(_ raw: String) -> URL? {
        guard var parts = URLComponents(string: raw),
              parts.scheme == "http" || parts.scheme == "https",
              parts.host == forum || parts.host == "forum.mcgl.ru",
              parts.user == nil, parts.password == nil, parts.port == nil,
              parts.path.range(of: #"^/blog/[0-9]+/?$"#, options: .regularExpression) != nil else { return nil }
        parts.scheme = "https"
        parts.host = forum
        parts.query = nil
        parts.fragment = nil
        return parts.url
    }

    private static func matches(_ pattern: String, in value: String) -> [[String]] {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) else { return [] }
        let text = value as NSString
        return regex.matches(in: value, range: NSRange(location: 0, length: text.length)).map { match in
            (1..<match.numberOfRanges).map { text.substring(with: match.range(at: $0)) }
        }
    }

    private static func plainText(_ html: String) -> String {
        var text = html.replacingOccurrences(of: #"<br\s*/?\s*>"#, with: " ", options: [.regularExpression, .caseInsensitive])
            .replacingOccurrences(of: #"<[^>]*>"#, with: "", options: .regularExpression)
        let entities = ["&nbsp;": " ", "&#160;": " ", "&quot;": "\"", "&#39;": "'",
                        "&laquo;": "«", "&raquo;": "»", "&mdash;": "—", "&ndash;": "–",
                        "&lt;": "<", "&gt;": ">"]
        for (entity, replacement) in entities { text = text.replacingOccurrences(of: entity, with: replacement) }
        text = text.replacingOccurrences(of: "&amp;", with: "&")
        return String(text.split(whereSeparator: \.isWhitespace).joined(separator: " ").prefix(2200))
    }
}
