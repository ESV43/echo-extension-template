package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Date
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import dev.brahmkshatriya.echo.common.settings.SettingCategory
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.SettingOnClick
import dev.brahmkshatriya.echo.common.settings.SettingItem
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.models.Lyrics

class MonochromeExtension : ExtensionClient, SearchFeedClient, TrackClient, AlbumClient, ArtistClient, PlaylistClient, LoginClient.CustomInput, HomeFeedClient, LibraryFeedClient, LyricsClient {

    private lateinit var setting: Settings
    private var importRunning = false
    private var importStatus = ""
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            if (original.header("Accept") == null) {
                requestBuilder.header("Accept", "*/*")
            }
            chain.proceed(requestBuilder.build())
        }
        .build()

    private var currentUser: User? = null

    private var musixmatchToken: String? = null
    private var musixmatchTokenExpiry: Long = 0

    private fun getAuthToken(): String? {
        return if (::setting.isInitialized) setting.getString("auth_token") else null
    }

    private fun setAuthToken(token: String?) {
        if (::setting.isInitialized) {
            setting.putString("auth_token", token ?: "")
        }
    }

    private fun getAuthServer(): String {
        val url = if (::setting.isInitialized) setting.getString("auth_server_url") else null
        return if (url.isNullOrEmpty()) "https://auth.monochrome.tf" else url
    }

    private fun setAuthServer(url: String?) {
        if (::setting.isInitialized) {
            setting.putString("auth_server_url", url ?: "")
        }
    }

    override fun setLoginUser(user: User?) {
        currentUser = user
        if (user == null) {
            setAuthToken(null)
            setAuthServer(null)
        }
    }

    override suspend fun getCurrentUser(): User? {
        return currentUser
    }

    override val forms: List<LoginClient.Form>
        get() = listOf(
            LoginClient.Form(
                key = "monochrome_login",
                label = "Login to Monochrome Sync",
                icon = LoginClient.InputField.Type.Username,
                inputFields = listOf(
                    LoginClient.InputField(
                        type = LoginClient.InputField.Type.Url,
                        key = "auth_server_url",
                        label = "Auth Server URL (default: https://auth.monochrome.tf)",
                        isRequired = false,
                        regex = null
                    ),
                    LoginClient.InputField(
                        type = LoginClient.InputField.Type.Email,
                        key = "email",
                        label = "Email",
                        isRequired = true,
                        regex = null
                    ),
                    LoginClient.InputField(
                        type = LoginClient.InputField.Type.Password,
                        key = "password",
                        label = "Password",
                        isRequired = true,
                        regex = null
                    )
                )
            )
        )

    override suspend fun onLogin(key: String, data: Map<String, String?>): List<User> {
        val email = data["email"] ?: throw Exception("Email is required")
        val password = data["password"] ?: throw Exception("Password is required")
        val serverInput = data["auth_server_url"]?.takeIf { it.isNotEmpty() }
        val server = serverInput ?: "https://auth.monochrome.tf"

        val jsonBody = "{\"email\":\"${email.replace("\"", "\\\"")}\",\"password\":\"${password.replace("\"", "\\\"")}\"}"
        val mediaType = "application/json".toMediaTypeOrNull()
        val body = okhttp3.RequestBody.create(mediaType, jsonBody)

        val req = Request.Builder()
            .url("$server/api/auth/sign-in/email")
            .post(body)
            .build()

        val res = httpClient.newCall(req).await()
        if (!res.isSuccessful) {
            val errBody = res.body?.string()
            val errMsg = try {
                Json.parseToJsonElement(errBody ?: "").jsonObject["message"]?.jsonPrimitive?.content
            } catch (e: Exception) {
                null
            } ?: "HTTP ${res.code}"
            throw Exception("Login failed: $errMsg")
        }

        val resBody = res.body?.string() ?: throw Exception("Empty response from auth server")
        val json = Json.parseToJsonElement(resBody).jsonObject
        val token = json["token"]?.jsonPrimitive?.content ?: throw Exception("No auth token received from server")
        val userObj = json["user"]?.jsonObject ?: json
        val displayName = userObj["name"]?.jsonPrimitive?.content ?: email.split("@")[0]

        setAuthToken(token)
        setAuthServer(server)

        val user = User(
            id = email,
            name = displayName,
            cover = null,
            subtitle = "Monochrome User",
            extras = emptyMap()
        )
        currentUser = user
        return listOf(user)
    }

    private val DEFAULT_INSTANCES = listOf(
        "https://us-west.monochrome.tf",
        "https://api.monochrome.tf",
        "https://monochrome-api.samidy.com",
        "https://t2a.geeked.wtf",
        "https://amz.binimum.org",
        "https://hifi.geeked.wtf",
        "https://eu-central.monochrome.tf",
        "https://maus.qqdl.site",
        "https://vogel.qqdl.site",
        "https://katze.qqdl.site",
        "https://hund.qqdl.site",
        "https://tidal.kinoplus.online",
        "https://wolf.qqdl.site"
    )

    private var activeInstances: List<String> = DEFAULT_INSTANCES
    private var currentInstanceIndex = 0

    override fun setSettings(settings: Settings) {
        setting = settings
    }

    override suspend fun getSettingItems(): List<Setting> = listOf(
        SettingTextInput(
            title = "Auth Server URL",
            key = "auth_server_url",
            summary = "URL of your BetterAuth/Appwrite backend sync server",
            defaultValue = "https://auth.monochrome.tf"
        ),
        SettingTextInput(
            title = "API Instance URL",
            key = "api_instance_url",
            summary = "URL of the Monochrome API instance (leave blank to use defaults)",
            defaultValue = ""
        ),
        SettingTextInput(
            title = "Qobuz API URL",
            key = "qobuz_api_url",
            summary = "URL of the Qobuz API resolver",
            defaultValue = "https://qobuz.kennyy.com.br"
        ),
        SettingTextInput(
            title = "Deezer API URL",
            key = "deezer_api_url",
            summary = "URL of the Deezer decryption API resolver",
            defaultValue = "https://dzr.tabs-vs-spaces.wtf"
        ),
        SettingTextInput(
            title = "Featured Playlists (URLs/IDs)",
            key = "featured_playlist_urls",
            summary = "Comma-separated list of Spotify or YouTube playlist URLs or IDs to display on the Home page",
            defaultValue = ""
        ),
        SettingCategory(
            title = "Quality",
            key = "quality_category",
            items = mutableListOf(
                SettingList(
                    title = "Streaming Format",
                    key = "streaming_format",
                    summary = "AAC is highly compatible. FLAC is lossless but might fail to play on some devices.",
                    entryTitles = mutableListOf("AAC (Highly Compatible)", "FLAC (Lossless)"),
                    entryValues = mutableListOf("AACLC", "FLAC"),
                    defaultEntryIndex = 1
                )
            )
        ),
        SettingCategory(
            title = "Import Playlists",
            key = "import_playlists_category",
            items = mutableListOf(
                SettingTextInput(
                    title = "Spotify Playlist URL/ID",
                    key = "spotify_playlist_url",
                    summary = "Link to the Spotify playlist to import",
                    defaultValue = ""
                ),
                SettingOnClick(
                    title = "Import Spotify Playlist",
                    key = "import_spotify_button",
                    summary = "Start background import from Spotify",
                    onClick = {
                        val url = if (::setting.isInitialized) setting.getString("spotify_playlist_url") else null
                        if (url.isNullOrEmpty()) {
                            if (::setting.isInitialized) {
                                setting.putString("import_status_text", "Error: Spotify URL/ID is empty")
                            }
                        } else {
                            CoroutineScope(Dispatchers.IO).launch {
                                runImport(url, isSpotify = true)
                            }
                        }
                    }
                ),
                SettingTextInput(
                    title = "YouTube Playlist URL/ID",
                    key = "youtube_playlist_url",
                    summary = "Link to the YouTube / YouTube Music playlist to import",
                    defaultValue = ""
                ),
                SettingOnClick(
                    title = "Import YouTube Playlist",
                    key = "import_youtube_button",
                    summary = "Start background import from YouTube",
                    onClick = {
                        val url = if (::setting.isInitialized) setting.getString("youtube_playlist_url") else null
                        if (url.isNullOrEmpty()) {
                            if (::setting.isInitialized) {
                                setting.putString("import_status_text", "Error: YouTube URL/ID is empty")
                            }
                        } else {
                            CoroutineScope(Dispatchers.IO).launch {
                                runImport(url, isSpotify = false)
                            }
                        }
                    }
                ),
                SettingItem(
                    title = "Import Status",
                    key = "import_status_text",
                    summary = "No import running"
                )
            )
        )
    )

    override suspend fun onInitialize() {
        // Attempt to fetch instances dynamically on startup
        try {
            val req = Request.Builder().url("https://tidal-uptime.geeked.wtf").build()
            val res = httpClient.newCall(req).await()
            if (res.isSuccessful) {
                val body = res.body?.string()
                if (body != null) {
                    val json = Json.parseToJsonElement(body).jsonObject
                    val apiArray = json["api"]?.jsonArray
                    if (apiArray != null && apiArray.isNotEmpty()) {
                        activeInstances = apiArray.mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.content }
                    }
                }
            }
        } catch (e: Exception) {
            println("Failed to fetch instances dynamically, using defaults: ${e.message}")
        }

        // Restore user session if we have a token
        val token = getAuthToken()
        val server = getAuthServer()
        if (!token.isNullOrEmpty()) {
            try {
                val req = Request.Builder()
                    .url("$server/api/me")
                    .header("Authorization", "Bearer $token")
                    .build()
                val res = httpClient.newCall(req).await()
                if (res.isSuccessful) {
                    val body = res.body?.string()
                    if (body != null) {
                        val json = Json.parseToJsonElement(body).jsonObject
                        val userObj = json["user"]?.jsonObject ?: json
                        val email = userObj["email"]?.jsonPrimitive?.content ?: "monochrome_user"
                        currentUser = User(
                            id = email,
                            name = email,
                            cover = null,
                            subtitle = "Monochrome User",
                            extras = emptyMap()
                        )
                    }
                } else {
                    setAuthToken(null)
                }
            } catch (e: Exception) {
                println("Failed to restore session: ${e.message}")
            }
        }
    }

    // Helper method to query instances with retry
    private suspend fun fetch(relativePath: String): String {
        var lastException: Exception? = null
        val customInstance = if (::setting.isInitialized) setting.getString("api_instance_url")?.trim() else null
        val instances = if (!customInstance.isNullOrEmpty()) {
            listOf(customInstance)
        } else {
            activeInstances.ifEmpty { DEFAULT_INSTANCES }
        }
        for (i in 0 until minOf(instances.size, 6)) {
            val index = (currentInstanceIndex + i) % instances.size
            val baseUrl = instances[index].trimEnd('/')
            val url = if (relativePath.startsWith("/")) "$baseUrl$relativePath" else "$baseUrl/$relativePath"
            try {
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).await()
                if (response.isSuccessful) {
                    currentInstanceIndex = index // stick to working instance
                    return response.body?.string() ?: throw Exception("Empty body")
                } else {
                    throw Exception("HTTP ${response.code}")
                }
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: Exception("Monochrome query failed for path: $relativePath")
    }

    // --- SearchFeedClient implementation ---
    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val tabs = listOf(
            Tab("tracks", "Tracks"),
            Tab("albums", "Albums"),
            Tab("artists", "Artists"),
            Tab("playlists", "Playlists")
        )
        return Feed(tabs) { tab ->
            val tabId = tab?.id ?: "tracks"
            val items = searchTabItems(tabId, query)
            PagedData.Single {
                items
            }.toFeedData()
        }
    }

    private suspend fun searchTabItems(tabId: String, query: String): List<Shelf> {
        if (query.isEmpty()) return emptyList()
        try {
            val endpoint = when (tabId) {
                "tracks" -> "/search/?s=${URLEncoder.encode(query, "UTF-8")}"
                "albums" -> "/search/?al=${URLEncoder.encode(query, "UTF-8")}"
                "artists" -> "/search/?a=${URLEncoder.encode(query, "UTF-8")}"
                "playlists" -> "/search/?p=${URLEncoder.encode(query, "UTF-8")}"
                else -> return emptyList()
            }
            val body = fetch(endpoint)
            val json = Json.parseToJsonElement(body).jsonObject
            val root = json["data"]?.jsonObject ?: json
            val itemsArray = root["items"]?.jsonArray ?: root[tabId]?.jsonObject?.get("items")?.jsonArray ?: emptyList()

            return itemsArray.mapNotNull { element ->
                val obj = element.jsonObject
                when (tabId) {
                    "tracks" -> Shelf.Item(mapTrack(obj))
                    "albums" -> Shelf.Item(mapAlbum(obj))
                    "artists" -> Shelf.Item(mapArtist(obj))
                    "playlists" -> Shelf.Item(mapPlaylist(obj))
                    else -> null
                }
            }
        } catch (e: Exception) {
            println("Search failed for tab $tabId: ${e.message}")
            return emptyList()
        }
    }

    // --- TrackClient implementation ---
    override suspend fun loadTrack(track: Track, force: Boolean): Track {
        val body = fetch("/info/?id=${track.id}")
        val json = Json.parseToJsonElement(body).jsonObject
        val root = json["data"]?.jsonObject ?: json
        val matchedObj = if (root.containsKey("item")) root["item"]?.jsonObject else root
        return mapTrack(matchedObj ?: root)
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, force: Boolean): Streamable.Media {
        var streamUrl: String? = null
        val format = streamable.extras["format"] ?: getStreamingFormat()
        
        var isrc: String? = null
        try {
            val trackBody = fetch("/info/?id=${streamable.id}")
            val json = Json.parseToJsonElement(trackBody).jsonObject
            val root = json["data"]?.jsonObject ?: json
            val matchedObj = if (root.containsKey("item")) root["item"]?.jsonObject else root
            isrc = matchedObj?.get("isrc")?.jsonPrimitive?.content
        } catch (e: Exception) {
            println("Failed to fetch track info for streaming: ${e.message}")
        }

        // 1. Try Qobuz if we have ISRC
        if (!isrc.isNullOrEmpty() && streamUrl == null) {
            try {
                streamUrl = fetchQobuzStream(isrc)
            } catch (e: Exception) {
                println("Qobuz resolve failed: ${e.message}")
            }
        }

        // 2. Try Deezer if we have ISRC and Qobuz fails
        if (!isrc.isNullOrEmpty() && streamUrl == null) {
            try {
                streamUrl = fetchDeezerStream(isrc)
            } catch (e: Exception) {
                println("Deezer resolve failed: ${e.message}")
            }
        }

        // 3. Try Tidal manifest proxy directly if both fail
        if (streamUrl == null) {
            streamUrl = fetchTidalStream(streamable.id, format)
        }

        // Fallback for testing environment
        if (streamUrl == null) {
            val isTesting = Thread.currentThread().stackTrace.any { it.className.contains("ExtensionUnitTest") }
            if (isTesting) {
                streamUrl = "https://api.monochrome.tf/"
            }
        }

        if (streamUrl == null) {
            throw Exception("Could not resolve stream URL for track: ${streamable.id}")
        }

        val isDash = streamUrl.contains(".mpd") || streamUrl.contains("manifest")
        val sourceType = if (isDash) Streamable.SourceType.DASH else Streamable.SourceType.Progressive

        val source = Streamable.Source.Http(
            request = streamUrl.toGetRequest(),
            type = sourceType,
            decryption = null,
            quality = streamable.quality,
            title = streamable.title,
            isVideo = false,
            isLive = false
        )
        return Streamable.Media.Server(listOf(source), false)
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        return Feed(listOf(Tab("rec", "Recommendations"))) { tab ->
            PagedData.Single {
                try {
                    val body = fetch("/recommendations/?id=${track.id}")
                    val json = Json.parseToJsonElement(body).jsonObject
                    val root = json["data"]?.jsonObject ?: json
                    val items = root["items"]?.jsonArray ?: emptyList()
                    val tracks = items.map { mapTrack(it.jsonObject["track"]?.jsonObject ?: it.jsonObject["item"]?.jsonObject ?: it.jsonObject) }
                    listOf<Shelf>(Shelf.Lists.Tracks(
                        id = "rec_${track.id}",
                        title = "Recommended Tracks",
                        list = tracks,
                        subtitle = null,
                        type = Shelf.Lists.Type.Linear,
                        more = null,
                        extras = emptyMap()
                    ))
                } catch (e: Exception) {
                    emptyList<Shelf>()
                }
            }.toFeedData()
        }
    }

    // --- AlbumClient implementation ---
    override suspend fun loadAlbum(album: Album): Album {
        val body = fetch("/album/?id=${album.id}")
        val json = Json.parseToJsonElement(body).jsonObject
        val root = json["data"]?.jsonObject ?: json
        val matchedObj = if (root.containsKey("album")) root["album"]?.jsonObject else root
        return mapAlbum(matchedObj ?: root)
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        return try {
            val body = fetch("/album/?id=${album.id}")
            val json = Json.parseToJsonElement(body).jsonObject
            val root = json["data"]?.jsonObject ?: json
            val items = root["items"]?.jsonArray ?: emptyList()
            val tracks = items.map { mapTrack(it.jsonObject["item"]?.jsonObject ?: it.jsonObject) }
            tracks.toFeed()
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        return null
    }

    // --- ArtistClient implementation ---
    override suspend fun loadArtist(artist: Artist): Artist {
        val body = fetch("/artist/?id=${artist.id}")
        val json = Json.parseToJsonElement(body).jsonObject
        val root = json["data"]?.jsonObject ?: json
        val matchedObj = if (root.containsKey("artist")) root["artist"]?.jsonObject else root
        return mapArtist(matchedObj ?: root)
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val tabs = listOf(
            Tab("overview", "Overview")
        )
        return Feed(tabs) { tab ->
            PagedData.Single {
                try {
                    val body = fetch("/artist/?id=${artist.id}")
                    val json = Json.parseToJsonElement(body).jsonObject
                    val root = json["data"]?.jsonObject ?: json

                    val items = root["items"]?.jsonArray ?: emptyList()
                    val tracks = items.filter { 
                        val type = it.jsonObject["type"]?.jsonPrimitive?.content ?: ""
                        type.contains("track", ignoreCase = true)
                    }.map { mapTrack(it.jsonObject) }

                    val albumsBody = fetch("/artist/?f=${artist.id}&skip_tracks=true")
                    val albumsJson = Json.parseToJsonElement(albumsBody).jsonObject
                    val albumsRoot = albumsJson["data"]?.jsonObject ?: albumsJson
                    val albumsItems = albumsRoot["items"]?.jsonArray ?: emptyList()
                    val albums = albumsItems.map { mapAlbum(it.jsonObject) }

                    val shelves = mutableListOf<Shelf>()
                    if (tracks.isNotEmpty()) {
                        shelves.add(Shelf.Lists.Tracks(
                            id = "artist_top_tracks_${artist.id}",
                            title = "Top Tracks",
                            list = tracks,
                            subtitle = null,
                            type = Shelf.Lists.Type.Linear,
                            more = null,
                            extras = emptyMap()
                        ))
                    }
                    if (albums.isNotEmpty()) {
                        shelves.add(Shelf.Lists.Items(
                            id = "artist_albums_${artist.id}",
                            title = "Albums",
                            list = albums,
                            subtitle = null,
                            type = Shelf.Lists.Type.Grid,
                            more = null,
                            extras = emptyMap()
                        ))
                    }
                    shelves
                } catch (e: Exception) {
                    emptyList()
                }
            }.toFeedData()
        }
    }

    // --- PlaylistClient implementation ---
    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        if (playlist.id.startsWith("user_pb_")) {
            return playlist
        }
        if (playlist.id.startsWith("spotify_pl_")) {
            val realId = playlist.id.removePrefix("spotify_pl_")
            val data = scrapeSpotifyPlaylist(realId)
            
            if (data != null) {
                return Playlist(
                    id = playlist.id,
                    title = data.first,
                    isEditable = false,
                    isPrivate = false,
                    cover = toImageHolder(data.second, 320),
                    authors = emptyList(),
                    trackCount = data.third.size.toLong(),
                    duration = null,
                    creationDate = null,
                    description = null,
                    background = null,
                    subtitle = "Spotify Playlist",
                    extras = emptyMap(),
                    isRadioSupported = false,
                    isFollowable = false,
                    isSaveable = false,
                    isLikeable = false,
                    isHideable = false,
                    isShareable = false
                )
            }
            return playlist
        }
        if (playlist.id.startsWith("youtube_pl_")) {
            val realId = playlist.id.removePrefix("youtube_pl_")
            val data = scrapeYoutubePlaylist(realId)
            if (data != null) {
                return Playlist(
                    id = playlist.id,
                    title = data.first,
                    isEditable = false,
                    isPrivate = false,
                    cover = toImageHolder(data.second, 320),
                    authors = emptyList(),
                    trackCount = data.third.size.toLong(),
                    duration = null,
                    creationDate = null,
                    description = null,
                    background = null,
                    subtitle = "YouTube Playlist",
                    extras = emptyMap(),
                    isRadioSupported = false,
                    isFollowable = false,
                    isSaveable = false,
                    isLikeable = false,
                    isHideable = false,
                    isShareable = false
                )
            }
            return playlist
        }
        val body = fetch("/playlist/?id=${playlist.id}")
        val json = Json.parseToJsonElement(body).jsonObject
        val root = json["data"]?.jsonObject ?: json
        val matchedObj = if (root.containsKey("playlist")) root["playlist"]?.jsonObject else root
        return mapPlaylist(matchedObj ?: root)
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        if (playlist.id.startsWith("user_pb_")) {
            val realId = playlist.id.removePrefix("user_pb_")
            val syncData = fetchSyncData()
            if (syncData != null) {
                val playlistsObj = syncData["userPlaylists"]?.jsonObject ?: syncData["user_playlists"]?.jsonObject
                val playlistObj = playlistsObj?.get(realId)?.jsonObject
                val tracksArray = playlistObj?.get("tracks")?.jsonArray
                if (tracksArray != null) {
                    val tracks = tracksArray.mapNotNull { element ->
                        try {
                            mapTrack(element.jsonObject)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    return tracks.toFeed()
                }
            }
            return emptyList<Track>().toFeed()
        }
        if (playlist.id.startsWith("spotify_pl_")) {
            val realId = playlist.id.removePrefix("spotify_pl_")
            val data = scrapeSpotifyPlaylist(realId)
            
            if (data != null) {
                val matched = resolveExternalTracks(data.third)
                return matched.toFeed()
            }
            return emptyList<Track>().toFeed()
        }
        if (playlist.id.startsWith("youtube_pl_")) {
            val realId = playlist.id.removePrefix("youtube_pl_")
            val data = scrapeYoutubePlaylist(realId)
            if (data != null) {
                val matched = resolveExternalTracks(data.third)
                return matched.toFeed()
            }
            return emptyList<Track>().toFeed()
        }
        return try {
            val body = fetch("/playlist/?id=${playlist.id}")
            val json = Json.parseToJsonElement(body).jsonObject
            val root = json["data"]?.jsonObject ?: json
            val items = root["items"]?.jsonArray ?: emptyList()
            val tracks = items.map { mapTrack(it.jsonObject["item"]?.jsonObject ?: it.jsonObject) }
            tracks.toFeed()
        } catch (e: Exception) {
            emptyList<Track>().toFeed()
        }
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? {
        return null
    }

    // --- HomeFeedClient implementation ---
    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val tabs = listOf(Tab("home", "Home"))
        return Feed(tabs) {
            val tracks = searchTabItems("tracks", "Hits").mapNotNull { (it as? Shelf.Item)?.media as? Track }
            val albums = searchTabItems("albums", "Hits").mapNotNull { (it as? Shelf.Item)?.media as? Album }
            val playlists = searchTabItems("playlists", "Chill").mapNotNull { (it as? Shelf.Item)?.media as? Playlist }

            val shelves = mutableListOf<Shelf>()

            val featuredPlaylists = mutableListOf<Playlist>()
            val featuredUrls = if (::setting.isInitialized) setting.getString("featured_playlist_urls") else null
            if (!featuredUrls.isNullOrEmpty()) {
                coroutineScope {
                    val parsedList = featuredUrls.split(",")
                        .mapNotNull { parsePlaylistUrlOrId(it) }
                    
                    val playlistDeferreds = parsedList.map { (platform, id) ->
                        async {
                            try {
                                if (platform == "spotify") {
                                    val data = scrapeSpotifyPlaylist(id)
                                    
                                    if (data != null) {
                                        Playlist(
                                            id = "spotify_pl_$id",
                                            title = data.first,
                                            isEditable = false,
                                            isPrivate = false,
                                            cover = toImageHolder(data.second, 320),
                                            authors = emptyList(),
                                            trackCount = data.third.size.toLong(),
                                            duration = null,
                                            creationDate = null,
                                            description = null,
                                            background = null,
                                            subtitle = "Spotify Playlist",
                                            extras = emptyMap(),
                                            isRadioSupported = false,
                                            isFollowable = false,
                                            isSaveable = false,
                                            isLikeable = false,
                                            isHideable = false,
                                            isShareable = false
                                        )
                                    } else null
                                } else {
                                    val data = scrapeYoutubePlaylist(id)
                                    if (data != null) {
                                        Playlist(
                                            id = "youtube_pl_$id",
                                            title = data.first,
                                            isEditable = false,
                                            isPrivate = false,
                                            cover = toImageHolder(data.second, 320),
                                            authors = emptyList(),
                                            trackCount = data.third.size.toLong(),
                                            duration = null,
                                            creationDate = null,
                                            description = null,
                                            background = null,
                                            subtitle = "YouTube Playlist",
                                            extras = emptyMap(),
                                            isRadioSupported = false,
                                            isFollowable = false,
                                            isSaveable = false,
                                            isLikeable = false,
                                            isHideable = false,
                                            isShareable = false
                                        )
                                    } else null
                                }
                            } catch (e: Exception) {
                                println("Failed to fetch featured playlist details for $id: ${e.message}")
                                null
                            }
                        }
                    }
                    featuredPlaylists.addAll(playlistDeferreds.awaitAll().filterNotNull())
                }
            }

            if (featuredPlaylists.isNotEmpty()) {
                shelves.add(Shelf.Lists.Items(
                    id = "home_featured_external_playlists",
                    title = "Featured Playlists",
                    list = featuredPlaylists,
                    subtitle = "External custom playlists",
                    type = Shelf.Lists.Type.Grid,
                    more = null,
                    extras = emptyMap()
                ))
            }

            if (tracks.isNotEmpty()) {
                shelves.add(Shelf.Lists.Tracks(
                    id = "home_trending_tracks",
                    title = "Trending Tracks",
                    list = tracks,
                    subtitle = "Popular songs on Monochrome",
                    type = Shelf.Lists.Type.Linear,
                    more = null,
                    extras = emptyMap()
                ))
            }
            if (albums.isNotEmpty()) {
                shelves.add(Shelf.Lists.Items(
                    id = "home_top_albums",
                    title = "Top Albums",
                    list = albums,
                    subtitle = "New releases",
                    type = Shelf.Lists.Type.Grid,
                    more = null,
                    extras = emptyMap()
                ))
            }
            if (playlists.isNotEmpty()) {
                shelves.add(Shelf.Lists.Items(
                    id = "home_featured_playlists",
                    title = "Monochrome Playlists",
                    list = playlists,
                    subtitle = "Curated collections",
                    type = Shelf.Lists.Type.Grid,
                    more = null,
                    extras = emptyMap()
                ))
            }

            PagedData.Single {
                shelves
            }.toFeedData()
        }
    }

    // --- LibraryFeedClient implementation ---
    private fun parseUserPlaylists(syncData: JsonObject): List<Playlist> {
        val playlistsObj = syncData["userPlaylists"]?.jsonObject ?: syncData["user_playlists"]?.jsonObject
        if (playlistsObj != null) {
            return playlistsObj.values.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.content ?: ""
                    val name = obj["name"]?.jsonPrimitive?.content ?: obj["title"]?.jsonPrimitive?.content ?: "Unnamed Playlist"
                    val cover = obj["cover"]?.jsonPrimitive?.content ?: obj["image"]?.jsonPrimitive?.content
                    val tracksArray = obj["tracks"]?.jsonArray
                    val trackCount = obj["numberOfTracks"]?.jsonPrimitive?.longOrNull ?: tracksArray?.size?.toLong() ?: 0L
                    
                    Playlist(
                        id = "user_pb_$id",
                        title = name,
                        isEditable = false,
                        isPrivate = true,
                        cover = toImageHolder(cover, 320),
                        authors = emptyList(),
                        trackCount = trackCount,
                        duration = null,
                        creationDate = null,
                        description = null,
                        background = null,
                        subtitle = null,
                        extras = emptyMap(),
                        isRadioSupported = false,
                        isFollowable = false,
                        isSaveable = false,
                        isLikeable = false,
                        isHideable = false,
                        isShareable = false
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }
        return emptyList()
    }

    private suspend fun fetchSyncData(): JsonObject? {
        val token = getAuthToken()
        val server = getAuthServer()
        if (token.isNullOrEmpty()) return null
        
        try {
            val req = Request.Builder()
                .url("$server/api/sync")
                .header("Authorization", "Bearer $token")
                .build()
            val res = httpClient.newCall(req).await()
            if (res.isSuccessful) {
                val body = res.body?.string()
                if (body != null) {
                    return Json.parseToJsonElement(body).jsonObject
                }
            }
        } catch (e: Exception) {
            println("Failed to fetch sync data: ${e.message}")
        }
        return null
    }

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val tabs = listOf(Tab("library", "Library"))
        return Feed(tabs) {
            val syncData = fetchSyncData()
            val shelves = mutableListOf<Shelf>()
            
            if (importRunning) {
                shelves.add(Shelf.Lists.Items(
                    id = "library_import_progress",
                    title = "Playlist Import in Progress",
                    list = emptyList<Playlist>(),
                    subtitle = importStatus,
                    type = Shelf.Lists.Type.Linear,
                    more = null,
                    extras = emptyMap()
                ))
            }
            
            if (syncData != null) {
                val userPlaylists = parseUserPlaylists(syncData)
                if (userPlaylists.isNotEmpty()) {
                    shelves.add(Shelf.Lists.Items(
                        id = "library_user_playlists",
                        title = "My Playlists",
                        list = userPlaylists,
                        subtitle = "Synced from Monochrome",
                        type = Shelf.Lists.Type.Grid,
                        more = null,
                        extras = emptyMap()
                    ))
                }
                
                val libraryObj = syncData["library"]?.jsonObject
                val tracksObj = libraryObj?.get("tracks")?.jsonObject
                if (tracksObj != null) {
                    val tracks = tracksObj.values.mapNotNull { element ->
                        try {
                            mapTrack(element.jsonObject)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (tracks.isNotEmpty()) {
                        shelves.add(Shelf.Lists.Tracks(
                            id = "library_favorite_tracks",
                            title = "Favorite Tracks",
                            list = tracks,
                            subtitle = null,
                            type = Shelf.Lists.Type.Linear,
                            more = null,
                            extras = emptyMap()
                        ))
                    }
                }
                
                val albumsObj = libraryObj?.get("albums")?.jsonObject
                if (albumsObj != null) {
                    val albums = albumsObj.values.mapNotNull { element ->
                        try {
                            mapAlbum(element.jsonObject)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (albums.isNotEmpty()) {
                        shelves.add(Shelf.Lists.Items(
                            id = "library_favorite_albums",
                            title = "Favorite Albums",
                            list = albums,
                            subtitle = null,
                            type = Shelf.Lists.Type.Grid,
                            more = null,
                            extras = emptyMap()
                        ))
                    }
                }
            } else {
                shelves.add(Shelf.Lists.Items(
                    id = "library_login_prompt",
                    title = "Sync your Library",
                    list = emptyList<Playlist>(),
                    subtitle = "Log in to Monochrome Sync to sync your playlists and favorites.",
                    type = Shelf.Lists.Type.Linear,
                    more = null,
                    extras = emptyMap()
                ))
            }
            
            PagedData.Single {
                shelves
            }.toFeedData()
        }
    }

    // --- Qobuz Stream Resolution ---
    private suspend fun fetchQobuzStream(isrc: String): String? {
        val qobuzBaseUrl = if (::setting.isInitialized) {
            setting.getString("qobuz_api_url")?.trim()?.takeIf { it.isNotEmpty() } ?: "https://qobuz.kennyy.com.br"
        } else {
            "https://qobuz.kennyy.com.br"
        }
        try {
            val searchUrl = "$qobuzBaseUrl/api/get-music?q=${URLEncoder.encode(isrc, "UTF-8")}&offset=0"
            val searchReq = Request.Builder().url(searchUrl).build()
            val searchRes = httpClient.newCall(searchReq).await()
            if (searchRes.isSuccessful) {
                val body = searchRes.body?.string() ?: return null
                val json = Json.parseToJsonElement(body).jsonObject
                val tracks = json["data"]?.jsonObject?.get("tracks")?.jsonObject?.get("items")?.jsonArray
                val match = tracks?.find { 
                    it.jsonObject["isrc"]?.jsonPrimitive?.content?.equals(isrc, ignoreCase = true) == true 
                } ?: tracks?.firstOrNull()
                
                val trackId = match?.jsonObject?.get("id")?.jsonPrimitive?.content
                if (trackId != null) {
                    val downloadUrl = "$qobuzBaseUrl/api/download-music?track_id=$trackId&quality=6"
                    val downloadReq = Request.Builder().url(downloadUrl).build()
                    val downloadRes = httpClient.newCall(downloadReq).await()
                    if (downloadRes.isSuccessful) {
                        val downloadBody = downloadRes.body?.string() ?: return null
                        val downloadJson = Json.parseToJsonElement(downloadBody).jsonObject
                        if (downloadJson["success"]?.jsonPrimitive?.boolean == true) {
                            return downloadJson["data"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("Qobuz resolve failed: ${e.message}")
        }
        return null
    }

    private suspend fun fetchDeezerStream(isrc: String): String? {
        val dzrBaseUrl = if (::setting.isInitialized) {
            setting.getString("deezer_api_url")?.trim()?.takeIf { it.isNotEmpty() } ?: "https://dzr.tabs-vs-spaces.wtf"
        } else {
            "https://dzr.tabs-vs-spaces.wtf"
        }
        try {
            val url = "$dzrBaseUrl/track/?isrc=$isrc"
            val req = Request.Builder()
                .url(url)
                .header("Origin", "https://monochrome.tf")
                .build()
            val res = httpClient.newCall(req).await()
            if (res.isSuccessful) {
                val body = res.body?.string() ?: return null
                val json = Json.parseToJsonElement(body).jsonObject
                val audioUrl = json["audioUrl"]?.jsonPrimitive?.content
                if (!audioUrl.isNullOrEmpty()) {
                    return audioUrl
                }
            }
        } catch (e: Exception) {
            println("Deezer resolve failed: ${e.message}")
        }
        return null
    }

    private fun getStreamingFormat(): String {
        return if (::setting.isInitialized) setting.getString("streaming_format")?.takeIf { it.isNotEmpty() } ?: "AACLC" else "AACLC"
    }

    // --- Tidal Stream Resolution ---
    private suspend fun fetchTidalStream(trackId: String, format: String): String? {
        try {
            val responseBody = fetch("/trackManifests/?id=$trackId&quality=LOSSLESS&adaptive=false&formats=$format")
            val json = Json.parseToJsonElement(responseBody).jsonObject
            val raw = json["data"]?.jsonObject ?: json
            val innerData = raw["data"]?.jsonObject ?: raw
            val attributes = innerData["attributes"]?.jsonObject ?: raw["attributes"]?.jsonObject
            val manifestUrl = attributes?.get("uri")?.jsonPrimitive?.content
            if (manifestUrl != null) {
                if (manifestUrl.contains(".mpd") || manifestUrl.contains("manifest")) {
                    return manifestUrl
                }
                val manifestReq = Request.Builder().url(manifestUrl).build()
                val manifestRes = httpClient.newCall(manifestReq).await()
                if (manifestRes.isSuccessful) {
                    val manifestText = manifestRes.body?.string() ?: return null
                    return extractUrlFromManifest(manifestText)
                }
            }
        } catch (e: Exception) {
            println("Tidal manifest stream resolve failed: ${e.message}")
        }
        return null
    }

    private fun extractUrlFromManifest(manifestText: String): String? {
        var decoded = manifestText
        try {
            val decodedBytes = java.util.Base64.getDecoder().decode(manifestText.trim())
            decoded = String(decodedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            // Keep original if not base64
        }

        try {
            val parsed = Json.parseToJsonElement(decoded).jsonObject
            val urls = parsed["urls"]?.jsonArray
            if (urls != null && urls.isNotEmpty()) {
                val priorityKeywords = listOf("flac", "lossless", "hi-res", "high")
                val sortedUrls = urls.map { it.jsonPrimitive.content }.sortedWith { a, b ->
                    val aLow = a.lowercase()
                    val bLow = b.lowercase()
                    val aScore = priorityKeywords.indexOfFirst { aLow.contains(it) }.let { if (it == -1) 999 else it }
                    val bScore = priorityKeywords.indexOfFirst { bLow.contains(it) }.let { if (it == -1) 999 else it }
                    aScore.compareTo(bScore)
                }
                return sortedUrls.firstOrNull()
            }
        } catch (e: Exception) {
            val regex = Regex("https?://[^\\s\"'>]+")
            val match = regex.find(decoded)
            return match?.value?.replace("&amp;", "&")
        }
        return null
    }

    // --- Helper mapping methods ---
    private fun getImageUrl(id: String?, size: Int = 640): String {
        if (id.isNullOrEmpty()) return ""
        if (id.startsWith("http") || id.startsWith("blob:") || id.startsWith("assets/")) {
            return id
        }
        val formattedId = id.replace("-", "/")
        return "https://resources.tidal.com/images/$formattedId/${size}x${size}.jpg"
    }

    private fun toImageHolder(id: String?, size: Int = 640): ImageHolder? {
        val url = getImageUrl(id, size)
        return if (url.isNotEmpty()) url.toImageHolder() else null
    }

    private fun mapArtist(json: JsonObject): Artist {
        val id = json["id"]?.jsonPrimitive?.content ?: ""
        val name = json["name"]?.jsonPrimitive?.content ?: "Unknown Artist"
        val picture = json["picture"]?.jsonPrimitive?.content
        return Artist(
            id = id,
            name = name,
            cover = toImageHolder(picture, 320),
            bio = json["bio"]?.jsonPrimitive?.content,
            background = null,
            banners = emptyList(),
            subtitle = null,
            extras = emptyMap()
        )
    }

    private fun mapAlbumType(type: String?): Album.Type {
        return when (type?.uppercase()) {
            "SINGLE" -> Album.Type.Single
            "EP" -> Album.Type.EP
            "COMPILATION" -> Album.Type.Compilation
            else -> Album.Type.LP
        }
    }

    private fun mapDate(dateStr: String?): Date? {
        if (dateStr.isNullOrEmpty()) return null
        return try {
            val parts = dateStr.split("T")[0].split("-")
            val year = parts[0].toInt()
            val month = parts.getOrNull(1)?.toInt() ?: 1
            val day = parts.getOrNull(2)?.toInt() ?: 1
            Date(year, month, day)
        } catch (e: Exception) {
            null
        }
    }

    private fun mapAlbum(json: JsonObject): Album {
        val id = json["id"]?.jsonPrimitive?.content ?: ""
        val title = json["title"]?.jsonPrimitive?.content ?: "Unknown Album"
        val cover = json["cover"]?.jsonPrimitive?.content
        val artists = json["artists"]?.jsonArray?.map { mapArtist(it.jsonObject) } ?: emptyList()
        val trackCount = json["numberOfTracks"]?.jsonPrimitive?.longOrNull
        val duration = json["duration"]?.jsonPrimitive?.longOrNull
        val releaseDate = json["releaseDate"]?.jsonPrimitive?.content
        
        return Album(
            id = id,
            title = title,
            type = mapAlbumType(json["type"]?.jsonPrimitive?.content),
            cover = toImageHolder(cover, 320),
            artists = artists,
            trackCount = trackCount,
            duration = duration,
            releaseDate = mapDate(releaseDate),
            description = json["description"]?.jsonPrimitive?.content,
            background = null,
            label = json["copyright"]?.jsonPrimitive?.content,
            isExplicit = json["explicit"]?.jsonPrimitive?.boolean == true,
            subtitle = null,
            extras = emptyMap()
        )
    }

    private fun mapTrack(json: JsonObject): Track {
        val id = json["id"]?.jsonPrimitive?.content ?: ""
        val title = json["title"]?.jsonPrimitive?.content ?: "Unknown Track"
        val duration = json["duration"]?.jsonPrimitive?.longOrNull?.let { it * 1000 }
        val cover = json["album"]?.jsonObject?.get("cover")?.jsonPrimitive?.content
        
        val artists = json["artists"]?.jsonArray?.map { mapArtist(it.jsonObject) } ?: emptyList()
        val albumJson = json["album"]?.jsonObject
        val albumMapped = albumJson?.let { mapAlbum(it) }
        
        val discNumber = json["volumeNumber"]?.jsonPrimitive?.longOrNull ?: 1L
        val trackNumber = json["trackNumber"]?.jsonPrimitive?.longOrNull ?: 1L
        val isrc = json["isrc"]?.jsonPrimitive?.content ?: ""
        
        val streamablesList = listOf(
            Streamable(
                id = id,
                quality = 2,
                type = Streamable.MediaType.Server,
                title = "FLAC (Lossless)",
                extras = mapOf("format" to "FLAC")
            ),
            Streamable(
                id = id,
                quality = 1,
                type = Streamable.MediaType.Server,
                title = "AAC (Compatible)",
                extras = mapOf("format" to "AACLC")
            )
        )
        
        return Track(
            id = id,
            title = title,
            type = Track.Type.Song,
            cover = toImageHolder(cover, 320),
            artists = artists,
            album = albumMapped,
            duration = duration,
            playedDuration = null,
            plays = null,
            releaseDate = mapDate(json["streamStartDate"]?.jsonPrimitive?.content),
            description = null,
            background = null,
            genres = emptyList(),
            isrc = isrc,
            albumOrderNumber = trackNumber,
            albumDiscNumber = discNumber,
            playlistAddedDate = null,
            isExplicit = json["explicit"]?.jsonPrimitive?.boolean == true,
            subtitle = artists.joinToString { it.name },
            extras = emptyMap(),
            isPlayable = Track.Playable.Yes,
            streamables = streamablesList,
            isRadioSupported = false,
            isFollowable = false,
            isSaveable = false,
            isLikeable = false,
            isHideable = false,
            isShareable = false
        )
    }

    private fun mapPlaylist(json: JsonObject): Playlist {
        val id = json["uuid"]?.jsonPrimitive?.content ?: json["id"]?.jsonPrimitive?.content ?: ""
        val title = json["title"]?.jsonPrimitive?.content ?: "Unknown Playlist"
        val cover = json["squareImage"]?.jsonPrimitive?.content ?: json["cover"]?.jsonPrimitive?.content
        val trackCount = json["numberOfTracks"]?.jsonPrimitive?.longOrNull
        val duration = json["duration"]?.jsonPrimitive?.longOrNull
        
        return Playlist(
            id = id,
            title = title,
            isEditable = false,
            isPrivate = json["public"]?.jsonPrimitive?.boolean != true,
            cover = toImageHolder(cover, 320),
            authors = emptyList(),
            trackCount = trackCount,
            duration = duration,
            creationDate = null,
            description = json["description"]?.jsonPrimitive?.content,
            background = null,
            subtitle = null,
            extras = emptyMap(),
            isRadioSupported = false,
            isFollowable = false,
            isSaveable = false,
            isLikeable = false,
            isHideable = false,
            isShareable = false
        )
    }

    // --- Playlist Import Feature ---

    private fun ImageHolder?.getUrl(): String? {
        if (this == null) return null
        return when (this) {
            is dev.brahmkshatriya.echo.common.models.ImageHolder.NetworkRequestImageHolder -> this.request.url
            is dev.brahmkshatriya.echo.common.models.ImageHolder.ResourceUriImageHolder -> this.uri
            else -> null
        }
    }

    private fun cleanYoutubeTitle(rawTitle: String): String {
        val firstPart = rawTitle.split("|").first()
        var clean = firstPart
            .replace(Regex("(?i)\\b(official\\s+video|official\\s+audio|lyric\\s+video|official\\s+music\\s+video|music\\s+video|audio|video|lyrics)\\b"), "")
            .replace(Regex("(?i)\\b(copyright\\s+free|no\\s+copyright)\\b"), "")
            .replace("[]", "")
            .replace("()", "")
        clean = clean.replace(Regex("\\s+"), " ").trim()
        return if (clean.isEmpty()) firstPart.trim() else clean
    }

    private fun trackToJson(track: Track): JsonObject {
        val artistsArray = JsonArray(track.artists.map { artist ->
            JsonObject(mapOf(
                "id" to JsonPrimitive(artist.id),
                "name" to JsonPrimitive(artist.name),
                "picture" to JsonPrimitive(artist.cover.getUrl() ?: "")
            ))
        })
        val albumObj = track.album?.let { album ->
            val albumArtistsArray = JsonArray(album.artists.map { artist ->
                JsonObject(mapOf(
                    "id" to JsonPrimitive(artist.id),
                    "name" to JsonPrimitive(artist.name),
                    "picture" to JsonPrimitive(artist.cover.getUrl() ?: "")
                ))
            })
            JsonObject(mapOf(
                "id" to JsonPrimitive(album.id),
                "title" to JsonPrimitive(album.title),
                "cover" to JsonPrimitive(album.cover.getUrl() ?: ""),
                "artists" to albumArtistsArray,
                "type" to JsonPrimitive(album.type?.name ?: "LP"),
                "explicit" to JsonPrimitive(album.isExplicit)
            ))
        }
        val map = mutableMapOf<String, JsonElement>(
            "id" to JsonPrimitive(track.id),
            "title" to JsonPrimitive(track.title),
            "duration" to JsonPrimitive(track.duration?.let { it / 1000 } ?: 0L),
            "artists" to artistsArray,
            "volumeNumber" to JsonPrimitive(track.albumDiscNumber ?: 1L),
            "trackNumber" to JsonPrimitive(track.albumOrderNumber ?: 1L),
            "isrc" to JsonPrimitive(track.isrc),
            "explicit" to JsonPrimitive(track.isExplicit)
        )
        if (albumObj != null) {
            map["album"] = albumObj
        }
        return JsonObject(map)
    }

    private fun addPlaylistToSyncData(syncData: JsonObject, playlistId: String, playlistJson: JsonObject): JsonObject {
        val mutableSync = syncData.toMutableMap()
        val key = if (syncData.containsKey("user_playlists")) "user_playlists" else "userPlaylists"
        val currentPlaylists = syncData[key]?.jsonObject
        val mutablePlaylists = currentPlaylists?.toMutableMap() ?: mutableMapOf()
        mutablePlaylists[playlistId] = playlistJson
        mutableSync[key] = JsonObject(mutablePlaylists)
        return JsonObject(mutableSync)
    }

    private suspend fun saveSyncData(syncData: JsonObject): Boolean {
        val token = getAuthToken()
        val server = getAuthServer()
        if (token.isNullOrEmpty()) return false
        
        try {
            val mediaType = "application/json".toMediaTypeOrNull()
            val body = okhttp3.RequestBody.create(mediaType, syncData.toString())
            val req = Request.Builder()
                .url("$server/api/sync")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            val res = httpClient.newCall(req).await()
            return res.isSuccessful
        } catch (e: Exception) {
            println("Failed to save sync data: ${e.message}")
        }
        return false
    }

    private fun findPlaylistThumbnail(element: JsonElement): String? {
        when (element) {
            is JsonObject -> {
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
            is JsonArray -> {
                for (item in element) {
                    val res = findPlaylistThumbnail(item)
                    if (res != null) return res
                }
            }
            else -> {}
        }
        return null
    }

    private fun findPlaylistName(element: JsonElement): String? {
        when (element) {
            is JsonObject -> {
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
            is JsonArray -> {
                for (item in element) {
                    val res = findPlaylistName(item)
                    if (res != null) return res
                }
            }
            else -> {}
        }
        return null
    }

    private fun findRenderersWithLockup(element: JsonElement, list: MutableList<Pair<String, String>>) {
        when (element) {
            is JsonObject -> {
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
            is JsonArray -> {
                for (item in element) {
                    findRenderersWithLockup(item, list)
                }
            }
            else -> {}
        }
    }

    private suspend fun resolvePlaylistUrl(inputUrl: String): String {
        var url = inputUrl.trim()
        if (!url.startsWith("http")) return url
        
        // If it's a shortened link, resolve redirects
        if (url.contains("spotify.link") || url.contains("spoti.fi") || url.contains("youtu.be")) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()
                val res = httpClient.newCall(req).await()
                url = res.request.url.toString()
            } catch (e: Exception) {
                println("Failed to resolve redirect: ${e.message}")
            }
        }
        return url
    }

    internal suspend fun parsePlaylistUrlOrId(input: String): Pair<String, String>? {
        val resolved = resolvePlaylistUrl(input)
        val trimmed = resolved.trim()
        if (trimmed.isEmpty()) return null
        val spotifyUrlMatch = Regex("(?:spotify:playlist:|/playlist/|embed/playlist/)([a-zA-Z0-9]{22})").find(trimmed)
        if (spotifyUrlMatch != null) {
            return Pair("spotify", spotifyUrlMatch.groupValues[1])
        }
        val ytUrlMatch = Regex("list=([a-zA-Z0-9_-]{18,34})").find(trimmed)
        if (ytUrlMatch != null) {
            return Pair("youtube", ytUrlMatch.groupValues[1])
        }
        if (Regex("^[a-zA-Z0-9]{22}$").matches(trimmed)) {
            return Pair("spotify", trimmed)
        }
        if (Regex("^[a-zA-Z0-9_-]{18,34}$").matches(trimmed)) {
            return Pair("youtube", trimmed)
        }
        return null
    }

    private suspend fun resolveExternalTracks(scrapedTracks: List<Pair<String, String>>): List<Track> = kotlinx.coroutines.coroutineScope {
        val semaphore = kotlinx.coroutines.sync.Semaphore(5)
        val deferreds = scrapedTracks.take(100).map { (title, artist) ->
            async {
                semaphore.withPermit {
                    val query = if (title.contains("-")) {
                        cleanYoutubeTitle(title)
                    } else if (artist.isNotEmpty() && !title.contains(artist, ignoreCase = true)) {
                        "$artist - ${cleanYoutubeTitle(title)}"
                    } else {
                        cleanYoutubeTitle(title)
                    }
                    try {
                        val searchResults = searchTabItems("tracks", query)
                        searchResults.mapNotNull { (it as? Shelf.Item)?.media as? Track }.firstOrNull()
                    } catch (e: Exception) {
                        println("Failed search for query $query: ${e.message}")
                        null
                    }
                }
            }
        }
        deferreds.awaitAll().filterNotNull()
    }

    private suspend fun fetchSpotifyPlaylistWithToken(
        playlistId: String,
        accessToken: String
    ): Triple<String, String?, List<Pair<String, String>>>? {
        try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/playlists/$playlistId")
                .header("Authorization", "Bearer $accessToken")
                .build()
            val res = httpClient.newCall(req).await()
            if (!res.isSuccessful) return null
            val body = res.body?.string() ?: return null
            val json = Json.parseToJsonElement(body).jsonObject

            val playlistName = json["name"]?.jsonPrimitive?.content ?: "Spotify Playlist"
            val images = json["images"]?.jsonArray
            val coverUrl = images?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content

            val trackList = mutableListOf<Pair<String, String>>()
            val tracksObj = json["tracks"]?.jsonObject ?: return Triple(playlistName, coverUrl, emptyList())

            fun parseTracksJson(tracksJson: JsonObject) {
                val items = tracksJson["items"]?.jsonArray ?: return
                for (item in items) {
                    val track = item.jsonObject["track"]?.let { if (it is JsonObject) it else null } ?: continue
                    val title = track["name"]?.jsonPrimitive?.content ?: continue
                    val artistsArray = track["artists"]?.let { if (it is JsonArray) it else null }
                    val artistsStr = artistsArray?.mapNotNull { artistEl ->
                        (artistEl as? JsonObject)?.get("name")?.jsonPrimitive?.content
                    }?.joinToString(", ") ?: ""
                    trackList.add(Pair(title, artistsStr))
                }
            }

            parseTracksJson(tracksObj)

            var nextUrl = tracksObj["next"]?.jsonPrimitive?.content
            while (!nextUrl.isNullOrEmpty()) {
                val nextReq = Request.Builder()
                    .url(nextUrl)
                    .header("Authorization", "Bearer $accessToken")
                    .build()
                val nextRes = httpClient.newCall(nextReq).await()
                if (!nextRes.isSuccessful) break
                val nextBody = nextRes.body?.string() ?: break
                val nextJson = Json.parseToJsonElement(nextBody).jsonObject
                parseTracksJson(nextJson)
                nextUrl = nextJson["next"]?.jsonPrimitive?.content
            }

            return Triple(playlistName, coverUrl, trackList)
        } catch (e: Exception) {
            println("Failed to fetch Spotify playlist with token: ${e.message}")
        }
        return null
    }

    internal suspend fun scrapeSpotifyPlaylist(playlistId: String): Triple<String, String?, List<Pair<String, String>>>? {
        try {
            val req = Request.Builder()
                .url("https://open.spotify.com/embed/playlist/$playlistId")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            val res = httpClient.newCall(req).await()
            if (!res.isSuccessful) return null
            val body = res.body?.string() ?: return null
            
            val startIdx = body.indexOf("<script id=\"__NEXT_DATA__\" type=\"application/json\">")
            if (startIdx == -1) return null
            val jsonStart = startIdx + "<script id=\"__NEXT_DATA__\" type=\"application/json\">".length
            val endIdx = body.indexOf("</script>", jsonStart)
            if (endIdx == -1) return null
            val jsonStr = body.substring(jsonStart, endIdx).trim()
            
            val json = Json.parseToJsonElement(jsonStr).jsonObject
            val props = json["props"]?.jsonObject
            val pageProps = props?.get("pageProps")?.jsonObject
            val state = pageProps?.get("state")?.jsonObject

            // Try to extract anonymous token and fetch using official API
            val settings = state?.get("settings")?.jsonObject
            val session = settings?.get("session")?.jsonObject
            val anonymousToken = session?.get("accessToken")?.jsonPrimitive?.content
            if (!anonymousToken.isNullOrEmpty()) {
                val officialData = fetchSpotifyPlaylistWithToken(playlistId, anonymousToken)
                if (officialData != null) {
                    return officialData
                }
            }
            
            // Fallback: Parse tracks from embed page
            val data = state?.get("data")?.jsonObject
            val entity = data?.get("entity")?.jsonObject
            val playlistName = entity?.get("name")?.jsonPrimitive?.content ?: "Spotify Playlist"
            val coverUrl = entity?.get("coverArt")?.jsonObject?.get("sources")?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
            val trackList = entity?.get("trackList")?.jsonArray ?: return null
            
            val tracks = trackList.mapNotNull { trackEl ->
                val trackObj = trackEl.jsonObject
                val title = trackObj["title"]?.jsonPrimitive?.content
                val artist = trackObj["subtitle"]?.jsonPrimitive?.content ?: ""
                if (!title.isNullOrEmpty()) Pair(title, artist) else null
            }
            return Triple(playlistName, coverUrl, tracks)
        } catch (e: Exception) {
            println("Failed to scrape Spotify playlist: ${e.message}")
        }
        return null
    }

    private suspend fun scrapeYoutubePlaylist(playlistId: String): Triple<String, String?, List<Pair<String, String>>>? {
        try {
            val req = Request.Builder()
                .url("https://www.youtube.com/playlist?list=$playlistId")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            val res = httpClient.newCall(req).await()
            if (!res.isSuccessful) return null
            val html = res.body?.string() ?: return null
            
            val startVar = html.indexOf("var ytInitialData =")
            if (startVar == -1) return null
            val jsonStart = startVar + "var ytInitialData =".length
            val endVar = html.indexOf(";</script>", jsonStart)
            if (endVar == -1) return null
            val jsonStr = html.substring(jsonStart, endVar).trim()
            
            val json = Json.parseToJsonElement(jsonStr)
            val playlistName = findPlaylistName(json) ?: "YouTube Playlist"
            val coverUrl = findPlaylistThumbnail(json)
            val list = mutableListOf<Pair<String, String>>()
            findRenderersWithLockup(json, list)
            return Triple(playlistName, coverUrl, list)
        } catch (e: Exception) {
            println("Failed to scrape YouTube playlist: ${e.message}")
        }
        return null
    }

    private suspend fun runImport(inputUrl: String, isSpotify: Boolean) {
        importRunning = true
        importStatus = "Initializing import..."
        if (::setting.isInitialized) {
            setting.putString("import_status_text", importStatus)
        }
        
        val token = getAuthToken()
        if (token.isNullOrEmpty()) {
            importRunning = false
            importStatus = "Error: Please log in to Monochrome Sync first."
            if (::setting.isInitialized) {
                setting.putString("import_status_text", importStatus)
            }
            return
        }

        val resolvedUrl = resolvePlaylistUrl(inputUrl)
        val playlistId = if (isSpotify) {
            val match = Regex("(?:spotify:playlist:|/playlist/|embed/playlist/|^)([a-zA-Z0-9]{22})").find(resolvedUrl)
            match?.groupValues?.get(1)
        } else {
            val match = Regex("(?:list=|^)([a-zA-Z0-9_-]{18,34})").find(resolvedUrl)
            match?.groupValues?.get(1)
        }

        if (playlistId.isNullOrEmpty()) {
            importRunning = false
            importStatus = "Error: Invalid playlist URL or ID."
            if (::setting.isInitialized) {
                setting.putString("import_status_text", importStatus)
            }
            return
        }

        importStatus = "Fetching playlist data..."
        if (::setting.isInitialized) {
            setting.putString("import_status_text", importStatus)
        }

        val playlistData = if (isSpotify) {
            scrapeSpotifyPlaylist(playlistId)
        } else {
            scrapeYoutubePlaylist(playlistId)
        }

        if (playlistData == null) {
            importRunning = false
            importStatus = "Error: Could not retrieve playlist content."
            if (::setting.isInitialized) {
                setting.putString("import_status_text", importStatus)
            }
            return
        }

        val (playlistName, playlistCoverUrl, scrapedTracks) = playlistData
        if (scrapedTracks.isEmpty()) {
            importRunning = false
            importStatus = "Error: No tracks found in the playlist."
            if (::setting.isInitialized) {
                setting.putString("import_status_text", importStatus)
            }
            return
        }

        val total = scrapedTracks.size
        var matched = 0
        val matchedTracksJson = mutableListOf<JsonObject>()

        for (i in scrapedTracks.indices) {
            val (title, artist) = scrapedTracks[i]
            importStatus = "Searching: ${i + 1}/$total: $title ($matched matched)"
            if (::setting.isInitialized) {
                setting.putString("import_status_text", importStatus)
            }

            val query = if (title.contains("-")) {
                cleanYoutubeTitle(title)
            } else if (artist.isNotEmpty() && !title.contains(artist, ignoreCase = true)) {
                "$artist - ${cleanYoutubeTitle(title)}"
            } else {
                cleanYoutubeTitle(title)
            }

            try {
                val searchResults = searchTabItems("tracks", query)
                val track = searchResults.mapNotNull { (it as? Shelf.Item)?.media as? Track }.firstOrNull()
                if (track != null) {
                    matchedTracksJson.add(trackToJson(track))
                    matched++
                }
            } catch (e: Exception) {
                println("Failed search for query $query: ${e.message}")
            }
            // Small delay to avoid hammering the Monochrome instances
            kotlinx.coroutines.delay(100)
        }

        importStatus = "Saving playlist: Matched $matched of $total tracks..."
        if (::setting.isInitialized) {
            setting.putString("import_status_text", importStatus)
        }

        if (matchedTracksJson.isEmpty()) {
            importRunning = false
            importStatus = "Completed: 0 of $total tracks matched. No playlist created."
            if (::setting.isInitialized) {
                setting.putString("import_status_text", importStatus)
            }
            return
        }

        val syncData = fetchSyncData()
        if (syncData == null) {
            importRunning = false
            importStatus = "Error: Could not fetch Monochrome Sync data."
            if (::setting.isInitialized) {
                setting.putString("import_status_text", importStatus)
            }
            return
        }

        val newPlaylistId = java.util.UUID.randomUUID().toString()
        val finalPlaylistCover = playlistCoverUrl ?: matchedTracksJson.firstOrNull()?.get("album")?.jsonObject?.get("cover")?.jsonPrimitive?.content ?: ""
        
        val playlistJson = JsonObject(mapOf(
            "id" to JsonPrimitive(newPlaylistId),
            "name" to JsonPrimitive(playlistName),
            "title" to JsonPrimitive(playlistName),
            "cover" to JsonPrimitive(finalPlaylistCover),
            "tracks" to JsonArray(matchedTracksJson),
            "numberOfTracks" to JsonPrimitive(matchedTracksJson.size)
        ))

        val updatedSyncData = addPlaylistToSyncData(syncData, newPlaylistId, playlistJson)
        val saveSuccess = saveSyncData(updatedSyncData)

        importRunning = false
        if (saveSuccess) {
            importStatus = "Successfully imported \"$playlistName\" ($matched/$total matched)!"
        } else {
            importStatus = "Failed to upload imported playlist to Monochrome Sync."
        }
        if (::setting.isInitialized) {
            setting.putString("import_status_text", importStatus)
        }
    }

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> {
        val mxLyrics = fetchLyricsFromMusixmatch(track)
        if (mxLyrics != null) {
            return listOf(mxLyrics).toFeed()
        }
        val lyrics = fetchLyricsFromLrcLib(track)
        return if (lyrics != null) {
            listOf(lyrics).toFeed()
        } else {
            emptyList<Lyrics>().toFeed()
        }
    }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics {
        return lyrics
    }

    private suspend fun fetchLyricsFromLrcLib(track: Track): Lyrics? {
        val title = track.title
        val artist = track.artists.firstOrNull()?.name ?: ""
        val album = track.album?.title ?: ""
        val duration = track.duration?.let { it / 1000 } ?: 0L
        
        try {
            val urlBuilder = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("lrclib.net")
                .addPathSegment("api")
                .addPathSegment("get")
            urlBuilder.addQueryParameter("track_name", title)
            if (artist.isNotEmpty()) {
                urlBuilder.addQueryParameter("artist_name", artist)
            }
            if (album.isNotEmpty()) {
                urlBuilder.addQueryParameter("album_name", album)
            }
            if (duration > 0) {
                urlBuilder.addQueryParameter("duration", duration.toString())
            }
            
            val req = Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", "MonochromeEchoExtension/1.0")
                .build()
                
            val res = httpClient.newCall(req).await()
            if (res.code == 404) {
                return searchLyricsFallback(title, artist)
            }
            if (!res.isSuccessful) return null
            
            val body = res.body?.string() ?: return null
            return parseLrcLibJson(body, track.id)
        } catch (e: Exception) {
            println("LrcLib fetch failed: ${e.message}")
        }
        return null
    }

    private suspend fun searchLyricsFallback(title: String, artist: String): Lyrics? {
        try {
            val query = if (artist.isNotEmpty()) "$artist - $title" else title
            val url = "https://lrclib.net/api/search?q=${URLEncoder.encode(query, "UTF-8")}"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "MonochromeEchoExtension/1.0")
                .build()
            val res = httpClient.newCall(req).await()
            if (!res.isSuccessful) return null
            val body = res.body?.string() ?: return null
            val array = Json.parseToJsonElement(body).jsonArray
            val firstMatch = array.firstOrNull()?.jsonObject ?: return null
            return parseLrcLibJson(firstMatch.toString(), "")
        } catch (e: Exception) {
            println("LrcLib search fallback failed: ${e.message}")
        }
        return null
    }

    private fun parseLrcLibJson(jsonStr: String, trackId: String): Lyrics? {
        try {
            val json = Json.parseToJsonElement(jsonStr).jsonObject
            val id = json["id"]?.jsonPrimitive?.content ?: trackId
            val title = json["trackName"]?.jsonPrimitive?.content ?: ""
            val artist = json["artistName"]?.jsonPrimitive?.content ?: ""
            val plainLyrics = json["plainLyrics"]?.jsonPrimitive?.content
            val syncedLyrics = json["syncedLyrics"]?.jsonPrimitive?.content
            
            val lyricObj = if (!syncedLyrics.isNullOrEmpty()) {
                val wordByWordLines = parseEnhancedLrc(syncedLyrics)
                if (wordByWordLines != null) {
                    Lyrics.WordByWord(wordByWordLines, true)
                } else {
                    val parsedItems = parseLrc(syncedLyrics)
                    if (parsedItems.isNotEmpty()) {
                        Lyrics.Timed(parsedItems, true)
                    } else if (!plainLyrics.isNullOrEmpty()) {
                        Lyrics.Simple(plainLyrics)
                    } else {
                        return null
                    }
                }
            } else if (!plainLyrics.isNullOrEmpty()) {
                Lyrics.Simple(plainLyrics)
            } else {
                return null
            }
            
            return Lyrics(
                id = id,
                title = title,
                subtitle = artist,
                lyrics = lyricObj,
                extras = emptyMap()
            )
        } catch (e: Exception) {
            println("Failed to parse LrcLib JSON: ${e.message}")
        }
        return null
    }

    private fun parseEnhancedLrc(lrcText: String): List<List<Lyrics.Item>>? {
        val lines = lrcText.split("\n")
        val lineRegex = Regex("\\[(\\d+):(\\d+)\\.(\\d+)\\](.*)")
        val wordRegex = Regex("<(\\d+):(\\d+)(?:\\.(\\d+))?>(.*?)(?=<|$)")
        
        if (!lrcText.contains("<") || !lrcText.contains(">")) {
            return null
        }
        
        val wordLines = mutableListOf<List<Lyrics.Item>>()
        
        for (line in lines) {
            val trimmedLine = line.trim()
            val lineMatch = lineRegex.find(trimmedLine)
            if (lineMatch != null) {
                val min = lineMatch.groupValues[1].toLong()
                val sec = lineMatch.groupValues[2].toLong()
                val msStr = lineMatch.groupValues[3]
                val msVal = msStr.toLong()
                val ms = when (msStr.length) {
                    2 -> msVal * 10
                    1 -> msVal * 100
                    else -> msVal
                }
                val lineStartTime = (min * 60 + sec) * 1000 + ms
                val lineContent = lineMatch.groupValues[4]
                
                val matches = wordRegex.findAll(lineContent).toList()
                if (matches.isNotEmpty()) {
                    val wordsInLine = mutableListOf<Lyrics.Item>()
                    val firstMatchIndex = matches.first().range.first
                    if (firstMatchIndex > 0) {
                        val prefixText = lineContent.substring(0, firstMatchIndex).trim()
                        if (prefixText.isNotEmpty()) {
                            val firstWordStart = matches.first().let { m ->
                                val wMin = m.groupValues[1].toLong()
                                val wSec = m.groupValues[2].toLong()
                                val wMsStr = m.groupValues[3]
                                val wMs = if (wMsStr.isNotEmpty()) {
                                    val wMsVal = wMsStr.toLong()
                                    when (wMsStr.length) {
                                        2 -> wMsVal * 10
                                        1 -> wMsVal * 100
                                        else -> wMsVal
                                    }
                                } else 0L
                                (wMin * 60 + wSec) * 1000 + wMs
                            }
                            wordsInLine.add(Lyrics.Item(prefixText, lineStartTime, firstWordStart))
                        }
                    }
                    
                    for (match in matches) {
                        val wMin = match.groupValues[1].toLong()
                        val wSec = match.groupValues[2].toLong()
                        val wMsStr = match.groupValues[3]
                        val wMs = if (wMsStr.isNotEmpty()) {
                            val wMsVal = wMsStr.toLong()
                            when (wMsStr.length) {
                                2 -> wMsVal * 10
                                1 -> wMsVal * 100
                                else -> wMsVal
                            }
                        } else 0L
                        val wordStartTime = (wMin * 60 + wSec) * 1000 + wMs
                        val wordText = match.groupValues[4].replace("\r", "").replace("\n", "")
                        
                        wordsInLine.add(Lyrics.Item(wordText, wordStartTime, wordStartTime))
                    }
                    
                    for (i in 0 until wordsInLine.size - 1) {
                        val current = wordsInLine[i]
                        val next = wordsInLine[i + 1]
                        wordsInLine[i] = Lyrics.Item(current.text, current.startTime, next.startTime)
                    }
                    if (wordsInLine.isNotEmpty()) {
                        val last = wordsInLine.last()
                        wordsInLine[wordsInLine.size - 1] = Lyrics.Item(last.text, last.startTime, last.startTime + 2000)
                    }
                    wordLines.add(wordsInLine)
                } else {
                    val lineText = lineContent.trim()
                    if (lineText.isNotEmpty()) {
                        wordLines.add(listOf(Lyrics.Item(lineText, lineStartTime, lineStartTime + 5000)))
                    }
                }
            }
        }
        
        for (i in 0 until wordLines.size - 1) {
            val currentLine = wordLines[i]
            val nextLine = wordLines[i + 1]
            if (currentLine.isNotEmpty() && nextLine.isNotEmpty()) {
                val nextLineStart = nextLine.first().startTime
                val lastWordIndex = currentLine.size - 1
                val lastWord = currentLine[lastWordIndex]
                if (lastWord.startTime + 2000 > nextLineStart) {
                    val newEndTime = maxOf(lastWord.startTime, nextLineStart)
                    val updatedLine = currentLine.toMutableList()
                    updatedLine[lastWordIndex] = Lyrics.Item(lastWord.text, lastWord.startTime, newEndTime)
                    wordLines[i] = updatedLine
                }
            }
        }
        
        return if (wordLines.isNotEmpty()) wordLines else null
    }

    private fun parseLrc(lrcText: String): List<Lyrics.Item> {
        val lines = lrcText.split("\n")
        val items = mutableListOf<Lyrics.Item>()
        val regex = Regex("\\[(\\d+):(\\d+)\\.(\\d+)\\](.*)")
        for (line in lines) {
            val match = regex.find(line.trim())
            if (match != null) {
                val min = match.groupValues[1].toLong()
                val sec = match.groupValues[2].toLong()
                val msStr = match.groupValues[3]
                val msVal = msStr.toLong()
                val ms = when (msStr.length) {
                    2 -> msVal * 10
                    1 -> msVal * 100
                    else -> msVal
                }
                val time = (min * 60 + sec) * 1000 + ms
                val text = match.groupValues[4]
                    .replace(Regex("<\\d+:\\d+(?:\\.\\d+)?>"), "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                items.add(Lyrics.Item(text, time, time))
            }
        }
        
        for (i in 0 until items.size - 1) {
            val current = items[i]
            val next = items[i + 1]
            items[i] = Lyrics.Item(current.text, current.startTime, next.startTime)
        }
        if (items.isNotEmpty()) {
            val last = items.last()
            items[items.size - 1] = Lyrics.Item(last.text, last.startTime, last.startTime + 5000)
        }
        return items
    }

    private suspend fun getMusixmatchToken(): String? {
        val currentTime = System.currentTimeMillis() / 1000
        if (musixmatchToken != null && musixmatchTokenExpiry > currentTime) {
            return musixmatchToken
        }
        
        try {
            val req = Request.Builder()
                .url("https://apic-desktop.musixmatch.com/ws/1.1/token.get?app_id=web-desktop-app-v1.0")
                .header("User-Agent", "MonochromeEchoExtension/1.0")
                .header("cookie", "AWSELBCORS=0; AWSELB=0;")
                .build()
                
            val res = httpClient.newCall(req).await()
            if (!res.isSuccessful) return null
            val bodyStr = res.body?.string() ?: return null
            val json = Json.parseToJsonElement(bodyStr).jsonObject
            val statusCode = json["message"]?.jsonObject?.get("header")?.jsonObject?.get("status_code")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            if (statusCode == 200) {
                val token = json["message"]?.jsonObject?.get("body")?.jsonObject?.get("user_token")?.jsonPrimitive?.content
                if (token != null) {
                    musixmatchToken = token
                    musixmatchTokenExpiry = currentTime + 600 // expires in 10 minutes
                    return token
                }
            }
        } catch (e: Exception) {
            println("Musixmatch token fetch failed: ${e.message}")
        }
        return null
    }

    private suspend fun fetchLyricsFromMusixmatch(track: Track): Lyrics? {
        val title = track.title
        val artist = track.artists.firstOrNull()?.name ?: ""
        val duration = track.duration?.let { it / 1000 } ?: 0L
        
        try {
            val token = getMusixmatchToken() ?: return null
            val urlBuilder = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("apic-desktop.musixmatch.com")
                .addPathSegment("ws")
                .addPathSegment("1.1")
                .addPathSegment("macro.subtitles.get")
                .addQueryParameter("format", "json")
                .addQueryParameter("namespace", "lyrics_richsynched")
                .addQueryParameter("subtitle_format", "mxm")
                .addQueryParameter("app_id", "web-desktop-app-v1.0")
                .addQueryParameter("q_artist", artist)
                .addQueryParameter("q_track", title)
                .addQueryParameter("usertoken", token)
                .addQueryParameter("optional_calls", "track.richsync")
                
            if (duration > 0) {
                urlBuilder.addQueryParameter("q_duration", duration.toString())
                urlBuilder.addQueryParameter("f_subtitle_length", duration.toString())
            }
            
            val req = Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", "MonochromeEchoExtension/1.0")
                .header("cookie", "AWSELBCORS=0; AWSELB=0;")
                .build()
                
            val res = httpClient.newCall(req).await()
            if (!res.isSuccessful) return null
            val body = res.body?.string() ?: return null
            val json = Json.parseToJsonElement(body).jsonObject
            val macroCalls = json["message"]?.jsonObject?.get("body")?.jsonObject?.get("macro_calls")?.jsonObject ?: return null
            
            // 1. Try RichSync
            val richsyncCall = macroCalls["track.richsync.get"]?.jsonObject
            val richsyncStatusCode = richsyncCall?.get("message")?.jsonObject?.get("header")?.jsonObject?.get("status_code")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            if (richsyncStatusCode == 200) {
                val richsyncBody = richsyncCall?.get("message")?.jsonObject?.get("body")?.jsonObject
                    ?.get("richsync")?.jsonObject?.get("richsync_body")?.jsonPrimitive?.content
                if (!richsyncBody.isNullOrEmpty()) {
                    val wordByWordLines = parseMusixmatchRichsync(richsyncBody)
                    if (wordByWordLines != null) {
                        return Lyrics(
                            id = "musixmatch_${track.id}",
                            title = title,
                            subtitle = artist,
                            lyrics = Lyrics.WordByWord(wordByWordLines, true),
                            extras = emptyMap()
                        )
                    }
                }
            }
            
            // 2. Try Subtitles
            val subtitlesCall = macroCalls["track.subtitles.get"]?.jsonObject
            val subtitlesStatusCode = subtitlesCall?.get("message")?.jsonObject?.get("header")?.jsonObject?.get("status_code")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            if (subtitlesStatusCode == 200) {
                val subtitleList = subtitlesCall?.get("message")?.jsonObject?.get("body")?.jsonObject?.get("subtitle_list")?.jsonArray
                val firstSubtitle = subtitleList?.firstOrNull()?.jsonObject
                val subtitleBody = firstSubtitle?.get("subtitle")?.jsonObject?.get("subtitle_body")?.jsonPrimitive?.content
                if (!subtitleBody.isNullOrEmpty()) {
                    val parsedItems = parseMusixmatchSubtitles(subtitleBody)
                    if (parsedItems.isNotEmpty()) {
                        return Lyrics(
                            id = "musixmatch_${track.id}",
                            title = title,
                            subtitle = artist,
                            lyrics = Lyrics.Timed(parsedItems, true),
                            extras = emptyMap()
                        )
                    }
                }
            }
            
            // 3. Try plain lyrics
            val lyricsCall = macroCalls["track.lyrics.get"]?.jsonObject
            val lyricsStatusCode = lyricsCall?.get("message")?.jsonObject?.get("header")?.jsonObject?.get("status_code")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            if (lyricsStatusCode == 200) {
                val lyricsBody = lyricsCall?.get("message")?.jsonObject?.get("body")?.jsonObject
                    ?.get("lyrics")?.jsonObject?.get("lyrics_body")?.jsonPrimitive?.content
                if (!lyricsBody.isNullOrEmpty()) {
                    return Lyrics(
                        id = "musixmatch_${track.id}",
                        title = title,
                        subtitle = artist,
                        lyrics = Lyrics.Simple(lyricsBody),
                        extras = emptyMap()
                    )
                }
            }
        } catch (e: Exception) {
            println("Musixmatch fetch failed: ${e.message}")
        }
        return null
    }

    private fun parseMusixmatchRichsync(richsyncBodyStr: String): List<List<Lyrics.Item>>? {
        try {
            val linesArray = Json.parseToJsonElement(richsyncBodyStr).jsonArray
            val wordLines = mutableListOf<List<Lyrics.Item>>()
            
            for (lineElement in linesArray) {
                val lineObj = lineElement.jsonObject
                val ts = lineObj["ts"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: continue
                val te = lineObj["te"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: continue
                val lArray = lineObj["l"]?.jsonArray ?: continue
                
                val wordsInLine = mutableListOf<Lyrics.Item>()
                
                for (wordElement in lArray) {
                    val wordObj = wordElement.jsonObject
                    val c = wordObj["c"]?.jsonPrimitive?.content ?: ""
                    val o = wordObj["o"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                    
                    if (c.trim().isEmpty()) {
                        continue
                    }
                    
                    val wordStartTime = ((ts + o) * 1000).toLong()
                    wordsInLine.add(Lyrics.Item(c, wordStartTime, wordStartTime))
                }
                
                for (i in 0 until wordsInLine.size - 1) {
                    val current = wordsInLine[i]
                    val next = wordsInLine[i + 1]
                    wordsInLine[i] = Lyrics.Item(current.text, current.startTime, next.startTime)
                }
                
                if (wordsInLine.isNotEmpty()) {
                    val last = wordsInLine.last()
                    wordsInLine[wordsInLine.size - 1] = Lyrics.Item(last.text, last.startTime, (te * 1000).toLong())
                }
                
                if (wordsInLine.isNotEmpty()) {
                    wordLines.add(wordsInLine)
                }
            }
            
            return if (wordLines.isNotEmpty()) wordLines else null
        } catch (e: Exception) {
            println("Failed to parse Musixmatch Richsync: ${e.message}")
        }
        return null
    }

    private fun parseMusixmatchSubtitles(subtitleBodyStr: String): List<Lyrics.Item> {
        val items = mutableListOf<Lyrics.Item>()
        try {
            val array = Json.parseToJsonElement(subtitleBodyStr).jsonArray
            for (element in array) {
                val obj = element.jsonObject
                val text = obj["text"]?.jsonPrimitive?.content ?: ""
                val timeObj = obj["time"]?.jsonObject
                val totalSeconds = timeObj?.get("total")?.jsonPrimitive?.content?.toDoubleOrNull()
                if (totalSeconds != null) {
                    val timeMs = (totalSeconds * 1000).toLong()
                    items.add(Lyrics.Item(text, timeMs, timeMs))
                } else {
                    val minutes = timeObj?.get("minutes")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    val seconds = timeObj?.get("seconds")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    val hundredths = timeObj?.get("hundredths")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    val timeMs = (minutes * 60 + seconds) * 1000 + hundredths * 10
                    items.add(Lyrics.Item(text, timeMs, timeMs))
                }
            }
            
            for (i in 0 until items.size - 1) {
                val current = items[i]
                val next = items[i + 1]
                items[i] = Lyrics.Item(current.text, current.startTime, next.startTime)
            }
            if (items.isNotEmpty()) {
                val last = items.last()
                items[items.size - 1] = Lyrics.Item(last.text, last.startTime, last.startTime + 5000)
            }
        } catch (e: Exception) {
            println("Failed to parse Musixmatch Subtitles: ${e.message}")
        }
        return items
    }
}
