package com.risiga.hotelbutler

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.risiga.hotelbutler.data.CartLine
import com.risiga.hotelbutler.data.HotelRepository
import com.risiga.hotelbutler.data.MenuItem
import com.risiga.hotelbutler.data.RoomCard
import com.risiga.hotelbutler.data.SessionStore
import com.risiga.hotelbutler.data.StayInfo
import com.risiga.hotelbutler.data.Announcement
import com.risiga.hotelbutler.voice.GreetingComposer
import com.risiga.hotelbutler.voice.SarvamVoice
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject

/**
 * In-room device — single room, portrait, Jehan Numa Palace × Butler concierge.
 * Polls its room; greets on check-in, farewells on checkout, and lets the guest
 * raise requests and order food entirely by voice. Every request lands live on
 * reception and the staff board.
 *
 * The conversation is driven by ONE context-aware "Butler brain" (see butlerBrain):
 * every turn the model sees the whole conversation and the real menu, and returns a
 * structured decision (what to say, which service to file, what to order). This lets
 * the guest switch topics mid-conversation like a real conversation, grounds food on
 * the real menu (no hallucinated dishes/prices), and handles Hindi/Hinglish naturally.
 * Emergencies are handled deterministically before the model, and food orders are only
 * placed after an explicit confirmation.
 */

// Butler blue + Jehan Numa Palace warmth
private val PRIMARY   = Color(0xFF1657D0)
private val PRIMARY_D = Color(0xFF0F3FA0)
private val ACCENT    = Color(0xFF2F80ED)
private val GOLD      = Color(0xFFC9A24B)
private val BG        = Color(0xFFF4F7FC)
private val CARD      = Color(0xFFFFFFFF)
private val INK       = Color(0xFF0F2742)
private val MUTED     = Color(0xFF6B7A90)
private val LINE      = Color(0xFFE6ECF5)

/** DEMO: when true, the single device follows whichever room reception activated most
 *  recently. Set to false for production (one fixed device per room, pinned by DEVICE_CODE). */
private const val FOLLOW_LATEST = true

class MainActivity : ComponentActivity() {
    private val repo = HotelRepository()
    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        micPerm.launch(Manifest.permission.RECORD_AUDIO)
        setContent { MaterialTheme { App(repo) } }
    }
}

private enum class Phase { LOADING, IDLE, GREETING, FAREWELL }

@Composable
private fun App(repo: HotelRepository) {
    val context = LocalContext.current
    val voice = remember { SarvamVoice(context, BuildConfig.SARVAM_API_KEY, BuildConfig.OPENAI_API_KEY) }

    var deviceCode by remember { mutableStateOf(if (SessionStore.demoMode && !FOLLOW_LATEST) null else SessionStore.currentDeviceCode) }
    var rooms by remember { mutableStateOf<List<RoomCard>>(emptyList()) }
    var pickerError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(deviceCode) {
        if (SessionStore.demoMode && !FOLLOW_LATEST && deviceCode == null) {
            runCatching { rooms = repo.loadDemoRooms() }.onFailure { pickerError = it.message }
        }
    }

    Surface(Modifier.fillMaxSize(), color = BG) {
        val code = deviceCode
        if (SessionStore.demoMode && !FOLLOW_LATEST && code == null) {
            if (pickerError != null) CenterText("Error: $pickerError")
            else PickerScreen(rooms) { deviceCode = it.deviceCode }
        } else {
            LiveDevice(code ?: SessionStore.currentDeviceCode, repo, voice,
                if (SessionStore.demoMode && !FOLLOW_LATEST) ({ deviceCode = null }) else null)
        }
    }
}

