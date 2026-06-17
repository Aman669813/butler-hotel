package com.risiga.hotelbutler.data

import com.risiga.hotelbutler.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.engine.okhttp.OkHttp

/**
 * One Supabase client for the whole app.
 *
 * The explicit OkHttp engine is the same fix you used in Butler — it resolves
 * the Ktor WebSocketCapability crash so Realtime can connect on the device.
 */
object SupabaseClientHolder {

    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON
        ) {
            httpEngine = OkHttp.create()      // <-- WebSocket fix
            install(Postgrest)
            install(Realtime)
        }
    }
}