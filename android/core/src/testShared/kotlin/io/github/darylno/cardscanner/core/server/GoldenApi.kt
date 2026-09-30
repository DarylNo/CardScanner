package io.github.darylno.cardscanner.core.server

import io.github.darylno.cardscanner.core.MiniJson

/** The golden fixture and its replay, shared by every [ScanStore] implementation's parity test. */
object GoldenApi {
    fun fixture(): Map<String, Any?> {
        val text = GoldenApi::class.java.getResourceAsStream("/api/expected.json")!!
            .use { String(it.readBytes(), Charsets.UTF_8) }
        @Suppress("UNCHECKED_CAST")
        return MiniJson.parse(text) as Map<String, Any?>
    }

    fun route(path: String) = path.replace(Regex("/api/scans/\\d+"), "/api/scans/{id}")

    /** Replays the fixture against a fresh store from [newStore]; returns one line per mismatch. */
    @Suppress("UNCHECKED_CAST")
    fun replay(newStore: (clock: () -> String) -> ScanStore): List<String> {
        val fx = fixture()
        var tick = 0
        val store = newStore { tick++; "T" + (tick).toString().padStart(4, '0') }
        val photos = HashMap<Long, ByteArray>()
        val api = PhoneApi(store, object : ScanImages {
            override fun read(id: Long) = photos[id]
            override fun write(id: Long, jpeg: ByteArray) { photos[id] = jpeg }
            override fun delete(id: Long) { photos.remove(id) }
        })
        for (s in fx["seeds"] as List<Map<String, Any?>>) {
            store.create(s["identified"] as Boolean, s["card_read"] as Map<String, Any?>?,
                s["confidence"] as Map<String, Any?>?, s["candidates"] as List<Any?>?, s["error"] as String?)
        }
        val out = ArrayList<String>()
        for ((i, step) in (fx["steps"] as List<Map<String, Any?>>).withIndex()) {
            val tag = "#$i ${step["method"]} ${step["path"]}"
            if (step["method"] == "FILE") {
                val got = api.fileScan(step["result"] as Map<String, Any?>, byteArrayOf(1, 2, 3),
                    (step["replace_scan_id"] as Number).toLong())
                jsonDiff(step["response"], MiniJson.parse(MiniJson.stringify(got)), "$")?.let { out += "$tag: $it" }
                val id = got["id"] as Long?
                if (id != null && photos[id] == null) out += "$tag: photo not kept"
                continue
            }
            val body = step["body"]?.let { MiniJson.stringify(it).toByteArray() }
            val res = api.handle(ApiRequest(step["method"] as String, step["path"] as String, body = body))
            if (res == null) { out += "$tag: not handled"; continue }
            val wantStatus = (step["status"] as Number).toInt()
            if (res.status != wantStatus) out += "$tag: status ${res.status}, server $wantStatus"
            val got = MiniJson.parse(String(res.body, Charsets.UTF_8))
            val diff = jsonDiff(step["response"], got, "$")
            if (diff != null) out += "$tag: $diff"
        }
        return out
    }

    private fun resource(name: String): Map<String, Any?> {
        val text = GoldenApi::class.java.getResourceAsStream("/api/$name")!!
            .use { String(it.readBytes(), Charsets.UTF_8) }
        @Suppress("UNCHECKED_CAST")
        return MiniJson.parse(text) as Map<String, Any?>
    }

    /** Timing fields of /api/price-status: wall-clock, not compared (cooldown only as zero / non-zero). */
    private val TIMING = setOf("pace_s", "next_check_s", "rate_per_s", "eta_s")

