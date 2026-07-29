package io.github.colorosfeiniu.bridge.resolver

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier
import android.os.SystemClock

internal data class SemanticResolution(
    val token: TokenHookRefs?,
    val gallery: GalleryHookRefs?,
    val tokenCandidateCount: Int,
    val galleryCandidateCount: Int,
    val elapsedMs: Long,
)

internal object SemanticDexResolver {
    fun resolve(
        classLoader: ClassLoader,
        needToken: Boolean,
        needGallery: Boolean,
    ): SemanticResolution {
        val started = SystemClock.elapsedRealtime()
        System.loadLibrary("dexkit")
        return DexKitBridge.create(classLoader, false).use { bridge ->
            val tokenCandidates = if (needToken) findTokenCandidates(bridge) else emptyList()
            val galleryCandidates = if (needGallery) findGalleryCandidates(bridge) else emptyList()
            SemanticResolution(
                token = tokenCandidates.singleOrNull(),
                gallery = galleryCandidates.singleOrNull(),
                tokenCandidateCount = tokenCandidates.size,
                galleryCandidateCount = galleryCandidates.size,
                elapsedMs = SystemClock.elapsedRealtime() - started,
            )
        }
    }

    private fun findTokenCandidates(bridge: DexKitBridge): List<TokenHookRefs> {
        val decryptMethods = methodsUsingExactStrings(
            bridge,
            TOKEN_DECRYPT_QUERY_ANCHORS,
        ).filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnTypeName == STRING_TYPE &&
                method.paramTypeNames == listOf(STRING_TYPE, STRING_TYPE) &&
                TOKEN_CHAIN_ANCHORS.all(reachableStrings(method)::contains)
        }
        return decryptMethods.flatMap { decrypt ->
            decrypt.invokes.filter { prefix ->
                prefix.declaredClassName == decrypt.declaredClassName &&
                    !Modifier.isStatic(prefix.modifiers) &&
                    prefix.returnTypeName == STRING_TYPE &&
                    prefix.paramTypeNames.isEmpty()
            }.map { prefix ->
                TokenHookRefs(
                    prefix = prefix.toMethodRef(),
                    decrypt = decrypt.toMethodRef(),
                    source = ResolutionSource.SEMANTIC,
                )
            }
        }.distinctBy { candidate ->
            candidate.prefix.descriptor to candidate.decrypt.descriptor
        }
    }

    private fun findGalleryCandidates(bridge: DexKitBridge): List<GalleryHookRefs> {
        val anchorMethods = methodsUsingString(
            bridge = bridge,
            value = GALLERY_STAT_FAILURE,
            matchType = StringMatchType.StartsWith,
        )
        val statMethods = anchorMethods.flatMap { anchor ->
            buildList {
                if (anchor.isStatShape()) add(anchor)
                bridge.getClassData(anchor.declaredClassName)
                    ?.methods
                    .orEmpty()
                    .filter { it.methodName == "<init>" }
                    .flatMapTo(this) { constructor ->
                        constructor.callers.filter { caller -> caller.isStatShape() }
                    }
            }
        }.distinctBy(MethodData::descriptor)
        return statMethods.flatMap { stat ->
            completeGalleryGroups(bridge, stat)
        }.distinctBy { group ->
            listOf(
                group.stat.descriptor,
                group.albums.descriptor,
                group.connection.descriptor,
                group.realAlbums.descriptor,
                group.cache.descriptor,
                group.photoCount.descriptor,
                group.videoCount.descriptor,
            )
        }
    }

    private fun completeGalleryGroups(
        bridge: DexKitBridge,
        stat: MethodData,
    ): List<GalleryHookRefs> {
        val providerMethods = bridge.getClassData(stat.declaredClassName)?.methods.orEmpty()
        val albumsMethods = providerMethods.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnTypeName == LIST_TYPE &&
                method.paramTypeNames == listOf(INT_TYPE, INT_TYPE, STRING_TYPE)
        }
        val connectionMethods = providerMethods.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnTypeName != VOID_TYPE &&
                method.paramTypeNames == listOf(STRING_TYPE, BOOLEAN_TYPE) &&
                stat.paramTypeNames[0] == method.returnTypeName
        }
        val cacheMethods = methodsByPrototype(
            bridge = bridge,
            returnTypeName = stat.returnTypeName,
            parameterTypeNames = listOf(STRING_TYPE),
        ).filter { Modifier.isStatic(it.modifiers) }
        val dtoClass = bridge.getClassData(stat.returnTypeName) ?: return emptyList()
        val dtoToStrings = dtoClass.methods.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.methodName == "toString" &&
                method.returnTypeName == STRING_TYPE &&
                method.paramTypeNames.isEmpty() &&
                PHOTO_COUNT_TO_STRING in method.usingStrings &&
                method.usingStrings.any { it.contains(VIDEO_COUNT_TO_STRING) }
        }
        val countFields = dtoClass.fields.filter { field ->
            !Modifier.isStatic(field.modifiers) && field.typeName == INT_TYPE
        }
        if (dtoToStrings.size != 1 || countFields.size != 2) return emptyList()
        val countFieldsByDescriptor = countFields.associateBy(FieldData::descriptor)
        val orderedCounts = dtoToStrings.single().usingFields
            .map { usingField -> usingField.field.descriptor }
            .distinct()
            .mapNotNull(countFieldsByDescriptor::get)
        if (orderedCounts.size != 2) return emptyList()

        return connectionMethods.flatMap { connection ->
            val realAlbumsMethods = providerMethods.filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.returnTypeName == LIST_TYPE &&
                    method.paramTypeNames == listOf(
                        connection.returnTypeName,
                        STRING_TYPE,
                        INT_TYPE,
                        INT_TYPE,
                    )
            }
            albumsMethods.flatMap { albums ->
                realAlbumsMethods.flatMap { realAlbums ->
                    cacheMethods.map { cache ->
                        GalleryHookRefs(
                            stat = stat.toMethodRef(),
                            albums = albums.toMethodRef(),
                            connection = connection.toMethodRef(),
                            realAlbums = realAlbums.toMethodRef(),
                            cache = cache.toMethodRef(),
                            photoCount = orderedCounts[0].toFieldRef(),
                            videoCount = orderedCounts[1].toFieldRef(),
                            source = ResolutionSource.SEMANTIC,
                        )
                    }
                }
            }
        }
    }

    private fun methodsUsingExactStrings(
        bridge: DexKitBridge,
        strings: Set<String>,
    ): List<MethodData> = bridge.findMethod {
        searchPackages(PACKAGE_PREFIX)
        matcher {
            strings.forEach { value ->
                addUsingString(value, StringMatchType.Equals)
            }
        }
    }

    private fun methodsUsingString(
        bridge: DexKitBridge,
        value: String,
        matchType: StringMatchType,
    ): List<MethodData> = bridge.findMethod {
        searchPackages(PACKAGE_PREFIX)
        matcher {
            addUsingString(value, matchType)
        }
    }

    private fun MethodData.isStatShape(): Boolean =
        Modifier.isStatic(modifiers) &&
            paramTypeNames.size == 2 &&
            paramTypeNames[1] == STRING_TYPE &&
            returnTypeName != VOID_TYPE

    private fun reachableStrings(root: MethodData): Set<String> {
        val strings = linkedSetOf<String>()
        val queue = ArrayDeque<Pair<MethodData, Int>>()
        val visited = hashSetOf<String>()
        queue += root to 0
        while (queue.isNotEmpty()) {
            val (method, depth) = queue.removeFirst()
            if (!visited.add(method.descriptor)) continue
            strings += method.usingStrings
            if (depth >= MAX_TOKEN_CALL_DEPTH) continue
            method.invokes
                .filter { it.declaredClassName == root.declaredClassName }
                .forEach { queue += it to depth + 1 }
        }
        return strings
    }

    private fun methodsByPrototype(
        bridge: DexKitBridge,
        returnTypeName: String,
        parameterTypeNames: List<String>,
    ): List<MethodData> = bridge.findMethod {
        searchPackages(PACKAGE_PREFIX)
        matcher {
            returnType(returnTypeName)
            paramTypes(parameterTypeNames)
        }
    }

    private fun MethodData.toMethodRef(): MethodRef = MethodRef(
        descriptor = descriptor,
        className = declaredClassName,
        name = methodName,
        returnTypeName = returnTypeName,
        parameterTypeNames = paramTypeNames,
        isStatic = Modifier.isStatic(modifiers),
    )

    private fun FieldData.toFieldRef(): FieldRef = FieldRef(
        descriptor = descriptor,
        className = declaredClassName,
        name = fieldName,
        typeName = typeName,
        isStatic = Modifier.isStatic(modifiers),
    )

    private const val PACKAGE_PREFIX = "com.oplus.aiunit.vision"
    private const val STRING_TYPE = "java.lang.String"
    private const val LIST_TYPE = "java.util.List"
    private const val INT_TYPE = "int"
    private const val BOOLEAN_TYPE = "boolean"
    private const val VOID_TYPE = "void"
    private const val CRYPTO_MANAGER_CLASS = "com.oplus.hardware.cryptoeng.CryptoEngManager"
    private const val GALLERY_STAT_FAILURE = "getGalleryStat failed for device:"
    private const val MAX_TOKEN_CALL_DEPTH = 4
    private val TOKEN_DECRYPT_QUERY_ANCHORS = setOf(
        "TokenDecryptor",
        "AES/GCM/NoPadding",
    )
    private val TOKEN_CHAIN_ANCHORS = setOf(
        "SHA-256",
        CRYPTO_MANAGER_CLASS,
    )
    private const val PHOTO_COUNT_TO_STRING = "NasGalleryStatDto(photoCount="
    private const val VIDEO_COUNT_TO_STRING = "videoCount="
}
