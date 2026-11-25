package io.github.colorosfeiniu.bridge

/**
 * Minimal read-only DEX reader.
 *
 * Only the tables the bridge actually needs are decoded, and nothing here touches Android APIs, so
 * the parsing stays covered by plain JVM unit tests. Every accessor is bounds checked because the
 * input is whatever APK happens to be installed on the device.
 */
internal class DexFile private constructor(private val data: ByteArray) {

    private val stringIdsSize = data.uintAt(STRING_IDS_SIZE)
    private val stringIdsOffset = data.uintAt(STRING_IDS_OFFSET)
    private val typeIdsSize = data.uintAt(TYPE_IDS_SIZE)
    private val typeIdsOffset = data.uintAt(TYPE_IDS_OFFSET)
    private val protoIdsSize = data.uintAt(PROTO_IDS_SIZE)
    private val protoIdsOffset = data.uintAt(PROTO_IDS_OFFSET)
    private val fieldIdsSize = data.uintAt(FIELD_IDS_SIZE)
    private val fieldIdsOffset = data.uintAt(FIELD_IDS_OFFSET)
    private val methodIdsSize = data.uintAt(METHOD_IDS_SIZE)
    private val methodIdsOffset = data.uintAt(METHOD_IDS_OFFSET)
    private val classDefsSize = data.uintAt(CLASS_DEFS_SIZE)
    private val classDefsOffset = data.uintAt(CLASS_DEFS_OFFSET)

    private val usable = stringIdsSize > 0 && stringIdsOffset > 0

    /** The first string in the pool matching [predicate], or null when there is none. */
    fun firstString(predicate: (String) -> Boolean): String? {
        if (!usable) return null
        for (index in 0 until stringIdsSize) {
            val value = string(index) ?: continue
            if (predicate(value)) return value
        }
        return null
    }

    /**
     * Scans the string pool for the first string containing [needle] and matching [predicate],
     * avoiding `String` allocations until [needle] actually matches the raw UTF-8 span.
     */
    fun firstStringContaining(needle: String, predicate: (String) -> Boolean): String? {
        if (!usable || needle.isEmpty()) return null
        if (!isAscii(needle)) return firstString { it.contains(needle) && predicate(it) }

        val needleBytes = needle.toByteArray(Charsets.US_ASCII)
        if (!containsAsciiBytes(needleBytes)) return null

        for (index in 0 until stringIdsSize) {
            val span = stringByteSpan(index)
            if (span < 0L) continue
            val start = (span ushr 32).toInt()
            val end = span.toInt()
            if (end - start < needleBytes.size) continue
            if (!spanContainsBytes(start, end, needleBytes)) continue
            val value = runCatching { String(data, start, end - start, Charsets.UTF_8) }.getOrNull()
                ?: continue
            if (predicate(value)) return value
        }
        return null
    }

    /** Pool index of [value], or -1 when the string is absent. */
    fun indexOfString(value: String): Int {
        if (!usable) return -1
        if (isAscii(value)) {
            val needle = value.toByteArray(Charsets.US_ASCII)
            for (index in 0 until stringIdsSize) {
                val start = stringDataStart(index)
                if (start < 0 || start + needle.size >= data.size) continue
                if (data[start] != needle[0] || data[start + needle.size].toInt() != 0) continue
                var matched = true
                for (i in 1 until needle.size) {
                    if (data[start + i] != needle[i]) {
                        matched = false
                        break
                    }
                }
                if (matched) return index
            }
            return -1
        }
        for (index in 0 until stringIdsSize) {
            if (string(index) == value) return index
        }
        return -1
    }

    /** The first defined class matching [predicate], or null when there is none. */
    fun firstClass(predicate: (DexClass) -> Boolean): DexClass? {
        if (classDefsSize <= 0 || classDefsOffset <= 0) return null
        for (index in 0 until classDefsSize) {
            val clazz = classAt(index) ?: continue
            if (predicate(clazz)) return clazz
        }
        return null
    }

