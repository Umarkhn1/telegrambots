package uz.tuit.lms

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import uz.tuit.lms.data.Auth
import uz.tuit.lms.data.Prefs
import uz.tuit.lms.data.Repo
import uz.tuit.lms.ui.AppRoot
import uz.tuit.lms.ui.Nav
import uz.tuit.lms.ui.theme.LmsTheme

class MainActivity : ComponentActivity() {

    private val nav = Nav()

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Системная заставка без иконки и не задерживается: сразу виден свой экран загрузки (SplashScreen).
        splash.setKeepOnScreenCondition { false }
        if (savedInstanceState == null) handle(intent)

        setContent {
            val theme by Prefs.theme.collectAsState()
            val dynamic by Prefs.dynamic.collectAsState()
            val navState = remember { nav }
            LmsTheme(theme = theme, dynamic = dynamic) {
                // Корневая поверхность задаёт цвет текста по умолчанию: без неё тексты без явного цвета
                // были бы чёрными и терялись в тёмной теме.
                androidx.compose.material3.Surface(
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                ) {
                    AppRoot(navState, onLoggedIn = ::askNotifications)
                }
            }
        }

        lifecycleScope.launch { Repo.ensureSession() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // Вернулись в приложение — проверим, не истекла ли сессия (раз в 10 минут).
        lifecycleScope.launch { if (Prefs.loggedIn) Repo.ensureSession() }
    }

    private fun handle(intent: Intent?) {
        val link = intent?.getStringExtra(EXTRA_OPEN) ?: intent?.data?.let { uri ->
            // Ярлыки: tuitlms://open/<link>
            if (uri.scheme == "tuitlms") uri.pathSegments.joinToString(":") else null
        }
        if (link != null) nav.open(link)
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_OPEN = "open"
    }
}
