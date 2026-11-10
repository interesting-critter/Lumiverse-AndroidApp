package chat.lumiverse.prototype

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LevelListDrawable
import android.os.Parcel
import android.os.Bundle
import android.os.Build
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestWorkerBuilder
import com.google.android.material.slider.Slider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class CharacterWidgetsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private fun origin() = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
    private fun snapshot(account: String = "owner", count: Int = 2, generated: Long = System.currentTimeMillis()) = WidgetSnapshot.parse(JSONObject()
        .put("version", 1).put("accountId", account).put("generatedAt", generated)
        .put("recent", JSONArray((1..count).map { JSONObject().put("characterId", "char-$it").put("name", "Character $it").put("chatId", "chat-$it") }))
        .put("favorites", JSONArray().put(JSONObject().put("characterId", "favorite").put("name", "Favorite").put("chatId", JSONObject.NULL))))
    private fun enroll(local: Boolean = false, account: String = "owner", registration: String = "registration") : JSONObject {
        val origin = origin()
        NativeCharacterWidgets.select(context, origin, local)
        NativeCharacterWidgets.configure(context, JSONObject().put("accountId", account).put("serverOrigin", origin)
            .put("enrollment", JSONObject().put("registrationId", registration).put("credential", "a".repeat(43))
                .put("accountId", account).put("expiresAt", System.currentTimeMillis() + 30L * 24 * 3600 * 1000)))
        return CharacterWidgetStore.current(context)!!
    }
    private fun refresh() = TestWorkerBuilder<CharacterWidgetWorker>(context, Executor { it.run() }).build().doWork()
    @Before fun setup() {
        CharacterWidgetStore.invalidate(context, true)
        server = MockWebServer()
        server.start()
        // Revocation cleanup is asynchronous; never let it consume a snapshot reply.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when {
                request.method == "DELETE" -> MockResponse().setResponseCode(204)
                request.path?.endsWith("/snapshot") == true -> MockResponse().setHeader("Content-Type", "application/json").setBody(snapshot().raw.toString())
                else -> MockResponse().setResponseCode(404)
            }
        }
    }
    @After fun cleanup() { CharacterWidgetStore.invalidate(context, true); server.shutdown() }

    @Test fun refreshesBothBundledAndRemoteSelectionsWithoutBrowserCookiesOrFirebase() {
        for (local in listOf(true, false)) {
            val active = enroll(local)
            assertFalse(NativeCharacterWidgets.status(context).getBoolean("needsEnrollment"))
            assertEquals(ListenableWorker.Result.success(), refresh())
            assertEquals(2, CharacterWidgetStore.snapshot(context, active)!!.recent.size)
            var request = server.takeRequest()
            while (request.method == "DELETE") request = server.takeRequest()
            assertEquals("Bearer " + "a".repeat(43), request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
            // Drain the three bounded portrait requests before switching modes.
            repeat(3) { server.takeRequest() }
        }
    }
    @Test fun serverSwitchLogoutAndLateResponsesCannotRestorePrivateContent() {
        val active = enroll()
        val revision = CharacterWidgetStore.revision(context)
        assertTrue(CharacterWidgetStore.commit(context, active, revision, snapshot(), emptyMap()))
        NativeCharacterWidgets.select(context, "https://different.example", false)
        assertNull(CharacterWidgetStore.current(context))
        assertFalse(CharacterWidgetStore.commit(context, active, revision, snapshot(), emptyMap()))
        val replacement = enroll(account = "tenant", registration = "tenant-registration")
        assertFalse(CharacterWidgetStore.commit(context, replacement, CharacterWidgetStore.revision(context), snapshot(), emptyMap()))
        NativeCharacterWidgets.configure(context, JSONObject().put("accountId", JSONObject.NULL).put("serverOrigin", origin()))
        assertNull(CharacterWidgetStore.current(context))
    }
    @Test fun invalidationRejectsAnInflightSnapshotAndAnOlderResponse() {
        val active = enroll()
        val revision = CharacterWidgetStore.revision(context)
        NativeCharacterWidgets.invalidate(context)
        assertFalse(CharacterWidgetStore.commit(context, active, revision, snapshot(), emptyMap()))
        val next = CharacterWidgetStore.revision(context)
        assertTrue(CharacterWidgetStore.commit(context, active, next, snapshot(generated = 200), emptyMap()))
        assertFalse(CharacterWidgetStore.commit(context, active, next, snapshot(generated = 100), emptyMap()))
    }
    @Test fun portraitBoundsAndSnapshotIdentifiersAreValidated() {
        val bad = snapshot().raw
        bad.getJSONArray("recent").getJSONObject(0).put("characterId", "../another")
        try { WidgetSnapshot.parse(bad); fail("Unsafe identifier accepted") } catch (_: IllegalArgumentException) { }
        try { snapshot(count = 17); fail("Unbounded collection accepted") } catch (_: IllegalArgumentException) { }
        assertFalse(CharacterWidgetWorker.boundedJPEG(ByteArray(256 * 1024 + 1)))
        for (size in listOf(192 to 256, 193 to 256)) {
            val image = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.RGB_565)
            val bytes = java.io.ByteArrayOutputStream().apply { image.compress(Bitmap.CompressFormat.JPEG, 80, this) }.toByteArray()
            assertEquals(size.first <= 192, CharacterWidgetWorker.boundedJPEG(bytes))
            image.recycle()
        }
    }
    @Test fun deepLinksOnlyResolveMembersOfTheirOriginalRegistrationAndCollection() {
        val snapshot = snapshot()
        assertEquals("/chat/chat-1", CharacterWidgetLink.destination("lumiverse://widget/registration/recent/char-1", "registration", snapshot))
        assertEquals("/widget/character/favorite", CharacterWidgetLink.destination("lumiverse://widget/registration/favorites/favorite", "registration", snapshot))
        for (raw in listOf("lumiverse://widget/old/recent/char-1", "lumiverse://widget/registration/favorites/char-1",
            "lumiverse://widget/registration/recent/char-1?target=other", "lumiverse://widget/registration/recent/%63har-1",
            "lumiverse://widget/registration/recent/char-1#x", "lumiverse://widget:80/registration/recent/char-1"))
            assertNull(CharacterWidgetLink.destination(raw, "registration", snapshot))
    }
    @Test fun revokedCredentialPurgesCachedMetadata() {
        val active = enroll()
        CharacterWidgetStore.commit(context, active, CharacterWidgetStore.revision(context), snapshot(), emptyMap())
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401) }
        assertEquals(ListenableWorker.Result.success(), refresh())
        assertNull(CharacterWidgetStore.current(context))
        assertNull(CharacterWidgetStore.snapshot(context, active))
    }
    @Test fun redirectNeverForwardsTheBearerAndOfflineKeepsTheLastSnapshot() {
        val active = enroll()
        CharacterWidgetStore.commit(context, active, CharacterWidgetStore.revision(context), snapshot(), emptyMap())
        val other = MockWebServer().apply { start() }
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse()
            .setResponseCode(302).setHeader("Location", other.url("/stolen")) }
        assertEquals(ListenableWorker.Result.success(), refresh())
        assertEquals(1, server.requestCount)
        assertEquals(0, other.requestCount)
        other.shutdown()
        assertNotNull(CharacterWidgetStore.snapshot(context, active))
        server.shutdown()
        assertEquals(ListenableWorker.Result.retry(), refresh())
        assertNotNull(CharacterWidgetStore.snapshot(context, active))
        // Replace the stopped server for cleanup().
        server = MockWebServer().apply { start() }
    }
    @Test fun bundledPortChangePreservesTapUntilTheMatchingAccountHasRefreshed() {
        val active = enroll(local = true)
        CharacterWidgetStore.commit(context, active, CharacterWidgetStore.revision(context), snapshot(), emptyMap())
        val intent = Intent().putExtra("lumiverse_widget", "lumiverse://widget/registration/recent/char-1")
        NativeCharacterWidgets.captureTapFromPage(context, intent, "http://tauri.localhost", true)
        assertTrue("The cold-launch chooser consumed the tap", intent.hasExtra("lumiverse_widget"))
        val old = server
        server = MockWebServer().apply { start() }
        val newOrigin = origin()
        NativeCharacterWidgets.captureTap(context, intent, newOrigin, true)
        val replacement = enroll(local = true, registration = "new-registration")
        assertFalse(intent.hasExtra("lumiverse_widget"))
        assertNull(NativeCharacterWidgets.pendingDestination(context, newOrigin))
        CharacterWidgetStore.commit(context, replacement, CharacterWidgetStore.revision(context), snapshot(), emptyMap())
        assertNull(NativeCharacterWidgets.pendingDestination(context, "https://different.example"))
        assertEquals("/chat/chat-1", NativeCharacterWidgets.pendingDestination(context, newOrigin))
        assertNull(NativeCharacterWidgets.pendingDestination(context, newOrigin))
        old.shutdown()
    }
    @Test fun remoteViewsInflateAllGridSizesAndStayWithinBinderBudget() {
        val active = enroll()
        val snapshot = snapshot(count = 16)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for ((columns, rows) in listOf(2 to 2, 4 to 2, 4 to 4)) {
                val views = CharacterWidgets.layout(context, active, snapshot, false, columns, rows, mutableMapOf())
                val parcel = Parcel.obtain()
                views.writeToParcel(parcel, 0)
                assertTrue("Oversized RemoteViews: ${parcel.dataSize()}", parcel.dataSize() < 700_000)
                parcel.recycle()
                val root = views.apply(context, FrameLayout(context))
                val grid = root.findViewById<LinearLayout>(R.id.widget_grid)
                assertEquals(rows, grid.childCount)
                assertEquals(columns, (grid.getChildAt(0) as LinearLayout).childCount)
                assertEquals("Recent", root.findViewById<TextView>(R.id.widget_title).text.toString())
                val width = if (columns == 2) 180 else 360
                val height = if (rows == 4) 360 else 180
                val density = context.resources.displayMetrics.density
                val w = (width * density).toInt(); val h = (height * density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, w, h)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File(context.cacheDir, "widget-${columns}x$rows.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val favorites = CharacterWidgets.layout(context, active, snapshot, true, 4, 2, mutableMapOf()).apply(context, FrameLayout(context))
            assertEquals("Favorites", favorites.findViewById<TextView>(R.id.widget_title).text.toString())
            if (Build.VERSION.SDK_INT >= 31) {
                val images = mutableMapOf<String, Bitmap>()
                val responsive = RemoteViews(mapOf(SizeF(110f, 110f) to CharacterWidgets.layout(context, active, snapshot, false, 2, 2, images),
                    SizeF(250f, 110f) to CharacterWidgets.layout(context, active, snapshot, false, 4, 2, images),
                    SizeF(250f, 270f) to CharacterWidgets.layout(context, active, snapshot, false, 4, 4, images)))
                val parcel = Parcel.obtain()
                responsive.writeToParcel(parcel, 0)
                assertTrue("Responsive RemoteViews exceeds binder budget: ${parcel.dataSize()}", parcel.dataSize() < 700_000)
                parcel.recycle()
            }
        }
    }
    @Test fun bothProvidersBindToARealAndroidWidgetHostAndPublishTheirCollections() {
        val active = enroll()
        CharacterWidgetStore.commit(context, active, CharacterWidgetStore.revision(context), snapshot(), emptyMap())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, 713)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try {
            for ((provider, title) in listOf(RecentCharactersWidget::class.java to "Recent", FavoriteCharactersWidget::class.java to "Favorites")) {
                val id = host.allocateAppWidgetId()
                try {
                    assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, provider)))
                    CharacterWidgets.renderAll(context)
                    var view: AppWidgetHostView? = null
                    instrumentation.runOnMainSync {
                        host.startListening()
                        view = host.createView(context, id, manager.getAppWidgetInfo(id))
                        view!!.updateAppWidgetSize(Bundle(), 180, 180, 180, 180)
                    }
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        assertEquals(title, view!!.findViewById<TextView>(R.id.widget_title).text.toString())
                        assertEquals(View.VISIBLE, view!!.findViewById<View>(R.id.widget_grid).visibility)
                    }
                } finally { host.deleteAppWidgetId(id) }
            }
        } finally { host.stopListening(); host.deleteHost(); instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }
    @Test fun widgetAppearanceDefaultsPreserveLookAndTintKeepsPaletteRgb() {
        WidgetAppearance.set(context, WidgetAppearance.DEFAULT_OPACITY_PCT, WidgetAppearance.DEFAULT_CORNER_RADIUS_DP)
        try {
            assertEquals(100, WidgetAppearance.opacityPct(context))
            assertEquals(24, WidgetAppearance.cornerRadiusDp(context))
            val opaque = Color.parseColor("#171321")
            // Default (100%) must be pixel-identical to the opaque palette color.
            assertEquals(opaque, WidgetAppearance.tintedBackground(opaque, 100))
            val half = WidgetAppearance.tintedBackground(opaque, 50)
            assertEquals(128, Color.alpha(half))
            assertEquals(Color.red(opaque), Color.red(half))
            assertEquals(Color.green(opaque), Color.green(half))
            assertEquals(Color.blue(opaque), Color.blue(half))
            assertEquals(0, Color.alpha(WidgetAppearance.tintedBackground(opaque, 0)))
            assertEquals(Color.red(opaque), Color.red(WidgetAppearance.tintedBackground(opaque, 0)))
            // Storage coerces out-of-range input.
            WidgetAppearance.set(context, -5, 99)
            assertEquals(0, WidgetAppearance.opacityPct(context))
            assertEquals(64, WidgetAppearance.cornerRadiusDp(context))
        } finally {
            WidgetAppearance.set(context, WidgetAppearance.DEFAULT_OPACITY_PCT, WidgetAppearance.DEFAULT_CORNER_RADIUS_DP)
        }
    }
    @Test fun layoutAppliesOpacityAndRadiusWithoutTouchingForegroundOrAccent() {
        val active = enroll()
        val snap = snapshot()
        WidgetAppearance.set(context, 50, 10)
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val root = CharacterWidgets.layout(context, active, snap, false, 2, 2, mutableMapOf())
                    .apply(context, FrameLayout(context))
                val image = root.findViewById<ImageView>(R.id.widget_background)
                val levels = image.drawable as LevelListDrawable
                val background = levels.current as GradientDrawable
                val tinted = background.color!!.defaultColor
                // enrolled palette default background, half alpha, exact RGB.
                assertEquals(128, image.imageAlpha)
                assertEquals(Color.parseColor("#171321"), Color.rgb(Color.red(tinted), Color.green(tinted), Color.blue(tinted)))
                assertEquals(10, levels.level)
                assertEquals((10f * context.resources.displayMetrics.density).roundToInt().toFloat(), background.cornerRadius, 0.01f)
                // Foreground stays exactly the palette foreground.
                assertEquals(Color.parseColor("#f5f0ff"), root.findViewById<TextView>(R.id.widget_title).currentTextColor)
                assertEquals(255, root.findViewById<ImageView>(R.id.widget_symbol).imageAlpha)
                assertNotNull(root.findViewById<ImageView>(R.id.widget_symbol).colorFilter)
                // The enrolled palette itself is untouched by appearance settings.
                val palette = CharacterWidgetStore.current(context)!!.optJSONObject("palette")!!
                assertEquals("#171321", palette.getString("background"))
                assertEquals("#f5f0ff", palette.getString("foreground"))
                assertEquals("#bca6ff", palette.getString("accent"))
            }
        } finally {
            WidgetAppearance.set(context, WidgetAppearance.DEFAULT_OPACITY_PCT, WidgetAppearance.DEFAULT_CORNER_RADIUS_DP)
        }
    }
    @Test fun backgroundPixelsMatchTheSelectedRadiusAndOpacityAtEveryWidgetSize() {
        val backgroundColor = Color.parseColor("#254667")
        val active = enroll().put("palette", JSONObject().put("background", "#254667")
            .put("foreground", "#aabbcc").put("accent", "#fedcba"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            instrumentation.runOnMainSync {
                for ((columns, rows) in listOf(2 to 2, 4 to 2, 4 to 4)) {
                    for ((width, height) in listOf(180 to 260, 360 to 180)) {
                        val density = context.resources.displayMetrics.density
                        val w = (width * density).toInt(); val h = (height * density).toInt()
                        for (radius in listOf(0, 10, 24, 64)) for (opacity in listOf(0, 50, 100)) {
                            WidgetAppearance.set(context, opacity, radius)
                            val root = CharacterWidgets.layout(context, active, null, false, columns, rows, mutableMapOf())
                                .apply(context, FrameLayout(context))
                            val image = root.findViewById<ImageView>(R.id.widget_background)
                            image.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                            image.layout(0, 0, w, h)
                            val actual = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val expected = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            try {
                                image.draw(Canvas(actual))
                                val shape = if (radius == 24 && opacity == 100) {
                                    // The original drawable is the exact default-appearance reference.
                                    context.getDrawable(R.drawable.widget_background)!!.mutate().apply {
                                        setColorFilter(backgroundColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
                                    }
                                } else GradientDrawable().apply {
                                    setColor(WidgetAppearance.tintedBackground(backgroundColor, opacity))
                                    cornerRadius = (radius * density).roundToInt().toFloat()
                                }
                                shape.setBounds(0, 0, w, h)
                                shape.draw(Canvas(expected))
                                assertTrue("Background changed at ${columns}x$rows, ${width}x$height dp, radius=$radius, opacity=$opacity",
                                    actual.sameAs(expected))
                            } finally { actual.recycle(); expected.recycle() }
                        }
                    }
                }
            }
        } finally {
            WidgetAppearance.set(context, WidgetAppearance.DEFAULT_OPACITY_PCT, WidgetAppearance.DEFAULT_CORNER_RADIUS_DP)
        }
    }
    @Test fun widgetPlacementUsesDefaultsWithoutRequiringConfiguration() {
        val manager = AppWidgetManager.getInstance(context)
        for (provider in listOf(RecentCharactersWidget::class.java, FavoriteCharactersWidget::class.java)) {
            val info = manager.installedProviders.first { it.provider == ComponentName(context, provider) }
            if (Build.VERSION.SDK_INT >= 31) {
                assertEquals(ComponentName(context, WidgetAppearanceConfigActivity::class.java), info.configure)
                assertEquals(AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE or AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL,
                    info.widgetFeatures)
            } else assertNull(info.configure)
        }
    }
    @Test fun configurationSaveCancelAndInvalidWidgetLeaveEnrollmentUntouched() {
        val active = enroll()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, 716)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        val id = host.allocateAppWidgetId()
        fun launch(widgetId: Int) = instrumentation.startActivitySync(
            Intent(context, WidgetAppearanceConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        try {
            WidgetAppearance.set(context, 100, 24)
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, RecentCharactersWidget::class.java)))
            val saved = launch(id)
            instrumentation.runOnMainSync {
                val controls = descendants(saved.window.decorView)
                controls.filterIsInstance<Slider>()[0].value = 50f
                controls.filterIsInstance<Slider>()[1].value = 64f
                val save = controls.filterIsInstance<TextView>().first { it.text == context.getString(R.string.widget_appearance_save) }
                controls.filterIsInstance<EditText>().single().setText("101")
                assertFalse(save.isEnabled)
                controls.filterIsInstance<EditText>().single().setText("50")
                assertTrue(save.isEnabled)
                save.performClick()
            }
            instrumentation.waitForIdleSync()
            assertEquals(50, WidgetAppearance.opacityPct(context))
            assertEquals(64, WidgetAppearance.cornerRadiusDp(context))
            val canceled = launch(id)
            instrumentation.runOnMainSync {
                val controls = descendants(canceled.window.decorView)
                controls.filterIsInstance<Slider>()[0].value = 0f
                controls.filterIsInstance<Slider>()[1].value = 0f
                controls.filterIsInstance<TextView>().first { it.text == context.getString(R.string.widget_appearance_cancel) }.performClick()
            }
            instrumentation.waitForIdleSync()
            assertEquals(50, WidgetAppearance.opacityPct(context))
            assertEquals(64, WidgetAppearance.cornerRadiusDp(context))
            val invalid = launch(Int.MAX_VALUE)
            instrumentation.waitForIdleSync()
            assertTrue(invalid.isFinishing)
            assertEquals(50, WidgetAppearance.opacityPct(context))
            assertEquals(64, WidgetAppearance.cornerRadiusDp(context))
            assertEquals(active.toString(), CharacterWidgetStore.current(context)!!.toString())
        } finally {
            WidgetAppearance.set(context, WidgetAppearance.DEFAULT_OPACITY_PCT, WidgetAppearance.DEFAULT_CORNER_RADIUS_DP)
            host.deleteAppWidgetId(id); host.deleteHost(); instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
    @Test fun logoutClearsThePublishedCollectionAtHighDisplayDensity() {
        val active = enroll()
        CharacterWidgetStore.commit(context, active, CharacterWidgetStore.revision(context), snapshot(), emptyMap())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, 715)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        val id = host.allocateAppWidgetId()
        try {
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, RecentCharactersWidget::class.java)))
            CharacterWidgets.renderAll(context)
            var view: AppWidgetHostView? = null
            instrumentation.runOnMainSync {
                host.startListening()
                view = host.createView(context, id, manager.getAppWidgetInfo(id))
                view!!.updateAppWidgetSize(Bundle(), 180, 180, 180, 180)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(View.VISIBLE, view!!.findViewById<View>(R.id.widget_grid).visibility)
            }
            // Also exercise a display-size change without needing a second device.
            val larger = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
                densityDpi = 640
            })
            NativeCharacterWidgets.clear(larger)
            instrumentation.waitForIdleSync()
            assertNull(CharacterWidgetStore.current(context))
            instrumentation.runOnMainSync {
                assertEquals(View.GONE, view!!.findViewById<View>(R.id.widget_grid).visibility)
                assertEquals(context.getString(R.string.widget_sign_in), view!!.findViewById<TextView>(R.id.widget_empty).text.toString())
            }
        } finally {
            host.stopListening(); host.deleteAppWidgetId(id); host.deleteHost()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
