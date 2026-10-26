package com.manjul.kbapi.report

import java.util.concurrent.ConcurrentHashMap

/** Single-instance store for tests and local runs. */
class InMemoryReportStore : ReportStore {
    private val reports = ConcurrentHashMap<Pair<String, String>, AiReport>()

    override suspend fun save(report: AiReport) {
        reports[report.uid to report.clientReqId] = report
    }

    fun all(): List<AiReport> = reports.values.sortedBy { it.createdAt }
}
