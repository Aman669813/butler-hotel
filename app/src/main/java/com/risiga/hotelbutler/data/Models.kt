package com.risiga.hotelbutler.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Row returned by get_active_stay_for_device(). Drives the greeting. */
@Serializable
data class StayInfo(
    @SerialName("stay_id")       val stayId: String? = null,
    @SerialName("property_id")   val propertyId: String,
    @SerialName("room_id")       val roomId: String,
    @SerialName("room_number")   val roomNumber: String,
    @SerialName("room_type")     val roomType: String? = null,
    @SerialName("guest_name")    val guestName: String,
    @SerialName("language_pref") val languagePref: String = "en-IN",
    @SerialName("vip")           val vip: Boolean = false,
    @SerialName("occasion")      val occasion: String? = null,
    @SerialName("company")       val company: String? = null,
    @SerialName("visit_count")   val visitCount: Int = 1
)

/** A recently completed, unrated request — the device asks the guest for feedback. */
@Serializable
data class DoneReq(
    val id: String,
    @SerialName("item_name") val item: String = "your request"
)

/** A proactive update the device should speak in-room (cab ETA, order progress). */
@Serializable
data class Announcement(
    val kind: String,
    @SerialName("ref_id") val refId: String,
    val payload: String = ""
)

/** Row returned by list_demo_rooms(). Powers the room picker. */
@Serializable
data class RoomCard(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("room_number") val roomNumber: String,
    @SerialName("room_type")   val roomType: String? = null,
    val occupied: Boolean = false
)

@Serializable
data class ServiceCatalogItem(
    val id: String,
    @SerialName("department_id") val departmentId: String? = null,
    val name: String,
    @SerialName("name_hi") val nameHi: String? = null,
    val category: String? = null,
    @SerialName("sla_minutes") val slaMinutes: Int = 15,
    val charge: Double = 0.0
)

@Serializable
data class MenuItem(
    val id: String,
    val name: String,
    @SerialName("name_hi") val nameHi: String? = null,
    val category: String? = null,
    val price: Double,
    @SerialName("is_veg") val isVeg: Boolean = true
)

@Serializable
data class NewServiceRequest(
    @SerialName("property_id") val propertyId: String,
    @SerialName("room_id")     val roomId: String,
    @SerialName("stay_id")     val stayId: String? = null,
    @SerialName("catalog_id")  val catalogId: String? = null,
    @SerialName("item_name")   val itemName: String,
    @SerialName("raw_text")    val rawText: String? = null,
    val quantity: Int = 1,
    val status: String = "new"
)

@Serializable
data class ServiceRequestRow(
    val id: String,
    @SerialName("item_name") val itemName: String? = null,
    val status: String,
    @SerialName("eta_minutes") val etaMinutes: Int? = null
)

@Serializable
data class NewRoomServiceOrder(
    @SerialName("property_id") val propertyId: String,
    @SerialName("room_id")     val roomId: String,
    @SerialName("stay_id")     val stayId: String? = null,
    val status: String = "new",
    @SerialName("total_amount") val totalAmount: Double,
    val note: String? = null
)

@Serializable
data class NewOrderItem(
    @SerialName("order_id")      val orderId: String,
    @SerialName("menu_item_id")  val menuItemId: String? = null,
    @SerialName("name_snapshot") val nameSnapshot: String,
    val qty: Int,
    @SerialName("unit_price") val unitPrice: Double,
    @SerialName("line_total") val lineTotal: Double
)

@Serializable
data class NewFolioPosting(
    @SerialName("property_id") val propertyId: String,
    @SerialName("stay_id")     val stayId: String? = null,
    @SerialName("source_type") val sourceType: String,
    @SerialName("source_id")   val sourceId: String,
    val description: String,
    val amount: Double,
    val status: String = "pending"
)

@Serializable
data class IdRow(val id: String)

/** A line in the room-service cart (not serialized directly — sent inside the RPC's JSON array). */
data class CartLine(val id: String, val name: String, val price: Double, val qty: Int)