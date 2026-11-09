package chat.lumiverse.prototype

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class LumiverseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) { NativeNotifications.receive(applicationContext, message.data) }
    override fun onNewToken(token: String) { NativeNotifications.tokenChanged(applicationContext, token) }
}
