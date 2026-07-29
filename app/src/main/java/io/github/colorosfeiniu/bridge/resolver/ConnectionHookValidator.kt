package io.github.colorosfeiniu.bridge.resolver

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class ValidatedTokenHooks(
    val prefix: Method,
    val decrypt: Method,
)

internal data class ValidatedGalleryHooks(
    val stat: Method,
    val albums: Method,
    val connection: Method,
    val realAlbums: Method,
    val cache: Method,
    val photoCount: Field,
    val videoCount: Field,
)

internal object ConnectionHookValidator {
    fun validateToken(
        refs: TokenHookRefs,
        classLoader: ClassLoader,
    ): ValidatedTokenHooks? = runCatching {
        val prefix = loadMethod(refs.prefix, classLoader)
        val decrypt = loadMethod(refs.decrypt, classLoader)
        require(prefix.declaringClass == decrypt.declaringClass)
        require(!Modifier.isStatic(prefix.modifiers))
        require(prefix.returnType == String::class.java)
        require(prefix.parameterTypes.isEmpty())
        require(!Modifier.isStatic(decrypt.modifiers))
        require(decrypt.returnType == String::class.java)
        require(
            decrypt.parameterTypes.contentEquals(
                arrayOf(String::class.java, String::class.java),
            ),
        )
        ValidatedTokenHooks(prefix, decrypt)
    }.getOrNull()

    fun validateGallery(
        refs: GalleryHookRefs,
        classLoader: ClassLoader,
    ): ValidatedGalleryHooks? = runCatching {
        val stat = loadMethod(refs.stat, classLoader)
        val albums = loadMethod(refs.albums, classLoader)
        val connection = loadMethod(refs.connection, classLoader)
        val realAlbums = loadMethod(refs.realAlbums, classLoader)
        val cache = loadMethod(refs.cache, classLoader)
        val photoCount = loadField(refs.photoCount, classLoader)
        val videoCount = loadField(refs.videoCount, classLoader)

        require(
            setOf(
                stat.declaringClass,
                albums.declaringClass,
                connection.declaringClass,
                realAlbums.declaringClass,
            ).size == 1,
        )
        require(Modifier.isStatic(stat.modifiers))
        require(stat.parameterTypes.size == 2)
        require(stat.parameterTypes[1] == String::class.java)
        require(!Modifier.isStatic(albums.modifiers))
        require(List::class.java.isAssignableFrom(albums.returnType))
        require(
            albums.parameterTypes.contentEquals(
                arrayOf(
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                ),
            ),
        )
        require(!Modifier.isStatic(connection.modifiers))
        require(connection.returnType != Void.TYPE)
        require(
            connection.parameterTypes.contentEquals(
                arrayOf(String::class.java, Boolean::class.javaPrimitiveType),
            ),
        )
        require(stat.parameterTypes[0] == connection.returnType)
        require(!Modifier.isStatic(realAlbums.modifiers))
        require(List::class.java.isAssignableFrom(realAlbums.returnType))
        require(
            realAlbums.parameterTypes.contentEquals(
                arrayOf(
                    connection.returnType,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ),
            ),
        )
        require(Modifier.isStatic(cache.modifiers))
        require(cache.returnType == stat.returnType)
        require(cache.parameterTypes.contentEquals(arrayOf(String::class.java)))
        require(photoCount != videoCount)
        require(photoCount.declaringClass == stat.returnType)
        require(videoCount.declaringClass == stat.returnType)
        require(photoCount.type == Int::class.javaPrimitiveType)
        require(videoCount.type == Int::class.javaPrimitiveType)
        require(!Modifier.isStatic(photoCount.modifiers))
        require(!Modifier.isStatic(videoCount.modifiers))

        ValidatedGalleryHooks(
            stat = stat,
            albums = albums,
            connection = connection,
            realAlbums = realAlbums,
            cache = cache,
            photoCount = photoCount,
            videoCount = videoCount,
        )
    }.getOrNull()

    private fun loadMethod(ref: MethodRef, classLoader: ClassLoader): Method {
        val type = Class.forName(ref.className, false, classLoader)
        return type.declaredMethods.single { method ->
            method.name == ref.name &&
                method.returnType.name == ref.returnTypeName &&
                method.parameterTypes.map(Class<*>::getName) == ref.parameterTypeNames &&
                Modifier.isStatic(method.modifiers) == ref.isStatic &&
                method.toMethodRef().descriptor == ref.descriptor
        }.apply {
            isAccessible = true
        }
    }

    private fun loadField(ref: FieldRef, classLoader: ClassLoader): Field {
        val type = Class.forName(ref.className, false, classLoader)
        return type.declaredFields.single { field ->
            field.name == ref.name &&
                field.type.name == ref.typeName &&
                Modifier.isStatic(field.modifiers) == ref.isStatic &&
                field.toFieldRef().descriptor == ref.descriptor
        }.apply {
            isAccessible = true
        }
    }
}

internal fun Method.toMethodRef(): MethodRef = MethodRef(
    descriptor = buildString {
        append(declaringClass.toDexDescriptor())
        append("->")
        append(name)
        append('(')
        parameterTypes.forEach { append(it.toDexDescriptor()) }
        append(')')
        append(returnType.toDexDescriptor())
    },
    className = declaringClass.name,
    name = name,
    returnTypeName = returnType.name,
    parameterTypeNames = parameterTypes.map(Class<*>::getName),
    isStatic = Modifier.isStatic(modifiers),
)

internal fun Field.toFieldRef(): FieldRef = FieldRef(
    descriptor = "${declaringClass.toDexDescriptor()}->$name:${type.toDexDescriptor()}",
    className = declaringClass.name,
    name = name,
    typeName = type.name,
    isStatic = Modifier.isStatic(modifiers),
)

private fun Class<*>.toDexDescriptor(): String = when {
    isPrimitive -> when (this) {
        Void.TYPE -> "V"
        Boolean::class.javaPrimitiveType -> "Z"
        Byte::class.javaPrimitiveType -> "B"
        Char::class.javaPrimitiveType -> "C"
        Short::class.javaPrimitiveType -> "S"
        Int::class.javaPrimitiveType -> "I"
        Long::class.javaPrimitiveType -> "J"
        Float::class.javaPrimitiveType -> "F"
        Double::class.javaPrimitiveType -> "D"
        else -> error("Unsupported primitive type: $name")
    }
    isArray -> name.replace('.', '/')
    else -> "L${name.replace('.', '/')};"
}