    /**
     * Replays the pricing session (api/sweep.json) — the same seeds, the same
     * fake F2F price table and mid-lookup store hooks — against [ScanServer];
     * every status, body (timing fields aside) and the exact sequence of F2F
     * lookups each request made must match. One line per mismatch.
     */
    @Suppress("UNCHECKED_CAST")
    fun replaySweep(newStore: (clock: () -> String) -> ScanStore): List<String> {
        val fx = resource("sweep.json")
        var tick = 0
        val store = newStore { tick++; "T" + tick.toString().padStart(4, '0') }
        val api = PhoneApi(store, object : ScanImages {
            override fun read(id: Long): ByteArray? = null
            override fun write(id: Long, jpeg: ByteArray) {}
            override fun delete(id: Long) {}
        })
        val prices = fx["prices"] as Map<String, Any?>
        val hooks = fx["hooks"] as Map<String, Map<String, Any?>>
        val lookups = ArrayList<String>()
        val f2f = F2fLookup { name, set, cn, foil, _ ->
            val key = "$set|$cn|${if (foil) 1 else 0}"
            lookups += key
            hooks[key]?.let { h -> store.update((h["scan"] as Number).toLong(), h["fields"] as Map<String, Any?>) }
            when (val a = prices[key] ?: "none") {
                "unavailable" -> F2fAnswer.Unavailable(key)
                "none" -> F2fAnswer.NotListed
                else -> {
                    val m = a as Map<String, Any?>
                    F2fAnswer.Found(linkedMapOf("name" to name, "set_code" to set, "collector_number" to cn,
                        "foil" to foil, "handle" to m["handle"],
                        "url" to "https://facetofacegames.com/products/${m["handle"]}",
                        "conditions" to LinkedHashMap(m["conditions"] as Map<String, Any?>)))
                }
            }
        }
        var t = 0.0
        val server = ScanServer(api, PriceSweep(store, api, f2f, launch = { it.run() }, now = { t += 0.001; t }))
        for (seed in fx["seeds"] as List<Map<String, Any?>>) {
            val c = seed["create"] as Map<String, Any?>
            val row = store.create(c["identified"] as Boolean, c["card_read"] as Map<String, Any?>?,
                c["confidence"] as Map<String, Any?>?, c["candidates"] as List<Any?>?, c["error"] as String?)
            (seed["selection"] as Map<String, Any?>?)?.let {
                store.update(row["id"] as Long, linkedMapOf("status" to "selected", "selection" to it))
            }
        }
        val out = ArrayList<String>()
        for ((i, step) in (fx["steps"] as List<Map<String, Any?>>).withIndex()) {
            val tag = "#$i ${step["method"]} ${step["path"]}"
            val before = lookups.size
            val res = server.handle(ApiRequest(step["method"] as String, step["path"] as String,
                body = step["body"]?.let { MiniJson.stringify(it).toByteArray() }))
            if (res == null) { out += "$tag: not handled"; continue }
            val want = (step["status"] as Number).toInt()
            if (res.status != want) out += "$tag: status ${res.status}, server $want"
            var got = MiniJson.parse(String(res.body, Charsets.UTF_8))
            var exp = step["response"]
            if (step["path"] == "/api/price-status") {
                val g = got as Map<String, Any?>; val e = exp as Map<String, Any?>
                if (((g["cooldown_s"] as Number).toLong() > 0) != ((e["cooldown_s"] as Number).toLong() > 0)) {
                    out += "$tag: cooldown ${g["cooldown_s"]} vs server ${e["cooldown_s"]}"
                }
                got = g.filterKeys { it !in TIMING && it != "cooldown_s" }
                exp = e.filterKeys { it !in TIMING && it != "cooldown_s" }
            }
            jsonDiff(exp, got, "$")?.let { out += "$tag: $it" }
            val made = lookups.subList(before, lookups.size).toList()
            if (made != step["lookups"]) out += "$tag: lookups $made, server ${step["lookups"]}"
        }
        return out
    }

    /** Null when equal; numbers compare by value (Python int 2 == JSON 2), booleans stay booleans. */
    fun jsonDiff(want: Any?, got: Any?, at: String): String? = when {
        want is Map<*, *> && got is Map<*, *> -> {
            if (want.keys != got.keys) "$at keys ${want.keys} vs ${got.keys}"
            else want.keys.firstNotNullOfOrNull { k -> jsonDiff(want[k], got[k], "$at.$k") }
        }
        want is List<*> && got is List<*> ->
            if (want.size != got.size) "$at length ${want.size} vs ${got.size}"
            else want.indices.firstNotNullOfOrNull { jsonDiff(want[it], got[it], "$at[$it]") }
        want is Boolean || got is Boolean -> if (want == got) null else "$at: $want vs $got"
        want is Number && got is Number ->
            if (want.toDouble() == got.toDouble()) null else "$at: $want vs $got"
        else -> if (want == got) null else "$at: $want vs $got"
    }
}
