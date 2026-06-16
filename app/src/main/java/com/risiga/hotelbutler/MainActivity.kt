package com.risiga.hotelbutler

import android.Manifest
import org.json.JSONObject
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-room device — single room, portrait, Jehan Numa Palace × Butler concierge.
 * Polls its room; greets on check-in, farewells on checkout, and lets the
 * guest raise service requests (towels / food / housekeeping / spa / maintenance)
 * by tap or voice — each lands live on reception and the staff board.
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
 *  recently — so management can test ANY room (check in → device greets that room/guest).
 *  Set to false for production (one fixed device per room, pinned by DEVICE_CODE). */
private const val FOLLOW_LATEST = true

private data class Service(val icon: String, val title: String, val sub: String)

private val SERVICES = listOf(
    Service("\uD83D\uDEC1", "Fresh Towels", "Housekeeping"),
    Service("\uD83C\uDF7D\uFE0F", "Room Service", "Order food & drinks"),
    Service("\uD83E\uDDF9", "Housekeeping", "Clean / tidy my room"),
    Service("\uD83D\uDC86", "Spa & Wellness", "Book a session"),
    Service("\uD83D\uDD27", "Maintenance", "Plumbing / electrical"),
    Service("\uD83D\uDE96", "Book a Cab", "Taxi / airport drop"),
    Service("\uD83E\uDDFA", "Laundry", "Wash & press"),
    Service("\u23F0", "Wake-up Call", "Set a reminder"),
    Service("\uD83D\uDECE\uFE0F", "Front Desk", "Speak to reception")
)

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
                        // Keep the screen in sync with the latest data on every poll, so a
                        // front-desk correction (occasion, name, language) reflects live.
                        stay = active
                        SessionStore.setStay(active)
                        // Track the spoken stay so the farewell can fire on checkout.
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

// ---------------- Purely-voice concierge (hands-free, no touch) ----------------
private enum class VoicePhase { GREETING, IDLE, LISTENING, THINKING, SPEAKING }

/** After Butler speaks, wait this long before listening so the mic doesn't catch its own tail. */
private const val MIC_SETTLE_MS = 500L

private fun isWake(t: String): Boolean {
    val s = t.lowercase()
    return s.contains("butler") || s.contains("butlr") || s.contains("batler") ||
            s.contains("बटलर") || s.contains("बट्लर")
}
private fun isStopPhrase(t: String): Boolean {
    val s = t.lowercase()
    return s.contains("that's all") || s.contains("thats all") || s.contains("that is all") ||
            s.contains("no thanks") || s.contains("no thank") || s.contains("nothing else") ||
            s.contains("good bye") || s.contains("goodbye") || s.contains(" bye") || s == "bye" ||
            s.contains("बस ") || s.endsWith("बस") || s.contains("धन्यवाद") || s.contains("शुक्रिया")
}
private fun wakeHint(lang: String)      = if (lang == "hi-IN") "कहिए  “हे बटलर”" else "Say  “Hey Butler”"
private fun listeningHint(lang: String) = if (lang == "hi-IN") "सुन रहा हूँ…" else "Listening…"
private fun thinkingHint(lang: String)  = if (lang == "hi-IN") "एक पल…" else "One moment…"
private fun promptYes(lang: String)     = if (lang == "hi-IN") "जी, मैं सुन रहा हूँ। बताइए, मैं आपकी क्या मदद कर सकता हूँ?" else "Yes, I'm listening. How may I help you?"
private fun closingLine(lang: String)   = if (lang == "hi-IN") "जी ज़रूर। जब भी ज़रूरत हो, बस कहिए  “हे बटलर”।" else "Of course. Whenever you need me, just say  “Hey Butler”."
private fun fallbackLine(lang: String)  = if (lang == "hi-IN") "क्षमा कीजिए, अभी थोड़ी दिक्कत आ रही है। मैंने आपका अनुरोध नोट कर लिया है।" else "I'm sorry, I'm having a little trouble just now. I've noted your request for our team."

