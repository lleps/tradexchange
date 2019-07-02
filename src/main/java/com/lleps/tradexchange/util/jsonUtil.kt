package com.lleps.tradexchange.util

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths
import java.math.BigDecimal
import java.text.DecimalFormat


@Suppress("UNCHECKED_CAST")
private fun mapper(): ObjectMapper  = ObjectMapper().apply {
    val module = SimpleModule()
    module.addSerializer(java.lang.Double::class.java, DoubleSerializer() as JsonSerializer<java.lang.Double>)
    module.addSerializer(Double::class.javaPrimitiveType, DoubleSerializer())
    registerModule(module)
}

val INTERNAL_MAPPER = mapper()

val INTERNAL_PRETTY_MAPPER = mapper().writerWithDefaultPrettyPrinter()!!


// this serializer writes double values as float (that is, use half the precision to reduce json size)
class DoubleSerializer : JsonSerializer<Double>() {
    override fun serialize(value: Double, jgen: JsonGenerator, provider: SerializerProvider) {
        jgen.writeNumber(value.toFloat())
    }
}

// JSON TO/FROM FILES
fun Any.saveTo(fileName: String) {
    Files.write(Paths.get(fileName), this.toJsonString().toByteArray(Charset.defaultCharset()))
}

inline fun <reified T> loadFrom(fileName: String): T? {
    return try {
        INTERNAL_MAPPER.readValue(Files.readAllBytes(Paths.get(fileName)).toString(Charset.defaultCharset()), T::class.java)
    } catch (e: IOException) {
        null
    }
}

// JSON TO/FROM STRINGS
fun Any.toJsonString(prettyPrinting: Boolean = false): String {
    return if (!prettyPrinting) {
        INTERNAL_MAPPER.writeValueAsString(this)
    } else {
        INTERNAL_PRETTY_MAPPER.writeValueAsString(this)
    }
}

inline fun <reified T> parseJson(string: String): T {
    return INTERNAL_MAPPER.readValue(string, T::class.java)
}