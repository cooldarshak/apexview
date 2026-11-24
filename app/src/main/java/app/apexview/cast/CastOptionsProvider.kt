package app.apexview.cast

import android.content.Context
import app.apexview.BuildConfig
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/** Uses the registered layout receiver when configured, otherwise the standard atlas receiver. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(appContext: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(BuildConfig.CAST_RECEIVER_APP_ID.ifEmpty { CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID })
        .build()

    override fun getAdditionalSessionProviders(appContext: Context): List<SessionProvider>? = null
}