    /** Every class in the image; a malformed entry truncates the walk instead of throwing. */
    fun classes(): List<DexClass> {
        if (classDefsSize <= 0 || classDefsOffset <= 0) return emptyList()
        val result = ArrayList<DexClass>(classDefsSize)
        for (index in 0 until classDefsSize) {
            result += classAt(index) ?: break
        }
        return result
    }

    private fun classAt(index: Int): DexClass? {
        val entry = classDefsOffset + index * CLASS_DEF_SIZE
        if (entry < 0 || entry + CLASS_DEF_SIZE > data.size) return null
        val descriptor = typeDescriptor(data.uintAt(entry)) ?: return null
        val classDataOffset = data.uintAt(entry + CLASS_DEF_DATA_OFFSET)
        val interfacesOffset = data.uintAt(entry + CLASS_DEF_INTERFACES)
        val superName = data.uintAt(entry + CLASS_DEF_SUPERCLASS).let { superIndex ->
            if (superIndex == NO_INDEX) null else typeDescriptor(superIndex)?.toClassName()
        }
        return DexClass(descriptor, superName, interfacesOffset, classDataOffset)
    }

    private fun string(index: Int): String? {
        val span = stringByteSpan(index)
        if (span < 0L) return null
        val start = (span ushr 32).toInt()
        val end = span.toInt()
        return runCatching { String(data, start, end - start, Charsets.UTF_8) }.getOrNull()
    }

    private fun stringDataStart(index: Int): Int {
        if (index < 0 || index >= stringIdsSize) return -1
        val idOffset = stringIdsOffset + index * STRING_ID_SIZE
        if (idOffset < 0 || idOffset + STRING_ID_SIZE > data.size) return -1
        val offset = data.uintAt(idOffset)
        if (offset < 0 || offset >= data.size) return -1
        var cursor = offset
        while (cursor < data.size) {
            val byte = data[cursor].toInt() and 0xff
            cursor++
            if (byte and 0x80 == 0) break
        }
        return if (cursor >= data.size) -1 else cursor
    }

    private fun stringByteSpan(index: Int): Long {
        val start = stringDataStart(index)
        if (start < 0) return -1L
        var cursor = start
        while (cursor < data.size && data[cursor].toInt() != 0) cursor++
        if (cursor > data.size || cursor == start) return -1L
        return (start.toLong() shl 32) or (cursor.toLong() and 0xffffffffL)
    }

    private fun isAscii(value: String): Boolean =
        value.isNotEmpty() && value.all { it.code in 1..127 }

    private fun containsAsciiBytes(needle: ByteArray): Boolean =
        spanContainsBytes(0, data.size, needle)

    private fun spanContainsBytes(start: Int, end: Int, needle: ByteArray): Boolean {
        val len = needle.size
        if (len == 0 || end - start < len) return false
        val first = needle[0]
        val limit = end - len
        var pos = start
        while (pos <= limit) {
            if (data[pos] == first) {
                var i = 1
                while (i < len && data[pos + i] == needle[i]) i++
                if (i == len) return true
            }
            pos++
        }
        return false
    }

    private fun typeDescriptor(index: Int): String? {
        if (index < 0 || index >= typeIdsSize) return null
        val entry = typeIdsOffset + index * TYPE_ID_SIZE
        if (entry < 0 || entry + TYPE_ID_SIZE > data.size) return null
        return string(data.uintAt(entry))
    }

    private fun protoDescriptor(index: Int): String? {
        if (index < 0 || index >= protoIdsSize) return null
        val entry = protoIdsOffset + index * PROTO_ID_SIZE
        if (entry < 0 || entry + PROTO_ID_SIZE > data.size) return null

        val returnType = typeDescriptor(data.uintAt(entry + PROTO_ID_RETURN_TYPE)) ?: return null
        val parametersOffset = data.uintAt(entry + PROTO_ID_PARAMETERS)
        if (parametersOffset <= 0) return "()$returnType"
        if (parametersOffset + 4 > data.size) return null

        val parameters = StringBuilder()
        val count = data.uintAt(parametersOffset)
        if (count < 0 || parametersOffset + 4 + count.toLong() * 2L > data.size) return null
        for (position in 0 until count) {
            val at = parametersOffset + 4 + position * 2
            parameters.append(typeDescriptor(data.ushortAt(at)) ?: return null)
        }
        return "($parameters)$returnType"
    }