@Composable
private fun VoiceConcierge(
    stay: StayInfo, deviceCode: String, repo: HotelRepository, voice: SarvamVoice, onBack: (() -> Unit)?
) {
    val greeting = remember(stay) { GreetingComposer.compose(stay) }
    val language = remember(stay) { GreetingComposer.languageOf(stay) }
    val sys = remember(stay.stayId) { buildSystemPrompt(stay) }
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
                // While idle, keep delivering proactive updates in real time —
                // staff accepting a request, setting an ETA, marking on-the-way, completing, etc.
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
            var spaAsked = false   // spa slot-fill: ask once for treatment + time, then confirm
            while (isActive) {
                vphase = VoicePhase.LISTENING; status = listeningHint(language)
                val wav = voice.listen(maxMs = 13000, silenceMs = 1300, startTimeoutMs = 7000)
                if (wav == null) { silent++; if (silent >= 1) break else continue }
                silent = 0

                vphase = VoicePhase.THINKING; status = thinkingHint(language)
                val (saidRaw, detLang) = runCatching { voice.transcribeAuto(wav) }.getOrNull() ?: ("" to "")
                val said = saidRaw.trim()
                if (said.isBlank()) continue
                // Answer in the language the guest actually used THIS turn (auto-detected), so an
                // English question gets an English answer even in a Hindi-default room, and vice-versa.
                val turnLang = if (detLang.startsWith("hi") || said.any { it.code in 0x0900..0x097F }) "hi-IN" else "en-IN"
                turns.add("user" to said)

                // file a ticket for EVERY actionable request in the utterance
                val rawItems = detectAllServiceItems(said)
                // a question ("where can I eat", "what's nearby?") should be answered by the AI, not logged as an order
                val items = if (isInfoQuestion(said))
                    rawItems.filter { it == "Emergency / SOS" || it == "Complaint" || it == "Doctor / medical" }
                else rawItems
                val stop = isStopPhrase(said)
                val askingSpa = items.contains("Spa appointment") && !spaAsked        // first spa mention → ask for type/time
                val consumingSpaPref = spaAsked && !askingSpa && !stop                 // the next utterance carries the preference

                // Room service → real menu-grounded voice ordering (replaces the canned confirm)
                if (items.contains("Room service request") &&
                    !items.contains("Emergency / SOS") && !items.contains("Complaint")) {
                    items.filter { it != "Room service request" && it != "Guest request" }.forEach { other ->
                        val logLabel = if (other == "Cab booking") other + cabDetailSuffix(said) else other
                        runCatching { repo.logServiceRequest(deviceCode, logLabel, 1, said, priorityFor(other)) }
                    }
                    handleRoomService(
                        firstUtterance = said, lang = turnLang, deviceCode = deviceCode,
                        repo = repo, voice = voice, menuCache = menuCache,
                        setPhase = { vphase = it }, setStatus = { status = it },
                        addTurn = { role, text -> turns.add(role to text) }
                    )
                    delay(MIC_SETTLE_MS)
                    continue
                }
                if (!consumingSpaPref) items.forEach { item ->
                    if (askingSpa && item == "Spa appointment") return@forEach         // hold spa ticket until preference is given
                    val logLabel = if (item == "Cab booking") item + cabDetailSuffix(said) else item
                    runCatching { repo.logServiceRequest(deviceCode, logLabel, 1, said, priorityFor(item)) }
                }

                val reply: String = when {
                    items.contains("Emergency / SOS") -> emergencyLine(turnLang)   // top priority
                    items.contains("Complaint") -> apologyLine(turnLang)   // empathetic, red-flagged
                    askingSpa          -> { spaAsked = true; spaLine(turnLang) }   // offer treatments + ask which/when
                    consumingSpaPref   -> {                                        // preference given → log it with detail + confirm
                        spaAsked = false
                        runCatching { repo.logServiceRequest(deviceCode, "Spa appointment", 1, said, priorityFor("Spa appointment")) }
                        spaConfirm(turnLang)
                    }
                    isWeatherQuery(said) -> voice.weather(turnLang)        // live Bhopal weather + "planning to visit nearby?" follow-up
                    items.isNotEmpty() -> confirmLine(items, turnLang)     // deterministic — never depends on the LLM
                    stop               -> closingLine(turnLang)
                    else               -> {
                        // Common info questions (dining, sightseeing, hotel facts) are answered from
                        // curated Bhopal knowledge FIRST — concrete, instant, and never hedges. The LLM
                        // handles everything else; if it hedges or fails, fall back to curated/graceful.
                        val curated = if (isInfoQuestion(said)) localAnswer(said, turnLang) else null
                        if (curated != null) curated
                        else {
                            val ai = runCatching { voice.chat(listOf("system" to sys) + turns.toList()) }
                                .onFailure { Log.e("ButlerChat", "chat failed", it) }
                                .getOrNull()?.trim().orEmpty()
                            if (ai.isNotBlank() && !looksEvasive(ai)) ai
                            else (localAnswer(said, turnLang) ?: graceful(turnLang))
                        }
                    }
                }
                turns.add("assistant" to reply)
                vphase = VoicePhase.SPEAKING; status = ""
                runCatching { voice.speak(reply, turnLang) }
                if (items.isEmpty() && stop) break
                delay(MIC_SETTLE_MS)   // let the room/speaker settle so the mic doesn't hear Butler's tail
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
        // top bar
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
            // pulsing orb
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

            // last exchange (visual confirmation)
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

// ---------------- Concierge (guest present) ----------------
@Composable
private fun ConciergeScreen(
    stay: StayInfo, deviceCode: String, repo: HotelRepository, voice: SarvamVoice, onBack: (() -> Unit)?
) {
    val scope = rememberCoroutineScope()
    val greeting = remember(stay) { GreetingComposer.compose(stay) }
    val language = remember(stay) { GreetingComposer.languageOf(stay) }
    val firstName = stay.guestName.trim().split(" ").firstOrNull() ?: stay.guestName

    var pending by remember { mutableStateOf<Service?>(null) }     // confirmation dialog
    var showMenu by remember { mutableStateOf(false) }             // room-service menu
    var showConvo by remember { mutableStateOf(false) }            // talk-to-Butler conversation
    var banner by remember { mutableStateOf<String?>(null) }        // success toast
    var status by remember { mutableStateOf("") }

    LaunchedEffect(stay.stayId) {
        runCatching { voice.speak(greeting, language) }.onFailure { status = "TTS: ${it.message}" }
    }
    LaunchedEffect(banner) { if (banner != null) { delay(3500); banner = null } }

    fun raise(item: String, raw: String) {
        scope.launch {
            runCatching { repo.logServiceRequest(deviceCode, item, 1, raw) }
                .onSuccess {
                    banner = "✓ Butler has notified the team"
                    runCatching { voice.speak(GreetingComposer.serviceAck(stay), language) }
                }
                .onFailure { status = "Request failed: ${it.message}" }
        }
    }

    if (showMenu) {
        MenuScreen(stay, deviceCode, repo, voice,
            onClose = { showMenu = false },
            onPlaced = { showMenu = false; banner = "✓ Your order has been sent to the kitchen" })
        return
    }

    if (showConvo) {
        ConversationScreen(stay, deviceCode, repo, voice, onClose = { showConvo = false })
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        // ---- Header / greeting hero ----
        Box(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(PRIMARY, PRIMARY_D)))
                .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                .padding(start = 24.dp, end = 24.dp, top = 36.dp, bottom = 28.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("\uD83D\uDECE\uFE0F", fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("JEHAN NUMA  ·  BUTLER", color = Color(0xFFBFD2F5),
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x33FFFFFF))
                        .padding(horizontal = 12.dp, vertical = 5.dp)) {
                        Text("Room ${stay.roomNumber}", color = Color.White,
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text(greeting, color = Color.White, fontSize = 22.sp, lineHeight = 30.sp,
                    fontWeight = FontWeight.SemiBold)
                if (stay.vip) {
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(GOLD)
                        .padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text("★  VIP GUEST", color = Color(0xFF3A2A00),
                            fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }
            }
        }

        // ---- success banner ----
        if (banner != null) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color(0xFFE7F4EC))
                .padding(14.dp)) {
                Text(banner!!, color = Color(0xFF1E7A43), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(if (banner == null) 22.dp else 4.dp))
        Text("How may I help you, $firstName?",
            color = INK, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(16.dp))

        // ---- service grid (2 columns) ----
        Column(Modifier.padding(horizontal = 16.dp)) {
            SERVICES.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { svc ->
                        ServiceCard(svc, Modifier.weight(1f)) {
                            if (svc.title == "Room Service") showMenu = true else pending = svc
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---- talk to Butler ----
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Button(
                onClick = { showConvo = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PRIMARY)
            ) {
                Text("\uD83C\uDFA4  Speak to Butler", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            if (status.isNotEmpty()) { Spacer(Modifier.height(10.dp)); Text(status, color = MUTED, fontSize = 13.sp) }
            if (onBack != null) { Spacer(Modifier.height(16.dp)); OutlinedButton(onClick = onBack) { Text("← Back to rooms") } }
            Spacer(Modifier.height(28.dp))
        }
    }

    // ---- confirmation dialog ----
    pending?.let { svc ->
        AlertDialog(
            onDismissRequest = { pending = null },
            confirmButton = {
                TextButton(onClick = { raise(svc.title, svc.title); pending = null }) {
                    Text("Yes, please", color = PRIMARY, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel", color = MUTED) } },
            title = { Text("${svc.icon}  ${svc.title}", fontWeight = FontWeight.Bold, color = INK) },
            text = { Text("Shall I request “${svc.title}” for Room ${stay.roomNumber}?", color = MUTED) },
            containerColor = CARD
        )
    }
}

@Composable
private fun ServiceCard(svc: Service, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(CARD)
            .clickable { onClick() }
            .padding(16.dp)
            .heightIn(min = 92.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(svc.icon, fontSize = 26.sp)
        Spacer(Modifier.height(8.dp))
        Text(svc.title, color = INK, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(svc.sub, color = MUTED, fontSize = 12.sp)
    }
}

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

// ---------------- Room-service menu ----------------
@Composable
private fun MenuScreen(
    stay: StayInfo, deviceCode: String, repo: HotelRepository, voice: SarvamVoice,
    onClose: () -> Unit, onPlaced: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val language = remember(stay.stayId) { GreetingComposer.languageOf(stay) }
    var menu by remember { mutableStateOf<List<MenuItem>>(emptyList()) }
    val cart = remember { mutableStateMapOf<String, Int>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var placing by remember { mutableStateOf(false) }

    LaunchedEffect(deviceCode) {
        runCatching { repo.listMenu(deviceCode) }
            .onSuccess { menu = it; loading = false }
            .onFailure { error = it.message; loading = false }
    }

    val total = menu.sumOf { (cart[it.id] ?: 0) * it.price }
    val count = cart.values.sum()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(PRIMARY).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Color.White, fontSize = 28.sp,
                modifier = Modifier.clickable { onClose() }.padding(end = 14.dp))
            Column {
                Text("Room Service", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Room ${stay.roomNumber}", color = Color(0xFFBFD2F5), fontSize = 12.sp)
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading menu…", color = MUTED) }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Could not load menu: $error", color = MUTED, modifier = Modifier.padding(24.dp), textAlign = TextAlign.Center)
            }
            else -> {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    menu.groupBy { it.category ?: "Menu" }.forEach { (cat, items) ->
                        Text(cat.uppercase(), color = MUTED, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                        items.forEach { item ->
                            MenuRow(item, cart[item.id] ?: 0,
                                onAdd = { cart[item.id] = (cart[item.id] ?: 0) + 1 },
                                onRemove = { val q = (cart[item.id] ?: 0) - 1; if (q <= 0) cart.remove(item.id) else cart[item.id] = q })
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
                if (count > 0) {
                    Surface(shadowElevation = 14.dp, color = CARD) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("₹${"%.0f".format(total)}", color = INK, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text("$count item${if (count > 1) "s" else ""}", color = MUTED, fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    if (placing) return@Button
                                    placing = true
                                    val lines = menu.filter { (cart[it.id] ?: 0) > 0 }
                                        .map { CartLine(it.id, it.name, it.price, cart[it.id]!!) }
                                    scope.launch {
                                        runCatching { repo.placeRoomServiceOrder(deviceCode, lines) }
                                            .onSuccess {
                                                runCatching { voice.speak(GreetingComposer.serviceAck(stay), language) }
                                                onPlaced()
                                            }
                                            .onFailure { error = "Order failed: ${it.message}"; placing = false }
                                    }
                                },
                                shape = RoundedCornerShape(14.dp), modifier = Modifier.height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PRIMARY)
                            ) { Text(if (placing) "Placing…" else "Place order", fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(item: MenuItem, qty: Int, onAdd: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(13.dp).clip(RoundedCornerShape(3.dp))
            .background(if (item.isVeg) Color(0xFF2E7D32) else Color(0xFFC62828)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = INK, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("₹${"%.0f".format(item.price)}", color = MUTED, fontSize = 13.sp)
        }
        if (qty == 0) {
            OutlinedButton(onClick = onAdd, shape = RoundedCornerShape(10.dp)) {
                Text("Add", color = PRIMARY, fontWeight = FontWeight.Bold)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepBtn("−", onRemove)
                Text("$qty", color = INK, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp))
                StepBtn("+", onAdd)
            }
        }
    }
    HorizontalDivider(color = LINE)
}

@Composable
private fun StepBtn(label: String, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFEAF1FB))
        .clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(label, color = PRIMARY, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------- Conversational Butler ----------------
/**
 * Turns a spoken utterance into a clean, department-routable request label,
 * or null when it's just conversation (greetings, local tips) so we don't
 * create noise tickets. Handles English + Hindi/Hinglish (STT returns Devanagari).
 * The label is what the staff board reads to route to the right manager.
 */
// A handful of short English words are PREFIXES of unrelated words and must match
// as a whole word only (e.g. "spa" must not fire on "space", "fan" not "fantastic").
private val WORD_EXACT = setOf("spa", "fan", "bill", "tap", "tv", "gym", "ac")

/** Token-aware keyword test. Devanagari/multi-word/punctuated keys use substring
 *  (that is how Sarvam returns them); bare English words match at a word boundary so
 *  "eat" no longer fires on "great"/"repeat", while stems like "towel"→"towels" still work. */
private fun kw(t: String, keyRaw: String): Boolean {
    val ascii = keyRaw.all { it.code < 128 }
    if (!ascii) return t.contains(keyRaw)
    if (keyRaw != keyRaw.trim() || keyRaw.any { it == ' ' || it == '.' || it == '-' }) return t.contains(keyRaw)
    val k = Regex.escape(keyRaw)
    return if (keyRaw in WORD_EXACT)
        Regex("(?<![a-z0-9])$k(?![a-z0-9])").containsMatchIn(t)   // whole word only
    else
        Regex("(?<![a-z0-9])$k").containsMatchIn(t)               // word start (allows plurals/stems)
}

private fun detectServiceItem(textIn: String): String? {
    val t = textIn.lowercase()
    fun has(vararg ks: String) = ks.any { kw(t, it) }
    return when {
        has("towel", "तौलि") -> "Fresh towels"
        has("laundry", "ironing", "press my", "wash my", "धुलाई", "लॉन्ड्री", "कपड़े धो", "इस्त्री") -> "Laundry"
        has("cab", "taxi", "car ", "pick up", "pickup", "airport", "drop me", "कैब", "टैक्सी", "गाड़ी", "हवाई अड्डा", "एयरपोर्ट") -> "Cab booking"
        has("clean", "tidy", "housekeep", "make up the room", "सफाई", "साफ", "कमरा साफ") -> "Housekeeping"
        has("water", "amenit", "toiletr", "soap", "shampoo", "slipper", "पानी", "साबुन", "शैम्पू") -> "Room amenities"
        has(" ac", "a.c", "cooling", "heater", "bulb", "light not", "plumb", "tap", "leak", "geyser",
            "hot water", "tv ", "televis", "wifi", "wi-fi", "internet", "fan ", "socket", "switch",
            "repair", "not working", "एसी", "बिजली", "नल", "मरम्मत", "पंखा", "ठीक नहीं", "खराब") -> "Maintenance"
        has("spa", "massage", "salon", "wellness", "sauna", "मसाज", "स्पा") -> "Spa appointment"
        has("wake", "alarm", "जगा", "वेक अप", "अलार्म") -> "Wake-up call"
        has("checkout", "check out", "check-out", "bill", "invoice", "folio", "reception",
            "front desk", "चेक आउट", "बिल", "रिसेप्शन") -> "Front desk"
        has("doctor", "medic", "first aid", "unwell", "feeling sick", "डॉक्टर", "दवा", "तबीयत") -> "Doctor / medical"
        // food: with no touch menu, file a room-service ticket so staff can fulfil it
        has("biryani", "paneer", "naan", "dosa", "sandwich", "food", "eat", "hungry", "meal",
            "खाना", "भूख", "ऑर्डर", "मेन्यू") -> "Room service request"
        // generic request intent — kept as a ticket so nothing important is dropped
        has("need", "want", "send", "bring", "get me", "could you", "can you", "please",
            "चाहिए", "भेज", "ला दो", "दे दो", "मंगवा", "भिजवा", "करवा") -> "Guest request"
        else -> null
    }
}

/** Detects EVERY actionable request in one utterance (handles English and the
 *  Devanagari spellings Sarvam returns, e.g. "टॉवल्स", "कैब", "एयरपोर्ट"). */
private fun isInfoQuestion(textIn: String): Boolean {
    val t = textIn.lowercase()
    val markers = listOf(
        "?", "suggest", "recommend", "where ", "what ", "which ", "how ", "when ", "why ",
        "best ", "nearby", "good place", "good spot", "tell me", "things to do", "sightsee",
        "what to", "places to", "explore", "weather", "mausam", "\u092e\u094c\u0938\u092e",
        "timing", "hours", "cost", "how much", "kahan", "\u0915\u0939\u093e\u0901", "batao", "\u092c\u0924\u093e\u0913"
    )
    return markers.any { t.contains(it) }
}

private fun isWeatherQuery(textIn: String): Boolean {
    val t = textIn.lowercase()
    val markers = listOf(
        "weather", "mausam", "\u092e\u094c\u0938\u092e", "temperature", "\u0924\u093e\u092a\u092e\u093e\u0928",
        "garmi", "\u0917\u0930\u094d\u092e\u0940", "garam", "\u0917\u0930\u092e", "thand", "\u0920\u0902\u0921",
        "baarish", "barish", "\u092c\u093e\u0930\u093f\u0936", "rain", "raining", "how hot", "how cold",
        "hot today", "cold today", "kaisa hai bahar", "bahar ka mausam"
    )
    return markers.any { t.contains(it) }
}

private fun detectAllServiceItems(textIn: String): List<String> {
    val t = textIn.lowercase()
    fun has(vararg ks: String) = ks.any { kw(t, it) }
    val out = LinkedHashSet<String>()
    if (has("emergency", "help me", "i need help", "sos", "fire ", "accident", "heart attack", "ambulance", "can't breathe", "आपातकाल", "मदद कर", "बचाओ", "आग लग", "एम्बुलेंस", "दुर्घटना")) out.add("Emergency / SOS")
    if (has("towel", "तौलि", "टॉवल", "टॉवेल", "टावल", "तौलिया", "तौलिये")) out.add("Fresh towels")
    if (has("laundry", "ironing", "press my", "wash my", "लॉन्ड्री", "धुलाई", "इस्त्री", "कपड़े धो")) out.add("Laundry")
    if (has("cab", "taxi", "airport", "pick up", "pickup", "drop me", "कैब", "टैक्सी", "गाड़ी", "एयरपोर्ट", "हवाई अड्डा")) out.add("Cab booking")
    if (has("clean", "tidy", "housekeep", "house keeping", "make up the room", "make the room", "सफाई", "साफ", "कमरा साफ")) out.add("Housekeeping")
    if (has("water", "पानी", "वाटर", "amenit", "toiletr", "soap", "shampoo", "slipper", "साबुन", "शैम्पू",
            "blanket", "कंबल", "pillow", "तकिया", "hanger", "charger", "चार्जर", "extra bed", "toothbrush", "toothpaste")) out.add("Room amenities")
    if (has(" ac ", "a.c", "cooling", "कूलिंग", "एसी", "heater", "bulb", "light not", "लाइट",
            "plumb", "नल", "tap", "leak", "geyser", "गीज़र", "hot water", "tv", "टीवी", "televis",
            "wifi", "वाईफाई", "wi-fi", "internet", "fan", "पंखा", "फैन", "socket", "switch", "बिजली",
            "repair", "मरम्मत", "not working", "ठीक नहीं", "खराब")) out.add("Maintenance")
    if (has("spa", "स्पा", "massage", "मसाज", "salon", "wellness", "sauna")) out.add("Spa appointment")
    if (has("wake", "वेक", "alarm", "अलार्म", "जगा")) out.add("Wake-up call")
    if (has("checkout", "check out", "check-out", "चेक आउट", "bill", "बिल", "invoice", "folio",
            "reception", "रिसेप्शन", "front desk", "फ्रंट डेस्क")) out.add("Front desk")
    if (has("doctor", "डॉक्टर", "medic", "दवा", "first aid", "unwell", "feeling sick", "तबीयत")) out.add("Doctor / medical")
    if (has("late checkout", "late check out", "checkout late", "check out late", "लेट चेकआउट", "देर से चेक", "देरी से")) out.add("Late checkout request")
    if (has("complaint", "complain", " problem", "not happy", "unhappy", "very bad", "worst", "poor service", "rude", "dirty room", "शिकायत", "गंदा", "खराब सेवा", "नाराज")) out.add("Complaint")
    if (has("lost", "misplaced", "left behind", "left my", "forgot my", "missing", "खो गया", "छूट गया", "भूल गया", "गुम")) out.add("Lost & found")
    if (has("biryani", "बिरयानी", "paneer", "पनीर", "naan", "नान", "dosa", "डोसा", "sandwich",
            "food", "खाना", "eat", "hungry", "भूख", "meal", "ऑर्डर", "menu", "मेन्यू",
            "room service", "रूम सर्विस", "order food", "dinner", "lunch", "breakfast in room")) out.add("Room service request")
    val negated = t.contains("don't") || t.contains("do not") || t.contains("not need") ||
            t.contains("no need") || t.contains("nahi") || t.contains("\u0928\u0939\u0940\u0902") || t.contains("\u092e\u0924 ")
    if (out.isEmpty() && !negated && has("need", "want", "send", "bring", "get me", "could you", "can you", "please",
            "\u091a\u093e\u0939\u093f\u090f", "\u092d\u0947\u091c", "\u0932\u093e \u0926\u094b", "\u0926\u0947 \u0926\u094b", "\u092e\u0902\u0917\u0935\u093e", "\u092d\u093f\u091c\u0935\u093e", "\u0915\u0930\u0935\u093e")) out.add("Guest request")
    return out.toList()
}

private val ITEM_EN = mapOf(
    "Fresh towels" to "fresh towels", "Laundry" to "laundry service", "Cab booking" to "a cab",
    "Housekeeping" to "housekeeping", "Room amenities" to "room amenities", "Maintenance" to "maintenance help",
    "Spa appointment" to "a spa appointment", "Wake-up call" to "a wake-up call", "Front desk" to "front-desk assistance",
    "Doctor / medical" to "medical assistance", "Room service request" to "room service", "Guest request" to "your request",
    "Late checkout request" to "a late checkout request", "Lost & found" to "a lost-item report", "Complaint" to "your concern",
    "Emergency / SOS" to "emergency assistance")
private val ITEM_HI = mapOf(
    "Fresh towels" to "ताज़े तौलिये", "Laundry" to "लॉन्ड्री सेवा", "Cab booking" to "एक कैब",
    "Housekeeping" to "हाउसकीपिंग", "Room amenities" to "कमरे का ज़रूरी सामान", "Maintenance" to "मेंटेनेंस सहायता",
    "Spa appointment" to "स्पा अपॉइंटमेंट", "Wake-up call" to "वेक-अप कॉल", "Front desk" to "फ्रंट डेस्क सहायता",
    "Doctor / medical" to "डॉक्टर सहायता", "Room service request" to "रूम सर्विस", "Guest request" to "आपका अनुरोध",
    "Late checkout request" to "लेट चेकआउट का अनुरोध", "Lost & found" to "गुमशुदा सामान की रिपोर्ट", "Complaint" to "आपकी शिकायत",
    "Emergency / SOS" to "आपातकालीन सहायता")

private fun joinHuman(parts: List<String>, andWord: String): String = when (parts.size) {
    0 -> ""; 1 -> parts[0]; 2 -> "${parts[0]} $andWord ${parts[1]}"
    else -> parts.dropLast(1).joinToString(", ") + " $andWord " + parts.last()
}
/** Deterministic spoken confirmation of what was arranged — never depends on the LLM. */
private fun confirmLine(items: List<String>, lang: String): String =
    if (lang == "hi-IN") "जी ज़रूर। मैंने आपके लिए " +
            joinHuman(items.map { ITEM_HI[it] ?: it }, "और") + " की व्यवस्था कर दी है, और हमारी टीम को सूचित कर दिया है।"
    else "Right away. I've arranged " +
            joinHuman(items.map { ITEM_EN[it] ?: it }, "and") + " for you, and notified our team."
private fun spaLine(lang: String) =
    if (lang == "hi-IN")
        "ज़रूर! हमारे स्पा में स्वीडिश और डीप-टिशू मसाज, अरोमाथेरेपी, रिफ्रेशिंग फेशियल, और आयुर्वेदिक सिग्नेचर थेरेपी उपलब्ध हैं। आप कौन-सी लेना चाहेंगे, और किस समय?"
    else
        "I'd be delighted to arrange a spa session. We offer Swedish and deep-tissue massage, aromatherapy, a rejuvenating facial, and our Ayurvedic signature therapy. Which would you like, and what time suits you?"
private fun spaConfirm(lang: String) =
    if (lang == "hi-IN")
        "बहुत बढ़िया — मैंने आपकी पसंद के अनुसार स्पा में बुकिंग करवा दी है। वे सब कुछ तैयार रखेंगे। 💆"
    else
        "Wonderful — I've booked that with our spa and noted your preference. They'll have everything ready for you. 💆"

/** True if the AI reply dodges the question by asking the guest to clarify/specify a place,
 *  instead of just answering — we replace such replies with a concrete curated answer. */
private fun looksEvasive(t: String): Boolean {
    val s = t.lowercase()
    return s.contains("could you specify") || s.contains("can you specify") || s.contains("please specify") ||
            s.contains("which place") || s.contains("which area") || s.contains("particular place") ||
            s.contains("specific place") || s.contains("could you clarify") || s.contains("can you clarify") ||
            s.contains("rephrase") || s.contains("what kind of") || s.contains("what type of") ||
            s.contains("\u0916\u093e\u0938 \u091c\u0917\u0939") || s.contains("\u0915\u094c\u0928 \u0938\u0940 \u091c\u0917\u0939") ||
            s.contains("\u0915\u093f\u0938 \u091c\u0917\u0939") || s.contains("\u0938\u094d\u092a\u0937\u094d\u091f \u0915\u0930")
}

private fun localAnswer(textIn: String, lang: String): String? {
    val t = textIn.lowercase()
    val hi = lang == "hi-IN"
    fun has(vararg k: String) = k.any { kw(t, it) }
    return when {
        has("spa", "massage", "wellness", "मसाज", "स्पा") -> spaLine(lang)
        has("restaurant", "restaurants", "dinner", "eat", "to eat", "place to eat", "lunch", "food", "food near",
            "street food", "sarafa", "chappan", "chatori", "where to eat", "good place", "good spot", "hungry",
            "खाना", "खाने", "खाऊ", "भोजन", "रेस्टोरेंट", "रेस्तरां", "भूख", "khana", "khane", "khau") ->
            if (hi) "यादगार शाम के लिए पुराने शहर की चटोरी गली जाएँ — भोपाल की मशहूर स्ट्रीट-फ़ूड गली, जहाँ बन-कबाब, बिरयानी और सुलेमानी चाय का लुत्फ़ उठाएँ। क्या मैं कैब की व्यवस्था करूँ?"
            else "For a memorable evening, head to Chatori Gali in the old city, Bhopal's legendary street-food lane that buzzes after dark with bun-kabab, biryani and sulaimani chai. Shall I arrange a cab for you?"
        has("things to do", "sightsee", "visit", "places", "place to visit", "tourist", "explore", "see nearby",
            "what to see", "ghoom", "\u0918\u0942\u092e", "घूमने", "देख", "दर्शनीय", "जगह", "kya dekhe", "ghoomne") ->
            if (hi) "ऊपरी झील (बड़ा तालाब) और वन विहार नेशनल पार्क, भव्य ताज-उल-मसाजिद, और गौहर महल व भारत भवन ज़रूर देखें। साँची स्तूप और भीमबेटका — दोनों यूनेस्को विश्व धरोहर — बेहतरीन डे-ट्रिप हैं। क्या मैं कैब बुक करूँ?"
            else "Be sure to see the Upper Lake (Bada Talab) and Van Vihar National Park, the grand Taj-ul-Masajid, and the heritage of Gohar Mahal and Bharat Bhavan. Sanchi Stupa and Bhimbetka, both UNESCO World Heritage Sites, make wonderful day trips. Want me to arrange a cab?"
        has("breakfast", "\u0928\u093e\u0936\u094d\u0924\u093e") ->
            if (hi) "नाश्ता सुबह 7 से 10:30 तक हमारे ऑल-डे डाइनर में मिलता है — भोपाली पोहा-जलेबी ज़रूर चखें।"
            else "Breakfast is 7:00 to 10:30am at our all-day diner — do try the local poha-jalebi!"
        has("check out", "checkout", "check-out") ->
            if (hi) "\u091a\u0947\u0915-\u0906\u0909\u091f \u0926\u094b\u092a\u0939\u0930 12 \u092c\u091c\u0947 \u0939\u0948; \u091a\u093e\u0939\u0947\u0902 \u0924\u094b \u092e\u0948\u0902 \u0932\u0947\u091f \u091a\u0947\u0915-\u0906\u0909\u091f \u0915\u093e \u0905\u0928\u0941\u0930\u094b\u0927 \u0915\u0930 \u0938\u0915\u0924\u093e \u0939\u0942\u0901\u0964"
            else "Checkout is at 12 noon. I can request a late checkout for you if you'd like."
        has("wifi", "wi-fi", "internet", "password") ->
            if (hi) "\u0939\u092e\u093e\u0930\u093e \u0935\u093e\u0908-\u092b\u093c\u093e\u0908 'JehanNuma_Guest' \u0939\u0948 \u2014 \u092e\u0948\u0902 \u092b\u094d\u0930\u0902\u091f \u0921\u0947\u0938\u094d\u0915 \u0938\u0947 \u092a\u093e\u0938\u0935\u0930\u094d\u0921 \u092d\u093f\u091c\u0935\u093e \u0938\u0915\u0924\u093e \u0939\u0942\u0901\u0964"
            else "Our Wi-Fi network is 'JehanNuma_Guest' \u2014 I can have the front desk share the password."
        has("pool", "swim") -> if (hi) "\u0938\u094d\u0935\u093f\u092e\u093f\u0902\u0917 \u092a\u0942\u0932 \u0938\u0941\u092c\u0939 6 \u0938\u0947 \u0930\u093e\u0924 9 \u092c\u091c\u0947 \u0924\u0915 \u0916\u0941\u0932\u093e \u0930\u0939\u0924\u093e \u0939\u0948\u0964" else "The pool is open 6:00am to 9:00pm; towels are available poolside."
        has("gym", "fitness") -> if (hi) "\u0939\u092e\u093e\u0930\u093e \u092b\u093c\u093f\u091f\u0928\u0947\u0938 \u0938\u0947\u0902\u091f\u0930 24 \u0918\u0902\u091f\u0947 \u0916\u0941\u0932\u093e \u0930\u0939\u0924\u093e \u0939\u0948\u0964" else "Our fitness centre is open 24 hours for in-house guests."
        has("weather", "mausam", "\u092e\u094c\u0938\u092e") -> if (hi) "\u092d\u094b\u092a\u093e\u0932 \u0915\u0940 \u0936\u093e\u092e\u0947\u0902 \u0906\u092e\u0924\u094c\u0930 \u092a\u0930 \u0938\u0941\u0939\u093e\u0935\u0928\u0940 \u0939\u094b\u0924\u0940 \u0939\u0948\u0902 \u2014 \u0939\u0932\u094d\u0915\u093e \u0917\u0930\u092e \u0915\u092a\u0921\u093c\u093e \u0938\u093e\u0925 \u0930\u0916\u0947\u0902\u0964" else "Bhopal evenings are usually pleasant \u2014 a light layer is handy for the evening."
        else -> null
    }
}

private fun graceful(lang: String) =
    if (lang == "hi-IN") "ज़रूर, मैं अभी हमारी फ्रंट डेस्क से आपकी सहायता करवाता हूँ।"
    else "Certainly — I'll have our front desk help you with that right away."

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

/** Urgent for complaints/lost items; high for late checkout; normal otherwise. */
private fun priorityFor(label: String): String = when (label) {
    "Emergency / SOS", "Complaint", "Lost & found" -> "urgent"
    "Late checkout request" -> "high"
    else -> "normal"
}

private fun feedbackPrompt(item: String, lang: String) = if (lang == "hi-IN")
    "आपका अनुरोध — $item — पूरा कर दिया गया है। क्या आप हमारी सेवा से संतुष्ट हैं?"
else "Your request — $item — has been completed. Was everything to your satisfaction?"
private fun thanksLine(lang: String) = if (lang == "hi-IN") "बहुत बढ़िया! आपका दिन शुभ हो।" else "Wonderful! Do enjoy your stay."
private fun sorryLine(lang: String) = if (lang == "hi-IN")
    "मुझे खेद है। मैंने हमारे ड्यूटी मैनेजर को सूचित कर दिया है, वे जल्द ही आपकी सहायता करेंगे।"
else "I'm sorry to hear that. I've informed our duty manager, who will attend to it right away."
private fun apologyLine(lang: String) = if (lang == "hi-IN")
    "मुझे बहुत खेद है। मैंने यह तुरंत हमारे ड्यूटी मैनेजर तक पहुँचा दिया है, कोई जल्द ही आपकी सहायता करेगा।"
else "I'm very sorry to hear that. I've flagged this to our duty manager right away, and someone will attend to you personally."
private fun emergencyLine(lang: String) = if (lang == "hi-IN")
    "मैं तुरंत हमारी टीम को सूचित कर रहा हूँ — मदद आ रही है। कृपया शांत रहें और वहीं रहें।"
else "I'm alerting our team right now — help is on the way. Please stay calm and remain where you are."
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
private fun isPositive(t: String): Boolean { val s = t.lowercase()
    return Regex("\\b(yes|yeah|yep|great|good|perfect|satisfied|happy|thanks|thank you|excellent|nice|fine|okay|ok)\\b").containsMatchIn(s) ||
            s.contains("हाँ") || s.contains("जी हाँ") || s.contains("अच्छा") || s.contains("बढ़िया") || s.contains("धन्यवाद") || s.contains("शुक्रिया") || s.contains("संतुष्ट") || s.contains("परफेक्ट") }
private fun isNegative(t: String): Boolean { val s = t.lowercase()
    return Regex("\\b(no|not|bad|poor|terrible|worst|unhappy|dirty|cold|slow|disappointed|rude)\\b").containsMatchIn(s) ||
            s.contains("नहीं") || s.contains("बुरा") || s.contains("ख़राब") || s.contains("खराब") || s.contains("गंदा") || s.contains("देर") || s.contains("ठंडा") || s.contains("नाराज") }


// ============ Room service: menu-grounded voice ordering ============
private const val RS_MAX_ROUNDS = 4

private data class RsLine(val item: MenuItem, val qty: Int)

private fun menuForPrompt(menu: List<MenuItem>): String =
    menu.joinToString("\n") { "${it.id} | ${it.name} | ₹${"%.0f".format(it.price)}${if (it.isVeg) " (veg)" else ""}" }

private fun rsExtractPrompt(menu: List<MenuItem>): String =
    "You parse room-service orders for a hotel voice assistant. Return ONLY a JSON object, no markdown, no extra text:\n" +
            "{\"items\":[{\"id\":\"<menu id>\",\"qty\":<int>}],\"unmatched\":[\"<dish requested that is NOT on the menu>\"],\"suggest\":<true ONLY if the guest named no dish and is asking what's available>}\n" +
            "Rules: use ONLY ids that appear in the MENU below; never invent ids or dishes. Map Hindi/Hinglish dish names to the closest menu item only when clearly the same dish, else add the words to \"unmatched\". Default qty to 1 when unstated.\n\nMENU:\n" +
            menuForPrompt(menu)

private fun parseJsonObject(raw: String): JSONObject? {
    val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
    if (a < 0 || b <= a) return null
    return runCatching { JSONObject(raw.substring(a, b + 1)) }.getOrNull()
}

private fun buildLines(menu: List<MenuItem>, counts: Map<String, Int>): List<RsLine> =
    counts.mapNotNull { (id, q) -> menu.firstOrNull { it.id == id }?.let { RsLine(it, q) } }.filter { it.qty > 0 }

private fun rsTotal(lines: List<RsLine>): Double = lines.sumOf { it.item.price * it.qty }

private fun numWord(n: Int, hi: Boolean): String =
    if (hi) when (n) { 1->"एक";2->"दो";3->"तीन";4->"चार";5->"पाँच";else->n.toString() }
    else when (n) { 1->"one";2->"two";3->"three";4->"four";5->"five";6->"six";else->n.toString() }

private fun readBack(lines: List<RsLine>, lang: String): String {
    val hi = lang == "hi-IN"
    val parts = lines.map { "${numWord(it.qty, hi)} ${it.item.name}" }
    val joined = joinHuman(parts, if (hi) "और" else "and")
    val total = "%.0f".format(rsTotal(lines))
    return if (hi) "जी, यह रहा आपका ऑर्डर — $joined, कुल ₹$total। क्या मैं ऑर्डर कर दूँ?"
    else "That's $joined — ₹$total in total. Shall I place the order?"
}

private fun looksLikeAdd(t: String): Boolean {
    val s = t.lowercase()
    return s.contains("add") || s.contains("also") || s.contains("one more") || s.contains("make it") ||
            s.contains("plus") || s.contains("aur ") || s.contains("और") || s.contains("ek aur")
}

private fun isCancelRs(t: String): Boolean {
    val s = t.lowercase().trim()
    val short = s.split(Regex("\\s+")).size <= 3
    return s.contains("cancel") || s.contains("never mind") || s.contains("nevermind") ||
            s.contains("forget it") || s.contains("rehne do") || s.contains("रहने दो") ||
            (short && (s == "no" || s.contains("no thank") || s.contains("nahi") || s.contains("नहीं")))
}

private fun rsHandoff(hi: Boolean) =
    if (hi) "मैंने आपका अनुरोध हमारी रूम-सर्विस टीम को भेज दिया है, वे आपके कमरे में पुष्टि के लिए कॉल करेंगे।"
    else "I've sent your request to our room-service team; they'll call your room to confirm."

/** Menu-grounded voice ordering: read back, confirm, place — or hand off if it can't.
 *  Never places an order without an explicit "yes"; never invents items/prices; bounded turns. */
private suspend fun handleRoomService(
    firstUtterance: String,
    lang: String,
    deviceCode: String,
    repo: HotelRepository,
    voice: SarvamVoice,
    menuCache: MutableList<MenuItem>,
    setPhase: (VoicePhase) -> Unit,
    setStatus: (String) -> Unit,
    addTurn: (String, String) -> Unit,
) {
    val hi = lang == "hi-IN"

    suspend fun say(text: String) {
        addTurn("assistant", text)
        setPhase(VoicePhase.SPEAKING); setStatus("")
        runCatching { voice.speak(text, lang) }
        delay(MIC_SETTLE_MS)
    }
    suspend fun hear(): String? {
        setPhase(VoicePhase.LISTENING); setStatus(listeningHint(lang))
        val wav = voice.listen(maxMs = 13000, silenceMs = 1300, startTimeoutMs = 7000) ?: return null
        setPhase(VoicePhase.THINKING); setStatus(thinkingHint(lang))
        val said = runCatching { voice.transcribeAuto(wav) }.getOrNull()?.first?.trim().orEmpty()
        if (said.isNotBlank()) { addTurn("user", said); return said }
        return null
    }
    suspend fun fileTicket(raw: String) {
        runCatching { repo.logServiceRequest(deviceCode, "Room service request", 1, raw, "normal") }
    }

    if (menuCache.isEmpty()) {
        setPhase(VoicePhase.THINKING); setStatus(thinkingHint(lang))
        runCatching { repo.listMenu(deviceCode) }.getOrNull()?.let { menuCache.addAll(it) }
    }
    if (menuCache.isEmpty()) { fileTicket(firstUtterance); say(rsHandoff(hi)); return }

    val menu = menuCache.toList()
    val sysPrompt = rsExtractPrompt(menu)

    suspend fun extract(utterance: String): Triple<Map<String, Int>, List<String>, Boolean> {
        val raw = runCatching { voice.chat(listOf("system" to sysPrompt, "user" to "GUEST SAID: \"$utterance\"")) }
            .onFailure { Log.e("ButlerRS", "extract failed", it) }.getOrNull().orEmpty()
        val obj = parseJsonObject(raw) ?: return Triple(emptyMap(), emptyList(), false)
        val counts = LinkedHashMap<String, Int>()
        obj.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim(); if (id.isBlank()) continue
                counts[id] = (counts[id] ?: 0) + o.optInt("qty", 1).coerceIn(1, 20)
            }
        }
        val unmatched = mutableListOf<String>()
        obj.optJSONArray("unmatched")?.let { for (i in 0 until it.length()) it.optString(i).trim().takeIf { s -> s.isNotBlank() }?.let(unmatched::add) }
        return Triple(counts, unmatched, obj.optBoolean("suggest", false))
    }

    fun suggestionLine(): String {
        val picks = menu.take(4).joinToString(", ") { it.name }
        return if (hi) "ज़रूर! हमारे यहाँ $picks वगैरह उपलब्ध हैं। आप क्या लेना चाहेंगे?"
        else "Of course! We have $picks, among others. What would you like?"
    }

    val counts = LinkedHashMap<String, Int>()
    var pending: String? = firstUtterance
    var failures = 0
    var round = 0

    while (round < RS_MAX_ROUNDS) {
        round++
        if (pending != null) {
            val (newItems, unmatched, suggest) = extract(pending!!)
            pending = null
            newItems.forEach { (id, q) -> counts[id] = (counts[id] ?: 0) + q }
            if (counts.isEmpty()) {
                say(if (unmatched.isNotEmpty())
                    (if (hi) "क्षमा कीजिए, ${unmatched.first()} मेन्यू में नहीं है। ${suggestionLine()}"
                    else "I'm sorry, we don't have ${unmatched.first()}. ${suggestionLine()}")
                else suggestionLine())
                val next = hear()
                if (next == null) { failures++; if (failures >= 2) { fileTicket(firstUtterance); say(rsHandoff(hi)); return }; continue }
                if (isStopPhrase(next)) { say(if (hi) "ठीक है। और कुछ हो तो बताइए।" else "Alright. Let me know if you need anything else."); return }
                pending = next
                continue
            }
            if (unmatched.isNotEmpty())
                say(if (hi) "${unmatched.first()} तो हमारे पास नहीं है, बाकी नोट कर लिया।"
                else "We don't have ${unmatched.first()}, but I've noted the rest.")
        }

        val lines = buildLines(menu, counts)
        if (lines.isEmpty()) {
            counts.clear(); say(suggestionLine())
            pending = hear(); if (pending == null) { fileTicket(firstUtterance); say(rsHandoff(hi)); return }; continue
        }

        say(readBack(lines, lang))
        when (val ans = hear()) {
            null -> { failures++; if (failures >= 2) { fileTicket(firstUtterance); say(rsHandoff(hi)); return } }
            else -> when {
                isPositive(ans) && !looksLikeAdd(ans) -> {
                    setPhase(VoicePhase.THINKING); setStatus(thinkingHint(lang))
                    val placed = runCatching {
                        repo.placeRoomServiceOrder(deviceCode, lines.map { CartLine(it.item.id, it.item.name, it.item.price, it.qty) })
                    }.isSuccess
                    if (placed) say(if (hi) "बढ़िया! आपका ऑर्डर रसोई को भेज दिया गया है — लगभग 30 मिनट में पहुँच जाएगा।"
                    else "Wonderful! Your order's gone to the kitchen — it'll be about 30 minutes.")
                    else { fileTicket(firstUtterance); say(rsHandoff(hi)) }
                    return
                }
                isCancelRs(ans) -> {
                    say(if (hi) "कोई बात नहीं — ऑर्डर रद्द कर दिया। और कुछ चाहिए तो बताइए।"
                    else "No problem — I've cancelled that. Let me know if there's anything else."); return
                }
                else -> pending = ans   // add / modify → re-parse next round
            }
        }
    }
    fileTicket(firstUtterance)
    say(rsHandoff(hi))
}

private fun buildSystemPrompt(stay: StayInfo): String {
    return "You are Butler, the warm, courteous in-room concierge at Jehan Numa Palace, Bhopal. " +
            "You are speaking with ${stay.guestName} in Room ${stay.roomNumber}. " +
            "Reply in the SAME language the guest used in their last message: if they spoke Hindi, reply in natural Hindi; otherwise reply in English. Match their language, never mix scripts. " +
            "Keep replies short and natural — 1 to 3 sentences, like a gracious hotel concierge. " +
            "ALWAYS answer the guest's question directly and specifically. NEVER reply by asking them to clarify, rephrase, repeat, or specify a place — instead give your best concrete answer immediately. " +
            "The guest's words are auto-transcribed and may be slightly misspelled or phonetic (e.g. 'where can I eat' may arrive garbled); infer their intent and answer helpfully rather than asking them to repeat. " +
            "You answer questions about the hotel, the city, and local tips, and make small talk warmly. " +
            "Hotel facts you can share: breakfast 7:00-10:30am at the all-day diner (included on most rates), checkout 12 noon (late checkout on request), pool 6am-9pm, fitness centre 24h, spa 9am-9pm, free Wi-Fi network 'JehanNuma_Guest'. " +
            "Bhopal tips you know well: the Upper Lake or Bada Talab (the City of Lakes, lovely for sunset boating), Van Vihar National Park, Taj-ul-Masajid (one of India's largest mosques), Gohar Mahal and Bharat Bhavan, and the Tribal Museum; UNESCO day trips to Sanchi Stupa and Bhimbetka rock shelters; the street-food favourite is Chatori Gali and the must-try breakfast is poha-jalebi. " +
            "When the guest asks where to eat or what to see, immediately name 2-3 specific Bhopal places and offer to arrange a cab — do not ask which area they mean. " +
            "Never invent prices or room details you don't know; if unsure, offer to connect them to the front desk. Reply directly, no reasoning."
}

@Composable
private fun ConversationScreen(
    stay: StayInfo, deviceCode: String, repo: HotelRepository, voice: SarvamVoice, onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val language = remember(stay.stayId) { GreetingComposer.languageOf(stay) }
    val sys = remember(stay.stayId) { buildSystemPrompt(stay) }
    val turns = remember { mutableStateListOf<Pair<String, String>>() }   // (role, text)
    var recording by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Tap the mic and tell Butler what you need.") }
    val scrollState = rememberScrollState()
    LaunchedEffect(turns.size, busy) { scrollState.animateScrollTo(scrollState.maxValue) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(PRIMARY).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Color.White, fontSize = 28.sp,
                modifier = Modifier.clickable { onClose() }.padding(end = 14.dp))
            Column {
                Text("Butler", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Jehan Numa Concierge · Room ${stay.roomNumber}", color = Color(0xFFBFD2F5), fontSize = 12.sp)
            }
        }

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(16.dp)) {
            if (turns.isEmpty()) {
                Text("Namaste \uD83D\uDE4F  I'm Butler, your in-room concierge. Ask me for anything — towels, food, a wake-up call, or local tips.",
                    color = MUTED, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 12.dp))
            }
            turns.forEach { (role, text) -> ChatBubble(role == "user", text) }
            if (busy) ChatBubble(false, "…")
            Spacer(Modifier.height(8.dp))
        }

        Surface(shadowElevation = 14.dp, color = CARD) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (status.isNotEmpty()) { Text(status, color = MUTED, fontSize = 13.sp); Spacer(Modifier.height(10.dp)) }
                Button(
                    onClick = {
                        if (busy) return@Button
                        if (!voice.hasMic()) { status = "Microphone permission needed"; return@Button }
                        if (!recording) { voice.startRecording(); recording = true; status = "Listening…" }
                        else {
                            recording = false; status = "One moment…"; busy = true
                            scope.launch {
                                val said = runCatching {
                                    val wav = withContext(Dispatchers.IO) { voice.stopRecording() }
                                    voice.transcribe(wav, language)
                                }.getOrElse { busy = false; status = "Sorry, I didn't catch that — please try again."; return@launch }
                                if (said.isBlank()) { busy = false; status = "I didn't hear anything — tap the mic and speak."; return@launch }
                                turns.add("user" to said)
                                val reqItem = detectServiceItem(said)
                                if (reqItem != null) runCatching { repo.logServiceRequest(deviceCode, reqItem, 1, said) }
                                val msgs = listOf("system" to sys) + turns.toList()
                                val reply = runCatching { voice.chat(msgs) }
                                    .getOrElse { "I'm sorry, I'm having a little trouble just now. I've noted your request and our team will assist you shortly." }
                                turns.add("assistant" to reply)
                                busy = false; status = ""
                                runCatching { voice.speak(reply, language) }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (recording) ACCENT else PRIMARY)
                ) { Text(if (recording) "■  Stop & send" else "\uD83C\uDFA4  Speak", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun ChatBubble(isUser: Boolean, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(16.dp))
                .background(if (isUser) PRIMARY else Color(0xFFEAF1FB))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text, color = if (isUser) Color.White else INK, fontSize = 15.sp, lineHeight = 21.sp)
        }
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