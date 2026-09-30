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
