package com.github.bsaltz.springai.util

import org.springframework.ai.converter.BeanOutputConverter
import org.springframework.stereotype.Component
import java.util.Collections

@Component
class BeanOutputConverterCache {
    private val cache = Collections.synchronizedMap(mutableMapOf<Class<*>, BeanOutputConverter<*>>())

    fun <T> getConverter(clazz: Class<T>): BeanOutputConverter<T> {
        val converter = cache[clazz] ?: cache.computeIfAbsent(clazz) { BeanOutputConverter(clazz) }
        @Suppress("UNCHECKED_CAST")
        return converter as BeanOutputConverter<T>
    }

    companion object {
        inline fun <reified T> BeanOutputConverterCache.get(): BeanOutputConverter<T> = getConverter(T::class.java)
    }
}
