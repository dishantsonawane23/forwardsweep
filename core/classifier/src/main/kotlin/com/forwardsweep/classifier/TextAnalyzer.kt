package com.forwardsweep.classifier

import java.text.Normalizer

data class TextSignals(
    val wordCount: Int,
    /** 0..1 strength of greeting phrases (exact or fuzzy match). */
    val greeting: Double,
    val greetingMatches: List<String>,
    /** 0..1 meme phrasing ("when you…", "me:", "nobody:", "be like"). */
    val memePhrasing: Double,
    /** 0..1 receipts, invoices, bills, payment confirmations. */
    val document: Double,
    /** 0..1 phone UI chrome: chat app labels, clock, battery. */
    val screenUi: Double,
    val documentMatches: List<String>,
    /**
     * 0..1 "event poster / advert" evidence: phone numbers, dates, times, prices and words like
     * register, venue, entry fee. Needs several kinds together, because greeting banners often
     * carry the sender's phone number alone.
     */
    val poster: Double = 0.0,
    val posterMatches: List<String> = emptyList(),
)

/**
 * Keyword analysis over OCR output. Matching is tolerant of typical OCR errors
 * ("G00D M0RNING", "Good Mornig", dropped Devanagari vowel signs) through character
 * normalization and per-token edit distance.
 */
