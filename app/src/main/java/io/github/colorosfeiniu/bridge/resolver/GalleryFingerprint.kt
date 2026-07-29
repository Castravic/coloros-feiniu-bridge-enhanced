package io.github.colorosfeiniu.bridge.resolver

import android.content.Context
import java.io.File
import java.security.MessageDigest

internal data class ApkIdentity(
    val path: String,
    val length: Long,
    val lastModified: Long,
)

internal data class GalleryFingerprint(
    val packageName: String,
    val versionCode: Long,
    val lastUpdateTime: Long,
    val apks: List<ApkIdentity>,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    fun cacheNamespace(): String {
        val canonical = buildString {
            append("schema=").append(schemaVersion).append('\n')
            append("package=").append(packageName).append('\n')
            append("versionCode=").append(versionCode).append('\n')
            append("lastUpdateTime=").append(lastUpdateTime).append('\n')
            apks.sortedBy(ApkIdentity::path).forEach { apk ->
                append("apk=").append(apk.path).append('|')
                    .append(apk.length).append('|')
                    .append(apk.lastModified).append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    companion object {
        private const val SCHEMA_VERSION = 1

        @Suppress("DEPRECATION")
        fun from(context: Context): GalleryFingerprint {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val applicationInfo = context.applicationInfo
            val paths = buildList {
                add(applicationInfo.sourceDir)
                applicationInfo.splitSourceDirs?.let(::addAll)
            }.filterNotNull()
            return GalleryFingerprint(
                packageName = context.packageName,
                versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    packageInfo.longVersionCode
                } else {
                    packageInfo.versionCode.toLong()
                },
                lastUpdateTime = packageInfo.lastUpdateTime,
                apks = paths.distinct().map { path ->
                    val file = File(path)
                    ApkIdentity(
                        path = file.absolutePath,
                        length = file.length(),
                        lastModified = file.lastModified(),
                    )
                },
            )
        }
    }
}