@Composable
private fun LiveDevice(deviceCode: String, repo: HotelRepository, voice: SarvamVoice, onBack: (() -> Unit)?) {
    var phase by remember(deviceCode) { mutableStateOf(Phase.LOADING) }
    var stay by remember(deviceCode) { mutableStateOf<StayInfo?>(null) }
    var farewellStay by remember(deviceCode) { mutableStateOf<StayInfo?>(null) }
    var greetedId by remember(deviceCode) { mutableStateOf<String?>(null) }
    var err by remember(deviceCode) { mutableStateOf<String?>(null) }

    LaunchedEffect(deviceCode) {
        while (true) {
            runCatching { if (FOLLOW_LATEST) repo.loadLatestActiveStay() else repo.loadActiveStay(deviceCode) }
                .onSuccess { active ->
                    err = null
                    if (active != null) {
                        stay = active
                        SessionStore.setStay(active)
                        if (active.stayId != greetedId) greetedId = active.stayId
                        phase = Phase.GREETING
                    } else {
                        if (greetedId != null) {
                            farewellStay = stay; greetedId = null; stay = null
                            phase = Phase.FAREWELL; SessionStore.clear()
                        } else if (phase == Phase.LOADING) phase = Phase.IDLE
                    }
                }
                .onFailure { if (phase == Phase.LOADING) err = it.message }
            delay(4000)
        }
    }

    // Heartbeat — tell reception this device is alive and which room it's serving.
    LaunchedEffect(Unit) {
        while (isActive) {
            val code = if (FOLLOW_LATEST && stay != null) "ROOM-${stay!!.roomNumber}" else deviceCode
            repo.devicePing(code, phase.name.lowercase())
            delay(10000)
        }
    }

    when (phase) {
        Phase.LOADING -> CenterText(err?.let { "Error: $it" } ?: "Connecting to Jehan Numa Palace…")
        Phase.IDLE -> IdleScreen(deviceCode, onBack)
        Phase.GREETING -> stay?.let {
            val effCode = if (FOLLOW_LATEST) "ROOM-${it.roomNumber}" else deviceCode
            VoiceConcierge(it, effCode, repo, voice, onBack)
        }
        Phase.FAREWELL -> farewellStay?.let { FarewellScreen(it, voice) { phase = Phase.IDLE } }
    }
}

private fun roomLabelOf(code: String) = code.substringAfter("ROOM-", code)

// ---------------- Voice concierge (hands-free, no touch) ----------------
private enum class VoicePhase { GREETING, IDLE, LISTENING, THINKING, SPEAKING }

/** After Butler speaks, wait this long before listening so the mic doesn't catch its own tail. */
private const val MIC_SETTLE_MS = 500L

private fun isWake(t: String): Boolean {
    val s = t.lowercase()
    return s.contains("butler") || s.contains("butlr") || s.contains("batler") ||
            s.contains("बटलर") || s.contains("बट्लर")
}
private fun wakeHint(lang: String)      = if (lang == "hi-IN") "कहिए  “हे बटलर”" else "Say  “Hey Butler”"
private fun listeningHint(lang: String) = if (lang == "hi-IN") "सुन रहा हूँ…" else "Listening…"
private fun thinkingHint(lang: String)  = if (lang == "hi-IN") "एक पल…" else "One moment…"
private fun promptYes(lang: String)     = if (lang == "hi-IN") "जी, मैं सुन रहा हूँ। बताइए, मैं आपकी क्या मदद कर सकता हूँ?" else "Yes, I'm listening. How may I help you?"

