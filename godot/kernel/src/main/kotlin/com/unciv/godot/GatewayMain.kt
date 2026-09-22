package com.unciv.godot

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 仅用于本机离线前端，不提供公网、多会话或远程文件服务。 */
fun main(args: Array<String>) {
    fun argument(name: String, fallback: String): String = args.indexOf(name).let {
        if (it >= 0) args.getOrNull(it + 1) ?: error("缺少参数 $name") else fallback
    }
    val root = File(argument("--root", "../..")).canonicalFile
    val port = argument("--port", "17321").toInt()
    val token = System.getenv("UNCIV_GATEWAY_TOKEN") ?: error("请通过 godot/run.ps1 启动：缺少本机会话令牌")
    require(token.length >= 32) { "本机会话令牌长度不足" }
    KernelRuntime.initialize(root)
    val server = startGateway(port, token, GameSession(root))
    Runtime.getRuntime().addShutdownHook(Thread { stopGateway(server) })
    println("Godot 内核已就绪：127.0.0.1:${server.address.port}（单会话、无图形上下文）")
}

/** 在 127.0.0.1 上启动网关并返回已运行的服务；port 为 0 时由系统分配，供测试使用。 */
internal fun startGateway(port: Int, token: String, session: GameSession): HttpServer {
    val executor = Executors.newSingleThreadExecutor()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 8)
    server.executor = executor
    server.createContext("/api") { exchange ->
        exchange.use {
            var status = 200
            val response = try {
                require(exchange.requestURI.path == "/api" && exchange.requestMethod == "POST") { "仅支持 POST /api" }
                require(exchange.requestHeaders.getFirst("Origin") == null) { "不接受浏览器跨站请求" }
                require(exchange.requestHeaders.getFirst("Content-Type")?.startsWith("application/json") == true) { "需要 application/json" }
                val receivedToken = exchange.requestHeaders.getFirst("Authorization") ?: ""
                if (!MessageDigest.isEqual(receivedToken.toByteArray(), "Bearer $token".toByteArray())) {
                    status = 401
                    throw IllegalArgumentException("本机会话认证失败")
                }
                val bytes = exchange.requestBody.readNBytes(65537)
                require(bytes.size <= 65536) { "请求体过大" }
                session.handle(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject)
            } catch (error: Exception) {
                if (status == 200) status = 400
                dto("ok" to false, "error" to dto("code" to "HTTP_ERROR", "message" to (error.message ?: "请求格式错误")))
            }
            val bytes = response.toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }
    }
    server.start()
    return server
}

/** HttpServer.stop 不会关闭调用方提供的 executor；生产入口与测试使用相同的释放逻辑。 */
internal fun stopGateway(server: HttpServer) {
    try {
        server.stop(0)
    } finally {
        (server.executor as ExecutorService).shutdownNow()
    }
}
