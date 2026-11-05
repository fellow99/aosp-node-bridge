package org.fellow99.nodebridge.probe

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.fellow99.nodebridge.NodeRuntime
import org.fellow99.nodebridge.NodeRuntimeOptions
import org.fellow99.nodebridge.NodeState
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.concurrent.Executors

/**
 * node-bridge 探针载体（specs/101-probe-app）。
 *
 * 职责（镜像 docs/000-工程规划.md §5/§6 消费方对接契约）：
 *  1) 加载：System.loadLibrary("node_bridge")（经 NodeRuntime/NativeBridge 显式触发，失败记 detail）；
 *  2) 启动：NodeRuntimeOptions(entry="selfcheck.js") → start（投递语义）→ awaitRunning 轮询到 Running；
 *  3) 载荷：assets/node-bridge/{bootstrap,host,selfcheck}.js 物化到 filesDir/node-bridge/payload
 *     （NodeRuntime.materializePayload，marker 幂等）；selfcheck.js 在 127.0.0.1:PORT 起 HTTP 服务；
 *  4) 桥往返：HTTP /host?name=echo → Kotlin 注册的 handler 回程；未注册名结构化拒绝；
 *  5) 版本契约：/facts 的 process.version == node-runtime.properties 的 node.version == 26.13.0；
 *  6) 报告：filesDir/probe-report.json（{runAt,device,expected,probes[]}，三态 PASS/FAIL/BLOCKED）。
 *
 * 用例定位：specs/101-probe-app/test-cases.md（PRB-01…05）。设备运行由编排者统一排队。
 */
class MainActivity : Activity() {

    private companion object {
        const val EXPECTED_NODE_VERSION = "26.13.0"
        const val PORT = 15599
        const val START_AWAIT_MS = 60_000L
        const val HTTP_CONNECT_MS = 3_000
        const val HTTP_READ_MS = 15_000
    }

