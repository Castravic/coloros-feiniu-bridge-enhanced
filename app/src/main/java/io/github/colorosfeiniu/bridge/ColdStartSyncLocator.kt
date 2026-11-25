package io.github.colorosfeiniu.bridge

/**
 * Dynamically locates the Gallery cold-start local-sync listener, lifecycle controller, and
 * `addListener` registration coroutine across obfuscated and un-obfuscated Gallery builds.
 *
 * Returns `null` whenever any component is missing or ambiguous rather than guessing.
 */
internal object ColdStartSyncLocator {

    const val LISTENER_START_ANCHOR = "mLifecycleListener.onStarted isWaitExecuteFullSync:"
    const val LIFECYCLE_CALLBACKS_INTERFACE = "android.app.Application\$ActivityLifecycleCallbacks"
    const val LIFECYCLE_CALLBACKS_DESCRIPTOR = "Landroid/app/Application\$ActivityLifecycleCallbacks;"
    const val SUSPEND_LAMBDA_SUPERCLASS = "kotlin.coroutines.jvm.internal.SuspendLambda"
    const val SUSPEND_LAMBDA_DESCRIPTOR = "Lkotlin/coroutines/jvm/internal/SuspendLambda;"
    const val COPY_ON_WRITE_LIST = "java.util.concurrent.CopyOnWriteArrayList"
    const val LISTENER_FIELD_NAME = "\$listener"
    const val INVOKE_SUSPEND_NAME = "invokeSuspend"
    const val INVOKE_SUSPEND_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;"

    private const val VOID_NO_ARG = "()V"
    private const val BOOLEAN_NO_ARG = "()Z"
    private const val LIST_ADD_DESCRIPTOR = "(Ljava/lang/Object;)Z"

    private data class ListenerCandidate(
        val className: String,
        val superClassName: String?,
        val interfaceNames: List<String>,
        val nativeStartMethod: String,
    )

    private data class LifecycleCandidate(
        val className: String,
        val staticBoolMethods: List<String>,
    )

    private data class RegistrationCandidate(
        val className: String,
        val listenerFieldType: String,
    )

    /** Accumulates candidate classes across multi-DEX APK images in a single DEX pass. */
    class ScanState {
        private val listeners = mutableListOf<ListenerCandidate>()
        private val lifecycles = mutableListOf<LifecycleCandidate>()
        private val registrations = mutableListOf<RegistrationCandidate>()
        private val interfacesByClass = hashMapOf<String, List<String>>()

        fun accept(reader: DexFile) {
            val anchorIndex = reader.indexOfString(LISTENER_START_ANCHOR)
            val hasLifecycleInterface = reader.indexOfString(LIFECYCLE_CALLBACKS_DESCRIPTOR) >= 0
            val hasSuspendLambda = reader.indexOfString(SUSPEND_LAMBDA_DESCRIPTOR) >= 0
            val hasListenerField = reader.indexOfString(LISTENER_FIELD_NAME) >= 0

            for (clazz in reader.classes()) {
                val ifaces = clazz.interfaceNames
                val nestedIfaces = ifaces.filter { '$' in it }
                if (nestedIfaces.isNotEmpty()) {
                    interfacesByClass[clazz.className] = nestedIfaces
                }

                if (anchorIndex >= 0) {
                    val startMethods = clazz.methodsReferencingString(anchorIndex)
                        .filter { !it.isStatic && it.descriptor == VOID_NO_ARG }
                    if (startMethods.size == 1) {
                        listeners += ListenerCandidate(
                            className = clazz.className,
                            superClassName = clazz.superClassName,
                            interfaceNames = ifaces,
                            nativeStartMethod = startMethods.single().name,
                        )
                    }
                }

                if (hasLifecycleInterface && LIFECYCLE_CALLBACKS_INTERFACE in ifaces) {
                    val staticBools = clazz.methodShapes()
                        .filter { it.isStatic && it.descriptor == BOOLEAN_NO_ARG }
                        .map { it.name }
                    if (staticBools.isNotEmpty()) {
                        lifecycles += LifecycleCandidate(clazz.className, staticBools)
                    }
                }

                if (hasSuspendLambda && hasListenerField && clazz.superClassName == SUSPEND_LAMBDA_SUPERCLASS) {
                    val listenerField = clazz.fieldShapes()
                        .singleOrNull { !it.isStatic && it.name == LISTENER_FIELD_NAME }
                    if (listenerField != null) {
                        val invokesListAdd = clazz.methodInvokes(INVOKE_SUSPEND_NAME, INVOKE_SUSPEND_DESCRIPTOR)
                            .any { call ->
                                call.owner == COPY_ON_WRITE_LIST &&
                                    call.name == "add" &&
                                    call.descriptor == LIST_ADD_DESCRIPTOR
                            }
                        if (invokesListAdd) {
                            registrations += RegistrationCandidate(clazz.className, listenerField.typeName)
                        }
                    }
                }
            }
        }

        fun resolve(): ColdStartSyncTargets? {
            val listener = listeners.singleOrNull() ?: return null
            val superInterfaces = listener.superClassName?.let { interfacesByClass[it] }.orEmpty()
            val matches = mutableListOf<ColdStartSyncTargets>()

            for (lifecycle in lifecycles) {
                if (lifecycle.staticBoolMethods.size != 1) continue
                val foreground = lifecycle.staticBoolMethods.single()
                val prefix = "${lifecycle.className}$"
                for (reg in registrations) {
                    if (!reg.className.startsWith(prefix) || !reg.listenerFieldType.startsWith(prefix)) continue
                    val directMatch = reg.listenerFieldType in listener.interfaceNames
                    val superMatch = reg.listenerFieldType in superInterfaces
                    if (directMatch || superMatch) {
                        matches += ColdStartSyncTargets(
                            lifecycle = lifecycle.className,
                            foreground = foreground,
                            registration = reg.className,
                            listener = listener.className,
                            nativeStartMethod = listener.nativeStartMethod,
                        )
                    }
                }
            }
            return matches.singleOrNull()
        }
    }

    fun locate(dexes: Iterable<DexFile>): ColdStartSyncTargets? {
        val state = ScanState()
        dexes.forEach(state::accept)
        return state.resolve()
    }
}
