package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.util.zip.ZipFile
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull

class InspectCommon {
    private val sb = StringBuilder()

    private fun inspectClass(clazz: Class<*>) {
        try {
            sb.append("========================================\n")
            sb.append("Class: ${clazz.name}\n")
            sb.append("Modifiers: ${Modifier.toString(clazz.modifiers)}\n")
            sb.append("Superclass: ${clazz.superclass?.name}\n")
            sb.append("Interfaces: ${clazz.interfaces.joinToString { it.name }}\n")
            
            sb.append("\nConstructors:\n")
            clazz.declaredConstructors.forEach { constructor ->
                val params = constructor.parameterTypes.joinToString { it.name }
                sb.append("  ${Modifier.toString(constructor.modifiers)} ${clazz.simpleName}($params)\n")
            }

            sb.append("\nFields:\n")
            clazz.declaredFields.forEach { field ->
                sb.append("  ${Modifier.toString(field.modifiers)} ${field.type.name} ${field.name}\n")
            }

            sb.append("\nMethods:\n")
            clazz.declaredMethods.forEach { method ->
                val params = method.parameterTypes.joinToString { it.name }
                sb.append("  ${Modifier.toString(method.modifiers)} ${method.returnType.name} ${method.name}($params)\n")
            }
            sb.append("========================================\n\n")
        } catch (e: Throwable) {
            sb.append("Error inspecting class ${clazz.name}: ${e.message}\n")
        }
    }

