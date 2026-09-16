package lab.arl.target

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TargetAppInstrumentationTest {
    @Test
    fun packageNameMatchesApplicationId() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("lab.arl.target", context.packageName)
    }
}
