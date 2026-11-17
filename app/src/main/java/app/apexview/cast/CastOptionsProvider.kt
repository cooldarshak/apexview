package app.apexview.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/** Standard Google Cast receiver. It accepts one HLS video stream; no browser pairing is used. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(appContext: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .build()

    override fun getAdditionalSessionProviders(appContext: Context): List<SessionProvider>? = null
}
