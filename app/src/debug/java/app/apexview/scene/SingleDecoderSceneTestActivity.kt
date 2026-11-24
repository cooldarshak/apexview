package app.apexview.scene

import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity

/** Empty, offline host for testing the real texture renderer without login or F1 requests. */
class SingleDecoderSceneTestActivity : ComponentActivity() {
    lateinit var root: FrameLayout
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this)
        setContentView(root)
    }
}
