import com.google.gson.GsonBuilder
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths

val gson = GsonBuilder().setPrettyPrinting().create()

fun Any.saveTo(fileName: String) {
    Files.write(Paths.get(fileName), gson.toJson(this).toByteArray(Charset.defaultCharset()))
}

inline fun <reified T> loadFrom(fileName: String): T? {
    return try {
        gson.fromJson(Files.readAllBytes(Paths.get(fileName)).toString(Charset.defaultCharset()), T::class.java)
    } catch (e: IOException) {
        null
    }
}