    @Test
    fun runInspection() {
        try {
            val classLocation = ExtensionClient::class.java.protectionDomain.codeSource.location
            sb.append("Jar location: $classLocation\n")
            
            val classNames = mutableListOf<String>()
            
            if (classLocation.protocol == "file") {
                val file = File(classLocation.toURI())
                if (file.isFile && file.name.endsWith(".jar")) {
                    ZipFile(file).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.name.startsWith("dev/brahmkshatriya/echo/common/") && entry.name.endsWith(".class")) {
                                val className = entry.name.replace("/", ".").removeSuffix(".class")
                                if (!className.contains("$") || className.substringAfter("$").toIntOrNull() == null) {
                                    classNames.add(className)
                                }
                            }
                        }
                    }
                }
            }

            sb.append("Found ${classNames.size} classes in dev.brahmkshatriya.echo.common package.\n")
            classNames.sorted().forEach { className ->
                try {
                    val clazz = Class.forName(className)
                    inspectClass(clazz)
                } catch (e: Exception) {
                    sb.append("Could not load class: $className\n")
                }
            }
            
            File("inspect_result.txt").writeText(sb.toString())
            println("Inspection output written to inspect_result.txt")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Test
    fun testSettingOnClickSignature() {
        val field = dev.brahmkshatriya.echo.common.settings.SettingOnClick::class.java.getDeclaredField("onClick")
        println("onClick field generic type: ${field.genericType}")
        
        dev.brahmkshatriya.echo.common.settings.SettingOnClick::class.java.declaredConstructors.forEach { constructor ->
            println("Constructor: $constructor")
            constructor.genericParameterTypes.forEach { t ->
                println("  Param type: $t")
            }
        }
    }

    private fun findRenderers(element: kotlinx.serialization.json.JsonElement, list: MutableList<Pair<String, String>>) {
        when (element) {
            is kotlinx.serialization.json.JsonObject -> {
                if (element.containsKey("playlistVideoRenderer")) {
                    val r = element["playlistVideoRenderer"]?.jsonObject
                    println("Found playlistVideoRenderer! Keys: ${r?.keys}")
                    if (r != null) {
                        val title = r["title"]?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                            ?: r["title"]?.jsonObject?.get("simpleText")?.jsonPrimitive?.content
                            ?: ""
                        val artist = r["shortBylineText"]?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                            ?: ""
                        println("  Extracted Title: '$title' | Artist: '$artist'")
                        if (title.isNotEmpty()) {
                            list.add(Pair(title, artist))
                        }
                    }
                } else if (element.containsKey("musicResponsiveListItemRenderer")) {
                    val r = element["musicResponsiveListItemRenderer"]?.jsonObject
                    println("Found musicResponsiveListItemRenderer! Keys: ${r?.keys}")
                    if (r != null) {
                        val flex = r["flexColumns"]?.jsonArray
                        val titleCol = flex?.getOrNull(0)?.jsonObject?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
                        val title = titleCol?.get("text")?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                            ?: ""
                        val artistCol = flex?.getOrNull(1)?.jsonObject?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
                        val artist = artistCol?.get("text")?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                            ?: ""
                        println("  Extracted Title: '$title' | Artist: '$artist'")
                        if (title.isNotEmpty()) {
                            list.add(Pair(title, artist))
                        }
                    }
                } else {
                    for (value in element.values) {
                        findRenderers(value, list)
                    }
                }
            }
            is kotlinx.serialization.json.JsonArray -> {
                for (item in element) {
                    findRenderers(item, list)
                }
            }
            else -> {}
        }
    }

    private fun findRendererKeys(element: kotlinx.serialization.json.JsonElement, keys: MutableSet<String>) {
        when (element) {
            is kotlinx.serialization.json.JsonObject -> {
                for ((k, v) in element) {
                    if (k.endsWith("Renderer") || k.contains("Renderer")) {
                        keys.add(k)
                    }
                    findRendererKeys(v, keys)
                }
            }
            is kotlinx.serialization.json.JsonArray -> {
                for (item in element) {
                    findRendererKeys(item, keys)
                }
            }
            else -> {}
        }
    }

    private fun decodeHexEscapes(input: String): String {
        val regex = Regex("\\\\x([0-9a-fA-F]{2})")
        return regex.replace(input) { match ->
            val hex = match.groupValues[1]
            hex.toInt(16).toChar().toString()
        }
    }

    @Test
    fun testFetchSpotifyPlaylist() {
        val client = okhttp3.OkHttpClient()
        val playlistId = "37i9dQZF1DXcBWIGoYBM5M"
        val req = okhttp3.Request.Builder()
            .url("https://open.spotify.com/embed/playlist/$playlistId")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        val res = client.newCall(req).execute()
        println("Spotify embed code: ${res.code}")
        val body = res.body?.string() ?: ""
        println("Spotify body length: ${body.length}")
        
        val startIdx = body.indexOf("<script id=\"__NEXT_DATA__\" type=\"application/json\">")
        if (startIdx != -1) {
            val jsonStart = startIdx + "<script id=\"__NEXT_DATA__\" type=\"application/json\">".length
            val endIdx = body.indexOf("</script>", jsonStart)
            if (endIdx != -1) {
                val jsonStr = body.substring(jsonStart, endIdx).trim()
                File("spotify_json.txt").writeText(jsonStr)
                println("JSON written to spotify_json.txt")
            }
        }
    }

    @Test
    fun testFetchYoutubePlaylist() {
        val client = okhttp3.OkHttpClient()
        val playlistId = "PLRBp0Fe2GpgmsW46rJyudVFlY6IYjFBIK"
        val reqHtml = okhttp3.Request.Builder()
            .url("https://www.youtube.com/playlist?list=$playlistId")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        val resHtml = client.newCall(reqHtml).execute()
        val html = resHtml.body?.string() ?: ""
        println("YouTube HTML length: ${html.length}")
        
        val startVar = html.indexOf("var ytInitialData =")
        if (startVar != -1) {
            val jsonStart = startVar + "var ytInitialData =".length
            val endVar = html.indexOf(";</script>", jsonStart)
            if (endVar != -1) {
                val jsonStr = html.substring(jsonStart, endVar).trim()
                File("youtube_json.txt").writeText(jsonStr)
                println("YouTube JSON written to youtube_json.txt")
            }
        }
    }

    @Test
    fun testParseYoutubeJSON() {
        val file = File("youtube_json.txt")
        val finalFile = if (file.exists()) file else File("ext/youtube_json.txt")
        if (!finalFile.exists()) {
            println("youtube_json.txt does not exist")
            return
        }
        val jsonStr = finalFile.readText()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.parseToJsonElement(jsonStr)
        val playlistName = findPlaylistName(json)
        println("Extracted Playlist Name: $playlistName")
        val playlistThumb = findPlaylistThumbnail(json)
        println("Extracted Playlist Thumbnail: $playlistThumb")
        val list = mutableListOf<Pair<String, String>>()
        findRenderersWithLockup(json, list)
        println("Found ${list.size} tracks:")
        list.take(20).forEach {
            println("  Title: ${it.first} | Artist: ${it.second}")
        }
    }

    private fun findPlaylistThumbnail(element: kotlinx.serialization.json.JsonElement): String? {
        when (element) {
            is kotlinx.serialization.json.JsonObject -> {
                if (element.containsKey("playlistSidebarPrimaryInfoRenderer")) {
                    val r = element["playlistSidebarPrimaryInfoRenderer"]?.jsonObject
                    val thumbRenderer = r?.get("thumbnailRenderer")?.jsonObject
                    val videoThumb = thumbRenderer?.get("playlistVideoThumbnailRenderer")?.jsonObject
                        ?: thumbRenderer?.get("playlistCustomThumbnailRenderer")?.jsonObject
                        ?: r?.get("thumbnailRenderer")?.jsonObject
                    val thumb = videoThumb?.get("thumbnail")?.jsonObject ?: videoThumb
                    val url = thumb?.get("sources")?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
                        ?: thumb?.get("url")?.jsonPrimitive?.content
                    if (url != null) return url
                }
                for (value in element.values) {
                    val res = findPlaylistThumbnail(value)
                    if (res != null) return res
                }
            }
            is kotlinx.serialization.json.JsonArray -> {
                for (item in element) {
                    val res = findPlaylistThumbnail(item)
                    if (res != null) return res
                }
            }
            else -> {}
        }
        return null
    }

    private fun findPlaylistName(element: kotlinx.serialization.json.JsonElement): String? {
        when (element) {
            is kotlinx.serialization.json.JsonObject -> {
                if (element.containsKey("playlistSidebarPrimaryInfoRenderer")) {
                    val r = element["playlistSidebarPrimaryInfoRenderer"]?.jsonObject
                    val titleObj = r?.get("title")?.jsonObject
                    val runs = titleObj?.get("runs")?.jsonArray
                    val text = runs?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                        ?: titleObj?.get("simpleText")?.jsonPrimitive?.content
                    if (text != null) return text
                }
                if (element.containsKey("pageHeaderRenderer")) {
                    val r = element["pageHeaderRenderer"]?.jsonObject
                    val pageTitle = r?.get("pageTitle")?.jsonPrimitive?.content
                    if (pageTitle != null) return pageTitle
                }
                for (value in element.values) {
                    val res = findPlaylistName(value)
                    if (res != null) return res
                }
            }
            is kotlinx.serialization.json.JsonArray -> {
                for (item in element) {
                    val res = findPlaylistName(item)
                    if (res != null) return res
                }
            }
            else -> {}
        }
        return null
    }

    private fun findRenderersWithLockup(element: kotlinx.serialization.json.JsonElement, list: MutableList<Pair<String, String>>) {
        when (element) {
            is kotlinx.serialization.json.JsonObject -> {
                if (element.containsKey("playlistVideoRenderer")) {
                    val r = element["playlistVideoRenderer"]?.jsonObject
                    if (r != null) {
                        val title = r["title"]?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                            ?: r["title"]?.jsonObject?.get("simpleText")?.jsonPrimitive?.content
                            ?: ""
                        val artist = r["shortBylineText"]?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                            ?: ""
                        if (title.isNotEmpty()) {
                            list.add(Pair(title, artist))
                        }
                    }
                } else if (element.containsKey("musicResponsiveListItemRenderer")) {
                    val r = element["musicResponsiveListItemRenderer"]?.jsonObject
                    if (r != null) {
                        val flex = r["flexColumns"]?.jsonArray
                        val titleCol = flex?.getOrNull(0)?.jsonObject?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
                        val title = titleCol?.get("text")?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                            ?: ""
                        val artistCol = flex?.getOrNull(1)?.jsonObject?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
                        val artist = artistCol?.get("text")?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                            ?: ""
                        if (title.isNotEmpty()) {
                            list.add(Pair(title, artist))
                        }
                    }
                } else if (element.containsKey("lockupViewModel")) {
                    val r = element["lockupViewModel"]?.jsonObject
                    if (r != null) {
                        val metadata = r["metadata"]?.jsonObject
                        val lockupMetadata = metadata?.get("lockupMetadataViewModel")?.jsonObject
                        val title = lockupMetadata?.get("title")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: ""
                        val artist = lockupMetadata?.get("metadata")?.jsonObject
                            ?.get("contentMetadataViewModel")?.jsonObject
                            ?.get("metadataRows")?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("metadataParts")?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("text")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: ""
                        if (title.isNotEmpty()) {
                            list.add(Pair(title, artist))
                        }
                    }
                } else {
                    for (value in element.values) {
                        findRenderersWithLockup(value, list)
                    }
                }
            }
            is kotlinx.serialization.json.JsonArray -> {
                for (item in element) {
                    findRenderersWithLockup(item, list)
                }
            }
            else -> {}
        }
    }
}
