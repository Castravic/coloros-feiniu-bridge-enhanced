package io.github.colorosfeiniu.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

class LibxposedDescriptorTest {

    @Test
    fun `java_init list declares existing FeiniuBridgeHook entry class`() {
        val stream = javaClass.classLoader?.getResourceAsStream("META-INF/xposed/java_init.list")
        assertNotNull("META-INF/xposed/java_init.list must exist in resources", stream)
        val lines = stream!!.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(listOf("io.github.colorosfeiniu.bridge.FeiniuBridgeHook"), lines)

        val entryClass = Class.forName(lines.first())
        assertTrue(
            "FeiniuBridgeHook must extend io.github.libxposed.api.XposedModule",
            io.github.libxposed.api.XposedModule::class.java.isAssignableFrom(entryClass),
        )
    }

    @Test
    fun `module prop targets api 102 and specifies static scope`() {
        val stream = javaClass.classLoader?.getResourceAsStream("META-INF/xposed/module.prop")
        assertNotNull("META-INF/xposed/module.prop must exist in resources", stream)
        val props = Properties().apply { load(stream) }

        assertEquals("101", props.getProperty("minApiVersion"))
        assertEquals("102", props.getProperty("targetApiVersion"))
        assertEquals("true", props.getProperty("staticScope"))
    }

    @Test
    fun `scope list specifies the target packages`() {
        val stream = javaClass.classLoader?.getResourceAsStream("META-INF/xposed/scope.list")
        assertNotNull("META-INF/xposed/scope.list must exist in resources", stream)
        val scopes = stream!!.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }

        assertEquals(listOf("com.coloros.gallery3d", "com.heytap.mydevices"), scopes)
    }

    @Test
    fun `legacy xposed_init asset does not exist`() {
        val stream = javaClass.classLoader?.getResourceAsStream("assets/xposed_init")
        assertNull("legacy assets/xposed_init must not exist", stream)
    }
}
