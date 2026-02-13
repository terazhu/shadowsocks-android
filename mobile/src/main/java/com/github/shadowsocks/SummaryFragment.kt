package com.github.shadowsocks

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.shadowsocks.summary.AppUsage
import com.github.shadowsocks.summary.DomainUsage
import com.github.shadowsocks.summary.IpUsage
import com.github.shadowsocks.summary.SessionSummary
import com.github.shadowsocks.summary.SummaryStore
import java.text.SimpleDateFormat
import java.util.Locale

class SummaryFragment : ToolbarFragment() {
    private lateinit var list: RecyclerView
    private val adapter = SummaryAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            reload()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.layout_summary, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list = view.findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
        handler.postDelayed(refresh, 1000)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun reload() {
        val sessions = SummaryStore.readSessions()
        adapter.submit(buildRows(sessions))
    }

    private fun buildRows(sessions: List<SessionSummary>): List<Row> {
        val rows = mutableListOf<Row>()
        val totalTx = sessions.sumOf { it.txTotal }
        val totalRx = sessions.sumOf { it.rxTotal }
        val domains = aggregateDomains(sessions)
        val countries = aggregateCountries(domains)
        val ips = aggregateIps(sessions)
        val active = SummaryStore.readActiveSession()
        val lastSession = sessions.lastOrNull()
        rows.add(
            Row.Header(
                totalTx,
                totalRx,
                sessions.size,
                countries,
                domains.size,
                ips.size,
                active?.let { formatActive(it.startTime, it.txTotal, it.rxTotal) } ?: "—",
                lastSession?.let { formatSession(it.startTime, it.endTime) } ?: "—"
            )
        )
        rows.add(Row.Section(getString(R.string.summary_section_apps)))
        rows.addAll(appRows(aggregateApps(sessions)))
        rows.add(Row.Section(getString(R.string.summary_section_domains)))
        rows.addAll(domainRows(domains))
        rows.add(Row.Section(getString(R.string.summary_section_ips)))
        rows.addAll(ipRows(ips))
        rows.add(Row.Section(getString(R.string.summary_section_countries)))
        rows.addAll(countryRows(countries))
        return rows
    }

    private fun aggregateApps(sessions: List<SessionSummary>): List<AppUsage> {
        val map = mutableMapOf<String, Pair<Long, Long>>()
        sessions.forEach { s ->
            s.apps.forEach { a ->
                val prev = map[a.packageName]
                if (prev == null) map[a.packageName] = a.tx to a.rx
                else map[a.packageName] = prev.first + a.tx to prev.second + a.rx
            }
        }
        return map.entries.map { AppUsage(it.key, it.value.first, it.value.second) }
            .sortedByDescending { it.tx + it.rx }
            .take(50)
    }

    private fun aggregateDomains(sessions: List<SessionSummary>): List<DomainUsage> {
        val map = mutableMapOf<String, Int>()
        sessions.forEach { s ->
            s.domains.forEach { d ->
                map[d.domain] = (map[d.domain] ?: 0) + d.count
            }
        }
        return map.entries.map { DomainUsage(it.key, it.value) }
            .sortedByDescending { it.count }
            .take(50)
    }

    private fun aggregateIps(sessions: List<SessionSummary>): List<IpUsage> {
        val map = mutableMapOf<String, Int>()
        sessions.forEach { s ->
            s.ips.forEach { d ->
                map[d.ip] = (map[d.ip] ?: 0) + d.count
            }
        }
        return map.entries.map { IpUsage(it.key, it.value) }
            .sortedByDescending { it.count }
            .take(50)
    }

    private fun aggregateCountries(domains: List<DomainUsage>): List<Pair<String, Int>> {
        val map = mutableMapOf<String, Int>()
        domains.forEach { d ->
            val country = domainCountry(d.domain)
            map[country] = (map[country] ?: 0) + d.count
        }
        return map.entries.sortedByDescending { it.value }.map { it.key to it.value }.take(50)
    }

    private fun domainCountry(domain: String): String {
        val parts = domain.split('.')
        val tld = parts.lastOrNull()?.lowercase(Locale.getDefault()) ?: return getString(R.string.summary_unknown_country)
        if (tld.length == 2) {
            val country = Locale.Builder().setRegion(tld.uppercase(Locale.getDefault())).build().displayCountry
            return if (country.isNullOrBlank()) getString(R.string.summary_unknown_country) else country
        }
        return getString(R.string.summary_unknown_country)
    }

