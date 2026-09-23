package com.unciv.godot

import com.sun.net.httpserver.HttpServer
import com.unciv.logic.files.UncivFiles
import kotlinx.serialization.json.*
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** 只验证 GatewayMain 的 HTTP 外壳（认证、来源、方法、大小、格式）；命令语义由 GameSessionTest 覆盖。 */
class GatewayHttpTest {
    companion object {
        private const val token = "test-token-0123456789abcdef0123456789abcdef"
        private lateinit var server: HttpServer
        private lateinit var session: GameSession
        private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

        @BeforeClass @JvmStatic fun setup() {
            val root = File(System.getProperty("unciv.root"))
            KernelRuntime.initialize(root)
            session = GameSession(root)
            server = startGateway(0, token, session)
        }

        @AfterClass @JvmStatic fun teardown() {
            try {
                if (::server.isInitialized) {
                    stopGateway(server)
                    assertTrue("HTTP 工作线程应退出", (server.executor as ExecutorService).awaitTermination(5, TimeUnit.SECONDS))
                }
            } finally {
                client.close()
            }
        }
    }

    private val hello = """{"protocol":1,"action":"hello"}"""

    private fun send(
        body: String = hello, method: String = "POST", path: String = "/api",
        authorization: String? = "Bearer $token", contentType: String? = "application/json",
        origin: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}$path"))
            .timeout(Duration.ofSeconds(10))
            .method(method, if (method == "GET") HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        authorization?.let { builder.header("Authorization", it) }
        contentType?.let { builder.header("Content-Type", it) }
        origin?.let { builder.header("Origin", it) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun assertHttpError(response: HttpResponse<String>, status: Int) {
        assertEquals(response.body(), status, response.statusCode())
        val json = Json.parseToJsonElement(response.body()).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.boolean)
        assertEquals("HTTP_ERROR", json["error"]!!.jsonObject.text("code"))
        assertEquals("application/json; charset=utf-8", response.headers().firstValue("Content-Type").get())
        assertEquals("no-store", response.headers().firstValue("Cache-Control").get())
    }

    @Test fun validHelloIsAccepted() {
        val response = send()
        assertEquals(response.body(), 200, response.statusCode())
        val json = Json.parseToJsonElement(response.body()).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.boolean)
        assertEquals(session.sessionId, json.text("session"))
        assertEquals("application/json; charset=utf-8", response.headers().firstValue("Content-Type").get())
        assertEquals("no-store", response.headers().firstValue("Cache-Control").get())
    }

    @Test fun wrongOrMissingTokenIsRejectedWith401() {
        assertHttpError(send(authorization = "Bearer ${token.dropLast(1)}x"), 401)
        // 头值首尾空白会被 HTTP 层裁掉，因此不测尾随空格；大小写与前缀必须逐字节一致。
        assertHttpError(send(authorization = "bearer $token"), 401)
        assertHttpError(send(authorization = "Bearer ${token}0"), 401)
        assertHttpError(send(authorization = token), 401)
        assertHttpError(send(authorization = null), 401)
    }

    @Test fun browserOriginIsRejectedEvenWithValidToken() {
        assertHttpError(send(origin = "http://127.0.0.1"), 400)
        assertHttpError(send(origin = "null"), 400)
    }

    @Test fun onlyPostToApiIsAccepted() {
        assertHttpError(send(method = "GET"), 400)
        assertHttpError(send(method = "PUT"), 400)
        assertHttpError(send(path = "/api/"), 400)
        assertHttpError(send(path = "/api/other"), 400)
        assertEquals(404, send(path = "/").statusCode())
    }

    @Test fun nonJsonContentTypeIsRejected() {
        assertHttpError(send(contentType = "text/plain"), 400)
        assertHttpError(send(contentType = null), 400)
        assertEquals(200, send(contentType = "application/json; charset=utf-8").statusCode())
    }

