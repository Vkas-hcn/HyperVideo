package com.vi.pl.videoplayerdemo.download

/** Small, platform-free checks shared by the HTTP downloader and unit tests. */
object DownloadResume {
    fun contentRangeStart(value: String): Long? =
        Regex("""bytes\s+(\d+)-""").find(value)?.groupValues?.getOrNull(1)?.toLongOrNull()

    fun totalFromContentRange(value: String): Long =
        Regex("""/(\d+)$""").find(value)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: -1L

    fun resourceIdentity(url: String): String = runCatching {
        val uri = java.net.URI(url)
        java.net.URI(uri.scheme, uri.authority, uri.path, null, null).toString()
    }.getOrDefault(url.substringBefore('?').substringBefore('#'))
}
