package com.github.shadowsocks.summary

import com.github.shadowsocks.Core
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AppUsage(val packageName: String, val tx: Long, val rx: Long)
data class DomainUsage(val domain: String, val count: Int)
data class IpUsage(val ip: String, val count: Int)
data class ActiveSession(val startTime: Long, val txTotal: Long, val rxTotal: Long, val lastUpdate: Long)
data class SessionSummary(
    val startTime: Long,
    val endTime: Long,
    val txTotal: Long,
    val rxTotal: Long,
    val apps: List<AppUsage>,
    val domains: List<DomainUsage>,
    val ips: List<IpUsage>
)

object SummaryStore {
    private val lock = Any()
    private val file by lazy { File(Core.deviceStorage.noBackupFilesDir, "summary.json") }
    private var active = false
    private var activeDomains = mutableMapOf<String, Int>()
    private var activeIps = mutableMapOf<String, Int>()
    private var activeStartTime = 0L
    private var activeTxTotal = 0L
    private var activeRxTotal = 0L
    private var activeLastUpdate = 0L

    fun startSession() {
        synchronized(lock) {
            active = true
            activeDomains = mutableMapOf()
            activeIps = mutableMapOf()
            activeStartTime = System.currentTimeMillis()
            activeTxTotal = 0L
            activeRxTotal = 0L
            activeLastUpdate = activeStartTime
        }
    }

    fun recordDnsQuery(domain: String) {
        synchronized(lock) {
            if (!active) return
            val key = domain.lowercase()
            activeDomains[key] = (activeDomains[key] ?: 0) + 1
        }
    }

    fun recordDnsResult(domain: String, ips: List<String>) {
        synchronized(lock) {
            if (!active) return
            val key = domain.lowercase()
            activeDomains[key] = (activeDomains[key] ?: 0) + 1
            ips.forEach { ip ->
                activeIps[ip] = (activeIps[ip] ?: 0) + 1
            }
        }
    }

    fun updateActiveStats(txTotal: Long, rxTotal: Long) {
        synchronized(lock) {
            if (!active) return
            activeTxTotal = txTotal
            activeRxTotal = rxTotal
            activeLastUpdate = System.currentTimeMillis()
        }
    }

    fun endSession(
        startTime: Long,
        endTime: Long,
        txTotal: Long,
        rxTotal: Long,
        appUsage: Map<String, Pair<Long, Long>>
    ) {
        synchronized(lock) {
            val domains = activeDomains.entries
                .sortedByDescending { it.value }
                .take(200)
                .map { DomainUsage(it.key, it.value) }
            val ips = activeIps.entries
                .sortedByDescending { it.value }
                .take(200)
                .map { IpUsage(it.key, it.value) }
            active = false
            activeDomains.clear()
            activeIps.clear()
            val apps = appUsage.entries
                .sortedByDescending { it.value.first + it.value.second }
                .take(200)
                .map { AppUsage(it.key, it.value.first, it.value.second) }
            val session = SessionSummary(startTime, endTime, txTotal, rxTotal, apps, domains, ips)
            val existing = readSessionsInternal()
            val updated = (existing + session).takeLast(30)
            writeSessionsInternal(updated)
        }
    }

    fun readSessions(): List<SessionSummary> = synchronized(lock) { readSessionsInternal() }
    fun readActiveSession(): ActiveSession? = synchronized(lock) {
        if (!active) null else ActiveSession(activeStartTime, activeTxTotal, activeRxTotal, activeLastUpdate)
    }

    private fun readSessionsInternal(): List<SessionSummary> {
        if (!file.exists()) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        val root = JSONObject(text)
        val sessionsJson = root.optJSONArray("sessions") ?: return emptyList()
        return (0 until sessionsJson.length()).mapNotNull { index ->
            val obj = sessionsJson.optJSONObject(index) ?: return@mapNotNull null
            val appsJson = obj.optJSONArray("apps") ?: JSONArray()
            val domainsJson = obj.optJSONArray("domains") ?: JSONArray()
            val ipsJson = obj.optJSONArray("ips") ?: JSONArray()
            val apps = (0 until appsJson.length()).mapNotNull { i ->
                val a = appsJson.optJSONObject(i) ?: return@mapNotNull null
                val pkg = a.optString("pkg")
                if (pkg.isBlank()) return@mapNotNull null
                AppUsage(pkg, a.optLong("tx"), a.optLong("rx"))
            }
            val domains = (0 until domainsJson.length()).mapNotNull { i ->
                val d = domainsJson.optJSONObject(i) ?: return@mapNotNull null
                val host = d.optString("host")
                if (host.isBlank()) return@mapNotNull null
                DomainUsage(host, d.optInt("count"))
            }
            val ips = (0 until ipsJson.length()).mapNotNull { i ->
                val d = ipsJson.optJSONObject(i) ?: return@mapNotNull null
                val ip = d.optString("ip")
                if (ip.isBlank()) return@mapNotNull null
                IpUsage(ip, d.optInt("count"))
            }
            SessionSummary(
                obj.optLong("start"),
                obj.optLong("end"),
                obj.optLong("tx"),
                obj.optLong("rx"),
                apps,
                domains,
                ips
            )
        }
    }

    private fun writeSessionsInternal(sessions: List<SessionSummary>) {
        val root = JSONObject()
        val sessionsJson = JSONArray()
        sessions.forEach { s ->
            val obj = JSONObject()
            obj.put("start", s.startTime)
            obj.put("end", s.endTime)
            obj.put("tx", s.txTotal)
            obj.put("rx", s.rxTotal)
            val appsJson = JSONArray()
            s.apps.forEach { a ->
                val aObj = JSONObject()
                aObj.put("pkg", a.packageName)
                aObj.put("tx", a.tx)
                aObj.put("rx", a.rx)
                appsJson.put(aObj)
            }
            val domainsJson = JSONArray()
            s.domains.forEach { d ->
                val dObj = JSONObject()
                dObj.put("host", d.domain)
                dObj.put("count", d.count)
                domainsJson.put(dObj)
            }
            val ipsJson = JSONArray()
            s.ips.forEach { d ->
                val dObj = JSONObject()
                dObj.put("ip", d.ip)
                dObj.put("count", d.count)
                ipsJson.put(dObj)
            }
            obj.put("apps", appsJson)
            obj.put("domains", domainsJson)
            obj.put("ips", ipsJson)
            sessionsJson.put(obj)
        }
        root.put("sessions", sessionsJson)
        file.writeText(root.toString())
    }
}
