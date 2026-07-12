package app.nogarbo.leflac.service

import android.content.ComponentName
import android.content.Intent
import android.media.browse.MediaBrowser as PlatformMediaBrowser
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the Media3 and platform browser connection paths used by Android Auto. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class AndroidAutoServiceTest {

    @Test
    fun serviceExposesBrowsableConfiguredLibraryWithoutActivity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val expectedScheme = app.nogarbo.leflac.data.AndroidAutoVisualScheme.read(context).name

        withContext(Dispatchers.Main) {
            val token = SessionToken(context, ComponentName(context, AudioService::class.java))
            val browser = MediaBrowser.Builder(context, token).buildAsync().await()
            try {
                assertFalse(browser.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE))
                val rootResult = browser.getLibraryRoot(null).await()
                assertEquals(LibraryResult.RESULT_SUCCESS, rootResult.resultCode)
                val root = rootResult.value
                assertNotNull(root)
                assertEquals(AndroidAutoLibrary.ROOT_ID, root?.mediaId)
                assertEquals(true, root?.mediaMetadata?.isBrowsable)
                assertTrue(root?.mediaMetadata?.subtitle.toString().contains(expectedScheme))

                val childrenResult = browser.getChildren(AndroidAutoLibrary.ROOT_ID, 0, 10, null).await()
                assertEquals(LibraryResult.RESULT_SUCCESS, childrenResult.resultCode)
                assertTrue(childrenResult.value?.isNotEmpty() == true)
            } finally {
                browser.release()
            }
        }
    }

    @Test
    fun platformMediaBrowserBridgeExposesRootAndChildren() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        withTimeout(10_000L) {
            withContext(Dispatchers.Main) {
                val connectedRoot = CompletableDeferred<String>()
                val loadedChildren = CompletableDeferred<List<PlatformMediaBrowser.MediaItem>>()
                lateinit var browser: PlatformMediaBrowser

                val connectionCallback = object : PlatformMediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        connectedRoot.complete(browser.root)
                    }

                    override fun onConnectionFailed() {
                        connectedRoot.completeExceptionally(
                            AssertionError("Platform MediaBrowser connection failed")
                        )
                    }

                    override fun onConnectionSuspended() {
                        connectedRoot.completeExceptionally(
                            AssertionError("Platform MediaBrowser connection was suspended")
                        )
                    }
                }
                val subscriptionCallback = object : PlatformMediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(
                        parentId: String,
                        children: List<PlatformMediaBrowser.MediaItem>
                    ) {
                        loadedChildren.complete(children)
                    }

                    override fun onError(parentId: String) {
                        loadedChildren.completeExceptionally(
                            AssertionError("Failed to load children for $parentId")
                        )
                    }
                }

                browser = PlatformMediaBrowser(
                    context,
                    ComponentName(context, AudioService::class.java),
                    connectionCallback,
                    null
                )
                try {
                    browser.connect()
                    val rootId = connectedRoot.await()
                    assertEquals(AndroidAutoLibrary.ROOT_ID, rootId)

                    browser.subscribe(rootId, subscriptionCallback)
                    val children = loadedChildren.await()
                    assertTrue(children.isNotEmpty())
                } finally {
                    if (browser.isConnected) {
                        browser.unsubscribe(browser.root, subscriptionCallback)
                    }
                    browser.disconnect()
                }
            }
        }
    }

    @Test
    fun mediaSessionTimelineRetainsMarkedUpNextOrder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        withTimeout(10_000L) {
            withContext(Dispatchers.Main) {
                val token = SessionToken(context, ComponentName(context, AudioService::class.java))
                val browser = MediaBrowser.Builder(context, token).buildAsync().await()
                val prefs = context.getSharedPreferences("flac_prefs", android.content.Context.MODE_PRIVATE)
                val savedLastId = prefs.getString("playback_last_media_id", null)
                val savedLastPosition = prefs.getLong("playback_last_position_ms", 0L)
                val savedUpNext = prefs.getString("playback_up_next_media_ids", null)
                val savedFuture = prefs.getString("playback_future_media_ids", null)
                val originalItemCount = browser.mediaItemCount
                val createdCurrent = browser.mediaItemCount == 0
                val marker = System.nanoTime().toString()
                val firstId = "content://app.nogarbo.leflac.test/up-next-a-$marker"
                val secondId = "content://app.nogarbo.leflac.test/up-next-b-$marker"
                val currentDuration = browser.currentMediaItem?.mediaId?.let { mediaId ->
                    app.nogarbo.leflac.data.LocalAudioLibrary(context)
                        .load()
                        .findByMediaId(mediaId)
                        ?.duration
                } ?: browser.currentMediaItem?.mediaMetadata?.durationMs
                    ?: browser.duration
                val testDuration =
                    if (currentDuration >= app.nogarbo.leflac.data.LocalAudioLibrarySnapshot.MIX_DURATION_THRESHOLD_MS) {
                        app.nogarbo.leflac.data.LocalAudioLibrarySnapshot.MIX_DURATION_THRESHOLD_MS
                    } else {
                        180_000L
                    }

                fun item(id: String, title: String): MediaItem = MediaItem.Builder()
                    .setMediaId(id)
                    .setUri(Uri.parse(id))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(title)
                            .setArtist("Instrumentation")
                            .setDurationMs(testDuration)
                            .build()
                    )
                    .build()

                try {
                    if (createdCurrent) {
                        browser.setMediaItem(item("content://app.nogarbo.leflac.test/current-$marker", "Current"))
                        while (browser.mediaItemCount == 0) delay(20L)
                    }
                    val insertion = browser.currentMediaItemIndex + 1
                    browser.addMediaItems(
                        insertion,
                        listOf(
                            UpNextQueue.mark(item(firstId, "Queue A"), "test-a-$marker"),
                            UpNextQueue.mark(item(secondId, "Queue B"), "test-b-$marker")
                        )
                    )

                    while ((0 until browser.mediaItemCount).none { browser.getMediaItemAt(it).mediaId == secondId }) {
                        delay(20L)
                    }
                    val firstIndex = (0 until browser.mediaItemCount)
                        .first { browser.getMediaItemAt(it).mediaId == firstId }
                    assertEquals(secondId, browser.getMediaItemAt(firstIndex + 1).mediaId)
                    assertTrue(UpNextQueue.isMarked(browser.getMediaItemAt(firstIndex)))
                    assertTrue(UpNextQueue.isMarked(browser.getMediaItemAt(firstIndex + 1)))

                    while (PlaybackBus.upNext.value.none { it.mediaId == secondId }) delay(20L)
                    val publishedIds = PlaybackBus.upNext.value.map(UpNextEntry::mediaId)
                    assertTrue(publishedIds.indexOf(firstId) < publishedIds.indexOf(secondId))
                } finally {
                    withContext(NonCancellable) {
                        try {
                            (browser.mediaItemCount - 1 downTo 0)
                                .filter { index ->
                                    browser.getMediaItemAt(index).mediaId == firstId ||
                                        browser.getMediaItemAt(index).mediaId == secondId
                                }
                                .forEach(browser::removeMediaItem)
                            if (createdCurrent) browser.clearMediaItems()
                            for (attempt in 0 until 50) {
                                val restored = browser.mediaItemCount == originalItemCount &&
                                    (0 until browser.mediaItemCount).none { index ->
                                        browser.getMediaItemAt(index).mediaId == firstId ||
                                            browser.getMediaItemAt(index).mediaId == secondId
                                    }
                                if (restored) break
                                delay(20L)
                            }
                        } catch (_: Exception) {
                            // Preference restoration below is the final safety net.
                        }
                        try {
                            browser.release()
                        } catch (_: Exception) {
                            // The controller was already disconnected.
                        }
                        context.stopService(Intent(context, AudioService::class.java))
                        for (attempt in 0 until 50) {
                            if (AudioService.instance == null) break
                            delay(20L)
                        }
                        prefs.edit().apply {
                            if (savedLastId == null) remove("playback_last_media_id")
                            else putString("playback_last_media_id", savedLastId)
                            putLong("playback_last_position_ms", savedLastPosition)
                            if (savedUpNext == null) remove("playback_up_next_media_ids")
                            else putString("playback_up_next_media_ids", savedUpNext)
                            if (savedFuture == null) remove("playback_future_media_ids")
                            else putString("playback_future_media_ids", savedFuture)
                        }.commit()
                    }
                }
            }
        }
    }
}
