package com.github.shadowsocks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView

class LogFragment : ToolbarFragment() {
    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.layout_log, container, false)
        logTextView = view.findViewById(R.id.logText)
        scrollView = view.findViewById(R.id.scrollView)
        view.findViewById<Button>(R.id.btnClear).setOnClickListener {
            AppLog.clearLogs()
            refreshLogs()
        }
        view.findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            refreshLogs()
        }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.title = getString(R.string.log)
        refreshLogs()
    }

    override fun onResume() {
        super.onResume()
        refreshLogs()
    }

    private fun refreshLogs() {
        val sb = StringBuilder()
        for (log in AppLog.getLogs()) {
            sb.append(log).append("\n")
        }
        logTextView.text = if (sb.isEmpty()) "No logs yet." else sb.toString()
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }
}
