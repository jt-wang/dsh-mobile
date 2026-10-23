package dev.dshmobile.app

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dshmobile.app.ui.MobileApp
import dev.dshmobile.app.ui.MobileTheme
import dev.dshmobile.app.ui.MobileViewModel

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(if (android.os.Build.VERSION.SDK_INT < 33) dev.dshmobile.app.locale.appLanguageContext(newBase) else newBase)
    }

    override fun onResume() {
        super.onResume()
        dev.dshmobile.app.data.refreshNotificationChannelNames(dev.dshmobile.app.locale.appLanguageContext(this))
    }

    private var notificationIntent by androidx.compose.runtime.mutableStateOf<dev.dshmobile.app.data.NotificationIntentTarget?>(null)
    private var consumedDelivery: String? = null
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("notificationConsumedDelivery", consumedDelivery)
        super.onSaveInstanceState(outState)
    }
    private fun acceptNotificationIntent(value: android.content.Intent, restoredDelivery: String? = null) {
        // Save only consumption identity, never a restorable navigation target.
        val delivery = dev.dshmobile.app.data.notificationDeliveryIdentity(value)
        notificationIntent = dev.dshmobile.app.data.consumeNotificationIntent(value, delivery != null && delivery == restoredDelivery)
        consumedDelivery = delivery ?: restoredDelivery
        setIntent(value)
    }
    override fun onNewIntent(intent: android.content.Intent) {
        dev.dshmobile.app.data.notificationTrace("activity_new_intent chat=${intent.hasExtra("notificationChat")} question=${intent.hasExtra("notificationQuestion")}")
        super.onNewIntent(intent); acceptNotificationIntent(intent)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        dev.dshmobile.app.data.StartupPerf.start()
        super.onCreate(savedInstanceState)
        dev.dshmobile.app.data.notificationTrace("activity_create saved=${savedInstanceState != null} chat=${intent.hasExtra("notificationChat")} question=${intent.hasExtra("notificationQuestion")}")
        acceptNotificationIntent(intent, savedInstanceState?.getString("notificationConsumedDelivery"))
        enableEdgeToEdge()
        setContent {
            MobileTheme {
                val model: MobileViewModel = viewModel()
                androidx.compose.runtime.LaunchedEffect(notificationIntent) {
                    val target = notificationIntent ?: return@LaunchedEffect
                    if (target.settings) model.openNotificationSettings()
                    if (target.chatList) model.openNotificationChatList()
                    model.openNotificationChat(target.device, target.chat, target.question)
                    notificationIntent = null
                }
                MobileApp(model)
            }
        }
    }
}