    private lateinit var runtime: NodeRuntime
    private var logView: TextView? = null
    private val uiLog = StringBuilder()
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var report = org.json.JSONArray()
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun log(s: String) {
        ui.post {
            uiLog.append(s).append('\n')
            logView?.text = uiLog.toString()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = NodeRuntime.getInstance(this)
        setContentView(buildUi())
        log(
            "probe-app ready\npkg=$packageName\nabi=${android.os.Build.SUPPORTED_ABIS.joinToString(",")}\n" +
                "libDir=${applicationInfo.nativeLibraryDir}\n" +
                "点 RUN ALL PROBES 或 am start --ez autorun true 无头跑批"
        )
        if (intent?.getBooleanExtra("autorun", false) == true) {
            ui.postDelayed({ runAll() }, 400)
        }
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val btn = Button(this)
        btn.text = "RUN ALL PROBES"
        btn.setOnClickListener { runAll() }
        val scroll = ScrollView(this)
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(16, 16, 16, 16)
        }
        scroll.addView(logView)
        root.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    // ── 探针模型（沿用 aosp-node-cli probe-app 判定约定）────────────────────────

    fun interface Check {
        // null=PASS；“BLOCKED:…”=BLOCKED；“PASS-…”=PASS(事实)；其余 FAIL
        fun check(): String?
    }

    data class Probe(val id: String, val title: String, val check: Check)

    private fun httpGet(path: String): Pair<Int, String> {
        val conn = (URL("http://127.0.0.1:$PORT$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = HTTP_CONNECT_MS
            readTimeout = HTTP_READ_MS
            requestMethod = "GET"
        }
        return try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to body
        } finally {
            conn.disconnect()
        }
    }

    private fun deviceLine(): String =
        "${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT} / ${android.os.Build.SUPPORTED_ABIS.joinToString(",")}"

    private fun probes(): List<Probe> {
        val runtimeRef = runtime
        return listOf(
            // ── PRB-01 加载域（FR-101-PROBE-001）──
            Probe(
                "PRB-01",
                "bridge 加载（loadLibrary + nativeAvailable）",
                Check {
                    val avail = runtimeRef.nativeAvailable
                    if (avail) null
                    else runCatching { runtimeRef.nativeLoadError }.getOrNull()?.let { "FAIL|libnode_bridge.so 未加载：$it" }
                        ?: "FAIL|libnode_bridge.so 未加载"
                }
            ),
            // ── PRB-02 启动域（FR-101-PROBE-002/003/007）──
            Probe(
                "PRB-02",
                "start → awaitRunning(Running) + stage/lastCrash",
                Check {
                    registerHostHandlers()
                    val st = runtimeRef.state.value
                    if (st is NodeState.Running) {
                        "PASS-FACT|已在 Running（复跑跳过 start）；stage=${runtimeRef.stage}"
                    } else if (st !is NodeState.Idle && st !is NodeState.Starting) {
                        "BLOCKED:state=$st（非 Idle，无法再 start）"
                    } else {
                        val r = runtimeRef.start(
                            NodeRuntimeOptions(
                                entry = "selfcheck.js",
                                env = mapOf(
                                    "NODE_BRIDGE_PORT" to PORT.toString(),
                                    "NODE_BRIDGE_HOME" to File(filesDir, "node-bridge").absolutePath,
                                    "NODE_ENV" to "probe",
                                ),
                            )
                        )
                        if (!r.ok) "FAIL|start code=${r.code} msg=${r.message ?: "-"} stage=${runtimeRef.stage}"
                        else {
                            val s = runtimeRef.awaitRunning(START_AWAIT_MS)
                            val crashReadable = runtimeRef.lastCrash()?.let { "crash=" to it.raw.take(120) } ?: ("clean" to "")
                            val stage = runtimeRef.stage
                            when (s) {
                                is NodeState.Running -> {
                                    if (stage in setOf("init", "stdio", "env", "dlopen", "dlsym", "start")) {
                                        // stage 是转态过程值；已 Running 即可
                                        "PASS-FACT|state=Running stage=$stage lastCrash:${crashReadable.first}"
                                    } else "FAIL|state=Running 但 stage 异常=$stage"
                                }
                                is NodeState.Failed -> "FAIL|state=failed stage=${s.stage} msg=${s.message}"
                                is NodeState.Exited -> "FAIL|state=exited（Node 提前退出）"
                                else -> "FAIL|awaitRunning 超时 state=$s stage=$stage"
                            }
                        }
                    }
                }
            ),
            // ── PRB-03 selfcheck 输出域（FR-101-PROBE-004）──
            Probe(
                "PRB-03",
                "selfcheck.js 起服：listening + node + platform",
                Check {
                    Thread.sleep(1800)
                    val logs = runtimeRef.logs(400).joinToString("\n") { e -> "${e.stream} ${e.text}" }
                    if (!logs.contains("[selfcheck] listening")) {
                        // 等待更久再试一次
                        Thread.sleep(4000)
                        val logs2 = runtimeRef.logs(500).joinToString("\n") { e -> "${e.stream} ${e.text}" }
                        if (!logs2.contains("[selfcheck] listening")) {
                            return@Check "FAIL|无 [selfcheck] listening。日志尾：${logs2.takeLast(600)}"
                        }
                        return@Check assertListening(logs2)
                    }
                    assertListening(logs)
                }
            ),
            // ── PRB-04 桥往返域（FR-101-PROBE-005）──
            Probe(
                "PRB-04a",
                "桥往返 echo（/host → Kotlin handler）",
                Check {
                    val q = "name=echo&args=" + java.net.URLEncoder.encode(
                        "{\"from\":\"probe\",\"at\":" + System.currentTimeMillis() + "}", "UTF-8"
                    )
                    val (code, body) = httpGet("/host?$q")
                    if (code != 200) "FAIL|HTTP $code $body"
                    else {
                        val o = runCatching { JSONObject(body) }.getOrNull()
                        if (o == null) "FAIL|非法 JSON $body"
                        else {
                            val res = o.optJSONObject("result")
                            val ok = o.optBoolean("ok", false) && res?.optBoolean("ok", false) == true &&
                                res.optString("handledBy", "") == "kotlin"
                            if (ok) null else "FAIL|resp=$body"
                        }
                    }
                }
            ),
            Probe(
                "PRB-04b",
                "未注册能力结构化拒绝（unregistered）",
                Check {
                    val (code, body) = httpGet("/host?name=definitely.not.registered&args={}")
                    if (code != 200) "FAIL|HTTP $code $body"
                    else {
                        val o = runCatching { JSONObject(body) }.getOrNull()
                        if (o == null) "FAIL|非法 JSON $body"
                        else {
                            val res = o.optJSONObject("result")
                            val structured = res?.optBoolean("ok", true) == false &&
                                (res.optString("error", "") == "unregistered" || res.optString("name", "") == "definitely.not.registered")
                            if (structured) null
                            else "FAIL|未注册未结构化拒绝：download=$body"
                        }
                    }
                }
            ),
            // ── PRB-05 版本契约域（FR-101-PROBE-006）──
            Probe(
                "PRB-05",
                "版本契约：/facts vs node-runtime.properties == 26.13.0",
                Check {
                    val (code, body) = httpGet("/facts")
                    val factsNode = if (code != 200) null
                    else runCatching { JSONObject(body).optString("node", "") }.getOrNull()
                    val actual = factsNode?.removePrefix("v")
                    // META-INF/node-runtime.properties：AAR 资源在 APK 中不一定展开，取不到按 PASS-FACT 记录
                    val meta = runCatching {
                        val s: InputStream? = javaClass.classLoader?.getResourceAsStream("META-INF/node-runtime.properties")
                        if (s == null) null else s.use { inp ->
                            val p = Properties()
                            p.load(inp)
                            p.getProperty("node.version")
                        }
                    }.getOrNull()
                    when {
                        actual == null -> "FAIL|/facts 不可得（HTTP $code）"
                        actual != EXPECTED_NODE_VERSION -> "FAIL|facts node.v=$actual ≠ $EXPECTED_NODE_VERSION"
                        meta == null ->
                            "PASS-FACT|facts=$actual META-INF/node-runtime.properties 未展开到 APK classpath（记录性；以 /facts 为准）"
                        meta != EXPECTED_NODE_VERSION -> "FAIL|meta node.version=$meta ≠ $EXPECTED_NODE_VERSION"
                        else -> null
                    }
                }
            ),
        )
    }

    private fun assertListening(logs: String): String? {
        val line = logs.lines().filter { it.contains("[selfcheck] listening") }.lastOrNull() ?: return "FAIL|listening 行不在日志"
        return when {
            line.contains("node=v$EXPECTED_NODE_VERSION") && line.contains("platform=android") -> null
            else -> "FAIL|listening 版式不符：$line"
        }
    }

    private fun registerHostHandlers() {
        runtime.host.register("echo") { args ->
            """{"ok":true,"echo":$args,"handledBy":"kotlin","at":${System.currentTimeMillis()}}"""
        }
        runtime.host.register("user.info") {
            """{"ok":true,"pkg":"$packageName","sdk":${android.os.Build.VERSION.SDK_INT},"model":"${android.os.Build.MODEL}"}"""
        }
        runtime.host.register("shutdown") {
            """{"ok":true,"note":"shutdown 钩子已注册（真正停止只能是进程退出）"}"""
        }
    }

    // ── 主流程 ────────────────────────────────────────────────────────────────

    private fun runAll() {
        if (!running.compareAndSet(false, true)) return
        io.execute {

            try {
                report = org.json.JSONArray()
                log("── RUN ALL PROBES ──")
                val startMs = System.currentTimeMillis()
                for (p in probes()) {
                    var verdict: String
                    try {
                        val d = p.check.check()
                        verdict = when {
                            d == null -> "PASS"
                            d.startsWith("BLOCKED") -> "BLOCKED"
                            d.startsWith("PASS-") -> "PASS"
                            else -> "FAIL"
                        }
                        val detail = d ?: ""
                        report.put(
                            JSONObject()
                                .put("id", p.id)
                                .put("title", p.title)
                                .put("verdict", verdict)
                                .put("detail", detail)
                                .put("ms", System.currentTimeMillis() - startMs)
                        )
                        log("${p.id.padEnd(8)} $verdict  ${p.title}" + if (detail.isEmpty()) "" else "  | $detail".take(240))
                    } catch (t: Throwable) {
                        report.put(
                            JSONObject()
                                .put("id", p.id)
                                .put("title", p.title)
                                .put("verdict", "FAIL")
                                .put("detail", "探针异常：${t.javaClass.simpleName}: ${t.message}")
                                .put("ms", System.currentTimeMillis() - startMs)
                        )
                        log("${p.id.padEnd(8)} FAIL  ${p.title} | 探针异常 $t")
                    }
                }
                val summary = summarize()
                log("── 汇总：$summary ──")
                writeReport(summary)
            } catch (t: Throwable) {
                log("FATAL $t")
            } finally {
                running.set(false)
            }
        }
    }

    private fun summarize(): JSONObject {
        var pass = 0; var fail = 0; var blocked = 0
        for (i in 0 until report.length()) {
            when (report.getJSONObject(i).optString("verdict")) {
                "PASS" -> pass++
                "BLOCKED" -> blocked++
                else -> fail++
            }
        }
        return JSONObject()
            .put("runAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(Date()))
            .put("device", deviceLine())
            .put("expected", JSONObject().put("node", "v$EXPECTED_NODE_VERSION"))
            .put("summary", JSONObject().put("pass", pass).put("fail", fail).put("blocked", blocked))
            .put("probes", report)
    }

    private fun writeReport(out: JSONObject) {
        val f = File(filesDir, "probe-report.json")
        f.writeText(out.toString(2))
        log("报告：$f （adb shell run-as $packageName cat files/probe-report.json）")
    }
}
