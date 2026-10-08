package io.github.colorosfeiniu.bridge.resolver

/**
 * A class as the locators see it: name, superclass, declared methods and the strings its bodies load.
 *
 * The production adapter reads this view out of a DEX image ([DexClassView]); tests build it by hand,
 * so the locators stay covered by plain JVM unit tests even though the real anchors come from a
 * proprietary Gallery APK.
 */
internal data class ClassView(
    val className: String,
    val superClassName: String?,
    val methods: List<MethodView>,
    val strings: Set<String>,
)

internal data class MethodView(
    val name: String,
    val descriptor: String,
    val isStatic: Boolean,
    /** Field accesses inside this method body, used to pin the pause-reason text producer. */
    val fieldRefs: List<FieldRefView> = emptyList(),
)

/** A field access as the locators see it: declaring class, field name and field type descriptor. */
internal data class FieldRefView(
    val owner: String,
    val name: String,
    val type: String,
)

/** One dynamically located target: the class and one of its methods. */
internal data class LocatedMethod(
    val className: String,
    val methodName: String,
    val descriptor: String,
)

/**
 * Result of one gallery scan. Every field is null/empty unless the anchor matched exactly once, so a
 * caller can treat an absent field as "leave this enhancement off" instead of guessing.
 */
internal data class EnhancementTargets(
    val temperatureProvider: LocatedMethod?,
    val pauseConditionCheckers: List<LocatedMethod>,
    val pauseStateInfoClass: String?,
    val pauseReasonText: LocatedMethod?,
    val diagnostics: List<String>,
) {
    val isEmpty: Boolean
        get() = temperatureProvider == null &&
            pauseConditionCheckers.isEmpty() &&
            pauseStateInfoClass == null &&
            pauseReasonText == null
}

/**
 * Dynamic-first locators for the Gallery enhancement groups (temperature, pause reason text).
 *
 * Each group is anchored on a stable, obfuscation-resistant trace rather than on the class name:
 *
 * * **temperature** — the temperature provider declares `()F` and loads the debug flags
 *   `debug.gallery.temperature.test` / `debug.gallery.temperature.level`;
 * * **pause condition checker** — the only class loading the `NasBackupCondChk` log tag and
 *   declaring `(ZZ)Lcom/oplus/gallery/.../PauseReason;` methods;
 * * **pause reason text** — the subclass of the un-obfuscated `SyncStateInfo` whose text-producing
 *   `(Context)` / `(int, Context)` → `String` method reads the paused state, i.e. touches a field
 *   typed `PauseReason` or one of the un-obfuscated `R.string.nas_backup_paused*` resources.
 *
 * The pause candidates are deliberately narrowed by that field trace: the loose predicate (a
 * `SyncStateInfo` subclass with a text method) matches 14 classes / 17 methods in the 17.9.24
 * reference APK, which is ambiguous on device and would drop the enhancement.
 *
 * Any ambiguity (no candidate, or more than one candidate that survives validation) yields no
 * target, because these hooks change backup behaviour when they misfire.
 */
internal object EnhancementLocator {

    const val PAUSE_REASON_DESCRIPTOR =
        "Lcom/oplus/gallery/framework/abilities/cloudsync/nas/backup/state/PauseReason;"
    const val NAS_STRING_RESOURCE_OWNER = "com.oplus.gallery.basebiz.R\$string"
    const val PAUSED_STRING_RESOURCE_PREFIX = "nas_backup_paused"
    const val STATE_INFO_SUPERCLASS = "com.oplus.gallery.business_lib.cloudsync.SyncStateInfo"
    const val TEMPERATURE_TEST_FLAG = "debug.gallery.temperature.test"
    const val TEMPERATURE_LEVEL_FLAG = "debug.gallery.temperature.level"
    const val CONDITION_CHECKER_TAG = "NasBackupCondChk"

    private const val FLOAT_NO_ARG = "()F"
    private const val BOOLEAN_BOOLEAN_PAUSE_REASON = "(ZZ)$PAUSE_REASON_DESCRIPTOR"
    private const val CONTEXT_TO_STRING = "(Landroid/content/Context;)Ljava/lang/String;"
    private const val INT_CONTEXT_TO_STRING = "(ILandroid/content/Context;)Ljava/lang/String;"

