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
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
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

    private fun testIn(title: String, block: suspend CoroutineScope.() -> Unit) = runBlocking {
        println("\n-- $title --")
        block.invoke(this)
        println("\n")
    }
}