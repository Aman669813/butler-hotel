package com.risiga.hotelbutler.voice

import com.risiga.hotelbutler.data.StayInfo

/**
 * Builds the spoken GREETING (on check-in) and FAREWELL (on check-out).
 *
 * Style: warm and conversational — greet by name, welcome them, then (for every
 * purpose of visit EXCEPT business) add a personal wish, and finish with a gentle
 * offer of help. Business guests get the same warm welcome with no celebratory
 * wish — just a professional note.
 *
 * VERIFY BEFORE THE DEMO: en-IN and hi-IN are confirmed. mr-IN and gu-IN are
 * first-draft and MUST be checked by a native speaker AND previewed through
 * Sarvam (bulbul:v2). Any language not filled here falls back to English, so the
 * device never speaks broken text on stage.
 */
object GreetingComposer {

    private data class Pack(
        val salutation: String,
        val honorific: String,     // appended to name, e.g. " जी"
        val welcome: String,       // warm welcome line
        val offer: String,         // gentle offer of help
        val occasions: Map<String, String>,   // purpose-of-visit wishes ("" = no wish)
        val farewell: String       // %1$s = guest name (+honorific). Spoken at checkout.
    )

    private val packs: Map<String, Pack> = mapOf(

        // ---- ENGLISH (verified) ----
        "en-IN" to Pack(
            salutation = "Welcome",
            honorific = "",
            welcome = "It's truly a pleasure to have you with us at Jehan Numa Palace.",
            offer = "Please let me know if there's anything at all I can do to make your stay special.",
            occasions = mapOf(
                "birthday" to "And a very happy birthday to you!",
                "anniversary" to "And a very happy anniversary to you both!",
                "honeymoon" to "Wishing you both a beautiful and memorable stay together.",
                "wedding" to "Heartiest congratulations on your special occasion!",
                "family vacation" to "I hope you and your family have a truly wonderful time with us.",
                "leisure" to "I hope you have a relaxing and refreshing stay.",
                "business" to ""   // no wish for business -- warm welcome only
            ),
            farewell = "Thank you for staying with us, %1\$s. It was our pleasure to host you, " +
                    "and we hope you had a wonderful stay at Jehan Numa Palace. We look forward to welcoming " +
                    "you again. Have a safe and pleasant journey."
        ),

        // ---- HINDI (verified) ----
        "hi-IN" to Pack(
            salutation = "नमस्ते",
            honorific = " जी",
            welcome = "Jehan Numa Palace में आपका हार्दिक स्वागत है।",
            offer = "किसी भी चीज़ की ज़रूरत हो तो बेझिझक बताइएगा — मैं आपकी सेवा में हमेशा हाज़िर हूँ।",
            occasions = mapOf(
                "birthday" to "और आपको जन्मदिन की ढेर सारी शुभकामनाएँ!",
                "anniversary" to "और आप दोनों को सालगिरह की हार्दिक शुभकामनाएँ!",
                "honeymoon" to "आपके इस ख़ास सफ़र को यादगार बनाने में हमें बेहद खुशी होगी।",
                "wedding" to "इस शुभ अवसर पर आपको हार्दिक बधाई!",
                "family vacation" to "अपने परिवार के साथ आपका यह प्रवास बेहद सुखद और यादगार रहे।",
                "leisure" to "आपका यह प्रवास सुकून भरा और तरोताज़ा करने वाला रहे।",
                "business" to ""   // no wish for business -- warm welcome only
            ),
            farewell = "%1\$s, Jehan Numa Palace में पधारने के लिए हार्दिक धन्यवाद। आपकी सेवा करके हमें बहुत " +
                    "प्रसन्नता हुई। हमें आशा है कि आपका प्रवास सुखद रहा, और हम आपका पुनः स्वागत करने के " +
                    "लिए उत्सुक रहेंगे। आपकी यात्रा मंगलमय हो।"
        ),

        // ---- MARATHI (DRAFT -- verify) ----
        "mr-IN" to Pack(
            salutation = "नमस्कार",
            honorific = " जी",
            welcome = "Jehan Numa Palace मध्ये आपले मनःपूर्वक स्वागत आहे.",
            offer = "आपल्याला काहीही हवे असल्यास बेलाशंक सांगा — मी आपल्या सेवेसाठी सदैव तत्पर आहे.",
            occasions = mapOf(
                "birthday" to "आणि आपल्याला वाढदिवसाच्या हार्दिक शुभेच्छा!",
                "anniversary" to "आणि आपल्या दोघांना लग्नाच्या वाढदिवसाच्या हार्दिक शुभेच्छा!",
                "honeymoon" to "आपला हा खास प्रवास संस्मरणीय व्हावा हीच आमची इच्छा.",
                "wedding" to "या शुभप्रसंगी आपले मनःपूर्वक अभिनंदन!",
                "family vacation" to "आपल्या कुटुंबासोबतचा हा मुक्काम सुखद आणि संस्मरणीय ठरो.",
                "leisure" to "आपला हा मुक्काम आरामदायी आणि ताजेतवाने करणारा ठरो.",
                "business" to ""
            ),
            farewell = "%1\$s, Jehan Numa Palace मध्ये आल्याबद्दल मनःपूर्वक धन्यवाद. आपली सेवा करताना आम्हाला " +
                    "आनंद झाला. आपला मुक्काम सुखद झाला अशी आशा आहे. पुन्हा अवश्य भेट द्या. आपला प्रवास सुखाचा होवो."
        ),

        // ---- GUJARATI (DRAFT -- verify) ----
        "gu-IN" to Pack(
            salutation = "નમસ્તે",
            honorific = " જી",
            welcome = "Jehan Numa Palace માં આપનું હાર્દિક સ્વાગત છે.",
            offer = "આપને કંઈ પણ જરૂર હોય તો વિના સંકોચ જણાવશો — હું આપની સેવામાં હંમેશા હાજર છું.",
            occasions = mapOf(
                "birthday" to "અને આપને જન્મદિવસની ખૂબ ખૂબ શુભકામનાઓ!",
                "anniversary" to "અને આપ બંનેને લગ્નની વર્ષગાંઠની હાર્દિક શુભકામનાઓ!",
                "honeymoon" to "આપની આ ખાસ સફર યાદગાર બની રહે એવી અમારી શુભેચ્છા.",
                "wedding" to "આ શુભ પ્રસંગે આપને હાર્દિક અભિનંદન!",
                "family vacation" to "આપના પરિવાર સાથેનો આ રોકાણ સુખદ અને યાદગાર રહે.",
                "leisure" to "આપનો આ રોકાણ આરામદાયક અને તાજગીભર્યો રહે.",
                "business" to ""
            ),
            farewell = "%1\$s, Jehan Numa Palace માં પધારવા બદલ ખૂબ આભાર. આપની સેવા કરીને અમને આનંદ થયો. " +
                    "આશા છે કે આપનો રોકાણ સુખદ રહ્યો, અને આપને ફરી આવકારવા અમે ઉત્સુક છીએ. શુભ યાત્રા."
        )

        // ---- te-IN, ta-IN, bn-IN, kn-IN, ml-IN, pa-IN: add verified packs here ----
        // Until added, these locales fall back to English automatically.
    )

