package io.github.xsun71136.plugins.mcp.mcp

import io.github.xsun71136.plugins.mcp.BuildInfo
import io.github.xsun71136.plugins.mcp.McpLog
import io.github.xsun71136.plugins.mcp.McpSettingsStore
import io.github.xsun71136.plugins.mcp.json.Json
import io.github.xsun71136.plugins.mcp.tools.ToolRegistry
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One MCP client connection/session. */
class McpSession(val id: String, val remote: String, createdAt: Long) {
    @Volatile var protocolVersion: String = ""
    @Volatile var initialized: Boolean = false
    @Volatile var lastSeen: Long = createdAt
    @Volatile var sseActive: Boolean = false
    @Volatile var clientInfo: Map<String, Any?> = emptyMap()
    val createdAt: Long = createdAt
    val requests = AtomicLong(0)
    val toolCalls = AtomicLong(0)

    /** Pending server->client events for a long-lived SSE stream. */
    val events: LinkedBlockingQueue<String> = LinkedBlockingQueue()

    fun shortId(): String = if (id.length > 8) id.substring(0, 8) else id

    fun touch() {
        lastSeen = System.currentTimeMillis()
    }

    fun toMap(): Map<String, Any?> = Json.obj(
        "id" to id,
        "shortId" to shortId(),
        "remote" to remote,
        "protocolVersion" to protocolVersion,
        "initialized" to initialized,
        "sseActive" to sseActive,
        "client" to clientInfo,
        "createdAt" to createdAt,
        "lastSeen" to lastSeen,
        "requests" to requests.get(),
        "toolCalls" to toolCalls.get(),
    )
}

/**
 * The MCP server itself: a dependency-free HTTP/1.1 listener that speaks
 *
 *  - Streamable HTTP  : POST/GET/DELETE /mcp
 *  - legacy HTTP+SSE  : GET /sse + POST /messages
 *  - plain JSON-RPC   : POST /mcp with Accept: application/json
 *
 * It deliberately uses only java.net/java.io so nothing has to be bundled into
 * plugin.apk and nothing can clash with the IDE's own libraries.
 */
object McpServer {

    private const val TAG = "server"
    private const val BACKLOG = 32
    private const val IDLE_TIMEOUT_MS = 30 * 60 * 1000

    @Volatile private var running = false
    @Volatile private var listeningPort = -1
    @Volatile private var bindAddress = "127.0.0.1"
    @Volatile private var startedAt = 0L
    @Volatile private var lastError: String? = null

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    private val sessions = ConcurrentHashMap<String, McpSession>()
    private val totalRequests = AtomicLong(0)
    private val totalToolCalls = AtomicLong(0)
    private val totalBytesIn = AtomicLong(0)
    private val totalBytesOut = AtomicLong(0)

    // ------------------------------------------------------------ lifecycle

    val isRunning: Boolean get() = running

