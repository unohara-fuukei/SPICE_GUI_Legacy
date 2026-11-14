package com.spice.nativeapp.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPD 客户端纯逻辑单测（对齐 main src/lib/spd/client.ts / genes.ts / vectors.ts 语义）：
 * 信封解包、错误信封、代理解析、FASTA 解析、区域类型映射、特征↔区域转换、回执 id 提取。
 */
class SpdLibraryTest {

    // ─────────────────────────── envelope ───────────────────────────

    @Test
    fun `envelope unwraps data when meta present`() {
        // GET /health 实测形状：{"data":{…},"error":null,"meta":{…}}
        val r = SpdLibrary.envelope(
            200,
            """{"data":{"apiVersion":"1.0.0","service":"spd","status":"ok"},"error":null,"meta":{"schemaVersion":"1.0.0"}}"""
        )
        assertNull(r.error)
        assertEquals("spd", (r.value as JSONObject).optString("service"))
    }

    @Test
    fun `envelope surfaces error code and message`() {
        // 401 实测：{"data":null,"error":{"code":"UNAUTHORIZED","fields":null,"message":"login required"},…}
        val r = SpdLibrary.envelope(
            401,
            """{"data":null,"error":{"code":"UNAUTHORIZED","fields":null,"message":"login required"},"meta":{}}"""
        )
        assertNull(r.value)
        assertEquals("UNAUTHORIZED: login required", r.error)
    }

    @Test
    fun `envelope keeps bare creation receipt unwrapped`() {
        // 创建回执是裸对象（{id, created}），不带 data/meta 信封
        val r = SpdLibrary.envelope(200, """{"id":"spd:gene:abc","created":true}""")
        assertNull(r.error)
        assertEquals("spd:gene:abc", (r.value as JSONObject).optString("id"))
    }

    @Test
    fun `envelope unwraps data-null to null value`() {
        val r = SpdLibrary.envelope(200, """{"data":null,"error":null,"meta":{}}""")
        assertNull(r.error)
        assertNull(r.value)
    }

    @Test
    fun `envelope rejects non-JSON with status prefix`() {
        val r = SpdLibrary.envelope(502, "<html>Bad Gateway</html>")
        assertTrue(r.error!!.startsWith("SPD returned non-JSON (502)"))
    }

    @Test
    fun `envelope reports http error without error body`() {
        val r = SpdLibrary.envelope(400, """{"message":"validation failed"}""")
        assertTrue(r.error!!.startsWith("HTTP 400"))
        assertTrue(r.error!!.contains("validation failed"))
    }

    // ─────────────────────────── listItems ───────────────────────────

    @Test
    fun `listItems handles array, items, regions and rows`() {
        // 基因列表实测：data 是 {items:[…]}; 区域列表返回 {regions:[…]} 或数组
        assertEquals(3, SpdLibrary.listItems(JSONArray("[{},{},{}]"))!!.length())
        assertEquals(1, SpdLibrary.listItems(JSONObject("""{"items":[{}]}"""))!!.length())
        assertEquals(2, SpdLibrary.listItems(JSONObject("""{"regions":[{},{}]}"""))!!.length())
        assertEquals(1, SpdLibrary.listItems(JSONObject("""{"rows":[{}]}"""))!!.length())
        assertEquals(2, SpdLibrary.listItems(JSONArray("[{},{}]"))!!.length())
    }

    // ─────────────────────────── parseProxy ───────────────────────────

    @Test
    fun `parseProxy default proxy has host port and userinfo auth`() {
        // 本开发机实测代理免认证：curl -x http://host:port 匿名 200；
        // 默认串照抄环境变量（带 proxy-sid-…@ 用户名段），Basic 头随行无害
        val p = SpdLibrary.parseProxy(SpdLibrary.DEFAULT_PROXY)
        assertNull(p.error)
        assertTrue(p.authHeader!!.startsWith("Basic "))
        assertEquals(
            "vortex-3e0s8vctxbmdc6uqpftnfzswr.vortexip.cn-beijing2.volces.com:8080",
            (p.proxy!!.address() as java.net.InetSocketAddress).let { "${it.hostName}:${it.port}" }
        )
    }

    @Test
    fun `parseProxy blank means direct connection`() {
        val p = SpdLibrary.parseProxy("")
        assertNull(p.proxy) // null = Proxy.NO_PROXY 直连
        assertNull(p.authHeader)
    }

    @Test
    fun `parseProxy userinfo becomes basic auth`() {
        val p = SpdLibrary.parseProxy("http://user:pass@127.0.0.1:8888")
        assertNull(p.error)
        assertTrue(p.authHeader!!.startsWith("Basic "))
    }

    @Test
    fun `parseProxy rejects garbage as error and falls back to direct`() {
        val p = SpdLibrary.parseProxy("not a proxy")
        assertNull(p.proxy)
        assertTrue(p.error != null)
    }

    // ─────────────────────────── FASTA 解析 ───────────────────────────

    @Test
    fun `parseFastaSequence joins multi-line fasta and drops headers`() {
        // publishVector 上传格式：">name N bp" 头 + 60 列折行
        val text = ">vector 9 bp\nACGTACGTA\nCGT"
        assertEquals("ACGTACGTACGT", SpdLibrary.parseFastaSequence(text))
    }

