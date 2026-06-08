package com.risiga.hotelbutler.data

import com.risiga.hotelbutler.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory session for the device.
 *
 * In production each unit is flashed with its own DEVICE_CODE and currentDeviceCode
 * never changes. In demo mode the room picker sets currentDeviceCode at runtime so
 * the owner can walk any room.  Language uses full locale codes (en-IN / hi-IN).
 */
object SessionStore {

    val demoMode: Boolean = BuildConfig.DEMO_MODE

    @Volatile var currentDeviceCode: String = BuildConfig.DEVICE_CODE

    private val _stay = MutableStateFlow<StayInfo?>(null)
    val stay: StateFlow<StayInfo?> = _stay

    fun setStay(info: StayInfo?) { _stay.value = info }
    fun clear() { _stay.value = null; greetedStayId = null }

    val language: String get() = _stay.value?.languagePref ?: "en-IN"
    val guestName: String get() = _stay.value?.guestName ?: "Guest"

    /** First-entry-of-session guard so the greeting fires once per stay visit. */
    @Volatile private var greetedStayId: String? = null
    fun shouldGreet(): Boolean {
        val id = _stay.value?.stayId ?: return false
        return id != greetedStayId
    }
    fun markGreeted() { greetedStayId = _stay.value?.stayId }
}