    /** Resolves a `field_id` into its owner class, field name and type descriptor. */
    private fun fieldRef(index: Int): FieldRef? {
        if (index < 0 || index >= fieldIdsSize) return null
        val entry = fieldIdsOffset + index * FIELD_ID_SIZE
        if (entry < 0 || entry + FIELD_ID_SIZE > data.size) return null
        val owner = typeDescriptor(data.ushortAt(entry))?.toClassName() ?: return null
        val type = typeDescriptor(data.ushortAt(entry + FIELD_ID_TYPE)) ?: return null
        val name = string(data.uintAt(entry + FIELD_ID_NAME)) ?: return null
        return FieldRef(owner, name, type)
    }

    private fun fieldShape(index: Int, accessFlags: Int): FieldShape? {
        if (index < 0 || index >= fieldIdsSize) return null
        val entry = fieldIdsOffset + index * FIELD_ID_SIZE
        if (entry < 0 || entry + FIELD_ID_SIZE > data.size) return null
        val typeDescriptor = typeDescriptor(data.ushortAt(entry + FIELD_ID_TYPE)) ?: return null
        val name = string(data.uintAt(entry + FIELD_ID_NAME)) ?: return null
        return FieldShape(name, typeDescriptor, accessFlags)
    }

    /** Resolves a `method_id` into its name and `(params)return` descriptor. */
    private fun methodRef(index: Int): Pair<String, String>? {
        if (index < 0 || index >= methodIdsSize) return null
        val entry = methodIdsOffset + index * METHOD_ID_SIZE
        if (entry < 0 || entry + METHOD_ID_SIZE > data.size) return null
        val proto = protoDescriptor(data.ushortAt(entry + METHOD_ID_PROTO)) ?: return null
        val name = string(data.uintAt(entry + METHOD_ID_NAME)) ?: return null
        return name to proto
    }

    private fun methodInvokeRef(index: Int): MethodInvokeRef? {
        if (index < 0 || index >= methodIdsSize) return null
        val entry = methodIdsOffset + index * METHOD_ID_SIZE
        if (entry < 0 || entry + METHOD_ID_SIZE > data.size) return null
        val owner = typeDescriptor(data.ushortAt(entry))?.toClassName() ?: return null
        val proto = protoDescriptor(data.ushortAt(entry + METHOD_ID_PROTO)) ?: return null
        val name = string(data.uintAt(entry + METHOD_ID_NAME)) ?: return null
        return MethodInvokeRef(owner, name, proto)
    }

    /**
     * A field reference as read from a `field_id` item: the declaring class, the field name and the
     * field type descriptor. The owner and the type stay in the un-obfuscated form the Gallery
     * resource/enum classes use, so they survive obfuscation.
     */
    data class FieldRef(
        val owner: String,
        val name: String,
        val type: String,
    )

    /** A field declared by a class, with its type descriptor and access flags. */
    data class FieldShape(
        val name: String,
        val typeDescriptor: String,
        val accessFlags: Int,
    ) {
        val typeName: String get() = typeDescriptor.toClassName()
        val isStatic: Boolean get() = accessFlags and ACC_STATIC != 0
    }

    /** A method target referenced by an `invoke-*` opcode inside a method body. */
    data class MethodInvokeRef(
        val owner: String,
        val name: String,
        val descriptor: String,
    )

