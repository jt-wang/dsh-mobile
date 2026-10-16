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

    private var notificationIntent by androidx.compose.runtime.mutableStateOf<android.content.Intent?>(null)
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); setIntent(intent); notificationIntent = intent }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationIntent = intent
        enableEdgeToEdge()
        setContent {
            MobileTheme {
                val model: MobileViewModel = viewModel()
                androidx.compose.runtime.LaunchedEffect(notificationIntent) {
                    model.openNotificationChat(notificationIntent?.getStringExtra("notificationDevice"), notificationIntent?.getStringExtra("notificationChat"))
                }
                MobileApp(model)
            }
        }
    }
}
