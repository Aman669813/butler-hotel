package com.risiga.hotelbutler

/**
 * SINGLE SOURCE OF TRUTH for where a request goes.
 *
 * The Butler brain only writes what Butler *says*. This file decides the department.
 * Because routing is deterministic, the staff-board tab and the filed ticket can
 * never disagree, and a wrong/blank label from the model is corrected by keyword
 * rules before anything is written.
 */

/** The six staff tabs. `tab` is the EXACT string stored in service_requests.department
 *  and the string the board filters each tab on. Never change these without the SQL. */
enum class Department(val tab: String, val en: String, val hi: String) {
    MAINTENANCE ("maintenance",  "Maintenance",            "मेंटेनेंस"),
    HOUSEKEEPING("housekeeping", "Housekeeping",           "हाउसकीपिंग"),
    SPA         ("spa",          "Spa",                    "स्पा"),
    TRANSPORT   ("transport",    "Cab / Transport",        "कैब"),
    KITCHEN     ("kitchen",      "Kitchen / Room Service", "रसोई"),
    FRONT_DESK  ("front_desk",   "Front Desk",             "फ्रंट डेस्क")
}

/** Canonical labels the brain may return → fixed department. */
val SERVICE_ROUTING: Map<String, Department> = mapOf(
    "Maintenance"           to Department.MAINTENANCE,
    "Fresh towels"          to Department.HOUSEKEEPING,
    "Housekeeping"          to Department.HOUSEKEEPING,
    "Room amenities"        to Department.HOUSEKEEPING,
    "Laundry"               to Department.HOUSEKEEPING,
    "Spa appointment"       to Department.SPA,
    "Cab booking"           to Department.TRANSPORT,
    "Room service request"  to Department.KITCHEN,
    "Wake-up call"          to Department.FRONT_DESK,
    "Late checkout request" to Department.FRONT_DESK,
    "Lost & found"          to Department.FRONT_DESK,
    "Doctor / medical"      to Department.FRONT_DESK,
    "Complaint"             to Department.FRONT_DESK,
    "Front desk"            to Department.FRONT_DESK,
    "Emergency / SOS"       to Department.FRONT_DESK
)

val SERVICE_LABELS: Set<String> = SERVICE_ROUTING.keys

data class Routed(
    val department: Department,
    val item: String,       // clean item_name for the board
    val quantity: Int,
    val priority: String,   // normal / high / urgent
    val raw: String         // guest's exact words
)

private data class Rule(val dept: Department, val item: String, val keys: List<String>)

/**
 * High-confidence keyword rules. First match wins, so ORDER MATTERS — specific
 * actionable issues (broken thing, cleaning) sit above generic dissatisfaction so
 * "the bathroom is dirty" routes to Housekeeping, not Complaint.
 */
