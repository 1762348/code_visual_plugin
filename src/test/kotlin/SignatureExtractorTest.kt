package org.example.code_visual_plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SignatureExtractor 是纯函数（只用正则，不碰 IntelliJ 平台），
 * 所以可以不启动 IDE 直接跑。这里是第一组回归测试。
 */
class SignatureExtractorTest {

    @Test
    fun `从 Java 源码里抽取声明行`() {
        val text = """
            package com.demo;
            public class OrderService {
                public String placeOrder(String userId, String amount) {
                    return null;
                }
            }
        """.trimIndent()

        val sigs = SignatureExtractor.extract("OrderService.java", text)
        assertTrue("应至少抽到一条声明", sigs.isNotEmpty())
        assertTrue("应包含 placeOrder", sigs.any { it.contains("placeOrder") })
    }

    @Test
    fun `Vue 文件只解析 script 部分`() {
        val text = """
            <template>
              <div class="x">hello</div>
            </template>
            <script setup>
            const modules = ref([])
            function load() { return 1 }
            </script>
        """.trimIndent()

        val sigs = SignatureExtractor.extract("App.vue", text)
        assertTrue("应包含 modules", sigs.any { it.contains("modules") })
        assertTrue("模板里的 div 不应被当成声明", sigs.none { it.contains("<div") })
    }

    @Test
    fun `空文件不炸`() {
        assertEquals(emptyList<String>(), SignatureExtractor.extract("Empty.kt", ""))
    }
}