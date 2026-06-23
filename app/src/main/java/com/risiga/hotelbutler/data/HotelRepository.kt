package com.risiga.hotelbutler.data

import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
/**
 * Backend access for the device.
 *
 * Batch 2 scope: only the two RPC calls (these compile against your supabase-kt
 * version). The catalog / insert / realtime functions return in Batch 3 once we
 * match the exact v3 query syntax from your working Butler repository.
 */
class HotelRepository {

    private val sb = SupabaseClientHolder.client

    // ---- Room picker (demo) --------------------------------------------
    suspend fun loadDemoRooms(): List<RoomCard> =
        sb.postgrest.rpc("list_demo_rooms").decodeList()

    // ---- Greeting lookup -----------------------------------------------
    suspend fun loadActiveStay(deviceCode: String): StayInfo? {
        val params: JsonObject = buildJsonObject { put("p_device_code", deviceCode) }
        return sb.postgrest
            .rpc("get_active_stay_for_device", params)
            .decodeList<StayInfo>()
            .firstOrNull()
    }

    /** Demo mode: follow whichever room reception activated most recently. */
    suspend fun loadLatestActiveStay(): StayInfo? =
        sb.postgrest
            .rpc("get_latest_active_stay")
            .decodeList<StayInfo>()
            .firstOrNull()

    // ---- Service requests (device -> reception + staff board) ----------
    suspend fun logServiceRequest(
        deviceCode: String, department: String, item: String,
        quantity: Int = 1, raw: String? = null, priority: String = "normal"
    ) {
        try {
            sb.postgrest.rpc("log_service_request", buildJsonObject {
                put("p_device_code", deviceCode); put("p_department", department)
                put("p_item", item); put("p_quantity", quantity)
                put("p_raw", raw ?: item); put("p_priority", priority)
            })
            android.util.Log.d("SR", "filed OK (with dept): $item / $department / qty=$quantity")
        } catch (e: Exception) {
            android.util.Log.w("SR", "with-dept failed: ${e.message} — retrying without department")
            try {
                sb.postgrest.rpc("log_service_request", buildJsonObject {
                    put("p_device_code", deviceCode); put("p_item", item)
                    put("p_quantity", quantity); put("p_raw", raw ?: item); put("p_priority", priority)
                })
                android.util.Log.d("SR", "filed OK (no dept): $item / qty=$quantity")
            } catch (e2: Exception) {
                android.util.Log.e("SR", "INSERT FAILED for $item: ${e2.message}", e2)
            }
        }
    }

    /** A recently completed, unrated request for this room (for voice feedback). */
    suspend fun recentCompletedForDevice(deviceCode: String): List<DoneReq> {
        val params: JsonObject = buildJsonObject { put("p_device_code", deviceCode) }
        return sb.postgrest.rpc("recent_completed_for_device", params).decodeList()
    }

    /** Store the guest's spoken rating (1-5) and any words; low scores red-flag. */
    suspend fun rateRequest(id: String, rating: Int, feedback: String?) {
        val params: JsonObject = buildJsonObject {
            put("p_id", id); put("p_rating", rating); put("p_feedback", feedback ?: "")
        }
        sb.postgrest.rpc("rate_request", params)
    }

    /** Next proactive update to speak in-room (cab ETA / order progress), or null. */
    suspend fun nextAnnouncement(deviceCode: String): Announcement? {
        val params: JsonObject = buildJsonObject { put("p_device_code", deviceCode) }
        return sb.postgrest.rpc("next_announcement_for_device", params)
            .decodeList<Announcement>().firstOrNull()
    }

    /** Mark a proactive update as spoken so it isn't repeated. */
    suspend fun markAnnounced(kind: String, refId: String, payload: String) {
        val params: JsonObject = buildJsonObject {
            put("p_kind", kind); put("p_id", refId); put("p_payload", payload)
        }
        sb.postgrest.rpc("mark_announced", params)
    }

    /** Heartbeat: tell reception this device is alive and what it's doing. */
    suspend fun devicePing(deviceCode: String, state: String) {
        val params: JsonObject = buildJsonObject {
            put("p_device_code", deviceCode); put("p_state", state)
        }
        runCatching { sb.postgrest.rpc("device_ping", params) }
    }

    // ---- Room service (menu + order) -----------------------------------
    suspend fun listMenu(deviceCode: String): List<MenuItem> {
        val params: JsonObject = buildJsonObject { put("p_device_code", deviceCode) }
        return sb.postgrest.rpc("list_menu", params).decodeList()
    }

    suspend fun placeRoomServiceOrder(deviceCode: String, lines: List<CartLine>) {
        val items = buildJsonArray {
            lines.forEach { l ->
                add(buildJsonObject {
                    put("menu_item_id", l.id)
                    put("name", l.name)
                    put("price", l.price)
                    put("qty", l.qty)
                })
            }
        }
        val params: JsonObject = buildJsonObject {
            put("p_device_code", deviceCode)
            put("p_items", items)
        }
        sb.postgrest.rpc("place_room_service_order", params)
    }
}