@Composable
private fun VoiceConcierge(
    stay: StayInfo, deviceCode: String, repo: HotelRepository, voice: SarvamVoice, onBack: (() -> Unit)?
) {
    val greeting = remember(stay) { GreetingComposer.compose(stay) }
    val language = remember(stay) { GreetingComposer.languageOf(stay) }
    val firstName = stay.guestName.trim().split(" ").firstOrNull() ?: stay.guestName

    var vphase by remember(stay.stayId) { mutableStateOf(VoicePhase.GREETING) }
    var status by remember(stay.stayId) { mutableStateOf("") }
    val turns = remember(stay.stayId) { mutableStateListOf<Pair<String, String>>() }
    val menuCache = remember(stay.stayId) { mutableListOf<MenuItem>() }

    LaunchedEffect(stay.stayId) {
        // 1) Greet on check-in, then fall silent
        vphase = VoicePhase.SPEAKING; status = ""
        runCatching { voice.speak(greeting, language) }
        runCatching { voice.prewarm(promptYes(language), language) }   // cache the first wake reply → instant
        delay(MIC_SETTLE_MS)

        var menuTried = false
        while (isActive) {
            // Proactive spoken updates: cab ETA / order progress — each announced once.
            val ann = runCatching { repo.nextAnnouncement(deviceCode) }.getOrNull()
            if (ann != null && isActive) {
                announcementLine(ann, language)?.let { line ->
                    vphase = VoicePhase.SPEAKING; status = ""
                    runCatching { voice.speak(line, language) }
                    delay(MIC_SETTLE_MS)
                }
                runCatching { repo.markAnnounced(ann.kind, ann.refId, ann.payload) }
            }

            // Proactive feedback: if a request was just completed, ask how it went.
            val done = runCatching { repo.recentCompletedForDevice(deviceCode) }.getOrNull().orEmpty()
            if (done.isNotEmpty() && isActive) {
                val fb = done.first()
                vphase = VoicePhase.SPEAKING; status = ""
                runCatching { voice.speak(feedbackPrompt(fb.item, language), language) }
                delay(MIC_SETTLE_MS)
                vphase = VoicePhase.LISTENING; status = listeningHint(language)
                val fw = voice.listen(maxMs = 9000, silenceMs = 1200, startTimeoutMs = 6000)
                val ans = if (fw != null) runCatching { voice.transcribe(fw, language) }.getOrNull()?.trim().orEmpty() else ""
                val rating = when { ans.isBlank() -> 4; isNegative(ans) -> 2; isPositive(ans) -> 5; else -> 4 }
                runCatching { repo.rateRequest(fb.id, rating, ans) }
                if (rating <= 2) runCatching {
                    repo.logServiceRequest(deviceCode, "Complaint", 1, ans.ifBlank { "Guest dissatisfied with ${fb.item}" }, "urgent")
                }
                vphase = VoicePhase.SPEAKING
                runCatching { voice.speak(if (rating <= 2) sorryLine(language) else thanksLine(language), language) }
                delay(MIC_SETTLE_MS)
            }

            // 2) Silent — wait for the wake word "Hey Butler"
            vphase = VoicePhase.IDLE; status = wakeHint(language)
            var woke = false
            while (isActive && !woke) {
                val w = voice.listen(maxMs = 4500, silenceMs = 700, startTimeoutMs = 4000)
                if (w != null) {
                    val heard = runCatching { voice.transcribe(w, "en-IN") }.getOrNull().orEmpty()
                    if (isWake(heard)) { woke = true; continue }
                }
                val idleAnn = runCatching { repo.nextAnnouncement(deviceCode) }.getOrNull()
                if (idleAnn != null && isActive) {
                    announcementLine(idleAnn, language)?.let { line ->
                        vphase = VoicePhase.SPEAKING; status = ""
                        runCatching { voice.speak(line, language) }
                        delay(MIC_SETTLE_MS)
                        vphase = VoicePhase.IDLE; status = wakeHint(language)
                    }
                    runCatching { repo.markAnnounced(idleAnn.kind, idleAnn.refId, idleAnn.payload) }
                }
            }
            if (!isActive) break

            // 3) Awake — converse until the guest is done or goes quiet
            vphase = VoicePhase.SPEAKING; status = ""
            runCatching { voice.speak(promptYes(language), language) }
            delay(MIC_SETTLE_MS)
            var silent = 0
            while (isActive) {
                vphase = VoicePhase.LISTENING; status = listeningHint(language)
                val wav = voice.listen(maxMs = 13000, silenceMs = 1300, startTimeoutMs = 7000)
                if (wav == null) { silent++; if (silent >= 1) break else continue }
                silent = 0

                vphase = VoicePhase.THINKING; status = thinkingHint(language)
                val (saidRaw, detLang) = runCatching { voice.transcribeAuto(wav) }.getOrNull() ?: ("" to "")
                val said = saidRaw.trim()
                Log.d("STT", "RAW: $said")
                if (said.isBlank()) continue
                val turnLang = if (detLang.startsWith("hi") || said.any { it.code in 0x0900..0x097F }) "hi-IN" else "en-IN"
                turns.add("user" to said)
                Log.d("CONVERSATION", "USER: $said")

                // Emergency — deterministic, never wait on the model.
                if (isEmergency(said)) {
                    runCatching { repo.logServiceRequest(deviceCode, "Emergency / SOS", 1, said, "urgent") }
                    val r = emergencyLine(turnLang)
                    turns.add("assistant" to r); Log.d("CONVERSATION", "ASSISTANT: $r")
                    vphase = VoicePhase.SPEAKING; status = ""
                    runCatching { voice.speak(r, turnLang) }
                    delay(MIC_SETTLE_MS); continue
                }

                // Try once per session to load the real menu (if one exists) so food can be grounded.
                if (!menuTried) {
                    menuTried = true
                    runCatching { repo.listMenu(deviceCode) }.getOrNull()?.let { menuCache.addAll(it) }
                }

                // One context-aware brain turn: it decides intent, order, and reply together,
                // seeing the whole conversation — so the guest can switch topics any time.
                val brainPrompt = buildBrainPrompt(stay, menuCache.toList())
                val result = butlerBrain(brainPrompt, turns.toList(), menuCache.toList(), voice)

                val reply = if (result == null) graceful(turnLang) else {
                    // File a staff ticket if the brain identified one (emergency handled above).
                    result.service?.takeIf { it != "Emergency / SOS" }?.let { svc ->
                        val label = if (svc == "Cab booking") svc + cabDetailSuffix(said) else svc
                        runCatching { repo.logServiceRequest(deviceCode, label, 1, said, priorityFor(svc)) }
                    }
                    // Place a food order ONLY when the guest has confirmed; prices come from the real menu.
                    if (result.placeOrder && result.order.isNotEmpty()) {
                        runCatching {
                            repo.placeRoomServiceOrder(deviceCode,
                                result.order.map { CartLine(it.item.id, it.item.name, it.item.price, it.qty) })
                        }
                    }
                    result.reply.ifBlank { graceful(turnLang) }
                }

                turns.add("assistant" to reply)
                Log.d("CONVERSATION", "ASSISTANT: $reply")
                vphase = VoicePhase.SPEAKING; status = ""
                runCatching { voice.speak(reply, ttsLangOf(reply)) }
                if (result?.end == true) break
                delay(MIC_SETTLE_MS)
            }
            // loop back to silent wake-listening
        }
    }

    // ---- UI: ambient, no controls ----
    val orbColor = when (vphase) {
        VoicePhase.LISTENING -> ACCENT
        VoicePhase.THINKING  -> GOLD
        VoicePhase.SPEAKING  -> PRIMARY
        else                 -> Color(0xFF6B7A90)
    }
    val anim = rememberInfiniteTransition(label = "orb")
    val pulse by anim.animateFloat(
        initialValue = 0.92f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "pulse")
    val live = vphase == VoicePhase.LISTENING || vphase == VoicePhase.SPEAKING || vphase == VoicePhase.THINKING
    val scale = if (live) pulse else 1f

    val lastUser = turns.lastOrNull { it.first == "user" }?.second
    val lastButler = turns.lastOrNull { it.first == "assistant" }?.second

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B2545), Color(0xFF12325E))))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 30.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("\uD83D\uDECE\uFE0F  JEHAN NUMA · BUTLER", color = Color(0xFFBFD2F5),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x33FFFFFF))
                .padding(horizontal = 12.dp, vertical = 5.dp)) {
                Text("Room ${stay.roomNumber}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 30.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size((150 * scale).dp).clip(RoundedCornerShape(percent = 50))
                    .background(Brush.radialGradient(listOf(orbColor.copy(alpha = .95f), orbColor.copy(alpha = .35f)))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when (vphase) {
                        VoicePhase.LISTENING -> "\uD83C\uDFA4"
                        VoicePhase.THINKING  -> "\u2026"
                        VoicePhase.SPEAKING  -> "\uD83D\uDD0A"
                        else                 -> "\uD83D\uDECE\uFE0F"
                    }, fontSize = 46.sp
                )
            }
            Spacer(Modifier.height(30.dp))
            Text(
                if (vphase == VoicePhase.IDLE) wakeHint(language) else status.ifEmpty { "…" },
                color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (vphase == VoicePhase.IDLE) "I'm here whenever you need me, $firstName."
                else "Speak naturally — I'll take care of it.",
                color = Color(0xFF9FB3D1), fontSize = 13.sp, textAlign = TextAlign.Center
            )

            if (lastUser != null) {
                Spacer(Modifier.height(34.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(Color(0x1AFFFFFF)).padding(16.dp)) {
                    Column {
                        Text("You", color = Color(0xFF9FB3D1), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(lastUser, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp)
                        if (lastButler != null) {
                            Spacer(Modifier.height(12.dp))
                            Text("BUTLER", color = Color(0xFF8FE3B0), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text(lastButler, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp)
                        }
                    }
                }
            }
        }

        if (onBack != null) {
            OutlinedButton(onClick = onBack, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)) {
                Text("← Back to rooms", color = Color(0xFFBFD2F5))
            }
        }
    }
}

