package com.safeopen.viewer.render

/** HTML helpers. Every generated page carries a strict CSP: no scripts, no network, data: images only. */
object Html {

    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '&' -> sb.append("&amp;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun base64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    fun imageMime(name: String, data: ByteArray): String? {
        fun b(i: Int) = if (i < data.size) data[i].toInt() and 0xFF else -1
        return when {
            b(0) == 0x89 && b(1) == 0x50 -> "image/png"
            b(0) == 0xFF && b(1) == 0xD8 -> "image/jpeg"
            b(0) == 'G'.code && b(1) == 'I'.code && b(2) == 'F'.code -> "image/gif"
            b(0) == 'R'.code && b(8) == 'W'.code && b(9) == 'E'.code -> "image/webp"
            b(0) == 'B'.code && b(1) == 'M'.code -> "image/bmp"
            name.endsWith(".svg", true) -> "image/svg+xml"
            else -> null // EMF/WMF/TIFF are not renderable in WebView
        }
    }

    fun dataUri(name: String, data: ByteArray): String? {
        val mime = imageMime(name, data) ?: return null
        return "data:$mime;base64,${base64(data)}"
    }

    const val CSP = "default-src 'none'; img-src data:; style-src 'unsafe-inline'; media-src data:; font-src data:"

    private const val BASE_CSS = """
        :root { color-scheme: light; }
        * { box-sizing: border-box; }
        body { margin: 0; padding: 12px; font-family: sans-serif; font-size: 16px; line-height: 1.5;
               color: #1b1b1f; background: #eceff4; overflow-wrap: anywhere; }
        .paper { background: #fff; padding: 20px 18px; border-radius: 6px; box-shadow: 0 1px 3px rgba(0,0,0,.15);
                 max-width: 900px; margin: 0 auto; }
        img { max-width: 100%; height: auto; }
        table { border-collapse: collapse; margin: 8px 0; }
        td, th { border: 1px solid #c8ccd4; padding: 4px 6px; vertical-align: top; }
        .note { background: #fff6dd; border: 1px solid #f0d58c; color: #5a4300; padding: 8px 10px;
                border-radius: 6px; margin-bottom: 10px; font-size: 14px; }
        pre { white-space: pre-wrap; word-break: break-word; font-family: monospace; font-size: 13px; margin: 0; }
        h1,h2,h3,h4,h5,h6 { line-height: 1.25; margin: .6em 0 .3em; }
        p { margin: 0 0 .5em; min-height: 1em; }
        .link { color: #1f5fd1; text-decoration: underline; }
    """

    fun page(body: String, extraCss: String = "", bodyClass: String = ""): String =
        """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, minimum-scale=0.25, maximum-scale=8, user-scalable=yes">
<meta http-equiv="Content-Security-Policy" content="$CSP">
<style>$BASE_CSS
$extraCss</style></head><body class="$bodyClass">$body</body></html>"""

    fun note(text: String) = "<div class=\"note\" dir=\"auto\">${esc(text)}</div>"
}
