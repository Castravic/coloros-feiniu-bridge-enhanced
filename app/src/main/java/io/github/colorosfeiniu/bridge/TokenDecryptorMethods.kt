package io.github.colorosfeiniu.bridge

import java.lang.reflect.Method

/** Retains diagnostic targets on both known-name and structural discovery paths. */
internal object TokenDecryptorMethods {
    fun decryptMethodsOf(type: Class<*>, profile: TokenDecryptorProfile): List<Method> =
        runCatching {
            val methods = type.declaredMethods
            val confirmedContracts = profile.contracts.filter { contract ->
                methods.any { method ->
                    method.name == contract.prefixMethod &&
                        method.returnType == String::class.java && method.parameterTypes.isEmpty()
                }
            }
            methods.filter { method ->
                confirmedContracts.any { it.decryptMethod == method.name } &&
                    method.returnType == String::class.java &&
                    method.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java))
            }
        }.getOrDefault(emptyList())
}
