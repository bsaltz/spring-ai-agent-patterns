package com.github.bsaltz.springai.util

import com.github.bsaltz.springai.util.BeanOutputConverterCache.Companion.get
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.converter.BeanOutputConverter

class BeanOutputConverterCacheTest {
    private lateinit var cache: BeanOutputConverterCache

    @BeforeEach
    fun setUp() {
        cache = BeanOutputConverterCache()
    }

    @Test
    fun `getConverter should return a converter for a given class`() {
        // Given
        val clazz = TestData::class.java
        val json = """{"value": "test"}"""

        // When
        val converter = cache.getConverter(clazz)
        val result = converter.convert(json)

        // Then
        assertNotNull(result)
        assert(result is TestData) { "Converter should return TestData instance" }
        assert(result!!.value == "test") { "Converter should parse JSON correctly" }
    }

    @Test
    fun `getConverter should return the same instance when called twice with the same class`() {
        // Given
        val clazz = TestData::class.java

        // When
        val converter1 = cache.getConverter(clazz)
        val converter2 = cache.getConverter(clazz)

        // Then
        assertSame(converter1, converter2, "Cache should return the same instance")
    }

    @Test
    fun `getConverter should return different instances for different classes`() {
        // Given
        val clazz1 = TestData::class.java
        val clazz2 = OtherTestData::class.java

        // When
        val converter1 = cache.getConverter(clazz1)
        val converter2 = cache.getConverter(clazz2)

        // Then
        assert(converter1 !== converter2) { "Different classes should have different converters" }
    }

    @Test
    fun `get extension function should work with reified types`() {
        // Given
        val json = """{"value": "extension test"}"""

        // When
        val converter = cache.get<TestData>()
        val result = converter.convert(json)

        // Then
        assertNotNull(result)
        assert(result is TestData) { "Converter should return TestData instance" }
        assert(result!!.value == "extension test") { "Converter should parse JSON correctly" }
    }

    @Test
    fun `get extension function should return cached instance`() {
        // Given - first call using getConverter
        val converter1 = cache.getConverter(TestData::class.java)

        // When - second call using extension function
        val converter2 = cache.get<TestData>()

        // Then
        assertSame(converter1, converter2, "Extension function should use the same cache")
    }

    @Test
    fun `cache should be thread-safe`() {
        // Given
        val clazz = TestData::class.java
        val results = mutableListOf<BeanOutputConverter<TestData>>()

        // When - access cache from multiple threads
        val threads =
            (1..10).map {
                Thread {
                    val converter = cache.getConverter(clazz)
                    synchronized(results) {
                        results.add(converter)
                    }
                }
            }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Then - all threads should get the same instance
        val firstConverter = results.first()
        results.forEach { converter ->
            assertSame(firstConverter, converter, "All threads should receive the same cached instance")
        }
    }

    // Test data classes
    data class TestData(
        val value: String,
    )

    data class OtherTestData(
        val number: Int,
    )
}