    /** A method as declared by a class, with the access flags the locators filter on. */
    data class MethodShape(
        val name: String,
        val descriptor: String,
        val accessFlags: Int,
        val fieldRefs: List<FieldRef>,
    ) {
        val isStatic: Boolean get() = accessFlags and ACC_STATIC != 0
    }

    inner class DexClass internal constructor(
        val descriptor: String,
        /** Superclass class name, or null for `java.lang.Object` and unresolved entries. */
        val superClassName: String?,
        private val interfacesOffset: Int,
        private val classDataOffset: Int,
    ) {
        /** `Lcom/oplus/aiunit/vision/qp80;` becomes `com.oplus.aiunit.vision.qp80`. */
        val className: String
            get() = descriptor.toClassName()

        /** Implemented interfaces as dot-separated class names. */
        val interfaceNames: List<String> by lazy { readInterfaces() }

        private val methods: List<DexMethod> by lazy { readMethods() }

        /** Declared methods of this class only, as name/descriptor/access tuples. */
        fun methodShapes(): List<MethodShape> = methods.mapNotNull { method ->
            if (!method.hasValidReference) return@mapNotNull null
            MethodShape(method.name, method.descriptor, method.accessFlags, method.fieldRefs())
        }

        /** Declared static and instance fields of this class. */
        fun fieldShapes(): List<FieldShape> {
            if (classDataOffset <= 0 || classDataOffset >= data.size) return emptyList()
            val reader = Reader(classDataOffset)
            val staticFields = reader.uleb128()
            val instanceFields = reader.uleb128()
            val directMethods = reader.uleb128()
            val virtualMethods = reader.uleb128()
            if (staticFields < 0 || instanceFields < 0 || directMethods < 0 || virtualMethods < 0) {
                return emptyList()
            }

            val result = mutableListOf<FieldShape>()
            for (count in listOf(staticFields, instanceFields)) {
                var fieldIndex = 0
                repeat(count) {
                    val diff = reader.uleb128()
                    val accessFlags = reader.uleb128()
                    if (diff < 0 || accessFlags < 0) return result
                    fieldIndex += diff
                    fieldShape(fieldIndex, accessFlags)?.let(result::add)
                }
            }
            return result
        }

        fun declaresMethod(name: String, descriptor: String): Boolean =
            methods.any { it.name == name && it.descriptor == descriptor }

        fun referencesString(stringIndex: Int): Boolean =
            stringIndex >= 0 && methods.any { it.referencesString(stringIndex) }

        /** Declared methods whose bodies load [stringIndex], decoded lazily on match only. */
        fun methodsReferencingString(stringIndex: Int): List<MethodShape> {
            if (stringIndex < 0) return emptyList()
            return methods.mapNotNull { method ->
                if (!method.referencesString(stringIndex) || !method.hasValidReference) return@mapNotNull null
                MethodShape(method.name, method.descriptor, method.accessFlags, emptyList())
            }
        }

        /** Method invocations inside declared methods matching [name] and [descriptor]. */
        fun methodInvokes(name: String, descriptor: String): List<MethodInvokeRef> =
            methods.filter { it.name == name && it.descriptor == descriptor }
                .flatMap { it.invokedMethodRefs() }

        /** The subset of [stringIndices] whose value this class's own methods load. */
        fun referencedStrings(stringIndices: Collection<Int>): Set<String> {
            val wanted = stringIndices.filter { it >= 0 }.toSet()
            if (wanted.isEmpty()) return emptySet()
            val result = LinkedHashSet<String>()
            methods.forEach { method ->
                method.constStringIndices().forEach { index ->
                    if (index in wanted) string(index)?.let(result::add)
                }
            }
            return result
        }

        private fun readInterfaces(): List<String> {
            if (interfacesOffset <= 0 || interfacesOffset + 4 > data.size) return emptyList()
            val count = data.uintAt(interfacesOffset)
            if (count <= 0 || interfacesOffset + 4 + count.toLong() * 2L > data.size) return emptyList()
            val result = ArrayList<String>(count)
            for (position in 0 until count) {
                val typeIdx = data.ushortAt(interfacesOffset + 4 + position * 2)
                typeDescriptor(typeIdx)?.toClassName()?.let(result::add)
            }
            return result
        }

        private fun readMethods(): List<DexMethod> {
            if (classDataOffset <= 0 || classDataOffset >= data.size) return emptyList()
            val reader = Reader(classDataOffset)
            val staticFields = reader.uleb128()
            val instanceFields = reader.uleb128()
            val directMethods = reader.uleb128()
            val virtualMethods = reader.uleb128()
            if (staticFields < 0 || instanceFields < 0 || directMethods < 0 || virtualMethods < 0) {
                return emptyList()
            }

            repeat(staticFields + instanceFields) {
                reader.uleb128()
                reader.uleb128()
            }

            val result = mutableListOf<DexMethod>()
            for (count in listOf(directMethods, virtualMethods)) {
                var methodIndex = 0
                repeat(count) {
                    val diff = reader.uleb128()
                    val accessFlags = reader.uleb128()
                    val codeOffset = reader.uleb128()
                    if (diff < 0 || codeOffset < 0) return result
                    methodIndex += diff
                    if (methodIndex in 0 until methodIdsSize) {
                        result += DexMethod(methodIndex, accessFlags, codeOffset)
                    }
                }
            }
            return result
        }
    }

