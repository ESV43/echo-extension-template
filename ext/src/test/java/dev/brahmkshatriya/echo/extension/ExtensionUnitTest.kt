package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.models.Lyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.system.measureTimeMillis

@OptIn(DelicateCoroutinesApi::class)
@ExperimentalCoroutinesApi
class ExtensionUnitTest {
    private val extension: ExtensionClient = MonochromeExtension()
    private val searchQuery = "Skrillex"
    private val user = User("", "Test User")

    @Test
    fun testEmptySearch() = testIn("Testing Empty Search") {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        val search = extension.loadSearchFeed("").pagedDataOfFirst().loadPage(null).data
        search.forEach {
            println(it)
        }
    }

    @Test
    fun testSearch() = testIn("Testing Search") {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        println("Searching  : $searchQuery")
        val feed = extension.loadSearchFeed(searchQuery)
        println("Tabs : ${feed.tabs}")
        feed.pagedDataOfFirst().loadPage(null).data.forEach {
            println(it)
        }
    }

    @Test
    fun testHomeFeed() = testIn("Testing Home Feed") {
        if (extension !is HomeFeedClient) {
            println("HomeFeedClient is not implemented by this extension")
            return@testIn
        }
        val feed = extension.loadHomeFeed()
        println("Tabs : ${feed.tabs}")
        feed.pagedDataOfFirst().loadPage(null).data.forEach {
            println(it)
        }
    }

    private suspend fun searchTrack(q: String? = null): Track {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        val query = q ?: searchQuery
        println("Searching : $query")
        val track = extension.loadSearchFeed(query).pagedDataOfFirst().loadAll()
            .firstNotNullOfOrNull {
                when (it) {
                    is Shelf.Item -> it.media as? Track
                    is Shelf.Lists.Tracks -> it.list.firstOrNull()
                    is Shelf.Lists.Items -> it.list.firstOrNull() as? Track
                    else -> null
                }
            }
        return track ?: error("Track not found, try a different search query")
    }

