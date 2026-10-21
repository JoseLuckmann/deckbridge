package os.deckbridge.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity

/** Host vazio para os prints na JVM. Não aparece no lançador. */
class ScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
    }
}
