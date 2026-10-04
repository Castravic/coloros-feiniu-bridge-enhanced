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
 * * **pause reason text** — the subclass of the un-obfuscated `SyncStateInfo` that declares the
 *   text-producing `(Landroid/content/Context;)Ljava/lang/String;` method.
 *
 * Any ambiguity (no candidate, or more than one candidate that survives validation) yields no
 * target, because these hooks change backup behaviour when they misfire.
 */
internal object EnhancementLocator {

    const val PAUSE_REASON_DESCRIPTOR =
        "Lcom/oplus/gallery/framework/abilities/cloudsync/nas/backup/state/PauseReason;"
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
        return EnhancementTargets(
            temperatureProvider = locateTemperatureProvider(classes, diagnostics),
            pauseConditionCheckers = locatePauseConditionCheckers(classes, diagnostics),
            pauseStateInfoClass = locatePauseStateInfoClass(classes, diagnostics),
            pauseReasonText = locatePauseReasonText(classes, diagnostics),
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

    private fun locatePauseStateInfoClass(
        classes: List<ClassView>,
        diagnostics: MutableList<String>,
    ): String? {
        val candidates = classes
            .filter { clazz -> clazz.superClassName == STATE_INFO_SUPERCLASS }
            .filter { clazz ->
                clazz.methods.any { method ->
                    method.descriptor == CONTEXT_TO_STRING || method.descriptor == INT_CONTEXT_TO_STRING
                }
            }
        if (candidates.size != 1) {
            diagnostics += "pause state info: candidates=${candidates.size}"
            return null
        }
        diagnostics += "pause state info: ${candidates.single().className}"
        return candidates.single().className
    }

    private fun locatePauseReasonText(
        classes: List<ClassView>,
        diagnostics: MutableList<String>,
    ): LocatedMethod? {
        val candidates = classes
            .filter { clazz -> clazz.superClassName == STATE_INFO_SUPERCLASS }
            .flatMap { clazz ->
                clazz.methods
                    .filter { method ->
                        !method.isStatic &&
                            (method.descriptor == CONTEXT_TO_STRING ||
                                method.descriptor == INT_CONTEXT_TO_STRING)
                    }
                    .map { method -> LocatedMethod(clazz.className, method.name, method.descriptor) }
            }
        if (candidates.size != 1) {
            diagnostics += "pause reason text: candidates=${candidates.size}"
            return null
        }
        diagnostics += "pause reason text: ${candidates.single().className}"
        return candidates.single()
    }
}
