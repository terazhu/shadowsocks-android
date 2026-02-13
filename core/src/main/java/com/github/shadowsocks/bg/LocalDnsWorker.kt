package com.github.shadowsocks.bg

import android.net.LocalSocket
import com.github.shadowsocks.Core
import com.github.shadowsocks.net.ConcurrentLocalSocketListener
import com.github.shadowsocks.net.DnsResolverCompat
import com.github.shadowsocks.summary.SummaryStore
import com.github.shadowsocks.utils.readableMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import org.xbill.DNS.Message
import org.xbill.DNS.Rcode
import org.xbill.DNS.ARecord
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.Section
import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

class LocalDnsWorker(private val resolver: suspend (ByteArray) -> ByteArray) : ConcurrentLocalSocketListener(
        "LocalDnsThread", File(Core.deviceStorage.noBackupFilesDir, "local_dns_path")), CoroutineScope {
    override fun acceptInternal(socket: LocalSocket) = error("big no no")
    override fun accept(socket: LocalSocket) {
        launch {
            socket.use {
                val input = DataInputStream(socket.inputStream)
                val query = try {
                    ByteArray(input.readUnsignedShort()).also { input.read(it) }
                } catch (e: IOException) {  // connection early close possibly due to resolving timeout
                    return@use Timber.d(e)
                }
                try {
                    resolver(query)
                } catch (e: Exception) {
                    when (e) {
                        is TimeoutCancellationException -> Timber.w("Resolving timed out")
                        is CancellationException -> { } // ignore
                        is IOException -> Timber.d(e)
                        is UnsupportedOperationException -> Timber.w(e.message)
                        else -> Timber.w(e)
                    }
                    try {
                        DnsResolverCompat.prepareDnsResponse(Message(query)).apply {
                            header.rcode = Rcode.SERVFAIL
                        }.toWire()
                    } catch (_: IOException) {
                        byteArrayOf()   // return empty if cannot parse packet
                    }
                }?.let { response ->
                    val domain = parseDomain(query)
                    val ips = parseIps(response)
                    if (domain != null) {
                        if (ips.isNotEmpty()) SummaryStore.recordDnsResult(domain, ips)
                        else SummaryStore.recordDnsQuery(domain)
                    }
                    try {
                        val output = DataOutputStream(socket.outputStream)
                        output.writeShort(response.size)
                        output.write(response)
                    } catch (e: IOException) {
                        Timber.d(e.readableMessage)
                    }
                }
            }
        }
    }

    private fun parseDomain(query: ByteArray): String? {
        if (query.size < 13) return null
        var index = 12
        val parts = mutableListOf<String>()
        while (index < query.size) {
            val len = query[index].toInt() and 0xFF
            if (len == 0) break
            if (len > 63 || index + 1 + len > query.size) return null
            val part = String(query, index + 1, len)
            parts.add(part)
            index += len + 1
        }
        if (parts.isEmpty()) return null
        return parts.joinToString(".")
    }

    private fun parseIps(response: ByteArray): List<String> {
        return try {
            val message = Message(response)
            val answers = message.getSection(Section.ANSWER)
            answers.mapNotNull { record ->
                when (record) {
                    is ARecord -> record.address.hostAddress
                    is AAAARecord -> record.address.hostAddress
                    else -> null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
