package com.lleps.tradexchange.util

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths
import java.util.HashMap



@Suppress("UNCHECKED_CAST")
private fun mapper(): ObjectMapper  = ObjectMapper().apply {
    /*val module = SimpleModule()
    module.addSerializer(java.lang.Double::class.java, DoubleSerializer() as JsonSerializer<java.lang.Double>)
    module.addSerializer(Double::class.javaPrimitiveType, DoubleSerializer())
    registerModule(module)*/
}

val INTERNAL_MAPPER = mapper()

val INTERNAL_PRETTY_MAPPER = mapper().writerWithDefaultPrettyPrinter()!!

// this serializer writes double values as float (that is, use half the precision to reduce json size)
class DoubleSerializer : JsonSerializer<Double>() {
    override fun serialize(value: Double, jgen: JsonGenerator, provider: SerializerProvider) {
        jgen.writeNumber(value.toFloat())
    }
}

/** Convert this to json and save it to [fileName]. */
fun Any.saveTo(fileName: String) {
    Files.write(Paths.get(fileName), this.toJsonString().toByteArray(Charset.defaultCharset()))
}

/** Load the json from [fileName] and parse it as an instance of type [T]. */
inline fun <reified T> loadFrom(fileName: String): T? {
    return try {
        INTERNAL_MAPPER.readValue(Files.readAllBytes(Paths.get(fileName)).toString(Charset.defaultCharset()), T::class.java)
    } catch (e: IOException) {
        null
    }
}

/** Load the json from [fileName] and parse it as a json list of type [T]. */
inline fun <reified T> loadListFrom(fileName: String): List<T>? {
    return try {
        val colType = INTERNAL_MAPPER.typeFactory.constructCollectionType(List::class.java, T::class.java)
        INTERNAL_MAPPER.readValue(Files.readAllBytes(Paths.get(fileName)).toString(Charset.defaultCharset()), colType)
    } catch (e: IOException) {
        null
    }
}

/** Convert this object to a json string. */
fun Any.toJsonString(prettyPrinting: Boolean = false): String {
    return if (!prettyPrinting) {
        INTERNAL_MAPPER.writeValueAsString(this)
    } else {
        INTERNAL_PRETTY_MAPPER.writeValueAsString(this)
    }
}

/** Convert the json [string] to an instance of type [T]. */
inline fun <reified T> parseJson(string: String): T {
    return INTERNAL_MAPPER.readValue(string, T::class.java)
}

/** Convert the json [string] to a map. */
@Suppress("UNCHECKED_CAST")
fun parseJsonMap(string: String): Map<String, String> {
    val typeRef = object : TypeReference<LinkedHashMap<String, String>>() {}
    return INTERNAL_MAPPER.readValue(string, typeRef)
}