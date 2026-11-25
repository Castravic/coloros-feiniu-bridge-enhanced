package io.github.colorosfeiniu.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ColdStartSyncLocatorTest {

    @Test
    fun `ascii indexOfString and firstStringContaining match exact entries without false positives`() {
        val dexBytes = FixtureBuilder().apply {
            addClass(
                descriptor = "Lcom/example/Prefixes;",
                methods = listOf(
                    FixtureMethod(
                        name = "a",
                        returnType = "Ljava/lang/String;",
                        constStrings = listOf(
                            "shortGwToken",
                            "TokenDecryptorExtra",
                            "tRiM@2025#GwToken!sEcReT*kEy&vALu",
                        ),
                    ),
                ),
            )
        }.build()

        val dex = requireNotNull(DexFile.parse(dexBytes))
        assertTrue(dex.indexOfString("TokenDecryptorExtra") >= 0)
        assertEquals(-1, dex.indexOfString("TokenDecryptor"))
        assertEquals(-1, dex.indexOfString("TokenDecryptorExtraLong"))
        assertEquals(-1, dex.indexOfString(""))

        val prefix = dex.firstStringContaining("GwToken") { it.length == 33 }
        assertEquals("tRiM@2025#GwToken!sEcReT*kEy&vALu", prefix)
        assertNull(dex.firstStringContaining("AbsentAnchor") { true })
    }

    @Test
    fun `instruction walker skips switch and fill-array payloads before reading strings and invokes`() {
        val anchor = ColdStartSyncLocator.LISTENER_START_ANCHOR
        val dexBytes = FixtureBuilder().apply {
            addClass(
                descriptor = "Lcom/example/PayloadMethod;",
                methods = listOf(
                    FixtureMethod(
                        name = "run",
                        returnType = "V",
                        rawPrefixUnits = listOf(
                            // packed-switch-payload (0x0100, size = 2 -> 2*2 + 4 = 8 units)
                            // Put 0x001a (const-string opcode!) inside payload data to prove it is skipped
                            0x0100, 2, 0, 0, 0x001a, 0x0000, 0x001a, 0x0000,
                            // sparse-switch-payload (0x0200, size = 1 -> 1*4 + 2 = 6 units)
                            0x0200, 1, 0x001a, 0, 0x006e, 0,
                            // fill-array-data-payload (0x0300, width = 2, size = 3 -> (6+1)/2 + 4 = 7 units)
                            0x0300, 2, 3, 0, 0x001a, 0x001b, 0x006e,
                        ),
                        constStrings = listOf(anchor),
                        invokes = listOf(
                            FixtureInvoke("Ljava/util/concurrent/CopyOnWriteArrayList;", "add", listOf("Ljava/lang/Object;"), "Z"),
                        ),
                    ),
                ),
            )
        }.build()

        val dex = requireNotNull(DexFile.parse(dexBytes))
        val anchorIdx = dex.indexOfString(anchor)
        val clazz = dex.classes().single { it.className == "com.example.PayloadMethod" }

        assertTrue(clazz.referencesString(anchorIdx))
        assertEquals(listOf("run"), clazz.methodsReferencingString(anchorIdx).map { it.name })
        val calls = clazz.methodInvokes("run", "()V")
        assertEquals(1, calls.size)
        assertEquals("java.util.concurrent.CopyOnWriteArrayList", calls.single().owner)
        assertEquals("add", calls.single().name)
    }

    @Test
    fun `locates cold start sync chain across listener superclass and split dexes`() {
        val dexLifecycleAndListener = FixtureBuilder().apply {
            addClass(
                descriptor = "Lcom/example/Lifecycle;",
                interfaces = listOf("Landroid/app/Application\$ActivityLifecycleCallbacks;"),
                methods = listOf(
                    FixtureMethod(name = "c", returnType = "Z", isStatic = true),
                ),
            )
            addClass(
                descriptor = "Lcom/example/BaseListener;",
                interfaces = listOf("Lcom/example/Lifecycle\$a;"),
            )
            addClass(
                descriptor = "Lcom/example/Sync\$Listener;",
                superDescriptor = "Lcom/example/BaseListener;",
                methods = listOf(
                    FixtureMethod(
                        name = "g",
                        returnType = "V",
                        constStrings = listOf(ColdStartSyncLocator.LISTENER_START_ANCHOR),
                    ),
                ),
            )
        }.build()

        val dexRegistration = FixtureBuilder().apply {
            addClass(
                descriptor = "Lcom/example/Lifecycle\$addListener\$1;",
                superDescriptor = "Lkotlin/coroutines/jvm/internal/SuspendLambda;",
                instanceFields = listOf(
                    FixtureField("\$listener", "Lcom/example/Lifecycle\$a;"),
                ),
                methods = listOf(
                    FixtureMethod(
                        name = "invokeSuspend",
                        paramTypes = listOf("Ljava/lang/Object;"),
                        returnType = "Ljava/lang/Object;",
                        invokes = listOf(
                            FixtureInvoke("Ljava/util/concurrent/CopyOnWriteArrayList;", "add", listOf("Ljava/lang/Object;"), "Z"),
                        ),
                    ),
                ),
            )
            addClass(
                descriptor = "Lcom/example/Lifecycle\$removeListener\$1;",
                superDescriptor = "Lkotlin/coroutines/jvm/internal/SuspendLambda;",
                instanceFields = listOf(
                    FixtureField("\$listener", "Lcom/example/Lifecycle\$a;"),
                ),
                methods = listOf(
                    FixtureMethod(
                        name = "invokeSuspend",
                        paramTypes = listOf("Ljava/lang/Object;"),
                        returnType = "Ljava/lang/Object;",
                        invokes = listOf(
                            FixtureInvoke("Ljava/util/concurrent/CopyOnWriteArrayList;", "remove", listOf("Ljava/lang/Object;"), "Z"),
                        ),
                    ),
                ),
            )
        }.build()

        val located = ColdStartSyncLocator.locate(
            listOf(
                requireNotNull(DexFile.parse(dexLifecycleAndListener)),
                requireNotNull(DexFile.parse(dexRegistration)),
            ),
        )

        assertEquals(
            ColdStartSyncTargets(
                lifecycle = "com.example.Lifecycle",
                foreground = "c",
                registration = "com.example.Lifecycle\$addListener\$1",
                listener = "com.example.Sync\$Listener",
                nativeStartMethod = "g",
            ),
            located,
        )
    }

    @Test
    fun `locates listener whose superclass appears in an earlier anchorless dex`() {
        val dexBaseOnly = FixtureBuilder().apply {
            addClass(
                descriptor = "Lcom/example/BaseListener;",
                interfaces = listOf("Lcom/example/Lifecycle\$a;"),
            )
        }.build()

        val dexListenerLifecycleAndReg = FixtureBuilder().apply {
            addClass(
                descriptor = "Lcom/example/Lifecycle;",
                interfaces = listOf("Landroid/app/Application\$ActivityLifecycleCallbacks;"),
                methods = listOf(FixtureMethod(name = "c", returnType = "Z", isStatic = true)),
            )
            addClass(
                descriptor = "Lcom/example/Sync\$Listener;",
                superDescriptor = "Lcom/example/BaseListener;",
                methods = listOf(
                    FixtureMethod(
                        name = "g",
                        returnType = "V",
                        constStrings = listOf(ColdStartSyncLocator.LISTENER_START_ANCHOR),
                    ),
                ),
            )
            addClass(
                descriptor = "Lcom/example/Lifecycle\$addListener\$1;",
                superDescriptor = "Lkotlin/coroutines/jvm/internal/SuspendLambda;",
                instanceFields = listOf(FixtureField("\$listener", "Lcom/example/Lifecycle\$a;")),
                methods = listOf(
                    FixtureMethod(
                        name = "invokeSuspend",
                        paramTypes = listOf("Ljava/lang/Object;"),
                        returnType = "Ljava/lang/Object;",
                        invokes = listOf(
                            FixtureInvoke("Ljava/util/concurrent/CopyOnWriteArrayList;", "add", listOf("Ljava/lang/Object;"), "Z"),
                        ),
                    ),
                ),
            )
        }.build()

        val located = ColdStartSyncLocator.locate(
            listOf(
                requireNotNull(DexFile.parse(dexBaseOnly)),
                requireNotNull(DexFile.parse(dexListenerLifecycleAndReg)),
            ),
        )

        assertEquals(
            ColdStartSyncTargets(
                lifecycle = "com.example.Lifecycle",
                foreground = "c",
                registration = "com.example.Lifecycle\$addListener\$1",
                listener = "com.example.Sync\$Listener",
                nativeStartMethod = "g",
            ),
            located,
        )
    }

    @Test
    fun `rejects registration coroutine that only calls remove and rejects ambiguous matches`() {
        val removeOnlyDex = FixtureBuilder().apply {
            addClass(
                descriptor = "Lcom/example/Lifecycle;",
                interfaces = listOf("Landroid/app/Application\$ActivityLifecycleCallbacks;"),
                methods = listOf(FixtureMethod(name = "b", returnType = "Z", isStatic = true)),
            )
            addClass(
                descriptor = "Lcom/example/Sync\$Listener;",
                interfaces = listOf("Lcom/example/Lifecycle\$a;"),
                methods = listOf(
                    FixtureMethod(
                        name = "g",
                        returnType = "V",
                        constStrings = listOf(ColdStartSyncLocator.LISTENER_START_ANCHOR),
                    ),
                ),
            )
            addClass(
                descriptor = "Lcom/example/Lifecycle\$removeListener\$1;",
                superDescriptor = "Lkotlin/coroutines/jvm/internal/SuspendLambda;",
                instanceFields = listOf(FixtureField("\$listener", "Lcom/example/Lifecycle\$a;")),
                methods = listOf(
                    FixtureMethod(
                        name = "invokeSuspend",
                        paramTypes = listOf("Ljava/lang/Object;"),
                        returnType = "Ljava/lang/Object;",
                        invokes = listOf(
                            FixtureInvoke("Ljava/util/concurrent/CopyOnWriteArrayList;", "remove", listOf("Ljava/lang/Object;"), "Z"),
                        ),
                    ),
                ),
            )
        }.build()

        assertNull(ColdStartSyncLocator.locate(listOf(requireNotNull(DexFile.parse(removeOnlyDex)))))

        val ambiguousLifecycleDex = FixtureBuilder().apply {
            addClass(
                descriptor = "Lcom/example/Lifecycle;",
                interfaces = listOf("Landroid/app/Application\$ActivityLifecycleCallbacks;"),
                methods = listOf(
                    FixtureMethod(name = "b", returnType = "Z", isStatic = true),
                    FixtureMethod(name = "c", returnType = "Z", isStatic = true),
                ),
            )
            addClass(
                descriptor = "Lcom/example/Sync\$Listener;",
                interfaces = listOf("Lcom/example/Lifecycle\$a;"),
                methods = listOf(
                    FixtureMethod(
                        name = "g",
                        returnType = "V",
                        constStrings = listOf(ColdStartSyncLocator.LISTENER_START_ANCHOR),
                    ),
                ),
            )
            addClass(
                descriptor = "Lcom/example/Lifecycle\$add\$1;",
                superDescriptor = "Lkotlin/coroutines/jvm/internal/SuspendLambda;",
                instanceFields = listOf(FixtureField("\$listener", "Lcom/example/Lifecycle\$a;")),
                methods = listOf(
                    FixtureMethod(
                        name = "invokeSuspend",
                        paramTypes = listOf("Ljava/lang/Object;"),
                        returnType = "Ljava/lang/Object;",
                        invokes = listOf(
                            FixtureInvoke("Ljava/util/concurrent/CopyOnWriteArrayList;", "add", listOf("Ljava/lang/Object;"), "Z"),
                        ),
                    ),
                ),
            )
        }.build()

        assertNull(ColdStartSyncLocator.locate(listOf(requireNotNull(DexFile.parse(ambiguousLifecycleDex)))))
        assertFalse(requireNotNull(DexFile.parse(removeOnlyDex)).classes().isEmpty())
    }

    private data class FixtureField(
        val name: String,
        val typeDescriptor: String,
        val isStatic: Boolean = false,
    )

    private data class FixtureInvoke(
        val ownerDescriptor: String,
        val name: String,
        val paramTypes: List<String>,
        val returnType: String,
    )

    private data class FixtureMethod(
        val name: String,
        val paramTypes: List<String> = emptyList(),
        val returnType: String,
        val isStatic: Boolean = false,
        val constStrings: List<String> = emptyList(),
        val invokes: List<FixtureInvoke> = emptyList(),
        val rawPrefixUnits: List<Int> = emptyList(),
    )

    private data class FixtureClass(
        val descriptor: String,
        val superDescriptor: String = "Ljava/lang/Object;",
        val interfaces: List<String> = emptyList(),
        val instanceFields: List<FixtureField> = emptyList(),
        val methods: List<FixtureMethod> = emptyList(),
    )

    private class FixtureBuilder {
        private val classes = mutableListOf<FixtureClass>()

        fun addClass(
            descriptor: String,
            superDescriptor: String = "Ljava/lang/Object;",
            interfaces: List<String> = emptyList(),
            instanceFields: List<FixtureField> = emptyList(),
            methods: List<FixtureMethod> = emptyList(),
        ) {
            classes += FixtureClass(descriptor, superDescriptor, interfaces, instanceFields, methods)
        }

        fun build(): ByteArray {
            val types = linkedSetOf("Ljava/lang/Object;")
            val protos = linkedSetOf<Pair<String, List<String>>>()
            val fields = linkedSetOf<Triple<String, String, String>>()
            val methodKeys = linkedSetOf<Triple<String, Pair<String, List<String>>, String>>()
            val strings = linkedSetOf<String>()

            fun addProto(ret: String, params: List<String>) {
                protos += (ret to params)
                types += ret
                types += params
            }

            classes.forEach { c ->
                types += c.descriptor
                types += c.superDescriptor
                types += c.interfaces
                c.instanceFields.forEach { f ->
                    types += f.typeDescriptor
                    strings += f.name
                    fields += Triple(c.descriptor, f.name, f.typeDescriptor)
                }
                c.methods.forEach { m ->
                    val proto = m.returnType to m.paramTypes
                    addProto(m.returnType, m.paramTypes)
                    strings += m.name
                    strings += m.constStrings
                    methodKeys += Triple(c.descriptor, proto, m.name)
                    m.invokes.forEach { inv ->
                        val invProto = inv.returnType to inv.paramTypes
                        addProto(inv.returnType, inv.paramTypes)
                        types += inv.ownerDescriptor
                        strings += inv.name
                        methodKeys += Triple(inv.ownerDescriptor, invProto, inv.name)
                    }
                }
            }

            protos.forEach { (ret, params) ->
                strings += shortyFor(ret, params)
            }
            strings += types
            val sortedStrings = strings.sorted()
            val stringIdx = sortedStrings.withIndex().associate { (i, s) -> s to i }

            val sortedTypes = types.sortedBy { stringIdx.getValue(it) }
            val typeIdx = sortedTypes.withIndex().associate { (i, t) -> t to i }

            val sortedProtos = protos.toList()
            val protoIdx = sortedProtos.withIndex().associate { (i, p) -> p to i }

            val sortedFields = fields.sortedWith(
                compareBy(
                    { typeIdx.getValue(it.first) },
                    { stringIdx.getValue(it.second) },
                    { typeIdx.getValue(it.third) },
                ),
            )
            val fieldIdx = sortedFields.withIndex().associate { (i, f) -> f to i }

            val sortedMethods = methodKeys.sortedWith(
                compareBy(
                    { typeIdx.getValue(it.first) },
                    { stringIdx.getValue(it.third) },
                    { protoIdx.getValue(it.second) },
                ),
            )
            val methodIdx = sortedMethods.withIndex().associate { (i, m) -> m to i }

            val headerSize = 0x70
            val stringIdsOff = headerSize
            val typeIdsOff = stringIdsOff + 4 * sortedStrings.size
            val protoIdsOff = typeIdsOff + 4 * sortedTypes.size
            val fieldIdsOff = protoIdsOff + 12 * sortedProtos.size
            val methodIdsOff = fieldIdsOff + 8 * sortedFields.size
            val classDefsOff = methodIdsOff + 8 * sortedMethods.size
            val dataOff = classDefsOff + 32 * classes.size

            val data = ByteBuf(dataOff)

            val stringDataOffsets = sortedStrings.map { value ->
                data.offset().also {
                    data.uleb128(value.length)
                    data.bytes(value.toByteArray(Charsets.UTF_8))
                    data.byte(0)
                }
            }

            val protoParamOffsets = sortedProtos.map { (_, params) ->
                if (params.isEmpty()) {
                    0
                } else {
                    data.align4()
                    data.offset().also {
                        data.uint(params.size)
                        params.forEach { p -> data.ushort(typeIdx.getValue(p)) }
                    }
                }
            }

            val interfaceOffsets = classes.associate { c ->
                c.descriptor to if (c.interfaces.isEmpty()) {
                    0
                } else {
                    data.align4()
                    data.offset().also {
                        data.uint(c.interfaces.size)
                        c.interfaces.forEach { iface -> data.ushort(typeIdx.getValue(iface)) }
                    }
                }
            }

            val codeOffsets = mutableMapOf<Pair<String, String>, Int>()
            for (c in classes) {
                for (m in c.methods) {
                    data.align4()
                    codeOffsets[c.descriptor to m.name] = data.offset()
                    val insns = ByteArrayOutputStream()
                    m.rawPrefixUnits.forEach { unit ->
                        insns.write(unit and 0xff)
                        insns.write((unit ushr 8) and 0xff)
                    }
                    m.constStrings.forEach { s ->
                        val si = stringIdx.getValue(s)
                        insns.write(0x1a)
                        insns.write(0x00)
                        insns.write(si and 0xff)
                        insns.write((si ushr 8) and 0xff)
                    }
                    m.invokes.forEach { inv ->
                        val mi = methodIdx.getValue(
                            Triple(inv.ownerDescriptor, inv.returnType to inv.paramTypes, inv.name),
                        )
                        insns.write(0x6e) // invoke-virtual {v0}
                        insns.write(0x10)
                        insns.write(mi and 0xff)
                        insns.write((mi ushr 8) and 0xff)
                        insns.write(0x00)
                        insns.write(0x00)
                    }
                    insns.write(0x0e) // return-void
                    insns.write(0x00)

                    val insnBytes = insns.toByteArray()
                    val ins = m.paramTypes.size + if (m.isStatic) 0 else 1
                    data.ushort(ins + 2)
                    data.ushort(ins)
                    data.ushort(1)
                    data.ushort(0)
                    data.uint(0)
                    data.uint(insnBytes.size / 2)
                    data.bytes(insnBytes)
                }
            }

            val classDataOffsets = classes.associate { c ->
                c.descriptor to data.offset().also {
                    val staticMethods = c.methods.filter { it.isStatic }
                    val virtualMethods = c.methods.filter { !it.isStatic }
                    data.uleb128(0) // static_fields
                    data.uleb128(c.instanceFields.size)
                    data.uleb128(staticMethods.size)
                    data.uleb128(virtualMethods.size)

                    var prevField = 0
                    c.instanceFields
                        .sortedBy { fieldIdx.getValue(Triple(c.descriptor, it.name, it.typeDescriptor)) }
                        .forEach { f ->
                            val fi = fieldIdx.getValue(Triple(c.descriptor, f.name, f.typeDescriptor))
                            data.uleb128(fi - prevField)
                            prevField = fi
                            data.uleb128(0x1) // public
                        }

                    for ((methodsList, baseAcc) in listOf(staticMethods to 0x9, virtualMethods to 0x1)) {
                        var prevMethod = 0
                        methodsList
                            .sortedBy {
                                methodIdx.getValue(Triple(c.descriptor, it.returnType to it.paramTypes, it.name))
                            }
                            .forEach { m ->
                                val mi = methodIdx.getValue(
                                    Triple(c.descriptor, m.returnType to m.paramTypes, m.name),
                                )
                                data.uleb128(mi - prevMethod)
                                prevMethod = mi
                                data.uleb128(baseAcc)
                                data.uleb128(codeOffsets.getValue(c.descriptor to m.name))
                            }
                    }
                }
            }

            val dataBytes = data.toByteArray()
            val fileSize = dataOff + dataBytes.size
            val image = ByteArray(fileSize)

            fun putUint(at: Int, v: Int) {
                image[at] = (v and 0xff).toByte()
                image[at + 1] = ((v ushr 8) and 0xff).toByte()
                image[at + 2] = ((v ushr 16) and 0xff).toByte()
                image[at + 3] = ((v ushr 24) and 0xff).toByte()
            }

            fun putUshort(at: Int, v: Int) {
                image[at] = (v and 0xff).toByte()
                image[at + 1] = ((v ushr 8) and 0xff).toByte()
            }

            "dex\n035 ".toByteArray(Charsets.US_ASCII).copyInto(image, 0)
            putUint(0x20, fileSize)
            putUint(0x24, headerSize)
            putUint(0x28, 0x12345678)
            putUint(0x38, sortedStrings.size); putUint(0x3c, stringIdsOff)
            putUint(0x40, sortedTypes.size); putUint(0x44, typeIdsOff)
            putUint(0x48, sortedProtos.size); putUint(0x4c, protoIdsOff)
            putUint(0x50, sortedFields.size); putUint(0x54, fieldIdsOff)
            putUint(0x58, sortedMethods.size); putUint(0x5c, methodIdsOff)
            putUint(0x60, classes.size); putUint(0x64, classDefsOff)
            putUint(0x68, dataBytes.size); putUint(0x6c, dataOff)

            stringDataOffsets.forEachIndexed { i, off -> putUint(stringIdsOff + i * 4, off) }
            sortedTypes.forEachIndexed { i, t -> putUint(typeIdsOff + i * 4, stringIdx.getValue(t)) }
            sortedProtos.forEachIndexed { i, (ret, params) ->
                val base = protoIdsOff + i * 12
                putUint(base, stringIdx.getValue(shortyFor(ret, params)))
                putUint(base + 4, typeIdx.getValue(ret))
                putUint(base + 8, protoParamOffsets[i])
            }
            sortedFields.forEachIndexed { i, (owner, name, type) ->
                val base = fieldIdsOff + i * 8
                putUshort(base, typeIdx.getValue(owner))
                putUshort(base + 2, typeIdx.getValue(type))
                putUint(base + 4, stringIdx.getValue(name))
            }
            sortedMethods.forEachIndexed { i, (owner, proto, name) ->
                val base = methodIdsOff + i * 8
                putUshort(base, typeIdx.getValue(owner))
                putUshort(base + 2, protoIdx.getValue(proto))
                putUint(base + 4, stringIdx.getValue(name))
            }
            classes.forEachIndexed { i, c ->
                val base = classDefsOff + i * 32
                putUint(base, typeIdx.getValue(c.descriptor))
                putUint(base + 4, 0x1)
                putUint(base + 8, typeIdx.getValue(c.superDescriptor))
                putUint(base + 12, interfaceOffsets.getValue(c.descriptor))
                putUint(base + 16, -1)
                putUint(base + 20, 0)
                putUint(base + 24, classDataOffsets.getValue(c.descriptor))
                putUint(base + 28, 0)
            }
            dataBytes.copyInto(image, dataOff)
            return image
        }

        private fun shortyFor(ret: String, params: List<String>): String = buildString {
            append(if (ret.length == 1) ret[0] else 'L')
            params.forEach { p -> append(if (p.length == 1) p[0] else 'L') }
        }
    }

    private class ByteBuf(private val base: Int) {
        private val out = ByteArrayOutputStream()
        fun offset(): Int = base + out.size()
        fun align4() { while (offset() % 4 != 0) out.write(0) }
        fun byte(v: Int) = out.write(v and 0xff)
        fun bytes(b: ByteArray) = out.write(b, 0, b.size)
        fun ushort(v: Int) { byte(v); byte(v ushr 8) }
        fun uint(v: Int) { byte(v); byte(v ushr 8); byte(v ushr 16); byte(v ushr 24) }
        fun uleb128(v: Int) {
            var rem = v
            do {
                var piece = rem and 0x7f
                rem = rem ushr 7
                if (rem != 0) piece = piece or 0x80
                byte(piece)
            } while (rem != 0)
        }
        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
