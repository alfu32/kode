package editor.rest.ui

/** Common HTTP field names used by header completion in the request editor. */
object HttpHeaderCatalog {
    val names: List<String> = listOf(
        "Accept", "Accept-Charset", "Accept-Encoding", "Accept-Language", "Accept-Ranges",
        "Access-Control-Allow-Credentials", "Access-Control-Allow-Headers", "Access-Control-Allow-Methods",
        "Access-Control-Allow-Origin", "Access-Control-Expose-Headers", "Access-Control-Max-Age",
        "Access-Control-Request-Headers", "Access-Control-Request-Method", "Age", "Allow", "Alt-Svc",
        "Authorization", "Cache-Control", "Connection", "Content-Disposition", "Content-Encoding",
        "Content-Language", "Content-Length", "Content-Location", "Content-Range", "Content-Security-Policy",
        "Content-Type", "Cookie", "Date", "DNT", "ETag", "Expect", "Forwarded", "From", "Host",
        "If-Match", "If-Modified-Since", "If-None-Match", "If-Range", "If-Unmodified-Since", "Keep-Alive",
        "Last-Modified", "Link", "Location", "Origin", "Pragma", "Proxy-Authenticate", "Proxy-Authorization",
        "Range", "Referer", "Referrer-Policy", "Retry-After", "Sec-WebSocket-Accept", "Sec-WebSocket-Key",
        "Sec-WebSocket-Protocol", "Sec-WebSocket-Version", "Server", "Set-Cookie", "TE", "Trailer",
        "Transfer-Encoding", "Upgrade", "User-Agent", "Vary", "Via", "WWW-Authenticate", "Warning",
        "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Request-ID", "X-Robots-Tag"
    )
}