// ================= Butler brain (context-aware, every turn) =================

private data class RsLine(val item: MenuItem, val qty: Int)

private data class BrainResult(
    val reply: String,
    val service: String?,        // a staff ticket to file, or null
    val order: List<RsLine>,     // food items resolved against the real menu
    val placeOrder: Boolean,     // true only when the guest has confirmed the order
    val end: Boolean             // true when the guest is done
)

/** The set of staff tickets the brain may raise. Anything else it returns is ignored. */
private val SERVICE_LABELS = setOf(
    "Fresh towels", "Laundry", "Cab booking", "Housekeeping", "Room amenities", "Maintenance",
    "Spa appointment", "Wake-up call", "Front desk", "Doctor / medical", "Late checkout request",
    "Complaint", "Lost & found", "Emergency / SOS", "Room service request"
)

/** Pulls the first {...} JSON object out of a model reply and parses it; null on failure. */
private fun parseJsonObject(raw: String): JSONObject? {
    val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
    if (a < 0 || b <= a) return null
    return runCatching { JSONObject(raw.substring(a, b + 1)) }.getOrNull()
}

private fun buildBrainPrompt(stay: StayInfo, menu: List<MenuItem>): String {
    val menuText = if (menu.isEmpty()) "(menu temporarily unavailable)"
    else menu.joinToString("\n") { "${it.id} | ${it.name} | ₹${"%.0f".format(it.price)}${if (it.isVeg) " (veg)" else ""}" }
    return """
        You are Butler, the warm in-room concierge at Jehan Numa Palace, Bhopal, speaking with ${stay.guestName} in Room ${stay.roomNumber}.

        Reply ONLY with a single JSON object and nothing else, no markdown:
        {"reply":"<what to say>","service":"<one label or none>","order":[{"id":"<menu id>","qty":<int>}],"place_order":<true|false>,"end":<true|false>}

        Rules:
        - "reply": 1-2 short, natural sentences, like a gracious concierge. Reply in the SAME language the guest just used (Hindi to natural Hindi, English to English). Never mix scripts. Never ask the guest to repeat or rephrase — give your best answer.
        - Always answer the guest's MOST RECENT message. The topic can change at any time: if they were ordering food and then ask to clean the bathroom, switch immediately — set "service":"Housekeeping" and stop talking about food.
        - "service": pick the ONE label that matches a staff task, otherwise "none". Allowed labels: Fresh towels, Laundry, Cab booking, Housekeeping, Room amenities, Maintenance, Spa appointment, Wake-up call, Front desk, Doctor / medical, Late checkout request, Complaint, Lost & found, Emergency / SOS, Room service request.
        - Food: if the MENU below is empty or shows "unavailable", do NOT invent any dish or price — instead set "service":"Room service request" and "order":[], and tell the guest you have noted their food request and the kitchen will follow up to take their order. Only when the MENU lists real items: use ONLY ids from the MENU — never invent dishes or prices. Put chosen items in "order" with quantities. When the guest asks what is available, name 3-5 real items from the MENU. Read the order back (items + total using the MENU prices) and ask them to confirm. Set "place_order":true ONLY on the turn the guest confirms (yes / place it / go ahead), and on that turn include the full order in "order". Otherwise "place_order":false and "order":[]. If they ask for something not on the MENU, say it is unavailable and suggest a close item.
        - Questions about the hotel or Bhopal: answer concretely from the facts below; never invent prices or room details — offer the front desk if unsure.
        - "end": true only when the guest says goodbye or that they need nothing more.

        HOTEL FACTS: breakfast 7:00-10:30am at the all-day diner; checkout 12 noon (late checkout on request); pool 6am-9pm; fitness centre 24h; spa 9am-9pm; Wi-Fi network 'JehanNuma_Guest'.
        BHOPAL TIPS: Upper Lake (Bada Talab), Van Vihar National Park, Taj-ul-Masajid, Gohar Mahal, Bharat Bhavan, Tribal Museum; UNESCO day trips to Sanchi Stupa and Bhimbetka; street food at Chatori Gali; must-try breakfast poha-jalebi.

        MENU:
        $menuText
    """.trimIndent()
}

