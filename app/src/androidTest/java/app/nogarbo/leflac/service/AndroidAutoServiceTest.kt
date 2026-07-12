package app.nogarbo.leflac.service

import android.content.ComponentName
import android.media.browse.MediaBrowser as PlatformMediaBrowser
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the Media3 and platform browser connection paths used by Android Auto. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class AndroidAutoServiceTest {

    @Test
    fun coldStartExposesBrowsablePocketLibraryWithoutActivity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("flac_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .remove("android_auto_visual_scheme")
            .commit()

        withContext(Dispatchers.Main) {
            val token = SessionToken(context, ComponentName(context, AudioService::class.java))
            val browser = MediaBrowser.Builder(context, token).buildAsync().await()
            try {
                val rootResult = browser.getLibraryRoot(null).await()
                assertEquals(LibraryResult.RESULT_SUCCESS, rootResult.resultCode)
                val root = rootResult.value
                assertNotNull(root)
                assertEquals(AndroidAutoLibrary.ROOT_ID, root?.mediaId)
                assertEquals(true, root?.mediaMetadata?.isBrowsable)
                assertTrue(root?.mediaMetadata?.subtitle.toString().contains("POCKET"))

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
}