    // Spoken acknowledgement after a service request (en/hi verified; others fall back to en).
    private val acks: Map<String, String> = mapOf(
        "en-IN" to "Of course. I've let our team know — it'll be taken care of right away.",
        "hi-IN" to "जी ज़रूर। मैंने हमारी टीम को बता दिया है, यह अभी तुरंत कर दिया जाएगा।",
        "mr-IN" to "नक्कीच. मी आमच्या टीमला कळवले आहे, ते लगेच केले जाईल.",
        "gu-IN" to "ચોક્કસ. મેં અમારી ટીમને જાણ કરી દીધી છે, તે હમણાં જ થઈ જશે."
    )

    /**
     * Greeting spoken when a guest is checked in.
     * Order: salutation + name -> warm welcome -> purpose-of-visit wish (skipped for
     * business and for unknown occasions) -> gentle offer of help.
     */
    fun compose(stay: StayInfo): String {
        val pack = packs[stay.languagePref] ?: packs["en-IN"]!!
        val occLine = stay.occasion?.let { pack.occasions[it.lowercase()] }?.trim().orEmpty()
        val name = stay.guestName + pack.honorific
        val welcomeLine = if (stay.visitCount > 1) welcomeBack(stay.languagePref) else pack.welcome
        return buildString {
            append(pack.salutation).append(" ").append(name).append(". ")
            append(welcomeLine).append(" ")
            if (occLine.isNotEmpty()) append(occLine).append(" ")
            append(pack.offer)
        }.replace(Regex("\\s+"), " ").trim()
    }

    private fun welcomeBack(lang: String): String = when (lang) {
        "hi-IN" -> "Jehan Numa Palace में आपका पुनः स्वागत है — आपको फिर से अपने साथ पाकर हमें बेहद ख़ुशी है।"
        "mr-IN" -> "Jehan Numa Palace मध्ये पुन्हा आपले स्वागत आहे — आपल्याला पुन्हा भेटून आम्हाला आनंद झाला."
        "gu-IN" -> "Jehan Numa Palace માં ફરી આપનું સ્વાગત છે — આપને ફરી મળીને અમને ખૂબ આનંદ થયો."
        else -> "Welcome back to Jehan Numa Palace — it's wonderful to have you with us again."
    }

    /** Farewell spoken when the guest is checked out. */
    fun composeFarewell(stay: StayInfo): String {
        val pack = packs[stay.languagePref] ?: packs["en-IN"]!!
        val name = stay.guestName + pack.honorific
        return String.format(pack.farewell, name).replace(Regex("\\s+"), " ").trim()
    }

    /** The language we should speak/listen in for this stay. */
    fun languageOf(stay: StayInfo): String =
        if (packs.containsKey(stay.languagePref)) stay.languagePref else "en-IN"

    /** Spoken line after a guest raises a service request. */
    fun serviceAck(stay: StayInfo): String =
        acks[stay.languagePref] ?: acks["en-IN"]!!
}