class TextAnalyzer(
    greetingPhrases: List<String> = DEFAULT_GREETINGS,
    private val memePhrases: List<String> = DEFAULT_MEME,
    private val documentWords: List<String> = DEFAULT_DOCUMENT,
    private val uiWords: List<String> = DEFAULT_UI,
) {
    private val greetings: List<List<String>> = greetingPhrases.map { tokenize(it) }.filter { it.isNotEmpty() }

    fun analyze(ocr: OcrResult): TextSignals {
        val tokens = tokenize(ocr.fullText)
        if (tokens.isEmpty()) return TextSignals(0, 0.0, emptyList(), 0.0, 0.0, 0.0, emptyList())

        val greetingHits = greetings.filter { containsPhrase(tokens, it) }.map { it.joinToString(" ") }
        val joined = " " + tokens.joinToString(" ") + " "
        val memeHits = memePhrases.count { joined.contains(" $it ") || joined.contains(" $it") }
        val docHits = documentWords.filter { w -> tokens.any { it == w } }
        val uiHits = uiWords.count { joined.contains(" $it ") }
        val clock = TIME_RE.findAll(ocr.fullText).count()
        val money = MONEY_RE.findAll(ocr.fullText).count()
        val eventHits = EVENT_WORDS.filter { w -> tokens.any { it == w } }
        val phone = PHONE_RE.containsMatchIn(ocr.fullText)
        val date = DATE_RE.containsMatchIn(ocr.fullText.lowercase())
        val meridiem = MERIDIEM_RE.containsMatchIn(ocr.fullText.lowercase())
        val posterKinds = listOf(phone, date, meridiem, money > 0, eventHits.isNotEmpty(), eventHits.size >= 3).count { it }

        return TextSignals(
            wordCount = tokens.size,
            greeting = when {
                greetingHits.isEmpty() -> 0.0
                greetingHits.size == 1 -> 0.8
                else -> 1.0
            },
            greetingMatches = greetingHits,
            memePhrasing = (memeHits * 0.5).coerceAtMost(1.0),
            document = (docHits.size * 0.25 + money * 0.15).coerceAtMost(1.0),
            screenUi = (uiHits * 0.3 + (if (clock > 0) 0.2 else 0.0) + (if (clock > 2) 0.2 else 0.0)).coerceAtMost(1.0),
            documentMatches = docHits,
            poster = ((posterKinds - 1) / 2.0).coerceIn(0.0, 1.0),
            posterMatches = buildList {
                if (phone) add("phone number"); if (date) add("date"); if (meridiem) add("time")
                if (money > 0) add("price"); addAll(eventHits.take(3))
            },
        )
    }

    private fun containsPhrase(tokens: List<String>, phrase: List<String>): Boolean {
        // OCR sometimes glues words: "goodmorning", "शुभरात्रि".
        if (phrase.size > 1) {
            val glued = phrase.joinToString("")
            if (tokens.any { fuzzyEquals(it, glued) }) return true
        }
        if (phrase.size > tokens.size) return false
        return (0..tokens.size - phrase.size).any { start ->
            phrase.indices.all { k -> fuzzyEquals(tokens[start + k], phrase[k]) }
        }
    }

    companion object {
        private val TIME_RE = Regex("""\b([01]?\d|2[0-3]):[0-5]\d\b""")
        private val MONEY_RE = Regex("""(₹|rs\.?|inr)\s?\d""", RegexOption.IGNORE_CASE)

        fun tokenize(text: String): List<String> {
            val normalized = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFC)
                .replace("़", "") // nukta: OCR drops or adds it inconsistently
                .replace(Regex("[\\p{Punct}“”‘’…|«»।॥]"), " ")
            return normalized.split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .map { fixLatinOcr(it) }
        }

        /** Map digit look-alikes inside otherwise alphabetic Latin tokens: g00d → good, nlght → night. */
        private fun fixLatinOcr(token: String): String {
            val letters = token.count { it in 'a'..'z' }
            if (letters == 0 || letters < token.length / 2) return token
            return token.map {
                when (it) {
                    '0' -> 'o'; '1' -> 'i'; '3' -> 'e'; '5' -> 's'; '@' -> 'a'; '$' -> 's'
                    else -> it
                }
            }.joinToString("")
        }

        fun fuzzyEquals(a: String, b: String): Boolean {
            if (a == b) return true
            val allowed = when {
                b.length >= 8 -> 2
                b.length >= 4 -> 1
                else -> 0
            }
            if (allowed == 0 || kotlin.math.abs(a.length - b.length) > allowed) return false
            return levenshtein(a, b, allowed) <= allowed
        }

        private fun levenshtein(a: String, b: String, cap: Int): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                var rowMin = cur[0]
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                    rowMin = minOf(rowMin, cur[j])
                }
                if (rowMin > cap) return cap + 1
                prev = cur
            }
            return prev[b.length]
        }

        private val FESTIVALS = listOf(
            "diwali", "deepawali", "dipawali", "holi", "navratri", "dussehra", "dasara", "ganesh chaturthi",
            "eid", "eid mubarak", "christmas", "new year", "raksha bandhan", "rakhi", "makar sankranti",
            "sankranti", "pongal", "onam", "janmashtami", "mahashivratri", "shivratri", "gudi padwa",
            "independence day", "republic day", "durga puja", "lohri", "baisakhi", "karva chauth",
            "dhanteras", "bhai dooj", "guru purnima", "teachers day", "mothers day", "fathers day",
        )

        val DEFAULT_GREETINGS: List<String> = listOf(
            "good morning", "good night", "good evening", "good afternoon", "good day",
            "have a nice day", "have a great day", "have a good day", "have a blessed day",
            "sweet dreams", "good morning friends",
            "suprabhat", "shubh prabhat", "shubh ratri", "shubh sandhya", "shubh dopahar",
            "shubh sakal", "shubh divas", "shubh deepawali", "jai shree krishna", "jai shri ram",
            "jai shree ram", "radhe radhe", "har har mahadev", "om namah shivaya", "jai mata di",
            "happy sunday", "happy monday", "happy tuesday", "happy wednesday", "happy thursday",
            "happy friday", "happy saturday", "happy weekend", "blessed sunday", "best wishes",
            "warm wishes", "season's greetings",
            // Devanagari (Hindi + Marathi)
            "सुप्रभात", "शुभ प्रभात", "शुभ रात्रि", "शुभ रात्री", "शुभ संध्या", "शुभ सकाळ",
            "शुभ दिवस", "शुभ दीपावली", "दीपावली की शुभकामनाएं", "दिवाळीच्या शुभेच्छा",
            "हार्दिक शुभेच्छा", "शुभेच्छा", "शुभकामनाएं", "शुभ दोपहर", "जय श्री कृष्ण", "जय श्री राम",
            "राधे राधे", "हर हर महादेव", "गुड मॉर्निंग", "गुड नाइट", "शुभ रविवार", "शुभ सोमवार",
        ) + FESTIVALS.map { "happy $it" } + FESTIVALS.filter { it.contains(' ') }

        val DEFAULT_MEME = listOf(
            "when you", "when your", "when ur", "nobody:", "be like", "pov", "lol", "lmao",
            "that moment when", "that feeling when", "tfw", "mfw", "my face when", "ye log", "meanwhile",
            "expectation vs reality", "relatable", "literally me",
            "no one:", "teacher:", "mom:", "papa:", "wife:", "husband:",
        ).map { it.replace(":", "").trim() }

        val DEFAULT_DOCUMENT = listOf(
            "total", "subtotal", "invoice", "receipt", "bill", "gst", "gstin", "amount", "paid", "payment",
            "upi", "transaction", "txn", "ref", "qty", "rate", "tax", "balance", "account", "bank",
            "debited", "credited", "order", "date", "cash", "change", "mrp", "discount", "signature",
            "certificate", "aadhaar", "pan", "ticket", "pnr", "boarding", "seat", "passenger",
        )

        /** Words typical of event posters, trip/class adverts and invitations. */
        val EVENT_WORDS = setOf(
            "register", "registration", "registrations", "venue", "entry", "fee", "fees", "contact", "booking",
            "book", "seats", "limited", "join", "apply", "workshop", "tournament", "event", "camp", "trip", "trek",
            "package", "offer", "onwards", "rsvp", "invite", "invites", "presents", "batch", "session",
            "webinar", "meet", "summit", "program", "programme", "fellowship", "inviting", "speakers", "pass",
        )
        private val PHONE_RE = Regex("""(?<!\d)(?:\+91[\s-]?)?[6-9]\d{4}[\s-]?\d{5}(?!\d)""")
        private val DATE_RE = Regex("""\b\d{1,2}(?:st|nd|rd|th)?\s*(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\b|\b\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}\b""")
        private val MERIDIEM_RE = Regex("""\b\d{1,2}(?:[:.]\d{2})?\s*(?:am|pm)\b""")

        val DEFAULT_UI = listOf(
            "type a message", "last seen", "online", "typing", "reply", "forward", "forwarded",
            "delete for everyone", "this message was deleted", "tap to", "swipe", "settings", "search",
            "notifications", "wi fi", "bluetooth", "lte", "volte", "5g", "4g", "chats", "status", "calls",
            "share", "like", "comment", "follow", "subscribe", "views", "send", "cancel", "ok",
        )
    }
}