    @Synchronized
    fun start(reason: String = "manual"): Map<String, Any?> {
        val s = McpSettingsStore.current
        if (!s.enabled) {
            lastError = "server disabled in MCP settings"
            McpLog.warn(TAG, "start refused: $lastError")
            return Json.obj("ok" to false, "error" to lastError)
        }
        if (running && listeningPort == s.port) {
            McpLog.info(TAG, "already running on port $listeningPort (start requested by $reason)")
            return Json.obj("ok" to true, "alreadyRunning" to true, "port" to listeningPort, "endpoints" to endpoints())
        }
        stopInternal("restart")
        val address = if (s.bindAll) null else InetAddress.getByName("127.0.0.1")
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(address, s.port), BACKLOG)
            serverSocket = socket
            listeningPort = socket.localPort
            bindAddress = if (s.bindAll) "0.0.0.0" else "127.0.0.1"
            running = true
            startedAt = System.currentTimeMillis()
            lastError = null
            val t = Thread({ acceptLoop(socket) }, "acs-mcp-accept")
            t.isDaemon = true
            acceptThread = t
            t.start()
            McpLog.info(
                TAG,
                "MCP server listening on $bindAddress:$listeningPort (started by $reason) - endpoints: ${endpoints().joinToString()}",
            )
            Json.obj("ok" to true, "port" to listeningPort, "bind" to bindAddress, "endpoints" to endpoints())
        } catch (t: Throwable) {
            running = false
            listeningPort = -1
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            McpLog.error(TAG, "cannot bind ${if (s.bindAll) "0.0.0.0" else "127.0.0.1"}:${s.port}", t)
            Json.obj(
                "ok" to false,
                "error" to lastError,
                "port" to s.port,
                "hint" to "端口被占用时请在 MCP 设置里换一个端口；bindAll 需要设备允许应用监听网络端口",
            )
        }
    }

    @Synchronized
    fun stop(reason: String = "manual"): Map<String, Any?> {
        val wasRunning = running
        val port = listeningPort
        stopInternal(reason)
        McpLog.info(TAG, "MCP server stopped (wasRunning=$wasRunning port=$port reason=$reason)")
        return Json.obj("ok" to true, "wasRunning" to wasRunning, "port" to port, "reason" to reason)
    }

    private fun stopInternal(reason: String) {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        val t = acceptThread
        acceptThread = null
        runCatching { t?.interrupt() }
        for (session in sessions.values) {
            session.events.add("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/server/shutting_down\"}")
            session.sseActive = false
        }
        listeningPort = -1
    }

    @Synchronized
    fun restart(reason: String = "manual"): Map<String, Any?> {
        stopInternal(reason)
        Thread.sleep(150)
        return start(reason)
    }

    /** Called by the settings page whenever visibility of tools changed. */
    fun notifyToolsChanged() {
        if (!running) return
        val payload = Json.stringify(Json.obj("jsonrpc" to "2.0", "method" to "notifications/tools/list_changed"))
        var sent = 0
        for (session in sessions.values) {
            if (session.sseActive) {
                session.events.add(payload)
                sent++
            }
        }
        McpLog.debug(TAG, "tools/list_changed broadcast to $sent session(s)")
    }

    fun sessions(): List<McpSession> = sessions.values.toList()

    fun endpoints(): List<String> {
        val port = if (listeningPort > 0) listeningPort else McpSettingsStore.current.port
        val out = ArrayList<String>()
        out.add("http://127.0.0.1:$port/mcp")
        if (McpSettingsStore.current.bindAll) {
            for (ip in localAddresses()) out.add("http://$ip:$port/mcp")
        }
        return out
    }

    fun localAddresses(): List<String> {
        val out = ArrayList<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val nif = ifaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is InetAddress && !a.isLoopbackAddress && a.hostAddress?.indexOf(':') == -1) {
                        out.add(a.hostAddress ?: continue)
                    }
                }
            }
        } catch (t: Throwable) {
            McpLog.warn(TAG, "cannot enumerate network interfaces: ${t.message}")
        }
        return out.distinct()
    }

    fun status(): Map<String, Any?> {
        val s = McpSettingsStore.current
        val m = LinkedHashMap<String, Any?>()
        m["running"] = running
        m["enabled"] = s.enabled
        m["autoStart"] = s.autoStart
        m["port"] = if (listeningPort > 0) listeningPort else s.port
        m["bindAddress"] = bindAddress
        m["bindAll"] = s.bindAll
        m["startedAt"] = (if (startedAt == 0L) null else startedAt)
        m["uptimeMs"] = (if (running && startedAt > 0) System.currentTimeMillis() - startedAt else 0L)
        m["endpoints"] = endpoints()
        m["legacySseEndpoint"] = (if (listeningPort > 0) "http://${if (s.bindAll) localAddresses().firstOrNull() ?: "127.0.0.1" else "127.0.0.1"}:${listeningPort}/sse" else null)
        m["requireAuth"] = s.requireAuth
        m["authTokenConfigured"] = s.authToken.isNotEmpty()
        m["readOnly"] = s.readOnly
        m["protocolVersions"] = BuildInfo.PROTOCOL_VERSIONS
        m["serverName"] = s.serverName
        m["serverVersion"] = s.serverVersion
        m["registeredTools"] = ToolRegistry.size()
        m["enabledTools"] = ToolRegistry.enabledSpecs().size
        m["sessions"] = sessions.values.map { it.toMap() }
        m["sessionCount"] = sessions.size
        m["totalRequests"] = totalRequests.get()
        m["totalToolCalls"] = totalToolCalls.get()
        m["bytesIn"] = totalBytesIn.get()
        m["bytesOut"] = totalBytesOut.get()
        m["lastError"] = lastError
        m["settingsFile"] = McpSettingsStore.file()?.absolutePath
        m["localAddresses"] = localAddresses()
        return m
    }

    // ----------------------------------------------------------- accept loop

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                if (running) McpLog.debug(TAG, "accept interrupted: ${t.message}")
                break
            }
            val t = Thread({
                try {
                    handleConnection(client)
                } catch (e: Throwable) {
                    McpLog.error(TAG, "connection handler failed", e)
                } finally {
                    runCatching { client.close() }
                }
            }, "acs-mcp-conn")
            t.isDaemon = true
            t.start()
        }
        McpLog.info(TAG, "accept loop exited")
    }

    // ------------------------------------------------------------ http layer

    private fun handleConnection(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = IDLE_TIMEOUT_MS
        val remote = "${socket.inetAddress?.hostAddress ?: "?"}:${socket.port}"
        val input = socket.getInputStream()
        val output = BufferedOutputStream(socket.getOutputStream())

        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2) {
            writeSimple(output, 400, "Bad Request", "text/plain; charset=utf-8", "malformed request line".toByteArray(), null)
            return
        }
        val method = parts[0].uppercase()
        val rawTarget = parts[1]
        val headers = readHeaders(input)
        val path = rawTarget.substringBefore('?')
        val query = parseQuery(rawTarget)
        val accept = headers["accept"] ?: "*/*"
        McpLog.trace(TAG, "$method $rawTarget from $remote accept=$accept")

        if (method == "OPTIONS") {
            writeSimple(output, 204, "No Content", "text/plain", ByteArray(0), headers["origin"])
            return
        }

        if (path == "/health") {
            val body = Json.stringify(Json.obj("ok" to running, "port" to listeningPort, "server" to McpSettingsStore.current.serverName, "version" to McpSettingsStore.current.serverVersion))
            writeSimple(output, 200, "OK", "application/json", body.toByteArray(), headers["origin"])
            return
        }

        if (!authorized(headers, query)) {
            val extra = LinkedHashMap<String, String>()
            extra["WWW-Authenticate"] = "Bearer realm=\"acs-mcp\""
            val body = Json.stringify(Json.obj("error" to "unauthorized", "hint" to "send 'Authorization: Bearer <token>' (token is in the MCP settings page / acs-mcp/settings.json)"))
            writeSimple(output, 401, "Unauthorized", "application/json", body.toByteArray(), headers["origin"], extra)
            return
        }

        when {
            method == "GET" && path == "/" -> {
                writeSimple(output, 200, "OK", "text/html; charset=utf-8", statusHtml().toByteArray(), headers["origin"])
            }
            method == "GET" && (path == "/mcp" || path == "/sse") -> {
                handleSse(output, headers, path, remote)
            }
            method == "POST" && (path == "/mcp" || path == "/messages") -> {
                handlePost(input, output, headers, accept, path, remote)
            }
            method == "DELETE" && path == "/mcp" -> {
                val sid = headers["mcp-session-id"]
                val removed = if (sid != null) sessions.remove(sid) != null else false
                writeSimple(output, if (removed) 200 else 404, if (removed) "OK" else "Not Found", "application/json", Json.stringify(Json.obj("ok" to removed, "sessionId" to sid)).toByteArray(), headers["origin"])
            }
            else -> {
                val body = Json.stringify(
                    Json.obj(
                        "error" to "not found",
                        "path" to path,
                        "hint" to "MCP endpoints: POST/GET/DELETE /mcp (streamable http), GET /sse + POST /messages (legacy), GET /health, GET /",
                    ),
                )
                writeSimple(output, 404, "Not Found", "application/json", body.toByteArray(), headers["origin"])
            }
        }
        output.flush()
    }

    private fun authorized(headers: Map<String, String>, query: Map<String, String>): Boolean {
        val s = McpSettingsStore.current
        if (!s.requireAuth) return true
        val expected = s.authToken
        if (expected.isEmpty()) return true
        val header = headers["authorization"]
        if (header != null) {
            val v = header.trim()
            if (v.startsWith("Bearer ")) return constantTimeEquals(v.substring(7).trim(), expected)
            if (v == expected) return constantTimeEquals(v, expected)
        }
        headers["x-mcp-token"]?.let { if (constantTimeEquals(it.trim(), expected)) return true }
        query["token"]?.let { if (constantTimeEquals(it, expected)) return true }
        return false
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun handlePost(input: InputStream, output: BufferedOutputStream, headers: Map<String, String>, accept: String, path: String, remote: String) {
        val body = readBody(input, headers)
        if (body == null) {
            writeSimple(output, 413, "Payload Too Large", "application/json", Json.stringify(Json.obj("error" to "body exceeds maxBodyBytes")).toByteArray(), headers["origin"])
            return
        }
        totalBytesIn.addAndGet(body.size.toLong())
        val text = String(body, Charsets.UTF_8)
        val session = resolveSession(headers, remote)
        val parsed = Json.parseOrNull(text)
        if (parsed == null) {
            val err = errorResponse(null, RpcError.PARSE_ERROR, "parse error: body is not valid JSON")
            writeSimple(output, 400, "Bad Request", "application/json", Json.stringify(err).toByteArray(), headers["origin"], sessionHeader(session))
            return
        }

        val isBatch = parsed is List<*>
        val requests: List<Any?> = if (isBatch) Json.asList(parsed) else listOf(parsed)
        val responses = ArrayList<Map<String, Any?>>()
        for (req in requests) {
            val map = Json.asMap(req)
            totalRequests.incrementAndGet()
            session.requests.incrementAndGet()
            session.touch()
            val id = map["id"]
            try {
                if (map["jsonrpc"] != null && Json.str(map["jsonrpc"]) != "2.0") {
                    throw RpcError(RpcError.INVALID_REQUEST, "invalid request: jsonrpc must be \"2.0\"")
                }
                val result = McpDispatch.handle(map, session)
                if (result != null && id != null) {
                    responses.add(Json.obj("jsonrpc" to "2.0", "id" to id, "result" to result))
                    if (Json.strOf(map, "method") == "tools/call") totalToolCalls.incrementAndGet()
                } else if (result == null && id != null && Json.strOf(map, "method")?.startsWith("notifications/") == true) {
                    // notification with an id: acknowledge with an empty result
                    responses.add(Json.obj("jsonrpc" to "2.0", "id" to id, "result" to Json.obj()))
                }
            } catch (e: RpcError) {
                if (id != null) responses.add(Json.obj("jsonrpc" to "2.0", "id" to id, "error" to e.toMap()))
                McpLog.warn(TAG, "rpc error for '${Json.strOf(map, "method")}': ${e.message}")
            } catch (e: Throwable) {
                if (id != null) {
                    responses.add(
                        Json.obj(
                            "jsonrpc" to "2.0",
                            "id" to id,
                            "error" to Json.obj("code" to RpcError.INTERNAL_ERROR, "message" to "${e.javaClass.simpleName}: ${e.message}"),
                        ),
                    )
                }
                McpLog.error(TAG, "dispatch failed", e)
            }
        }

        val sessionHeader = sessionHeader(session)
        val protocolHeader = if (session.protocolVersion.isNotEmpty()) mapOf("Mcp-Protocol-Version" to session.protocolVersion) else emptyMap()

        if (path == "/messages") {
            // legacy HTTP+SSE transport: answers go to the GET /sse stream
            for (r in responses) session.events.add(Json.stringify(r))
            writeSimple(output, 202, "Accepted", "application/json", Json.stringify(Json.obj("accepted" to responses.size)).toByteArray(), headers["origin"], sessionHeader + protocolHeader)
            return
        }

        if (responses.isEmpty()) {
            writeSimple(output, 202, "Accepted", "text/plain", ByteArray(0), headers["origin"], sessionHeader + protocolHeader)
            return
        }

        val payload: Any = if (isBatch) responses else responses.first()
        val json = Json.stringify(payload)
        if (accept.contains("text/event-stream")) {
            val sse = StringBuilder()
            sse.append("event: message\n")
            sse.append("data: ").append(json.replace("\n", "\ndata: ")).append("\n\n")
            writeSimple(output, 200, "OK", "text/event-stream", sse.toString().toByteArray(), headers["origin"], sessionHeader + protocolHeader)
        } else {
            writeSimple(output, 200, "OK", "application/json", json.toByteArray(), headers["origin"], sessionHeader + protocolHeader)
        }
    }

    private fun handleSse(output: BufferedOutputStream, headers: Map<String, String>, path: String, remote: String) {
        val session = resolveSession(headers, remote)
        session.sseActive = true
        val head = StringBuilder()
        head.append("HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: text/event-stream; charset=utf-8\r\n")
        head.append("Cache-Control: no-cache, no-store\r\n")
        head.append("Connection: keep-alive\r\n")
        head.append("Access-Control-Allow-Origin: ").append(headers["origin"] ?: "*").append("\r\n")
        head.append("Access-Control-Allow-Headers: *\r\n")
        head.append("Access-Control-Expose-Headers: Mcp-Session-Id\r\n")
        head.append("Mcp-Session-Id: ").append(session.id).append("\r\n")
        head.append("\r\n")
        output.write(head.toString().toByteArray())
        if (path == "/sse") {
            val endpoint = "/messages?sessionId=${session.id}"
            output.write("event: endpoint\ndata: $endpoint\n\n".toByteArray())
        }
        output.write(": connected\n\n".toByteArray())
        output.flush()
        McpLog.info(TAG, "SSE stream opened for session ${session.shortId()} ($remote) on $path")
        try {
            while (running) {
                val event = session.events.poll(15, TimeUnit.SECONDS)
                if (event != null) {
                    output.write("event: message\ndata: ${event.replace("\n", "\ndata: ")}\n\n".toByteArray())
                } else {
                    output.write(": ping\n\n".toByteArray())
                }
                output.flush()
            }
        } catch (t: Throwable) {
            McpLog.debug(TAG, "SSE stream closed for ${session.shortId()}: ${t.message}")
        } finally {
            session.sseActive = false
        }
    }

    private fun resolveSession(headers: Map<String, String>, remote: String): McpSession {
        val id = headers["mcp-session-id"]
        if (id != null) {
            sessions[id]?.let { return it }
        }
        val fresh = McpSession(UUID.randomUUID().toString(), remote, System.currentTimeMillis())
        sessions[fresh.id] = fresh
        pruneSessions()
        return fresh
    }

    private fun pruneSessions() {
        val now = System.currentTimeMillis()
        val stale = sessions.values.filter { !it.sseActive && now - it.lastSeen > IDLE_TIMEOUT_MS }
        for (s in stale) sessions.remove(s.id)
        if (sessions.size > 128) {
            val oldest = sessions.values.sortedBy { it.lastSeen }.take(sessions.size - 128)
            for (s in oldest) sessions.remove(s.id)
        }
    }

    private fun sessionHeader(session: McpSession): Map<String, String> = mapOf("Mcp-Session-Id" to session.id)

    private fun errorResponse(id: Any?, code: Int, message: String): Map<String, Any?> =
        Json.obj("jsonrpc" to "2.0", "id" to id, "error" to Json.obj("code" to code, "message" to message))

    // --------------------------------------------------------- http plumbing

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var b = input.read()
        if (b == -1) return null
        while (b != -1 && b != '\n'.code) {
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 16384) throw IllegalStateException("header line too long")
            b = input.read()
        }
        return sb.toString()
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val k = line.substring(0, idx).trim().lowercase()
            val v = line.substring(idx + 1).trim()
            if (map.containsKey(k)) map[k] = map[k] + ", " + v else map[k] = v
        }
        return map
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray? {
        val max = McpSettingsStore.current.maxBodyBytes
        val transferEncoding = headers["transfer-encoding"]?.lowercase()
        if (transferEncoding != null && transferEncoding.contains("chunked")) {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val sizeLine = readLine(input) ?: break
                val size = try {
                    sizeLine.trim().substringBefore(';').toInt(16)
                } catch (e: Throwable) {
                    break
                }
                if (size == 0) {
                    readLine(input)
                    break
                }
                if (out.size() + size > max) return null
                val buf = ByteArray(size)
                var read = 0
                while (read < size) {
                    val n = input.read(buf, read, size - read)
                    if (n <= 0) break
                    read += n
                }
                out.write(buf, 0, read)
                readLine(input)
            }
            return out.toByteArray()
        }
        val length = headers["content-length"]?.trim()?.toIntOrNull() ?: 0
        if (length <= 0) return ByteArray(0)
        if (length > max) return null
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n <= 0) break
            read += n
        }
        return if (read == length) buf else buf.copyOf(read)
    }

    private fun writeSimple(
        output: BufferedOutputStream,
        status: Int,
        statusText: String,
        contentType: String,
        body: ByteArray,
        origin: String?,
        extraHeaders: Map<String, String>? = null,
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(statusText).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Access-Control-Allow-Origin: ").append(origin ?: "*").append("\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n")
        sb.append("Access-Control-Expose-Headers: Mcp-Session-Id, Mcp-Protocol-Version\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Connection: close\r\n")
        if (extraHeaders != null) {
            for ((k, v) in extraHeaders) sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")
        val head = sb.toString().toByteArray()
        output.write(head)
        if (body.isNotEmpty()) output.write(body)
        output.flush()
        totalBytesOut.addAndGet((head.size + body.size).toLong())
    }

    private fun parseQuery(target: String): Map<String, String> {
        val q = target.substringAfter('?', "")
        if (q.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val k = pair.substringBefore('=')
            val v = if (pair.contains('=')) pair.substringAfter('=') else ""
            map[urlDecode(k)] = urlDecode(v)
        }
        return map
    }

    private fun urlDecode(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (t: Throwable) {
        s
    }

    private fun statusHtml(): String {
        val s = McpSettingsStore.current
        val tools = ToolRegistry.enabledSpecs()
        val sb = StringBuilder()
        sb.append("<!doctype html><meta charset=\"utf-8\"><title>ACS MCP</title>")
        sb.append("<style>body{font-family:system-ui,sans-serif;background:#1e1e1e;color:#e6e6e6;margin:24px}")
        sb.append("h1{font-size:20px}code{background:#2d2d2d;padding:2px 6px;border-radius:4px}")
        sb.append("li{margin:2px 0}.ok{color:#4ec9b0}.bad{color:#f48771}</style>")
        sb.append("<h1>Android Code Studio &mdash; MCP Server</h1>")
        sb.append("<p>state: <span class=\"").append(if (running) "ok" else "bad").append("\">")
            .append(if (running) "RUNNING" else "STOPPED").append("</span>")
        sb.append(" &middot; port ").append(listeningPort).append(" &middot; bind ").append(bindAddress)
        sb.append(" &middot; readOnly=").append(s.readOnly).append(" &middot; auth=").append(s.requireAuth).append("</p>")
        sb.append("<p>endpoints:</p><ul>")
        for (e in endpoints()) sb.append("<li><code>").append(e).append("</code></li>")
        sb.append("</ul><p>enabled tools (").append(tools.size).append('/').append(ToolRegistry.size()).append("):</p><ul>")
        for (t in tools) sb.append("<li><code>").append(t.name).append("</code> &mdash; ").append(escapeHtml(t.description.take(120))).append("</li>")
        sb.append("</ul>")
        return sb.toString()
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