    @Test fun oversizedBodyIsRejected() {
        val prefix = hello.dropLast(1) + ",\"pad\":\""
        val suffix = "\"}"
        for (size in listOf(65536, 65537)) {
            val body = prefix + "x".repeat(size - prefix.length - suffix.length) + suffix
            assertEquals(size, body.toByteArray(Charsets.UTF_8).size)
            if (size == 65537) assertHttpError(send(body = body), 400)
            else {
                val response = send(body = body)
                assertEquals(200, response.statusCode())
                assertTrue(Json.parseToJsonElement(response.body()).jsonObject["ok"]!!.jsonPrimitive.boolean)
            }
        }
        // 上限按 UTF-8 字节而非字符数计算；中文字符也不能绕过限制。
        val multibyte = prefix + "中".repeat(22000) + suffix
        assertTrue(multibyte.length < 65536 && multibyte.toByteArray(Charsets.UTF_8).size > 65536)
        assertHttpError(send(body = multibyte), 400)
    }

    @Test fun malformedJsonIsRejectedWithoutTouchingSession() {
        val revision = session.revision
        val game = session.game
        assertHttpError(send(body = "{\"protocol\":1,"), 400)
        assertHttpError(send(body = ""), 400)
        assertHttpError(send(body = "[1,2,3]"), 400)
        assertHttpError(send(body = "\"hello\""), 400)
        assertEquals(revision, session.revision)
        assertSame(game, session.game)
    }

    @Test fun successfulAttackHttpReplayDoesNotDealDamageTwice() {
        val fixture = BattleFixtures.file("http-combat")
        val loaded = send(body = BattleFixtures.request(session, "load", "path" to fixture.absolutePath).toString())
        assertTrue(Json.parseToJsonElement(loaded.body()).jsonObject["ok"]!!.jsonPrimitive.boolean)
        val id = BattleFixtures.unit(session.game!!, "Archer").id
        val body = BattleFixtures.request(session, "attack", "unitId" to id, "x" to 0, "y" to 2).toString()
        val first = send(body)
        val json = Json.parseToJsonElement(first.body()).jsonObject
        assertTrue(json.toString(), json["ok"]!!.jsonPrimitive.boolean)
        assertTrue(json["battleResult"]!!.jsonObject.integer("damageToDefender") > 0)
        val after = com.unciv.logic.files.UncivFiles.gameInfoToString(session.game!!, false)
        val revision = session.revision
        val replay = send(body)
        assertEquals(200, replay.statusCode())
        assertEquals(first.body(), replay.body())
        assertEquals(revision, session.revision)
        assertEquals(after, com.unciv.logic.files.UncivFiles.gameInfoToString(session.game!!, false))
    }

