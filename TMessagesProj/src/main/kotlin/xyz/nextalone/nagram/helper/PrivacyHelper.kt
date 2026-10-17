package xyz.nextalone.nagram.helper

import android.net.Uri
import android.text.SpannableStringBuilder
import androidx.exifinterface.media.ExifInterface
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import xyz.nextalone.nagram.NaConfig
import java.io.File
import java.util.Locale
import java.util.regex.Pattern

/**
 * Privacy helpers for the personal build: removing tracking parameters from links
 * and identifying metadata from images sent as files.
 */
object PrivacyHelper {

    private val DEFAULT_TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "yclid",
        "twclid", "ttclid", "li_fat_id", "igshid", "igsh", "mc_cid", "mc_eid",
        "_hsenc", "_hsmi", "mkt_tok", "oly_anon_id", "oly_enc_id", "vero_id",
        "spm", "scm", "si", "feature", "ref_src", "ref_url", "s_cid", "trk", "trkCampaign",
    )

    private val TRACKING_PREFIXES = listOf("utm_", "pk_", "hsa_")

    // Hosts where "si"/"feature"/"spm" are known tracking-only parameters. Elsewhere they may be meaningful.
    private val HOST_SCOPED_PARAMS = mapOf(
        "si" to listOf("youtube.com", "youtu.be", "spotify.com"),
        "feature" to listOf("youtube.com", "youtu.be"),
        "spm" to listOf("aliexpress.com", "taobao.com", "tmall.com", "alibaba.com"),
        "scm" to listOf("aliexpress.com", "taobao.com", "tmall.com", "alibaba.com"),
    )

    private val URL_PATTERN = Pattern.compile("https?://[^\\s<>\"]+", Pattern.CASE_INSENSITIVE)

    private fun extraParams(): Set<String> =
        NaConfig.stripTrackingParamsExtra.String()
            ?.split(',', ' ', '\n')
            ?.map { it.trim().lowercase(Locale.ROOT) }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    private fun hostMatches(host: String?, domains: List<String>): Boolean {
        if (host == null) return false
        val h = host.lowercase(Locale.ROOT)
        return domains.any { h == it || h.endsWith(".$it") }
    }

    private fun isTrackingParam(name: String, host: String?, extra: Set<String>): Boolean {
        val n = name.lowercase(Locale.ROOT)
        if (n in extra) return true
        if (TRACKING_PREFIXES.any { n.startsWith(it) }) return true
        HOST_SCOPED_PARAMS[n]?.let { return hostMatches(host, it) }
        return n in DEFAULT_TRACKING_PARAMS
    }

    /** Returns the URL without tracking query parameters, or the same string if nothing changed. */
    @JvmStatic
    fun cleanUrl(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        val fragmentStart = url.indexOf('#', queryStart).let { if (it < 0) url.length else it }
        val host = runCatching { Uri.parse(url).host }.getOrNull()
        val extra = extraParams()
        val params = url.substring(queryStart + 1, fragmentStart).split('&')
        val kept = params.filter { p ->
            val name = p.substringBefore('=')
            name.isEmpty() || !isTrackingParam(Uri.decode(name), host, extra)
        }
        if (kept.size == params.size) return url
        val base = url.substring(0, queryStart)
        val fragment = url.substring(fragmentStart)
        return if (kept.isEmpty()) base + fragment else base + "?" + kept.joinToString("&") + fragment
    }

    @JvmStatic
    fun cleanUri(uri: Uri?): Uri? {
        if (uri == null || !NaConfig.stripTrackingParams.Bool()) return uri
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return uri
        val original = uri.toString()
        val cleaned = cleanUrl(original)
        return if (cleaned == original) uri else Uri.parse(cleaned)
    }

    /**
     * Cleans links inside outgoing text. Uses SpannableStringBuilder.replace so formatting
     * spans (bold, links, custom emoji) shift correctly with the shortened text.
     */
    @JvmStatic
    fun cleanTrackingInText(text: CharSequence?): CharSequence? {
        if (text == null || !NaConfig.stripTrackingParams.Bool()) return text
        val matcher = URL_PATTERN.matcher(text)
        val replacements = ArrayList<Triple<Int, Int, String>>()
        while (matcher.find()) {
            val url = matcher.group()
            val cleaned = cleanUrl(url)
            if (cleaned != url) replacements.add(Triple(matcher.start(), matcher.end(), cleaned))
        }
        if (replacements.isEmpty()) return text
        val builder = SpannableStringBuilder(text)
        for ((start, end, cleaned) in replacements.asReversed()) {
            builder.replace(start, end, cleaned)
        }
        return builder
    }

    private val METADATA_TAGS = arrayOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_DEST_BEARING, ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_MAP_DATUM, ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT, ExifInterface.TAG_MAKER_NOTE,
        ExifInterface.TAG_CAMERA_OWNER_NAME, ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_IMAGE_UNIQUE_ID,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
    )

    private val EXIF_WRITABLE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    /**
     * For images sent as files, returns the path of a copy with location, device and
     * timestamp metadata removed. The user's original file is never modified.
     * Returns the original path for other file types or on any failure.
     */
    @JvmStatic
    fun stripMetadataIfEnabled(path: String?): String? {
        if (path == null || !NaConfig.stripFileMetadata.Bool()) return path
        val source = File(path)
        val ext = source.extension.lowercase(Locale.ROOT)
        if (ext !in EXIF_WRITABLE_EXTENSIONS || !source.isFile) return path
        return try {
            val dir = File(AndroidUtilities.getCacheDir(), "stripped_" + System.nanoTime())
            if (!dir.mkdirs()) return path
            val copy = File(dir, source.name)
            source.copyTo(copy, overwrite = true)
            val exif = ExifInterface(copy.absolutePath)
            for (tag in METADATA_TAGS) {
                exif.setAttribute(tag, null)
            }
            exif.saveAttributes()
            copy.absolutePath
        } catch (e: Throwable) {
            FileLog.e(e)
            path
        }
    }
}