    @Test
    fun testTrackGet() = testIn("Testing Track Get") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val search = searchTrack()
        measureTimeMillis {
            val track = extension.loadTrack(search, false)
            println(track)
        }.also { println("time : $it") }
    }

    @Test
    fun testTrackStream() = testIn("Testing Track Stream") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val search = searchTrack()
        measureTimeMillis {
            val track = extension.loadTrack(search, false)
            val streamable = track.streamables.firstOrNull() ?: error("Track does not have streamables")
            val stream = extension.loadStreamableMedia(streamable, false)
            println("Resolved stream: $stream")
        }.also { println("time : $it") }
    }

    @Test
    fun testTrackRadio() = testIn("Testing Track Radio") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        if (extension !is RadioClient) {
            println("RadioClient is not implemented by this extension")
            return@testIn
        }
        val track = extension.loadTrack(searchTrack(), false)
        val radio = extension.radio(track, null)
        val radioTracks = extension.loadTracks(radio).loadAll()
        radioTracks.forEach {
            println(it)
        }
    }

    @Test
    fun testTrackLyrics() = testIn("Testing Track Lyrics Retrieval") {
        if (extension !is dev.brahmkshatriya.echo.common.clients.LyricsClient) {
            error("LyricsClient is not implemented")
        }
        val search = searchTrack("Scary Monsters and Nice Sprites")
        val lyricsFeed = extension.searchTrackLyrics("", search)
        val lyricsList = lyricsFeed.pagedDataOfFirst().loadPage(null).data
        println("Lyrics Feed size: ${lyricsList.size}")
        assert(lyricsList.isNotEmpty())
        val firstLyrics = lyricsList.first()
        println("Lyrics Title: ${firstLyrics.title}")
        println("Lyrics Subtitle: ${firstLyrics.subtitle}")
        val lyricObj = firstLyrics.lyrics
        println("Lyrics Type: ${lyricObj?.let { it::class.java.simpleName } ?: "Null"}")
        when (lyricObj) {
            is Lyrics.Simple -> {
                println("Plain Text Lyrics Snippet:\n${lyricObj.text.take(300)}...")
                assert(lyricObj.text.isNotEmpty())
            }
            is Lyrics.Timed -> {
                println("Timed Lyrics count: ${lyricObj.list.size}")
                lyricObj.list.take(5).forEach { item ->
                    println("  [${item.startTime} -> ${item.endTime}] ${item.text}")
                }
                assert(lyricObj.list.isNotEmpty())
            }
            else -> {
                println("Unknown lyrics object type")
            }
        }
    }

    @Test
    fun testTrackShelves() = testIn("Testing Track Shelves") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val track = extension.loadTrack(searchTrack(), false)
        val feed = extension.loadFeed(track)
        val mediaItems = feed?.pagedDataOfFirst()?.loadPage(null)?.data
        if (mediaItems.isNullOrEmpty()) println("No shelves found for track")
        else mediaItems.forEach {
            println(it)
        }
    }

    @Test
    fun testAlbumGet() = testIn("Testing Album Get") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val small = extension.loadTrack(searchTrack(), false).album ?: error("Track has no album")
        if (extension !is AlbumClient) error("AlbumClient is not implemented")
        val album = extension.loadAlbum(small)
        println(album)
        val feed = extension.loadTracks(album)
        val tracks = feed?.pagedDataOfFirst()?.loadPage(null)?.data
        if (tracks.isNullOrEmpty()) println("No tracks found for album")
        else tracks.forEach {
            println(it)
        }
    }

    @Test
    fun testArtistGet() = testIn("Testing Artist Get") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val track = extension.loadTrack(searchTrack(), false)
        val smallArtist = track.artists.firstOrNull() ?: error("Track has no artist")
        if (extension !is ArtistClient) error("ArtistClient is not implemented")
        val artist = extension.loadArtist(smallArtist)
        println(artist)
        val feed = extension.loadFeed(artist)
        val shelves = feed.pagedDataOfFirst().loadPage(null).data
        if (shelves.isEmpty()) println("No shelves found for artist")
        else shelves.forEach {
            println(it)
        }
    }

    // Test Setup
    private val mainThreadSurrogate = newSingleThreadContext("UI thread")

    @Before
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
        extension.setSettings(MockedSettings())
        runBlocking {
            extension.onInitialize()
            extension.onExtensionSelected()
            if (extension is LoginClient) extension.setLoginUser(user)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain() // reset the main dispatcher to the original Main dispatcher
        mainThreadSurrogate.close()
    }

    @Test
    fun testPlaylistImportSettings() = testIn("Testing Playlist Import Settings") {
        val settings = extension.getSettingItems()
        val keys = settings.flatMap { 
            if (it is dev.brahmkshatriya.echo.common.settings.SettingCategory) {
                it.items.map { item -> item.key }
            } else {
                listOf(it.key)
            }
        }
        println("Settings Keys: $keys")
        assert(keys.contains("spotify_playlist_url"))
        assert(keys.contains("youtube_playlist_url"))
        assert(keys.contains("import_spotify_button"))
        assert(keys.contains("import_youtube_button"))
        assert(keys.contains("import_status_text"))
    }

    @Test
    fun testLibraryProgressBanner() = testIn("Testing Library Progress Banner") {
        if (extension !is dev.brahmkshatriya.echo.common.clients.LibraryFeedClient) {
            error("LibraryFeedClient is not implemented")
        }
        
        val initialFeed = extension.loadLibraryFeed().pagedDataOfFirst().loadPage(null).data
        val initialProgressShelves = initialFeed.filter { it.id == "library_import_progress" }
        println("Initial progress shelves: ${initialProgressShelves.size}")
        assert(initialProgressShelves.isEmpty())
        
        val settingsList = extension.getSettingItems()
        val category = settingsList.find { it.key == "import_playlists_category" } as? dev.brahmkshatriya.echo.common.settings.SettingCategory
        val spotifyBtn = category?.items?.find { it.key == "import_spotify_button" } as? dev.brahmkshatriya.echo.common.settings.SettingOnClick
        
        val settingsField = extension.javaClass.getDeclaredField("setting")
        settingsField.isAccessible = true
        val settingsObj = settingsField.get(extension) as dev.brahmkshatriya.echo.common.settings.Settings
        settingsObj.putString("spotify_playlist_url", "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        
        // Invoke onClick button trigger
        spotifyBtn?.onClick?.invoke()
        
        // Wait for background coroutine to complete
        kotlinx.coroutines.delay(300)
        
        val statusText = settingsObj.getString("import_status_text")
        println("Import Status: $statusText")
        assert(statusText?.startsWith("Error: Please log in") == true)
    }

    @Test
    fun testParseEnhancedLrc() = testIn("Testing Parse Enhanced LRC") {
        val ext = extension as MonochromeExtension
        val lrc = """
            [00:12.00] <00:12.00> I <00:12.30> see <00:12.60> trees <00:12.90> of <00:13.20> green
            [00:15.00] <00:15.00> Red <00:15.50> roses <00:16.00> too
        """.trimIndent()
        
        val method = ext.javaClass.getDeclaredMethod("parseEnhancedLrc", String::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(ext, lrc) as? List<List<Lyrics.Item>>
        
        assert(result != null)
        assert(result!!.size == 2)
        
        val line1 = result[0]
        assert(line1.size == 5)
        assert(line1[0].text == " I ")
        assert(line1[0].startTime == 12000L)
        assert(line1[0].endTime == 12300L)
        assert(line1[4].text == " green")
        assert(line1[4].startTime == 13200L)
        assert(line1[4].endTime == 15000L)
        
        val line2 = result[1]
        assert(line2.size == 3)
        assert(line2[0].text == " Red ")
        assert(line2[0].startTime == 15000L)
        assert(line2[0].endTime == 15500L)
        assert(line2[2].text == " too")
        assert(line2[2].startTime == 16000L)
        assert(line2[2].endTime == 18000L)
        
        val parseLrcMethod = ext.javaClass.getDeclaredMethod("parseLrc", String::class.java)
        parseLrcMethod.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val lrcResult = parseLrcMethod.invoke(ext, lrc) as List<Lyrics.Item>
        assert(lrcResult.size == 2)
        assert(lrcResult[0].text == "I see trees of green")
        assert(lrcResult[1].text == "Red roses too")
    }

    @Test
    fun testMusixmatchLyrics() = testIn("Testing Musixmatch Lyrics Retrieval") {
        val ext = extension as MonochromeExtension
        val track = Track(
            id = "test_mx_track",
            title = "Beautiful Things",
            artists = listOf(
                Artist(
                    id = "test_artist",
                    name = "Benson Boone",
                    cover = null,
                    bio = null,
                    background = null,
                    banners = emptyList(),
                    subtitle = null,
                    extras = emptyMap()
                )
            ),
            duration = 180000L
        )
        
        val lyrics = ext.searchTrackLyrics("", track).pagedDataOfFirst().loadPage(null).data
        println("Fetched lyrics size: ${lyrics.size}")
        assert(lyrics.isNotEmpty())
        val lyric = lyrics.first()
        println("Lyrics Provider: ${lyric.id}")
        println("Lyrics Title: ${lyric.title}")
        val lyricObj = lyric.lyrics
        println("Lyrics Type: ${lyricObj?.let { it::class.java.simpleName }}")
        
        assert(lyricObj != null)
        when (lyricObj) {
            is Lyrics.WordByWord -> {
                println("RichSync lines count: ${lyricObj.list.size}")
                lyricObj.list.take(3).forEach { line ->
                    println("  Line:")
                    line.forEach { word ->
                        println("    [${word.startTime} -> ${word.endTime}] ${word.text}")
                    }
                }
                assert(lyricObj.list.isNotEmpty())
            }
            is Lyrics.Timed -> {
                println("Timed lines count: ${lyricObj.list.size}")
                lyricObj.list.take(5).forEach { item ->
                    println("  [${item.startTime} -> ${item.endTime}] ${item.text}")
                }
                assert(lyricObj.list.isNotEmpty())
            }
            is Lyrics.Simple -> {
                println("Plain lyrics snippet:\n${lyricObj.text.take(200)}...")
                assert(lyricObj.text.isNotEmpty())
            }
            else -> {
                println("Unknown lyrics object type")
            }
        }
    }

    @Test
    fun testParsePlaylistUrlOrId() = testIn("Testing Playlist URL or ID parsing") {
        val ext = extension as MonochromeExtension

        // Spotify
        assert(ext.parsePlaylistUrlOrId("37i9dQZF1DXcBWIGoYBM5M") == Pair("spotify", "37i9dQZF1DXcBWIGoYBM5M"))
        assert(ext.parsePlaylistUrlOrId("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M") == Pair("spotify", "37i9dQZF1DXcBWIGoYBM5M"))
        assert(ext.parsePlaylistUrlOrId("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M") == Pair("spotify", "37i9dQZF1DXcBWIGoYBM5M"))

        // YouTube
        assert(ext.parsePlaylistUrlOrId("PLRBp0Fe2GpgmsW46rJyudVFlY6IYjFBIK") == Pair("youtube", "PLRBp0Fe2GpgmsW46rJyudVFlY6IYjFBIK"))
        assert(ext.parsePlaylistUrlOrId("https://www.youtube.com/playlist?list=PLRBp0Fe2GpgmsW46rJyudVFlY6IYjFBIK") == Pair("youtube", "PLRBp0Fe2GpgmsW46rJyudVFlY6IYjFBIK"))
        assert(ext.parsePlaylistUrlOrId("   PLRBp0Fe2GpgmsW46rJyudVFlY6IYjFBIK   ") == Pair("youtube", "PLRBp0Fe2GpgmsW46rJyudVFlY6IYjFBIK"))

        // Null cases
        assert(ext.parsePlaylistUrlOrId("") == null)
        assert(ext.parsePlaylistUrlOrId("invalid") == null)
    }

    @Test
    fun testScrapeSpotifyPlaylist() = testIn("Testing Scrape Spotify Playlist") {
        val ext = extension as MonochromeExtension
        val playlistId = "37i9dQZF1DXcBWIGoYBM5M" // Today's Top Hits
        val res = ext.scrapeSpotifyPlaylist(playlistId)
        println("Result: $res")
        assert(res != null)
        val (name, cover, tracks) = res!!
        println("Playlist Name: $name")
        println("Playlist Cover: $cover")
        println("Tracks Count: ${tracks.size}")
        assert(tracks.isNotEmpty())
    }

    @Test
    fun testHomeFeedWithCustomFeaturedPlaylists() = testIn("Testing Home Feed with Custom Featured Playlists") {
        if (extension !is HomeFeedClient) return@testIn
        
        val settingsField = extension.javaClass.getDeclaredField("setting")
        settingsField.isAccessible = true
        val settingsObj = settingsField.get(extension) as dev.brahmkshatriya.echo.common.settings.Settings

        // Set one Spotify and one YouTube playlist URL
        settingsObj.putString("featured_playlist_urls", "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M,PLRBp0Fe2GpgmsW46rJyudVFlY6IYjFBIK")

        val feed = extension.loadHomeFeed()
        val shelves = feed.pagedDataOfFirst().loadPage(null).data
        
        val featuredExternalShelves = shelves.filter { it.id == "home_featured_external_playlists" }
        println("Featured external shelves: ${featuredExternalShelves.size}")
        if (featuredExternalShelves.isNotEmpty()) {
            val shelfList = (featuredExternalShelves.first() as Shelf.Lists.Items).list
            println("Custom playlists in shelf: ${shelfList.size}")
            shelfList.forEach { 
                val pl = it as Playlist
                println("  Playlist Name: ${pl.title} | ID: ${pl.id} | Track Count: ${pl.trackCount}")
            }
            assert(shelfList.isNotEmpty())
        }
    }

    private fun testIn(title: String, block: suspend CoroutineScope.() -> Unit) = runBlocking {
        println("\n-- $title --")
        block.invoke(this)
        println("\n")
    }
}