    @Test fun protocolErrorsComeFromSessionNotHttpLayer() {
        // 格式正确但语义错误的请求由 GameSession 以 200 + ok:false 回复，错误码不是 HTTP_ERROR。
        val response = send(body = """{"protocol":2,"action":"hello"}""")
        assertEquals(200, response.statusCode())
        val json = Json.parseToJsonElement(response.body()).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.boolean)
        assertEquals("PROTOCOL", json["error"]!!.jsonObject.text("code"))
        val unknownSession = send(body = """{"protocol":1,"action":"snapshot","session":"nope"}""")
        assertEquals("SESSION", Json.parseToJsonElement(unknownSession.body()).jsonObject["error"]!!.jsonObject.text("code"))
    }

    /** 通过 HTTP 载入发展场景，返回可写请求所需的工人 id 与城市 id。 */
    private fun loadDevelopment(name: String, withWorker: Boolean): Pair<Int?, String> {
        val fixture = DevelopmentFixtures.file(name) { game ->
            if (withWorker) DevelopmentFixtures.worker(game, DevelopmentFixtures.farm)
        }
        val loaded = send(body = BattleFixtures.request(session, "load", "path" to fixture.absolutePath).toString())
        assertTrue(loaded.body(), Json.parseToJsonElement(loaded.body()).jsonObject["ok"]!!.jsonPrimitive.boolean)
        val worker = if (withWorker) BattleFixtures.unit(session.game!!, "Worker").id else null
        return worker to session.game!!.currentPlayerCiv.cities.first().id
    }

    /**
     * 一条可写请求经真 HTTP 提交后：相同请求重放返回缓存响应且不重复写入；
     * 同 requestId 异参被拒（REQUEST_REUSED）；换新 requestId 但携带旧 revision 被拒（STALE_STATE）。
     * 三者都不得改动存档或推进 revision。
     */
    private fun assertIdempotentOverHttp(body: String) {
        val first = send(body)
        val json = Json.parseToJsonElement(first.body()).jsonObject
        assertTrue(json.toString(), json["ok"]!!.jsonPrimitive.boolean)
        val after = UncivFiles.gameInfoToString(session.game!!, false)
        val revision = session.revision
        val parsed = Json.parseToJsonElement(body).jsonObject

        val replay = send(body)
        assertEquals(200, replay.statusCode())
        assertEquals(first.body(), replay.body())
        assertEquals(revision, session.revision)
        assertEquals(after, UncivFiles.gameInfoToString(session.game!!, false))

        val reused = send(JsonObject(parsed + ("probe" to JsonPrimitive(true))).toString())
        assertEquals("REQUEST_REUSED", Json.parseToJsonElement(reused.body()).jsonObject["error"]!!.jsonObject.text("code"))
        assertEquals(revision, session.revision)
        assertEquals(after, UncivFiles.gameInfoToString(session.game!!, false))

        val stale = send(JsonObject(parsed + ("requestId" to JsonPrimitive(UUID.randomUUID().toString()))).toString())
        assertEquals("STALE_STATE", Json.parseToJsonElement(stale.body()).jsonObject["error"]!!.jsonObject.text("code"))
        assertEquals(revision, session.revision)
        assertEquals(after, UncivFiles.gameInfoToString(session.game!!, false))
    }

    @Test fun developmentCommandsAreIdempotentOverHttp() {
        val (workerId, cityId) = loadDevelopment("http-development", withWorker = true)
        // 施工：工人在农场地块开始改良。
        assertIdempotentOverHttp(BattleFixtures.request(session, "workerOrder",
            "unitId" to workerId, "type" to "start", "name" to "Farm").toString())
        // 人口分配：切换焦点会触发人口重分配。
        assertIdempotentOverHttp(BattleFixtures.request(session, "cityFocus",
            "cityId" to cityId, "focus" to "FoodFocus").toString())
        // 队列修改：加入生产项目。
        assertIdempotentOverHttp(BattleFixtures.request(session, "cityQueue",
            "cityId" to cityId, "type" to "add", "name" to "Warrior").toString())
    }

    @Test fun citizenWorkUnworkAreIdempotentOverHttp() {
        val (_, cityId) = loadDevelopment("http-citizen", withWorker = false)
        // 取一块己方城市已工作的非中心地块；先撤回腾出人口，再把同一地块重新分配。
        val city = session.game!!.currentPlayerCiv.cities.first { it.id == cityId }
        val worked = city.getWorkedTiles().first { !it.isCityCenter() }
        val x = worked.position.x
        val y = worked.position.y
        // unwork：真 HTTP 提交后重放返回缓存、异参 REQUEST_REUSED、旧 revision STALE_STATE，均不重复写入。
        assertIdempotentOverHttp(BattleFixtures.request(session, "cityCitizen",
            "cityId" to cityId, "x" to x, "y" to y, "type" to "unwork").toString())
        // work：撤回后有空闲人口，同一地块可再分配，验证相同的幂等与旧版本拒绝。
        assertIdempotentOverHttp(BattleFixtures.request(session, "cityCitizen",
            "cityId" to cityId, "x" to x, "y" to y, "type" to "work").toString())
    }

    private fun economyHttp(action: String, vararg args: Pair<String, Any?>) {
        val fixture = EconomyFixtures.file("http-$action")
        fun call(command: String, vararg params: Pair<String, Any?>) = Json.parseToJsonElement(
            send(BattleFixtures.request(session, command, *params).toString()).body()).jsonObject
        assertTrue(call("load", "path" to fixture.absolutePath)["ok"]!!.jsonPrimitive.boolean)
        val id = EconomyFixtures.capital(session.game!!).id
        val original = BattleFixtures.request(session, action, "cityId" to id, *args)
        val before = UncivFiles.gameInfoToString(session.game!!, false)
        for (badSession in listOf("old-session", "")) {
            val rejected = send(JsonObject(original + ("session" to JsonPrimitive(badSession))).toString())
            assertEquals("SESSION", Json.parseToJsonElement(rejected.body()).jsonObject["error"]!!.jsonObject.text("code"))
        }
        val foreign = session.game!!.civilizations.first { it.civName == "Greece" }.cities.first().id
        assertEquals("NOT_OWNED", call(action, "cityId" to foreign, *args)["error"]!!.jsonObject.text("code"))
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))
        for (alert in listOf(com.unciv.logic.civilization.AlertType.RecapturedCivilian, com.unciv.logic.civilization.AlertType.CityConquered)) {
            session.game!!.currentPlayerCiv.popupAlerts.add(com.unciv.logic.civilization.PopupAlert(alert, id))
            val current = session.game
            val state = UncivFiles.gameInfoToString(current!!, false)
            val revision = session.revision
            assertEquals("PENDING_DECISION", call(action, "cityId" to id, *args)["error"]!!.jsonObject.text("code"))
            val query = call("cityOptions", "cityId" to id)
            assertTrue(query["ok"]!!.jsonPrimitive.boolean)
            assertTrue(query["data"]!!.jsonObject["economy"]!!.jsonObject.text("blockedReason").isNotEmpty())
            assertSame(current, session.game)
            assertEquals(revision, session.revision)
            assertEquals(state, UncivFiles.gameInfoToString(session.game!!, false))
            val save = call("save", "name" to "http-economy-pending")
            assertTrue(save["ok"]!!.jsonPrimitive.boolean)
            assertTrue(call("load", "path" to save.text("savedPath"))["ok"]!!.jsonPrimitive.boolean)
            assertEquals("PENDING_DECISION", call(action, "cityId" to id, *args)["error"]!!.jsonObject.text("code"))
            session.game!!.currentPlayerCiv.popupAlerts.clear()
        }
        assertIdempotentOverHttp(BattleFixtures.request(session, action, "cityId" to id, *args).toString())
    }

    @Test fun tileEconomyHttpContract() = economyHttp("cityBuyTile", "x" to 2, "y" to 0)
    @Test fun purchaseEconomyHttpContract() = economyHttp("cityPurchase", "name" to "Warrior", "stat" to "Gold", "queueIndex" to 2)
    @Test fun saleEconomyHttpContract() = economyHttp("citySellBuilding", "name" to "Market")

    private fun diplomacyHttp(action: String) {
        val fixture = DiplomacyFixtures.file("http-$action", action != "diplomacyDeclareWar" && action != "diplomacyAlertDecision") {
            when (action) {
                "diplomacyRetractPeace" -> DiplomacyFixtures.propose(it, 20)
                "diplomacyTradeDecision" -> DiplomacyFixtures.incoming(it, 20)
                "diplomacyAlertDecision" -> DiplomacyFixtures.alert(it, com.unciv.logic.civilization.AlertType.DemandToNotAttackUs)
            }
        }
        fun response(body: JsonObject): JsonObject {
            val http = send(body.toString())
            assertEquals(200, http.statusCode())
            return Json.parseToJsonElement(http.body()).jsonObject
        }
        assertTrue(response(BattleFixtures.request(session, "load", "path" to fixture.absolutePath))["ok"]!!.jsonPrimitive.boolean)
        val current = session.game!!
        val before = UncivFiles.gameInfoToString(current, false)
        val revision = session.revision
        val query = BattleFixtures.request(session, "diplomacyOptions")
        val data = response(query)["data"]!!.jsonObject
        assertEquals(data, response(query)["data"])
        assertSame(current, session.game)
        assertEquals(revision, session.revision)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))
        val id = DiplomacyFixtures.enemy(current).civID
        val params = when (action) {
            "diplomacyDeclareWar" -> dto("civId" to id)
            "diplomacyProposePeace" -> dto("civId" to id, "ourGold" to 20, "theirGold" to 0)
            "diplomacyRetractPeace" -> dto("civId" to id, "tradeToken" to data["civilizations"]!!.jsonArray
                .first { it.jsonObject.text("civId") == id }.jsonObject["outgoingTrades"]!!.jsonArray.first().jsonObject.text("tradeToken"))
            "diplomacyTradeDecision" -> dto("tradeToken" to data["incomingTrade"]!!.jsonObject.text("tradeToken"), "choice" to "accept")
            else -> dto("alertToken" to data["pendingAlert"]!!.jsonObject.text("alertToken"), "choice" to "refuseAndDeclareWar")
        }
        val body = JsonObject(BattleFixtures.request(session, action) + params)
        assertHttpError(send(body.toString(), authorization = null), 401)
        assertEquals("SESSION", response(JsonObject(body + ("session" to JsonPrimitive("old-session"))))["error"]!!.jsonObject.text("code"))
        for ((key, value) in params) {
            val invalid = if (key.endsWith("Gold")) listOf(JsonNull, JsonPrimitive("2"), JsonPrimitive(2.5), JsonPrimitive(-1), JsonPrimitive(2147483648L), JsonPrimitive(true), JsonArray(emptyList()))
                else listOf(JsonNull, JsonPrimitive(""), JsonPrimitive(2), JsonPrimitive(true), JsonArray(emptyList()))
            assertEquals("INVALID_ARGUMENT", response(JsonObject(body - key))["error"]!!.jsonObject.text("code"))
            for (bad in invalid) assertEquals("$key=$bad (合法值 $value)", "INVALID_ARGUMENT",
                response(JsonObject(body + (key to bad)))["error"]!!.jsonObject.text("code"))
        }
        assertSame(current, session.game)
        assertEquals(revision, session.revision)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))
        assertIdempotentOverHttp(body.toString())
        assertEquals(revision + 1, session.revision)
        assertSame(current, session.game)
    }

    @Test fun declareWarHttpContract() = diplomacyHttp("diplomacyDeclareWar")
    @Test fun proposePeaceHttpContract() = diplomacyHttp("diplomacyProposePeace")
    @Test fun retractPeaceHttpContract() = diplomacyHttp("diplomacyRetractPeace")
    @Test fun tradeDecisionHttpContract() = diplomacyHttp("diplomacyTradeDecision")
    @Test fun alertDecisionHttpContract() = diplomacyHttp("diplomacyAlertDecision")

    /**
     * 宗教新入口的 HTTP 外壳契约：载入 FoundingReligion 待决场景，验证 religionOptions 只读确定性，
     * 再对最终创立 religionFound 逐项验证鉴权／session／严格 JSON／票据错配／未接入动作注入，
     * 所有可预判失败均不得改动存档、推进 revision 或输出原生异常栈；最后验证真 HTTP 幂等／重放／陈旧拒绝。
     */
    private fun religionHttp() {
        val fixture = ReligionFixtures.export(ReligionFixtures.foundingGame(true), "http-religion")
        fun response(body: JsonObject): JsonObject {
            val http = send(body.toString())
            assertEquals(200, http.statusCode())
            return Json.parseToJsonElement(http.body()).jsonObject
        }
        fun call(command: String, vararg params: Pair<String, Any?>) =
            response(BattleFixtures.request(session, command, *params))
        assertTrue(call("load", "path" to fixture.absolutePath)["ok"]!!.jsonPrimitive.boolean)
        val current = session.game!!
        val before = UncivFiles.gameInfoToString(current, false)
        val revision = session.revision

        // religionOptions 只读：确定性、同引用、同 revision、同持久文本。
        val query = BattleFixtures.request(session, "religionOptions")
        val data = response(query)["data"]!!.jsonObject
        val decision = data["decision"]!!.jsonObject
        assertEquals("foundReligion", decision.text("mode"))
        assertEquals(data, response(query)["data"])
        assertSame(current, session.game)
        assertEquals(revision, session.revision)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))

        // 从只读详情组装最终创立写请求（票据、可用符号、逐槽候选；不使用网关资格函数）。
        val symbol = data["symbols"]!!.jsonArray.map { it.jsonObject }.first { it.boolean("available") }.text("id")
        val used = HashSet<String>()
        val beliefs = decision["slots"]!!.jsonArray.map { slot ->
            slot.jsonObject["candidateIds"]!!.jsonArray.map { it.jsonPrimitive.content }.first { used.add(it) }
        }
        val body = JsonObject(BattleFixtures.request(session, "religionFound",
            "decisionToken" to decision.text("token"), "religionId" to symbol,
            "displayName" to "HTTP 信仰", "beliefs" to beliefs))

        // 鉴权与会话边界。
        assertHttpError(send(body.toString(), authorization = null), 401)
        assertEquals("SESSION", response(JsonObject(body + ("session" to JsonPrimitive("old-session"))))["error"]!!.jsonObject.text("code"))

        // 严格 JSON：缺项／错误类型／多余字段一律 INVALID_ARGUMENT；票据错配 RELIGION_DECISION；未接入动作 UNKNOWN_COMMAND。
        assertEquals("INVALID_ARGUMENT", response(JsonObject(body - "religionId"))["error"]!!.jsonObject.text("code"))
        assertEquals("INVALID_ARGUMENT", response(JsonObject(body + ("beliefs" to JsonPrimitive("x"))))["error"]!!.jsonObject.text("code"))
        assertEquals("INVALID_ARGUMENT", response(JsonObject(body + ("beliefs" to JsonArray(listOf(JsonPrimitive(1))))))["error"]!!.jsonObject.text("code"))
        assertEquals("INVALID_ARGUMENT", response(JsonObject(body + ("decisionToken" to JsonPrimitive(5))))["error"]!!.jsonObject.text("code"))
        assertEquals("INVALID_ARGUMENT", response(JsonObject(body + ("extra" to JsonPrimitive(1))))["error"]!!.jsonObject.text("code"))
        assertEquals("RELIGION_DECISION", response(JsonObject(body + ("decisionToken" to JsonPrimitive("wrong"))))["error"]!!.jsonObject.text("code"))
        assertEquals("UNKNOWN_COMMAND", call("religionSpread")["error"]!!.jsonObject.text("code"))
        assertSame(current, session.game)
        assertEquals(revision, session.revision)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))

        // 幂等：真 HTTP 提交后重放返回缓存、异参 REQUEST_REUSED、旧 revision STALE_STATE。
        assertIdempotentOverHttp(body.toString())
        assertEquals(revision + 1, session.revision)
        assertSame(current, session.game)
        assertEquals("HTTP 信仰", ReligionFixtures.religion(session.game!!).religion!!.getReligionDisplayName())
    }

    @Test fun religionFoundHttpContract() = religionHttp()

    @Test fun pendingEventBlocksDevelopmentWritesButQueriesAndSaveStillWork() {
        val (_, cityId) = loadDevelopment("http-pending", withWorker = false)
        // 注入一个尚未接入的待决事件（非白名单）：写操作应被阻止，只读查询与保存仍可用。
        session.game!!.currentPlayerCiv.popupAlerts.add(
            com.unciv.logic.civilization.PopupAlert(com.unciv.logic.civilization.AlertType.RecapturedCivilian, "worker"))
        val before = UncivFiles.gameInfoToString(session.game!!, false)
        val revision = session.revision

        val write = send(body = BattleFixtures.request(session, "cityQueue",
            "cityId" to cityId, "type" to "add", "name" to "Warrior").toString())
        assertEquals("PENDING_DECISION", Json.parseToJsonElement(write.body()).jsonObject["error"]!!.jsonObject.text("code"))
        assertEquals(revision, session.revision)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))

        val query = send(body = BattleFixtures.request(session, "cityOptions", "cityId" to cityId).toString())
        assertTrue(query.body(), Json.parseToJsonElement(query.body()).jsonObject["ok"]!!.jsonPrimitive.boolean)
        assertEquals(revision, session.revision)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))

        val save = send(body = BattleFixtures.request(session, "save", "name" to "http-pending-dev").toString())
        val saveJson = Json.parseToJsonElement(save.body()).jsonObject
        assertTrue(save.body(), saveJson["ok"]!!.jsonPrimitive.boolean)
        assertTrue(File(saveJson.text("savedPath")).isFile)

        session.game!!.currentPlayerCiv.popupAlerts.clear()
    }
}

