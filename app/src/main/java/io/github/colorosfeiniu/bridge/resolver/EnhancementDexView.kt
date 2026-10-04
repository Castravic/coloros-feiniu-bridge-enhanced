package io.github.colorosfeiniu.bridge.resolver

import io.github.colorosfeiniu.bridge.DexFile

/**
 * Projects a gallery DEX image onto the small [ClassView] list the enhancement locators consume.
 *
 * Only classes that can possibly matter are emitted — the ones loading an anchor string and the
 * subclasses of `SyncStateInfo` — because a real Gallery APK carries ~120k classes and materialising
 * strings and methods for all of them would be wasteful on device.
 *
 * A dex that loads none of the anchors is *not* skipped: the `SyncStateInfo` subclasses live in
 * several dex files (17.9.24: classes2/9/10/17), so the superclass check still has to run over every
 * class. For an anchor-less dex `referencedStrings(emptySet())` is the cheap no-op path, so the walk
 * only pays for the class-def table it already reads.
 */
internal object EnhancementDexView {

    fun from(dex: ByteArray): List<ClassView> {
        val reader = DexFile.parse(dex) ?: return emptyList()
        val anchorIndices = ANCHOR_VALUES
            .map { value -> reader.indexOfString(value) }
            .filter { index -> index >= 0 }

        val views = mutableListOf<ClassView>()
        for (clazz in reader.classes()) {
            val strings = clazz.referencedStrings(anchorIndices)
            val isStateInfo = clazz.superClassName == EnhancementLocator.STATE_INFO_SUPERCLASS
            if (strings.isEmpty() && !isStateInfo) continue
            views += ClassView(
                className = clazz.className,
                superClassName = clazz.superClassName,
                methods = clazz.methodShapes().map { method ->
                    MethodView(
                        name = method.name,
                        descriptor = method.descriptor,
                        isStatic = method.isStatic,
                        fieldRefs = method.fieldRefs.map { ref ->
                            FieldRefView(ref.owner, ref.name, ref.type)
                        },
                    )
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