/** One conversational turn. Returns null if the model call/parse fails (caller falls back gracefully). */
private suspend fun butlerBrain(
    sysPrompt: String, history: List<Pair<String, String>>, menu: List<MenuItem>, voice: SarvamVoice
): BrainResult? {
    val msgs = listOf("system" to sysPrompt) + history.takeLast(16)
    val raw = runCatching { voice.chat(msgs) }
        .onFailure { Log.e("ButlerBrain", "chat failed", it) }.getOrNull().orEmpty()
    val obj = parseJsonObject(raw) ?: return null
    val reply = obj.optString("reply").trim()
    val service = obj.optString("service").trim().takeIf { it in SERVICE_LABELS }
    val order = mutableListOf<RsLine>()
    obj.optJSONArray("order")?.let { arr ->
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim(); if (id.isBlank()) continue
            val qty = o.optInt("qty", 1).coerceIn(1, 20)
            menu.firstOrNull { it.id == id }?.let { order.add(RsLine(it, qty)) }   // ignore invented ids
        }
    }
    return BrainResult(reply, service, order, obj.optBoolean("place_order", false), obj.optBoolean("end", false))
}

// ---------------- Deterministic safety + helpers ----------------

/** Life-safety requests are handled before the model so they are never delayed. */
private fun isEmergency(t: String): Boolean {
    val s = t.lowercase()
    return listOf(
        "emergency", "help me", "sos", "fire ", "accident", "heart attack", "ambulance",
        "can't breathe", "cant breathe", "आपातकाल", "मदद कर", "बचाओ", "आग लग", "एम्बुलेंस", "दुर्घटना", "साँस"
    ).any { s.contains(it) }
}

