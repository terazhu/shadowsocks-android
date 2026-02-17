package com.github.shadowsocks.net

import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.github.shadowsocks.Core
import com.github.shadowsocks.Core.app
import com.github.shadowsocks.AppLog
import com.github.shadowsocks.core.R
import com.github.shadowsocks.preference.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLConnection

class HttpsTest : ViewModel() {
    sealed class Status {
        protected abstract val status: CharSequence
        open fun retrieve(setStatus: (CharSequence) -> Unit, errorCallback: (String) -> Unit) = setStatus(status)

        data object Idle : Status() {
            override val status get() = app.getText(R.string.vpn_connected)
        }
        data object Testing : Status() {
            override val status get() = app.getText(R.string.connection_test_testing)
        }
        class Success(
            private val proxyElapsed: Long,
            private val directElapsed: Long? = null
        ) : Status() {
            override val status: CharSequence
                get() = if (directElapsed != null) {
                    val overhead = proxyElapsed - directElapsed
                    val overheadStr = if (overhead >= 0) "+${overhead}ms" else "${overhead}ms"
                    app.getString(R.string.connection_test_available_with_overhead, proxyElapsed, directElapsed, overheadStr)
                } else {
                    app.getString(R.string.connection_test_available, proxyElapsed)
                }
        }
        sealed class Error : Status() {
            override val status get() = app.getText(R.string.connection_test_fail)
            protected abstract val error: String
            private var shown = false
            override fun retrieve(setStatus: (CharSequence) -> Unit, errorCallback: (String) -> Unit) {
                super.retrieve(setStatus, errorCallback)
                if (shown) return
                shown = true
                errorCallback(error)
            }

            class UnexpectedResponseCode(private val code: Int) : Error() {
                override val error get() = app.getString(R.string.connection_test_error_status_code, code)
            }
            class IOFailure(private val e: IOException) : Error() {
                override val error get() = app.getString(R.string.connection_test_error, e.message)
            }
        }
    }

    private var running: Job? = null
    val status = MutableLiveData<Status>(Status.Idle)

    fun testConnection() {
        cancelTest()
        status.value = Status.Testing
        AppLog.logInfo("HttpsTest", "Starting connectivity test...")
        running = GlobalScope.launch(Dispatchers.Main.immediate) {
            var proxyElapsed: Long? = null
            var directElapsed: Long? = null
            var proxyError: String? = null

            AppLog.logInfo("HttpsTest", "Testing via proxy: ${DataStore.proxy}")
            try {
                proxyElapsed = withContext(Dispatchers.IO) {
                    testWithProxy(DataStore.proxy)
                }
                AppLog.logInfo("HttpsTest", "Proxy test: ${proxyElapsed}ms")
            } catch (e: IOException) {
                proxyError = "Proxy test failed: ${e.message}"
                AppLog.logError("HttpsTest", proxyError!!, e)
            } catch (e: Exception) {
                proxyError = "Proxy test error: ${e.message}"
                AppLog.logError("HttpsTest", proxyError!!, e)
            }

            if (proxyElapsed != null) {
                val directAllowed = Core.isExternalAccessAllowed()
                if (!directAllowed) {
                    AppLog.logInfo("HttpsTest", "Requesting external access for direct test...")
                    Core.requestExternalAccess(app.getString(R.string.external_access_reason_test))
                }
                
                if (Core.isExternalAccessAllowed()) {
                    AppLog.logInfo("HttpsTest", "Testing direct connection (no proxy)...")
                    try {
                        directElapsed = withContext(Dispatchers.IO) {
                            testWithProxy(Proxy.NO_PROXY)
                        }
                        AppLog.logInfo("HttpsTest", "Direct test: ${directElapsed}ms")
                    } catch (e: IOException) {
                        AppLog.logWarn("HttpsTest", "Direct test failed: ${e.message}")
                    } catch (e: Exception) {
                        AppLog.logWarn("HttpsTest", "Direct test error: ${e.message}")
                    }
                } else {
                    AppLog.logInfo("HttpsTest", "Direct test skipped: external access not authorized")
                }
            }

            status.value = when {
                proxyElapsed != null -> {
                    if (directElapsed != null) {
                        val overhead = proxyElapsed - directElapsed
                        AppLog.logInfo("HttpsTest", "Proxy overhead: ${overhead}ms (proxy: ${proxyElapsed}ms, direct: ${directElapsed}ms)")
                    }
                    Status.Success(proxyElapsed, directElapsed)
                }
                proxyError != null -> Status.Error.IOFailure(IOException(proxyError))
                else -> Status.Error.IOFailure(IOException("Unknown error"))
            }
        }
    }

    private fun testWithProxy(proxy: Proxy): Long {
        val url = URL("https://cp.cloudflare.com")
        val conn = url.openConnection(proxy) as HttpURLConnection
        conn.setRequestProperty("Connection", "close")
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        return try {
            val start = SystemClock.elapsedRealtime()
            val code = conn.responseCode
            val elapsed = SystemClock.elapsedRealtime() - start
            if (code != 204 && !(code == 200 && conn.responseLength == 0L)) {
                throw IOException("Unexpected response code: $code")
            }
            elapsed
        } finally {
            conn.disconnect()
        }
    }

    private fun cancelTest() {
        running?.cancel()
        running = null
    }

    fun invalidate() {
        cancelTest()
        status.value = Status.Idle
    }

    private val URLConnection.responseLength: Long
        get() = if (Build.VERSION.SDK_INT >= 24) contentLengthLong else contentLength.toLong()
}
