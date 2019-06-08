package com.lleps.tradexchange.util

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths

val INTERNAL_MAPPER = ObjectMapper()

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
fun Any.toJsonString(): String {
    return INTERNAL_MAPPER.writeValueAsString(this)
}

inline fun <reified T> parseJson(string: String): T {
    return INTERNAL_MAPPER.readValue(string, T::class.java)
}