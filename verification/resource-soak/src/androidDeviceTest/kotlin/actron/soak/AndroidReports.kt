package actron.soak

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidReports {
    @Test fun resourceBudgetsOnAndroid() =
        runBlocking {
            val context = checkNotNull(InstrumentationRegistry.getInstrumentation().context)
            val reports = File(context.filesDir, "resource-soak-android")
            check(!reports.exists() || reports.deleteRecursively()) { "Cannot remove previous benchmark reports" }
            SoakPlatform.reportDirectory = reports.absolutePath
            runResourceSoak()
        }
}
