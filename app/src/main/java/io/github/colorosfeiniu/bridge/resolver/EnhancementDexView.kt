package io.github.colorosfeiniu.bridge.resolver

import io.github.colorosfeiniu.bridge.DexFile

/**
 * Projects a gallery DEX image onto the small [ClassView] list the enhancement locators consume.
 *
 * Only classes that can possibly matter are emitted — the ones loading an anchor string and the
 * subclasses of `SyncStateInfo` — because a real Gallery APK carries ~120k classes and materialising
 * strings and methods for all of them would be wasteful on device.
 */
internal object EnhancementDexView {

    fun from(dex: ByteArray): List<ClassView> {
        val reader = DexFile.parse(dex) ?: return emptyList()
        val anchorIndices = ANCHOR_VALUES
            .map { value -> reader.indexOfString(value) }
            .filter { index -> index >= 0 }
        if (anchorIndices.isEmpty()) return emptyList()

        val views = mutableListOf<ClassView>()
        for (clazz in reader.classes()) {
            val strings = clazz.referencedStrings(anchorIndices)
            val isStateInfo = clazz.superClassName == EnhancementLocator.STATE_INFO_SUPERCLASS
            if (strings.isEmpty() && !isStateInfo) continue
            views += ClassView(
                className = clazz.className,
                superClassName = clazz.superClassName,
                methods = clazz.methodShapes().map { method ->
                    MethodView(method.name, method.descriptor, method.isStatic)
                },
                strings = strings,
            )
        }
        return views
    }

    private val ANCHOR_VALUES = listOf(
        EnhancementLocator.TEMPERATURE_TEST_FLAG,
        EnhancementLocator.TEMPERATURE_LEVEL_FLAG,
        EnhancementLocator.CONDITION_CHECKER_TAG,
    )
}