    private inner class DexMethod(
        private val methodIndex: Int,
        val accessFlags: Int,
        private val codeOffset: Int,
    ) {
        // Anchor scans need only code offsets; decode signatures only for matching classes.
        private val reference by lazy { methodRef(methodIndex) }
        val hasValidReference: Boolean get() = reference != null
        val name: String get() = reference?.first.orEmpty()
        val descriptor: String get() = reference?.second.orEmpty()

        fun referencesString(stringIndex: Int): Boolean {
            var matched = false
            walkInstructions { cursor, end, opcode ->
                when {
                    opcode == OP_CONST_STRING && cursor + 4 <= end ->
                        if (data.ushortAt(cursor + 2) == stringIndex) {
                            matched = true
                            return@walkInstructions false
                        }

                    opcode == OP_CONST_STRING_JUMBO && cursor + 6 <= end ->
                        if (data.uintAt(cursor + 2) == stringIndex) {
                            matched = true
                            return@walkInstructions false
                        }
                }
                true
            }
            return matched
        }

        /** Every `field_id` this method reads or writes, resolved to owner/name/type. */
        fun fieldRefs(): List<DexFile.FieldRef> = fieldRefIndices().mapNotNull { index ->
            fieldRef(index)
        }

        /** Indices of every field-access operand in this method's code item. */
        fun fieldRefIndices(): List<Int> {
            val result = ArrayList<Int>()
            walkInstructions { cursor, end, opcode ->
                if (opcode in OP_IGET..OP_SPUT_SHORT && cursor + 4 <= end) {
                    result += data.ushortAt(cursor + 2)
                }
                true
            }
            return result
        }

        /** Indices of every `const-string` operand in this method's code item. */
        fun constStringIndices(): List<Int> {
            val result = ArrayList<Int>()
            walkInstructions { cursor, end, opcode ->
                when {
                    opcode == OP_CONST_STRING && cursor + 4 <= end ->
                        result += data.ushortAt(cursor + 2)

                    opcode == OP_CONST_STRING_JUMBO && cursor + 6 <= end ->
                        result += data.uintAt(cursor + 2)
                }
                true
            }
            return result
        }

        /** Targets of every standard invoke or invoke-range instruction in this body. */
        fun invokedMethodRefs(): List<MethodInvokeRef> {
            val result = ArrayList<MethodInvokeRef>()
            walkInstructions { cursor, end, opcode ->
                if (((opcode in OP_INVOKE_VIRTUAL..OP_INVOKE_INTERFACE) ||
                        (opcode in OP_INVOKE_VIRTUAL_RANGE..OP_INVOKE_INTERFACE_RANGE)) &&
                    cursor + 6 <= end
                ) {
                    methodInvokeRef(data.ushortAt(cursor + 2))?.let(result::add)
                }
                true
            }
            return result
        }

        private inline fun walkInstructions(visitor: (cursor: Int, end: Int, opcode: Int) -> Boolean) {
            if (codeOffset <= 0 || codeOffset + CODE_ITEM_HEADER_SIZE > data.size) return
            val units = data.uintAt(codeOffset + CODE_ITEM_INSNS_SIZE)
            if (units <= 0) return
            val start = codeOffset + CODE_ITEM_HEADER_SIZE
            val end = start + units * 2
            if (end > data.size) return

            var cursor = start
            while (cursor + 2 <= end) {
                val opcode = data[cursor].toInt() and 0xff
                if (!visitor(cursor, end, opcode)) return
                val step = nextInstructionUnits(cursor, end)
                if (step <= 0) break
                cursor += step * 2
            }
        }

        private fun nextInstructionUnits(cursor: Int, end: Int): Int {
            val unit0 = data.ushortAt(cursor)
            if (unit0 < 0) return -1
            val opcode = unit0 and 0xff
            if (opcode == 0 && unit0 != 0) {
                return when (unit0) {
                    PAYLOAD_PACKED_SWITCH -> {
                        if (cursor + 4 > end) return -1
                        data.ushortAt(cursor + 2) * 2 + 4
                    }

                    PAYLOAD_SPARSE_SWITCH -> {
                        if (cursor + 4 > end) return -1
                        data.ushortAt(cursor + 2) * 4 + 2
                    }

                    PAYLOAD_FILL_ARRAY_DATA -> {
                        if (cursor + 8 > end) return -1
                        val width = data.ushortAt(cursor + 2)
                        val size = data.uintAt(cursor + 4)
                        if (width <= 0 || size < 0) return -1
                        val units = (size.toLong() * width.toLong() + 1L) / 2L + 4L
                        if (units > Int.MAX_VALUE) -1 else units.toInt()
                    }

                    else -> 1
                }
            }
            return instructionUnits(opcode)
        }
    }