private fun emergencyLine(lang: String) = if (lang == "hi-IN")
    "मैं तुरंत हमारी टीम को सूचित कर रहा हूँ — मदद आ रही है। कृपया शांत रहें और वहीं रहें।"
else "I'm alerting our team right now — help is on the way. Please stay calm and remain where you are."

private fun graceful(lang: String) = if (lang == "hi-IN")
    "ज़रूर, मैं अभी हमारी फ्रंट डेस्क से आपकी सहायता करवाता हूँ।"
else "Certainly — I'll have our front desk help you with that right away."

/** Pick the TTS voice from the text itself, so a switch in language is spoken correctly. */
private fun ttsLangOf(text: String): String =
    if (text.any { it.code in 0x0900..0x097F }) "hi-IN" else "en-IN"

/** Pulls a destination out of a cab request so the front desk sees "Cab booking — Airport". */
private fun cabDetailSuffix(t: String): String {
    val s = t.lowercase()
    val dest = when {
        s.contains("airport") || s.contains("एयरपोर्ट") || s.contains("हवाई") -> "Airport"
        s.contains("station") || s.contains("स्टेशन") || s.contains("रेलवे") -> "Railway station"
        s.contains("mall") || s.contains("मॉल") -> "Mall"
        else -> Regex("(?:to the |to |के लिए )([a-z][a-z ]{2,22})").find(s)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.length > 2 }?.replaceFirstChar { it.uppercase() }
    }
    return if (dest != null) " — $dest" else ""
}

/** Urgent for complaints/lost items/emergency; high for late checkout; normal otherwise. */
private fun priorityFor(label: String): String = when (label) {
    "Emergency / SOS", "Complaint", "Lost & found" -> "urgent"
    "Late checkout request" -> "high"
    else -> "normal"
}

