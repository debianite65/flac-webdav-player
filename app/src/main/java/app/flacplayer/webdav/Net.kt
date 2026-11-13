package app.flacplayer.webdav

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Gemeinsamer HTTP-Client. Basic Auth wird nur an den konfigurierten Server gesendet. */
object Net {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor { chain ->
                val settings = FlacApp.instance.settings
                val request = chain.request()
                val base = settings.serverUrl.toHttpUrlOrNull()
                if (base != null &&
                    request.url.host == base.host &&
                    settings.username.isNotEmpty() &&
                    request.header("Authorization") == null
                ) {
                    chain.proceed(
                        request.newBuilder()
                            .header(
                                "Authorization",
                                Credentials.basic(settings.username, settings.password, Charsets.UTF_8),
                            )
                            .build(),
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .build()
    }
}
