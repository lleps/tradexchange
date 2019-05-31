package com.lleps.tradexchange.util

import com.google.gson.GsonBuilder
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths
import java.time.ZonedDateTime
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

val gson = GsonBuilder()
    //.setPrettyPrinting()
    .registerTypeAdapter(ZonedDateTime::class.java, object : TypeAdapter<ZonedDateTime>() {
        @Throws(IOException::class)
        override fun write(out: JsonWriter, value: ZonedDateTime) {
            out.value(value.toString())
        }

        @Throws(IOException::class)
        override fun read(`in`: JsonReader): ZonedDateTime {
            return ZonedDateTime.parse(`in`.nextString())
        }
    }).create()

fun Any.saveTo(fileName: String) {
    Files.write(Paths.get(fileName), gson.toJson(this).toByteArray(Charset.defaultCharset()))
}

fun Any.toJsonString(): String {
    return gson.toJson(this)
}

inline fun <reified T> loadFrom(fileName: String): T? {
    return try {
        gson.fromJson(Files.readAllBytes(Paths.get(fileName)).toString(Charset.defaultCharset()), T::class.java)
    } catch (e: IOException) {
        null
    }
}