    @Test
    fun `parseFastaSequence uppercases bare sequence`() {
        assertEquals("ACGT", SpdLibrary.parseFastaSequence("ac gt"))
    }

    // ─────────────────────── 区域类型映射（genes.ts 1:1） ───────────────────────

    @Test
    fun `region type aliases map to spd enum`() {
        assertEquals("five_prime_utr", SpdLibrary.mapFeatureTypeToRegionType("5'UTR"))
        assertEquals("five_prime_utr", SpdLibrary.mapFeatureTypeToRegionType("5′UTR"))
        assertEquals("five_prime_utr", SpdLibrary.mapFeatureTypeToRegionType("5 utr"))
        assertEquals("three_prime_utr", SpdLibrary.mapFeatureTypeToRegionType("3_utr"))
        assertEquals("other", SpdLibrary.mapFeatureTypeToRegionType("misc_feature"))
        assertEquals("other", SpdLibrary.mapFeatureTypeToRegionType("gene"))
        assertEquals("promoter", SpdLibrary.mapFeatureTypeToRegionType("Promoter"))
        assertEquals("cds", SpdLibrary.mapFeatureTypeToRegionType("CDS"))
        assertEquals("other", SpdLibrary.mapFeatureTypeToRegionType("totally_unknown_kind"))
    }

    @Test
    fun `toGeneRegions skips invalid rows instead of clamping`() {
        // 对齐 genes.ts：越界/反转行跳过（钳制会变成假断言）
        val features = listOf(
            Feature("cds1", "CDS", 1, 10, 1),          // 合法
            Feature("bad", "CDS", 8, 5, 1),            // 反转 → 跳过
            Feature("over", "CDS", 1, 100, 1),         // 越界(序列长 20) → 跳过
            Feature("zero", "CDS", 0, 5, 1)            // 0-based 起点 → 跳过
        )
        val (regions, skipped) = SpdLibrary.toGeneRegions(features, 20)
        assertEquals(1, regions.length())
        assertEquals(3, skipped)
        val row = regions.getJSONObject(0)
        assertEquals("dna", row.getString("molecule"))
        assertEquals("cds", row.getString("regionType"))
        assertEquals(1, row.getInt("startPosition"))
        assertEquals(10, row.getInt("endPosition"))
        assertEquals("forward", row.getString("strand"))
    }

    @Test
    fun `toGeneRegions reverse strand feature maps to reverse`() {
        val (regions, _) = SpdLibrary.toGeneRegions(listOf(Feature("x", "CDS", 2, 6, -1)), 10)
        assertEquals("reverse", regions.getJSONObject(0).getString("strand"))
    }

    @Test
    fun `toGeneRegions enforces max 500`() {
        val many = (1..520).map { Feature("f$it", "CDS", 1, 3, 1) }
        val (regions, skipped) = SpdLibrary.toGeneRegions(many, 10)
        assertEquals(500, regions.length())
        assertEquals(20, skipped)
    }

    @Test
    fun `mapGeneRegionsToFeatures filters molecule and validates coordinates`() {
        val payload = JSONObject("""{"regions":[
            {"molecule":"dna","regionType":"cds","description":"CDS1","startPosition":2,"endPosition":9,"strand":"forward"},
            {"molecule":"rna","regionType":"cds","description":"RNA row","startPosition":1,"endPosition":5,"strand":"forward"},
            {"molecule":"dna","regionType":"promoter","startPosition":50,"endPosition":60,"strand":"forward"}
        ]}""")
        val feats = SpdLibrary.mapGeneRegionsToFeatures(payload, 20, "dna")
        assertEquals(1, feats.size)
        assertEquals("CDS1", feats[0].name)
        assertEquals(2, feats[0].start)
        assertEquals(9, feats[0].end)
        assertEquals(1, feats[0].strand)
    }

    @Test
    fun `mapGeneRegionsToFeatures uses description fallback and legacy coordinates`() {
        val payload = JSONObject("""{"items":[
            {"molecule":"dna","regionType":"terminator","start":3,"end":8,"strand":"reverse"}
        ]}""")
        val feats = SpdLibrary.mapGeneRegionsToFeatures(payload, 20, "dna")
        assertEquals("terminator", feats[0].name) // description 空 → regionType
        assertEquals(-1, feats[0].strand)
    }

    // ─────────────────────── 回执 id 提取（genes.ts extractGeneId） ───────────────────────

    @Test
    fun `extractGeneId accepts bare, nested and enveloped receipts`() {
        assertEquals("g1", SpdLibrary.extractGeneId(JSONObject("""{"id":"g1","created":true}""")))
        assertEquals("g2", SpdLibrary.extractGeneId(JSONObject("""{"geneId":"g2"}""")))
        assertEquals("g3", SpdLibrary.extractGeneId(JSONObject("""{"gene":{"id":"g3"}}""")))
        assertEquals("g4", SpdLibrary.extractGeneId(JSONObject("""{"data":{"id":"g4"}}""")))
        assertEquals("g5", SpdLibrary.extractGeneId(JSONObject("""{"data":{"gene":{"id":"g5"}}}""")))
        assertEquals("", SpdLibrary.extractGeneId(JSONObject("""{"created":false}""")))
    }
}