    private inner class Reader(private var offset: Int) {
        fun uleb128(): Int {
            var result = 0
            var shift = 0
            while (offset < data.size) {
                val byte = data[offset].toInt() and 0xff
                offset++
                result = result or ((byte and 0x7f) shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                if (shift > 28) return -1
            }
            return -1
        }
    }

    companion object {
        private const val HEADER_SIZE = 0x70
        private const val STRING_IDS_SIZE = 0x38
        private const val STRING_IDS_OFFSET = 0x3c
        private const val TYPE_IDS_SIZE = 0x40
        private const val TYPE_IDS_OFFSET = 0x44
        private const val PROTO_IDS_SIZE = 0x48
        private const val PROTO_IDS_OFFSET = 0x4c
        private const val FIELD_IDS_SIZE = 0x50
        private const val FIELD_IDS_OFFSET = 0x54
        private const val METHOD_IDS_SIZE = 0x58
        private const val METHOD_IDS_OFFSET = 0x5c
        private const val CLASS_DEFS_SIZE = 0x60
        private const val CLASS_DEFS_OFFSET = 0x64

        private const val STRING_ID_SIZE = 4
        private const val TYPE_ID_SIZE = 4
        private const val PROTO_ID_SIZE = 12
        private const val PROTO_ID_RETURN_TYPE = 4
        private const val PROTO_ID_PARAMETERS = 8
        private const val CLASS_DEF_SUPERCLASS = 8
        private const val CLASS_DEF_INTERFACES = 12
        private const val NO_INDEX = -1
        private const val ACC_STATIC = 0x8
        private const val FIELD_ID_SIZE = 8
        private const val FIELD_ID_TYPE = 2
        private const val FIELD_ID_NAME = 4
        private const val METHOD_ID_SIZE = 8
        private const val METHOD_ID_PROTO = 2
        private const val METHOD_ID_NAME = 4
        private const val CLASS_DEF_SIZE = 32
        private const val CLASS_DEF_DATA_OFFSET = 24
        private const val CODE_ITEM_HEADER_SIZE = 16
        private const val CODE_ITEM_INSNS_SIZE = 12

        private const val OP_CONST_STRING = 0x1a
        private const val OP_CONST_STRING_JUMBO = 0x1b

        /** `iget` … `sput-short` are the format 22c field-access opcodes. */
        private const val OP_IGET = 0x52
        private const val OP_SPUT_SHORT = 0x6d

        private const val OP_INVOKE_VIRTUAL = 0x6e
        private const val OP_INVOKE_INTERFACE = 0x72
        private const val OP_INVOKE_VIRTUAL_RANGE = 0x74
        private const val OP_INVOKE_INTERFACE_RANGE = 0x78

        private const val PAYLOAD_PACKED_SWITCH = 0x0100
        private const val PAYLOAD_SPARSE_SWITCH = 0x0200
        private const val PAYLOAD_FILL_ARRAY_DATA = 0x0300

        /**
         * Instruction width in 16-bit code units, indexed by opcode. Walking instructions properly
         * keeps `const-string` operands from being read out of a misaligned position.
         */
        private val INSTRUCTION_UNITS = IntArray(256) { 1 }.apply {
            fun span(range: IntRange, units: Int) = range.forEach { this[it] = units }
            span(0x02..0x02, 2); span(0x03..0x03, 3)
            span(0x05..0x05, 2); span(0x06..0x06, 3)
            span(0x08..0x08, 2); span(0x09..0x09, 3)
            span(0x13..0x13, 2); span(0x14..0x14, 3)
            span(0x15..0x16, 2); span(0x17..0x17, 3); span(0x18..0x18, 5)
            span(0x19..0x1a, 2); span(0x1b..0x1b, 3); span(0x1c..0x1c, 2)
            span(0x1f..0x20, 2); span(0x22..0x23, 2); span(0x24..0x26, 3)
            span(0x29..0x29, 2); span(0x2a..0x2c, 3)
            span(0x2d..0x3d, 2)
            span(0x44..0x6d, 2)
            span(0x6e..0x72, 3); span(0x74..0x78, 3)
            span(0x90..0xaf, 2)
            span(0xd0..0xe2, 2)
            span(0xfa..0xfb, 4); span(0xfc..0xfd, 3); span(0xfe..0xff, 2)
        }

        private fun instructionUnits(opcode: Int): Int =
            INSTRUCTION_UNITS.getOrElse(opcode) { 1 }.coerceAtLeast(1)

        /** Returns a reader for [bytes], or null when they are not a standard DEX image. */
        fun parse(bytes: ByteArray): DexFile? {
            if (bytes.size < HEADER_SIZE) return null
            if (bytes[0] != 'd'.code.toByte() || bytes[1] != 'e'.code.toByte() ||
                bytes[2] != 'x'.code.toByte() || bytes[3] != '\n'.code.toByte()
            ) {
                return null
            }
            return DexFile(bytes)
        }

        private fun ByteArray.uintAt(offset: Int): Int {
            if (offset < 0 || offset + 4 > size) return -1
            return (this[offset].toInt() and 0xff) or
                ((this[offset + 1].toInt() and 0xff) shl 8) or
                ((this[offset + 2].toInt() and 0xff) shl 16) or
                ((this[offset + 3].toInt() and 0xff) shl 24)
        }

        private fun ByteArray.ushortAt(offset: Int): Int {
            if (offset < 0 || offset + 2 > size) return -1
            return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
        }
    }
}

/** `Lcom/oplus/aiunit/vision/qp80;` becomes `com.oplus.aiunit.vision.qp80`. */
internal fun String.toClassName(): String =
    removePrefix("L").removeSuffix(";").replace('/', '.')
