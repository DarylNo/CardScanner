package io.github.darylno.cardscanner.f2f

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The 24 h F2F disk cache (facetoface.py's): hit within the TTL, miss after it or when torn. */
@RunWith(RobolectricTestRunner::class)
class F2fCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun hitsWithinTheTtlAndMissesAfter() {
        val dir = tmp.newFolder("f2f")
        var now = System.currentTimeMillis()
        val cache = F2fCache(dir, ttlMs = 1000, nowMs = { now })
        val url = "https://facetofacegames.com/search/suggest.json?q=Opt"
        assertNull(cache.get(url))
        cache.put(url, JSONObject().put("handle", "opt-dom"))
        assertEquals("opt-dom", cache.get(url)!!.getString("handle"))
        assertNull(cache.get("$url&x=1"))                           // keyed by the whole URL
        now += 5_000
        assertNull(cache.get(url))                                  // stale → refetch
    }

    @Test fun aTornFileIsAMiss() {
        val dir = tmp.newFolder("f2f")
        val cache = F2fCache(dir)
        cache.put("u", JSONObject().put("a", 1))
        dir.listFiles()!!.single().writeText("{\"a\":")
        assertNull(cache.get("u"))
    }
}
