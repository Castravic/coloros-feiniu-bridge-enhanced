package io.github.colorosfeiniu.bridge

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Minimal reflection helpers standing in for the legacy `XposedHelpers` used before the libxposed
 * API 102 migration. Only the lookups the bridge actually makes are provided.
 *
 * The runtime helpers ([callMethod], [callStaticMethod], [newInstance]) never throw on a missing
 * target: a Gallery build that reshuffles names must degrade to a logged no-op, not to an exception
 * escaping an interceptor. Invocation failures of a target that *does* exist still propagate — those
 * are real bugs in the hooked code and callers wrap them where a fallback is meaningful.
 */
internal object BridgeReflect {

    /** Sink for "lookup missed" diagnostics; installed by the module during startup. */
    @Volatile
    var missLogger: ((String) -> Unit)? = null

    fun findClassOrNull(className: String, classLoader: ClassLoader?): Class<*>? =
        runCatching { Class.forName(className, false, classLoader) }.getOrNull()

    fun findField(type: Class<*>, name: String): Field? {
        var cursor: Class<*>? = type
        while (cursor != null) {
            cursor.declaredFields.firstOrNull { it.name == name }?.let { field ->
                field.isAccessible = true
                return field
            }
            cursor = cursor.superclass
        }
        return null
    }

    fun getObjectField(target: Any, name: String): Any? =
        findField(target.javaClass, name)?.get(target)

    /** Every concrete method named [name], the way legacy `hookAllMethods` walked the hierarchy. */
    fun allMethodsNamed(type: Class<*>, name: String): List<Method> {
        val methods = mutableListOf<Method>()
        var cursor: Class<*>? = type
        while (cursor != null) {
            cursor.declaredMethods
                .filter { it.name == name && !Modifier.isAbstract(it.modifiers) }
                .forEach { method ->
                    method.isAccessible = true
                    methods += method
                }
            cursor = cursor.superclass
        }
        return methods
    }

    fun allConstructors(type: Class<*>): List<Constructor<*>> =
        type.declaredConstructors.map { constructor ->
            constructor.apply { isAccessible = true }
        }

    fun callMethod(target: Any?, name: String, vararg args: Any?): Any? {
        if (target == null) {
            miss("method $name skipped: null target")
            return null
        }
        val method = findMethod(target.javaClass, name, args)
        if (method == null) {
            miss("no method $name(${args.size}) on ${target.javaClass.name}")
            return null
        }
        return method.invoke(target, *args)
    }

    fun callStaticMethod(type: Class<*>?, name: String, vararg args: Any?): Any? {
        if (type == null) {
            miss("static method $name skipped: null type")
            return null
        }
        val method = findMethod(type, name, args)
        if (method == null) {
            miss("no static method $name(${args.size}) on ${type.name}")
            return null
        }
        return method.invoke(null, *args)
    }

    fun newInstance(type: Class<*>?, vararg args: Any?): Any? {
        if (type == null) {
            miss("constructor skipped: null type")
            return null
        }
        val constructor = type.declaredConstructors.firstOrNull { candidate ->
            parametersMatch(candidate.parameterTypes, args)
        }
        if (constructor == null) {
            miss("no matching constructor on ${type.name}")
            return null
        }
        constructor.isAccessible = true
        return constructor.newInstance(*args)
    }

    private fun miss(message: String) {
        runCatching { missLogger?.invoke(message) }
    }

    private fun findMethod(type: Class<*>, name: String, args: Array<out Any?>): Method? {
        var cursor: Class<*>? = type
        while (cursor != null) {
            cursor.declaredMethods.firstOrNull { method ->
                method.name == name && parametersMatch(method.parameterTypes, args)
            }?.let { method ->
                method.isAccessible = true
                return method
            }
            cursor = cursor.superclass
        }
        return null
    }

    private fun parametersMatch(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (types.size != args.size) return false
        return types.withIndex().all { (index, parameter) ->
            val argument = args[index] ?: return@all !parameter.isPrimitive
            boxed(parameter).isAssignableFrom(argument.javaClass)
        }
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
        Char::class.javaPrimitiveType -> Char::class.javaObjectType
        Short::class.javaPrimitiveType -> Short::class.javaObjectType
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        Void.TYPE -> java.lang.Void::class.java
        else -> type
    }
}
