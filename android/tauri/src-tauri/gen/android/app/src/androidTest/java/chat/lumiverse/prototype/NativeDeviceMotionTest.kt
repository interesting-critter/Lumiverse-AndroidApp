package chat.lumiverse.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeDeviceMotionTest {
    @Test fun convertsAndroidQuaternionOrderAndNormalizes() {
        val q = NativeDeviceMotion.quaternion(floatArrayOf(0.1f, -0.2f, 0.3f, 0.9f))!!
        val length = kotlin.math.sqrt(0.95)
        assertEquals(0.1 / length, q.getDouble("x"), 0.000001)
        assertEquals(-0.2 / length, q.getDouble("y"), 0.000001)
        assertEquals(0.3 / length, q.getDouble("z"), 0.000001)
        assertEquals(0.9 / length, q.getDouble("w"), 0.000001)
    }

    @Test fun acceptsLegacyThreeComponentVectorsAndRejectsInvalidData() {
        val q = NativeDeviceMotion.quaternion(floatArrayOf(0f, 0.6f, 0f))!!
        assertEquals(0.6, q.getDouble("y"), 0.000001)
        assertEquals(0.8, q.getDouble("w"), 0.000001)
        assertNull(NativeDeviceMotion.quaternion(floatArrayOf(0f, 0f)))
        assertNull(NativeDeviceMotion.quaternion(floatArrayOf(Float.NaN, 0f, 0f, 1f)))
        assertNull(NativeDeviceMotion.quaternion(floatArrayOf(0f, 0f, 0f, 0f)))
        assertNull(NativeDeviceMotion.quaternion(floatArrayOf(0f, 0f, 0f, 2f)))
    }
}