    fun locate(classes: List<ClassView>): EnhancementTargets {
        val diagnostics = mutableListOf<String>()
        val pauseTrace = locatePauseTrace(classes)
        if (pauseTrace.size == 1) {
            diagnostics += "pause state info: ${pauseTrace.single().className}"
            diagnostics += "pause reason text: ${pauseTrace.single().method.className}." +
                pauseTrace.single().method.methodName
        } else {
            diagnostics += "pause state info: candidates=${pauseTrace.size}"
            diagnostics += "pause reason text: candidates=${pauseTrace.size}"
        }
        return EnhancementTargets(
            temperatureProvider = locateTemperatureProvider(classes, diagnostics),
            pauseConditionCheckers = locatePauseConditionCheckers(classes, diagnostics),
            pauseStateInfoClass = pauseTrace.singleOrNull()?.className,
            pauseReasonText = pauseTrace.singleOrNull()?.method,
            diagnostics = diagnostics,
        )
    }

    private fun locateTemperatureProvider(
        classes: List<ClassView>,
        diagnostics: MutableList<String>,
    ): LocatedMethod? {
        val anchors = classes.filter { clazz ->
            TEMPERATURE_TEST_FLAG in clazz.strings && TEMPERATURE_LEVEL_FLAG in clazz.strings
        }
        if (anchors.isEmpty()) {
            diagnostics += "temperature: no class loads the debug temperature flags"
            return null
        }

        val candidates = anchors.flatMap { clazz ->
            clazz.methods
                .filter { method -> method.isStatic && method.descriptor == FLOAT_NO_ARG }
                .map { method -> LocatedMethod(clazz.className, method.name, method.descriptor) }
        }
        if (anchors.size != 1 || candidates.size != 1) {
            diagnostics += "temperature: anchors=${anchors.size} candidates=${candidates.size}"
            return null
        }
        diagnostics += "temperature: ${candidates.single().className} via debug flags"
        return candidates.single()
    }

    private fun locatePauseConditionCheckers(
        classes: List<ClassView>,
        diagnostics: MutableList<String>,
    ): List<LocatedMethod> {
        val anchors = classes.filter { clazz -> CONDITION_CHECKER_TAG in clazz.strings }
        if (anchors.isEmpty()) {
            diagnostics += "pause condition checker: no class loads $CONDITION_CHECKER_TAG"
            return emptyList()
        }

        val candidates = anchors.flatMap { clazz ->
            clazz.methods
                .filter { method -> method.descriptor == BOOLEAN_BOOLEAN_PAUSE_REASON }
                .map { method -> LocatedMethod(clazz.className, method.name, method.descriptor) }
        }
        if (anchors.size != 1 || candidates.isEmpty()) {
            diagnostics += "pause condition checker: anchors=${anchors.size} candidates=${candidates.size}"
            return emptyList()
        }
        diagnostics += "pause condition checker: ${candidates.first().className} methods=" +
            candidates.joinToString(",") { it.methodName }
        return candidates
    }

    /** A pause text method together with the `SyncStateInfo` subclass that declares it. */
    private data class PauseTrace(
        val className: String,
        val method: LocatedMethod,
    )

    /**
     * The `SyncStateInfo` subclasses whose text method carries the pause trace, i.e. reads the
     * `PauseReason` enum or the `nas_backup_paused*` string resources. The state info class and the
     * text method share this predicate so the two targets can never disagree.
     */
    private fun locatePauseTrace(classes: List<ClassView>): List<PauseTrace> = classes
        .filter { clazz -> clazz.superClassName == STATE_INFO_SUPERCLASS }
        .flatMap { clazz ->
            clazz.methods
                .filter { method ->
                    !method.isStatic &&
                        (method.descriptor == CONTEXT_TO_STRING ||
                            method.descriptor == INT_CONTEXT_TO_STRING) &&
                        method.fieldRefs.any(::isPauseTrace)
                }
                .map { method ->
                    PauseTrace(
                        className = clazz.className,
                        method = LocatedMethod(clazz.className, method.name, method.descriptor),
                    )
                }
        }

    /**
     * The un-obfuscated engineering trace the pause text method leaves behind: it reads the paused
     * state class field (`PauseReason`) or one of the `nas_backup_paused*` resource ids. Both are
     * stable across Gallery builds while the obfuscated class names are not.
     */
    private fun isPauseTrace(ref: FieldRefView): Boolean =
        ref.type == PAUSE_REASON_DESCRIPTOR ||
            (ref.owner == NAS_STRING_RESOURCE_OWNER &&
                ref.name.startsWith(PAUSED_STRING_RESOURCE_PREFIX))
}