// ---------------- Proactive spoken updates ----------------
private fun announcementLine(a: Announcement, lang: String): String? {
    val hi = lang == "hi-IN"
    return when (a.kind) {
        "service_status" -> {
            val p = a.payload.split("|")
            serviceStatusLine(p.getOrNull(0).orEmpty(), p.getOrNull(1).orEmpty(), p.getOrNull(2)?.toIntOrNull(), hi)
        }
        "cab_eta" -> if (hi) "अच्छी ख़बर — आपकी कैब लगभग ${a.payload} मिनट में पहुँच रही है।"
        else "Good news — your cab will arrive in about ${a.payload} minutes."
        "order_status" -> when (a.payload) {
            "accepted"    -> if (hi) "आपका ऑर्डर रसोई ने स्वीकार कर लिया है।" else "Your order has been confirmed by the kitchen."
            "in_progress" -> if (hi) "आपका ऑर्डर आपके कमरे की ओर आ रहा है।" else "Your order is on its way up to your room."
            "done"        -> if (hi) "आपका ऑर्डर पहुँचा दिया गया है। आनंद लें!" else "Your order has been delivered. Enjoy!"
            else -> null
        }
        else -> null
    }
}

/** Friendly spoken update for any service request the staff acted on. payload = "status|item|eta". */
private fun serviceStatusLine(status: String, itemRaw: String, eta: Int?, hi: Boolean): String? {
    val it = itemRaw.lowercase()
    val isCab = it.contains("cab") || it.contains("taxi") || it.contains("airport")
    val isSpa = it.contains("spa") || it.contains("massage")
    val name = when {
        isCab -> if (hi) "कैब" else "cab"
        isSpa -> if (hi) "स्पा अपॉइंटमेंट" else "spa appointment"
        it.contains("towel") -> if (hi) "तौलिये" else "towels"
        it.contains("housekeep") || it.contains("clean") -> if (hi) "हाउसकीपिंग" else "housekeeping"
        it.contains("maintenance") || it.contains(" ac") || it.contains("repair") -> if (hi) "मेंटेनेंस अनुरोध" else "maintenance request"
        it.contains("laundry") -> if (hi) "लॉन्ड्री" else "laundry"
        it.contains("wake") -> if (hi) "वेक-अप कॉल" else "wake-up call"
        it.contains("water") || it.contains("amenit") -> if (hi) "सामान" else "amenities"
        else -> if (hi) "अनुरोध" else "request"
    }
    val etaEn = if (eta != null) " in about $eta minutes" else ""
    val etaHi = if (eta != null) " लगभग $eta मिनट में" else ""
    return when (status) {
        "accepted" -> when {
            isCab -> if (hi) "अच्छी ख़बर — आपकी कैब पक्की हो गई है और$etaHi पहुँच रही है।" else "Good news — your cab is confirmed and will arrive$etaEn."
            isSpa -> if (hi) "आपका स्पा अपॉइंटमेंट पक्का हो गया है — हमारी टीम$etaHi आपके पास आएगी।" else "Your spa appointment is confirmed — our therapist will be with you$etaEn."
            else  -> if (hi) "आपका $name स्वीकार कर लिया गया है$etaHi।" else "Your $name has been confirmed${if (eta != null) ", and will be attended$etaEn" else ""}."
        }
        "in_progress" -> when {
            isCab -> if (hi) "आपकी कैब रास्ते में है$etaHi।" else "Your cab is on the way${if (eta != null) ", about $eta minutes away" else ""}."
            else  -> if (hi) "आपका $name अभी तैयार किया जा रहा है$etaHi।" else "Your $name is being taken care of right now${if (eta != null) ", about $eta minutes" else ""}."
        }
        "done" -> when {
            isCab -> if (hi) "आपकी कैब प्रवेश द्वार पर पहुँच गई है। आपकी यात्रा शुभ हो!" else "Your cab has arrived at the entrance. Have a pleasant trip!"
            else  -> if (hi) "आपका $name पूरा हो गया है। और कुछ चाहिए तो बताइएगा!" else "Your $name is all set. Do let me know if there's anything else!"
        }
        else -> null
    }
}

