package chat.lumiverse.prototype

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeMicrophoneTest {
    @Test fun restrictsCaptureToTheSelectedSecureOrigin() {
        assertTrue(NativeMicrophone.allows("https://chat.example", "https://chat.example/chat/1", "https://chat.example/"))
        assertTrue(NativeMicrophone.allows("http://127.0.0.1:4321", "http://127.0.0.1:4321/", "http://127.0.0.1:4321"))
        assertFalse(NativeMicrophone.allows("https://chat.example", "https://oauth.example", "https://oauth.example"))
        assertFalse(NativeMicrophone.allows("https://chat.example", "https://chat.example", "https://iframe.example"))
        assertFalse(NativeMicrophone.allows("http://127.0.0.1:4321", "http://127.0.0.1:4321", "http://127.0.0.1:4322"))
        assertFalse(NativeMicrophone.allows("http://192.168.1.10", "http://192.168.1.10", "http://192.168.1.10"))
        assertFalse(NativeMicrophone.allows(null, "https://chat.example", "https://chat.example"))
        assertFalse(NativeMicrophone.allows("https://chat.example", "https://chat.example", "https://chat.example@evil.example"))
    }
}
