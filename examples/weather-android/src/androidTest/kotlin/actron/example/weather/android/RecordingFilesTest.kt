package actron.example.weather.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import java.util.regex.Pattern

/** End-to-end SAF tests: no intercepted intents, fake picker, or direct app-state injection. */
class RecordingFilesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val timeout = 10_000L

    private fun awaitDocumentsUi() {
        // AOSP and Google APIs emulator images ship different DocumentsUI packages.
        val picker = By.pkg(Pattern.compile("com\\.(android|google\\.android)\\.documentsui"))
        val appeared: Boolean = device.wait(Until.hasObject(picker), timeout)
        if (!appeared) {
            val hierarchy = java.io.ByteArrayOutputStream()
            device.dumpWindowHierarchy(hierarchy)
            throw AssertionError("System document picker did not appear; foreground=${device.currentPackageName}; hierarchy=$hierarchy")
        }
    }

    private fun enableInspector() {
        compose.onNodeWithTag("debug-settings").performClick()
        compose.onNodeWithTag("debug-panel-switch").performClick()
        compose.onNodeWithTag("debug-settings-done").performClick()
    }

    private fun downloads() {
        // UiObject resolves its selector for each action; UiObject2 can become stale as the drawer redraws.
        val roots = checkNotNull(device.findObject(UiSelector().description("Show roots")))
        if (roots.exists()) check(roots.click()) { "Cannot open document roots" }
        device.waitForIdle(timeout)
        val downloads = checkNotNull(device.findObject(UiSelector().text("Downloads")))
        check(downloads.waitForExists(timeout)) { "Downloads root did not appear" }
        check(downloads.click()) { "Cannot select Downloads" }
        device.waitForIdle(timeout)
    }

    private fun awaitRecordingAction(tag: String) {
        // The action remains in the tree while asynchronous document I/O disables it.
        compose.waitUntil(timeout) {
            compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun clickRecordingAction(tag: String) {
        awaitRecordingAction(tag)
        compose.onNodeWithTag(tag).assertIsEnabled().performClick()
    }

    @Test fun saveOpenAndCancelThroughSystemDocumentsUi() {
        enableInspector()
        val name = "actron-e2e-${System.nanoTime()}.json"
        clickRecordingAction("recording-save")
        awaitDocumentsUi()
        downloads()
        checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), timeout)).text = name
        checkNotNull(device.wait(Until.findObject(By.text(Pattern.compile("(?i)save"))), timeout)).click()
        clickRecordingAction("recording-open")
        awaitDocumentsUi()
        downloads()
        checkNotNull(device.wait(Until.findObject(By.text(name)), timeout)).click()
        compose.waitUntil(timeout) { compose.onAllNodesWithTag("recording-close").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history-live").assertDoesNotExist()
        compose.onNodeWithTag("history-continue").assertDoesNotExist()
        clickRecordingAction("recording-open")
        awaitDocumentsUi()
        device.pressBack()
        compose.onNodeWithTag("recording-close").assertExists().performClick()
        compose.onNodeWithTag("history-live").assertExists()
        clickRecordingAction("recording-save")
        awaitDocumentsUi()
        device.pressBack()
        awaitRecordingAction("recording-save")
        compose.onNodeWithTag("recording-save").assertIsEnabled()
    }

    @Test fun incompatibleFileIsRejectedThroughTheSystemPicker() {
        enableInspector()
        val resolver: android.content.ContentResolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val name = "actron-invalid-${System.nanoTime()}.json"
        val values =
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download")
            }
        val uri = checkNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { stream: java.io.OutputStream ->
                stream.write("""{"schema":"actron.weather.display/1","entries":[],"format":99,"definitions":[],"metadata":{"projectName":"Weather","exportedAt":"2026-10-20T00:00:00Z"}}""".toByteArray())
            }
            clickRecordingAction("recording-open")
            awaitDocumentsUi()
            downloads()
            checkNotNull(device.wait(Until.findObject(By.text(name)), timeout)).click()
            compose.waitUntil(timeout) { compose.onAllNodesWithTag("recording-error").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("OK").performClick()
            compose.onNodeWithTag("recording-close").assertDoesNotExist()
            compose.onNodeWithTag("history-live").assertExists()
        } finally {
            resolver.delete(uri, "", emptyArray())
        }
    }
}