    private fun appRows(apps: List<AppUsage>): List<Row.Item> {
        val pm = requireContext().packageManager
        val max = apps.maxOfOrNull { it.tx + it.rx } ?: 0L
        return apps.map { app ->
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(app.packageName, 0)).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                app.packageName
            }
            val subtitle = "↑${Formatter.formatFileSize(requireContext(), app.tx)} ↓${Formatter.formatFileSize(requireContext(), app.rx)}"
            Row.Item(app.packageName, label, subtitle, ItemType.APP, barProgress(app.tx + app.rx, max))
        }
    }

    private fun domainRows(domains: List<DomainUsage>): List<Row.Item> {
        val max = domains.maxOfOrNull { it.count } ?: 0
        return domains.map { d ->
            Row.Item("", d.domain, "×${d.count}", ItemType.DOMAIN, barProgress(d.count.toLong(), max.toLong()))
        }
    }

    private fun ipRows(ips: List<IpUsage>): List<Row.Item> {
        val max = ips.maxOfOrNull { it.count } ?: 0
        return ips.map { d ->
            Row.Item("", d.ip, "×${d.count}", ItemType.IP, barProgress(d.count.toLong(), max.toLong()))
        }
    }

    private fun countryRows(countries: List<Pair<String, Int>>): List<Row.Item> {
        val max = countries.maxOfOrNull { it.second } ?: 0
        return countries.map { c ->
            Row.Item("", c.first, "×${c.second}", ItemType.COUNTRY, barProgress(c.second.toLong(), max.toLong()))
        }
    }

    private fun barProgress(value: Long, max: Long): Int {
        if (max <= 0) return 0
        return ((value * 100) / max).toInt().coerceIn(0, 100)
    }

    private fun formatSession(start: Long, end: Long): String {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val duration = ((end - start) / 60000).coerceAtLeast(0)
        return "${fmt.format(start)}~${fmt.format(end)} · ${duration}m"
    }

    private fun formatActive(start: Long, tx: Long, rx: Long): String {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val duration = ((System.currentTimeMillis() - start) / 60000).coerceAtLeast(0)
        val up = Formatter.formatFileSize(requireContext(), tx)
        val down = Formatter.formatFileSize(requireContext(), rx)
        return "${fmt.format(start)} · ${duration}m · ↑$up ↓$down"
    }
}

private enum class ItemType { APP, DOMAIN, IP, COUNTRY }

private sealed class Row {
    data class Header(
        val tx: Long,
        val rx: Long,
        val sessions: Int,
        val countries: List<Pair<String, Int>>,
        val domainCount: Int,
        val ipCount: Int,
        val active: String,
        val lastSession: String
    ) : Row()
    data class Section(val title: String) : Row()
    data class Item(val pkg: String, val title: String, val subtitle: String, val type: ItemType, val progress: Int) : Row()
}

private class SummaryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val rows = mutableListOf<Row>()

    fun submit(newRows: List<Row>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> 0
        is Row.Section -> 1
        is Row.Item -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HeaderHolder(inflater.inflate(R.layout.layout_summary_header, parent, false))
            1 -> SectionHolder(inflater.inflate(R.layout.layout_summary_section, parent, false))
            else -> ItemHolder(inflater.inflate(R.layout.layout_summary_item, parent, false))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row)
            is Row.Section -> (holder as SectionHolder).bind(row)
            is Row.Item -> (holder as ItemHolder).bind(row)
        }
    }
}

private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val total: TextView = view.findViewById(R.id.summary_total)
    private val sessions: TextView = view.findViewById(R.id.summary_sessions)
    private val countries: TextView = view.findViewById(R.id.summary_countries)
    private val domains: TextView = view.findViewById(R.id.summary_domains)
    private val ips: TextView = view.findViewById(R.id.summary_ips)
    private val active: TextView = view.findViewById(R.id.summary_active)
    private val lastSession: TextView = view.findViewById(R.id.summary_last_session)

    fun bind(row: Row.Header) {
        val ctx = itemView.context
        total.text = ctx.getString(
            R.string.summary_total,
            Formatter.formatFileSize(ctx, row.tx),
            Formatter.formatFileSize(ctx, row.rx)
        )
        sessions.text = ctx.getString(R.string.summary_sessions, row.sessions)
        val topCountries = row.countries.take(5).joinToString(" · ") { "${it.first}×${it.second}" }
        countries.text = ctx.getString(R.string.summary_countries, if (topCountries.isBlank()) "—" else topCountries)
        domains.text = ctx.getString(R.string.summary_domains, row.domainCount)
        ips.text = ctx.getString(R.string.summary_ips, row.ipCount)
        active.text = ctx.getString(R.string.summary_active, row.active)
        lastSession.text = ctx.getString(R.string.summary_last_session, row.lastSession)
    }
}

private class SectionHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val title: TextView = view.findViewById(R.id.section_title)
    fun bind(row: Row.Section) {
        title.text = row.title
    }
}

private class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val icon: ImageView = view.findViewById(android.R.id.icon)
    private val text1: TextView = view.findViewById(android.R.id.text1)
    private val text2: TextView = view.findViewById(android.R.id.text2)
    private val bar: ProgressBar = view.findViewById(R.id.summary_bar)

    fun bind(row: Row.Item) {
        text1.text = row.title
        text2.text = row.subtitle
        bar.progress = row.progress
        when (row.type) {
            ItemType.APP -> {
                val pm = itemView.context.packageManager
                val drawable = try {
                    pm.getApplicationIcon(row.pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
                icon.setImageDrawable(drawable ?: itemView.context.getDrawable(R.drawable.ic_device_data_usage))
            }
            ItemType.DOMAIN -> icon.setImageResource(R.drawable.ic_action_dns)
            ItemType.IP -> icon.setImageResource(R.drawable.ic_action_dns)
            ItemType.COUNTRY -> icon.setImageResource(R.drawable.ic_maps_360)
        }
    }
}