private val RULES: List<Rule> = listOf(
    // --- Maintenance: anything broken / not working (fixes notes 1 & 6) ---
    Rule(Department.MAINTENANCE, "Maintenance", listOf(
        "not working","isn't working","stopped working","doesn't work","won't turn",
        "broken","repair","fix ","fault","flicker",
        "fan","light","lights","bulb","tube","lamp",
        "ac","a.c","air condition","cooling","heater","geyser","hot water",
        "tv","television","remote","socket","switch","plug","power","electric",
        "tap","faucet","leak","drip","flush","toilet not","drain","clog","blocked",
        "wifi not","internet not","ac not",
        "पंखा","लाइट","बत्ती","बल्ब","एसी","गरम पानी","गीजर","नल","लीक","टपक",
        "फ्लश","ख़राब","खराब","काम नहीं","ठीक कर","मरम्मत","बंद हो")),

    // --- Housekeeping: linens, cleaning, amenities (fixes notes 4 & 5) ---
    Rule(Department.HOUSEKEEPING, "Fresh towels", listOf(
        "towel","towels","तौलिया","तौलिये")),
    Rule(Department.HOUSEKEEPING, "Housekeeping", listOf(
        "bedsheet","bed sheet","linen","clean","cleaning","vacuum","dust","dirty",
        "trash","garbage","dustbin","make the bed","make up the room","tidy","mop",
        "चादर","सफाई","साफ","कचरा","डस्टबिन","गंदा","गंदी")),
    Rule(Department.HOUSEKEEPING, "Room amenities", listOf(
        "soap","shampoo","toiletries","toilet paper","tissue","water bottle",
        "drinking water","slipper","hanger","pillow","blanket","amenit","mineral water",
        "साबुन","शैम्पू","पानी की बोतल","तकिया","कंबल","कम्बल","चप्पल")),
    Rule(Department.HOUSEKEEPING, "Laundry", listOf(
        "laundry","wash my","ironing","press my","dry clean","iron my",
        "लॉन्ड्री","धुलाई","प्रेस","इस्त्री")),

    // --- Spa ---
    Rule(Department.SPA, "Spa appointment", listOf(
        "spa","massage","therapy","facial","salon","saloon",
        "स्पा","मसाज","मालिश")),

    // --- Transport ---
    Rule(Department.TRANSPORT, "Cab booking", listOf(
        "cab","taxi"," car ","pick up","pickup","drop me","airport","railway","station",
        "कैब","टैक्सी","गाड़ी","एयरपोर्ट","स्टेशन","हवाई अड्डा")),

    // --- Front desk: only genuine desk TASKS (never info questions, fixes note 2) ---
    Rule(Department.FRONT_DESK, "Wake-up call", listOf(
        "wake me","wake-up","wake up call","morning call","alarm",
        "जगा देना","वेक अप","अलार्म")),
    Rule(Department.FRONT_DESK, "Late checkout request", listOf(
        "late checkout","extend my stay","check out late","leave late",
        "लेट चेकआउट","देर से चेक")),
    Rule(Department.FRONT_DESK, "Lost & found", listOf(
        "i lost","i've lost","missing my","can't find my","left my",
        "खो गया","गुम","नहीं मिल रहा")),
    Rule(Department.FRONT_DESK, "Complaint", listOf(
        "complaint","not happy","disappointed","worst","rude","poor service",
        "unacceptable","शिकायत","नाराज","बुरी सेवा")),
    Rule(Department.FRONT_DESK, "Front desk", listOf(
        "bill","invoice","folio","payment","reception","front desk","manager",
        "बिल","रसीद","रिसेप्शन","मैनेजर"))
)

private val NUM_WORDS = mapOf(
    "one" to 1,"two" to 2,"three" to 3,"four" to 4,"five" to 5,"six" to 6,
    "couple" to 2,"few" to 3,"pair" to 2,
    "एक" to 1,"दो" to 2,"तीन" to 3,"चार" to 4,"पांच" to 5,"पाँच" to 5,"छह" to 6,"छः" to 6
)

/** Pull a quantity out of the utterance (digits or words). Defaults to 1. */
fun parseQuantity(text: String, default: Int = 1): Int {
    Regex("\\b(\\d{1,2})\\b").find(text)?.groupValues?.get(1)?.toIntOrNull()
        ?.let { if (it in 1..20) return it }
    val s = text.lowercase()
    for ((w, n) in NUM_WORDS) if (s.contains(w)) return n
    return default
}

/**
 * Decide the ticket from the guest's words + the model's proposed label.
 * Returns null when nothing should be filed (info questions, small talk).
 *
 * Evidence priority:
 *   1. keyword rules — authoritative; correct the model's wrong/blank labels
 *   2. model label — used ONLY for concrete service departments, never a bare
 *      "Front desk"/"none" (which the model over-uses for questions → false tickets)
 */
fun routeRequest(utterance: String, modelLabel: String?): Routed? {
    val s = utterance.lowercase()

    // 1) keyword rules win
    RULES.firstOrNull { r -> r.keys.any { s.contains(it) } }?.let { return build(it.dept, it.item, utterance) }

    // 2) trust the model only when it names a concrete (non front-desk) service
    val label = modelLabel?.takeIf { it in SERVICE_ROUTING }
    val dept  = label?.let { SERVICE_ROUTING[it] }
    if (label != null && dept != null && dept != Department.FRONT_DESK) {
        return build(dept, label, utterance)
    }

    // otherwise: no ticket — Butler just answers
    return null
}

private fun build(dept: Department, item: String, raw: String): Routed {
    val qty = if (dept == Department.HOUSEKEEPING) parseQuantity(raw) else 1
    val priority = when (item) {
        "Complaint", "Lost & found" -> "urgent"
        "Late checkout request"     -> "high"
        else                        -> "normal"
    }
    return Routed(dept, item, qty, priority, raw)
}