// ---------------- Feedback helpers ----------------
private fun feedbackPrompt(item: String, lang: String) = if (lang == "hi-IN")
    "आपका अनुरोध — $item — पूरा कर दिया गया है। क्या आप हमारी सेवा से संतुष्ट हैं?"
else "Your request — $item — has been completed. Was everything to your satisfaction?"
private fun thanksLine(lang: String) = if (lang == "hi-IN") "बहुत बढ़िया! आपका दिन शुभ हो।" else "Wonderful! Do enjoy your stay."
private fun sorryLine(lang: String) = if (lang == "hi-IN")
    "मुझे खेद है। मैंने हमारे ड्यूटी मैनेजर को सूचित कर दिया है, वे जल्द ही आपकी सहायता करेंगे।"
else "I'm sorry to hear that. I've informed our duty manager, who will attend to it right away."
private fun isPositive(t: String): Boolean { val s = t.lowercase()
    return Regex("\\b(yes|yeah|yep|great|good|perfect|satisfied|happy|thanks|thank you|excellent|nice|fine|okay|ok)\\b").containsMatchIn(s) ||
            s.contains("हाँ") || s.contains("जी हाँ") || s.contains("अच्छा") || s.contains("बढ़िया") || s.contains("धन्यवाद") || s.contains("शुक्रिया") || s.contains("संतुष्ट") || s.contains("परफेक्ट") }
private fun isNegative(t: String): Boolean { val s = t.lowercase()
    return Regex("\\b(no|not|bad|poor|terrible|worst|unhappy|dirty|cold|slow|disappointed|rude)\\b").containsMatchIn(s) ||
            s.contains("नहीं") || s.contains("बुरा") || s.contains("ख़राब") || s.contains("खराब") || s.contains("गंदा") || s.contains("देर") || s.contains("ठंडा") || s.contains("नाराज") }

// ---------------- Idle (room empty) ----------------
@Composable
private fun IdleScreen(deviceCode: String, onBack: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().padding(36.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("\uD83D\uDECE\uFE0F", fontSize = 44.sp)
        Spacer(Modifier.height(18.dp))
        Text("Jehan Numa Palace Butler", color = PRIMARY, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Text("Room ${roomLabelOf(deviceCode)}", color = INK, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Ready for the next guest", color = MUTED, fontSize = 15.sp)
        if (onBack != null) { Spacer(Modifier.height(40.dp)); OutlinedButton(onClick = onBack) { Text("← Back to rooms") } }
    }
}

// ---------------- Farewell (checkout) ----------------
@Composable
private fun FarewellScreen(stay: StayInfo, voice: SarvamVoice, onDone: () -> Unit) {
    val message = remember(stay.stayId) { GreetingComposer.composeFarewell(stay) }
    val language = remember(stay.stayId) { GreetingComposer.languageOf(stay) }
    LaunchedEffect(stay.stayId) {
        runCatching { voice.speak(message, language) }
        delay(9000); onDone()
    }
    Column(Modifier.fillMaxSize().padding(36.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("\uD83D\uDE4F", fontSize = 44.sp)
        Spacer(Modifier.height(18.dp))
        Text(message, color = INK, fontSize = 22.sp, lineHeight = 32.sp, textAlign = TextAlign.Center)
    }
}

// ---------------- Demo picker ----------------
@Composable
private fun PickerScreen(rooms: List<RoomCard>, onPick: (RoomCard) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().background(PRIMARY).padding(20.dp)) {
            Text("Jehan Numa Palace Butler  ·  Choose a room to simulate",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        if (rooms.isEmpty()) { CenterText("Loading rooms…"); return@Column }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(rooms) { r ->
                Column(
                    Modifier.fillMaxWidth().height(96.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (r.occupied) CARD else Color(0xFFE6ECF5))
                        .clickable { onPick(r) }.padding(14.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Room ${r.roomNumber}", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = PRIMARY)
                    Text(r.roomType ?: "", fontSize = 12.sp, color = MUTED)
                    Spacer(Modifier.height(4.dp))
                    Text(if (r.occupied) "Occupied" else "Vacant", fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, color = if (r.occupied) ACCENT else MUTED)
                }
            }
        }
    }
}

@Composable
private fun CenterText(t: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(t, fontSize = 16.sp, color = PRIMARY, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
    }
}