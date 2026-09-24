package com.zenithblue.sambas3.crash

import android.content.Context
import com.zenithblue.sambas3.logging.CaptureState
import com.zenithblue.sambas3.logging.LogBroker
import com.zenithblue.sambas3.logging.LogSessionManifest
import com.zenithblue.sambas3.logging.LogSessionStore
import com.zenithblue.sambas3.logging.SessionDiagnostics
import com.zenithblue.sambas3.logging.SessionDiagnosticsView
import com.zenithblue.sambas3.session.EmulationSessionRecord
import java.io.File

data class CrashReport(
    val directory: File,
    val classification: CrashClassification,
    val summary: String,
    val cause: String,
    val sources: Map<String, File>,
    val gameTitle: String? = null,
    val titleId: String? = null,
    val gameIconPath: String? = null,
    val sessionId: String? = null,
    val revision: Long = 0L,
    val rawStopReason: String? = null,
    val captureState: CaptureState? = null,
    val diagnostics: SessionDiagnosticsView? = null,
    val exitDiagnosis: ProcessExitDiagnosis? = null,
)

object CrashEvidenceCollector {
    fun collectSummary(
        context: Context,
        session: EmulationSessionRecord?,
        evidenceHint: String = "",
        injectedExitRecords: List<ProcessExitRecord>? = null,
    ): CrashReport {
        val logSession = session?.sessionId?.let { LogSessionStore.read(context, it) }
        val effectiveHint = if (evidenceHint.isNotBlank()) evidenceHint else (session?.stopReason ?: logSession?.stopReason ?: "")

        val exitRecords = injectedExitRecords ?: ProcessExitInfoCollector.getExitRecords(context)
        val matchedExit = ProcessExitMatcher.matchSession(session, exitRecords, context.packageName)
        val exitDiagnosis = if (matchedExit != null || session != null) {
            ProcessExitClassifier.classify(
                exitRecord = matchedExit,
                session = session,
                evidenceHint = effectiveHint,
            )
        } else null

        val view = logSession?.let {
            SessionDiagnostics.project(
                it,
                fatalEventId = session.fatalEventId,
                evidenceHint = effectiveHint,
            )
        }
        val classification = when {
            exitDiagnosis != null && exitDiagnosis.classification != ExitClassification.UNKNOWN ->
                exitDiagnosis.classification.legacyClassification
            view != null -> view.classification
            else -> CrashClassifier.classify(
                effectiveHint,
                session != null,
                cleanStop = false,
                fatalEventId = session?.fatalEventId,
                frontendReason = session?.stopReason ?: logSession?.stopReason,
            )
        }
        val sources = sessionOwnedSources(context, logSession).toMutableMap()
        val dir = File(context.filesDir, "crash_reports/${session?.sessionId ?: "report"}")

        if (exitDiagnosis?.rawTrace != null) {
            runCatching {
                dir.mkdirs()
                val traceFile = File(dir, "tombstone.txt")
                traceFile.writeText(exitDiagnosis.rawTrace)
                sources["tombstone.txt"] = traceFile
            }
        }

        val summary = exitDiagnosis?.summary?.ifBlank { null }
            ?: view?.outcome?.name?.replace('_', ' ')
            ?: classification.name.replace('_', ' ')

        val cause = when {
            exitDiagnosis != null && exitDiagnosis.classification != ExitClassification.UNKNOWN ->
                exitDiagnosis.summary
            view?.diagnosticCause != null ->
                view.diagnosticCause.name.replace('_', ' ')
            else -> CrashClassifier.likelyCause(effectiveHint)
        }

        return CrashReport(
            directory = dir,
            classification = classification,
            summary = summary,
            cause = cause,
            sources = sources,
            gameTitle = logSession?.gameTitleSnapshot ?: session?.gameName,
            titleId = logSession?.titleId ?: session?.titleId,
            gameIconPath = logSession?.gameIconSnapshotPath,
            sessionId = session?.sessionId ?: logSession?.sessionId,
            revision = logSession?.revision ?: 0L,
            rawStopReason = logSession?.stopReason ?: session?.stopReason,
            captureState = logSession?.captureState,
            diagnostics = view,
            exitDiagnosis = exitDiagnosis,
        )
    }

    fun collect(
        context: Context,
        session: EmulationSessionRecord?,
        evidenceHint: String = "",
        injectedExitRecords: List<ProcessExitRecord>? = null,
    ): CrashReport {
        return collectSummary(context, session, evidenceHint, injectedExitRecords)
    }

    fun reportForManifest(
        context: Context,
        manifest: LogSessionManifest,
        injectedExitRecords: List<ProcessExitRecord>? = null,
    ): CrashReport {
        val exitRecords = injectedExitRecords ?: ProcessExitInfoCollector.getExitRecords(context)
        val matchedExit = ProcessExitMatcher.matchManifest(manifest, exitRecords, context.packageName)
        val exitDiagnosis = if (matchedExit != null) {
            ProcessExitClassifier.classify(
                exitRecord = matchedExit,
                evidenceHint = manifest.stopReason ?: "",
            )
        } else null

        val view = SessionDiagnostics.project(manifest)
        val sources = sessionOwnedSources(context, manifest).toMutableMap()
        val dir = LogSessionStore.sessionDir(context, manifest.sessionId)

        if (exitDiagnosis?.rawTrace != null) {
            runCatching {
                dir.mkdirs()
                val traceFile = File(dir, "tombstone.txt")
                traceFile.writeText(exitDiagnosis.rawTrace)
                sources["tombstone.txt"] = traceFile
            }
        }

        val classification = when {
            exitDiagnosis != null && exitDiagnosis.classification != ExitClassification.UNKNOWN ->
                exitDiagnosis.classification.legacyClassification
            else -> view.classification
        }

        val summary = exitDiagnosis?.summary?.ifBlank { null } ?: view.outcome.name.replace('_', ' ')
        val cause = when {
            exitDiagnosis != null && exitDiagnosis.classification != ExitClassification.UNKNOWN ->
                exitDiagnosis.summary
            view.diagnosticCause != null -> view.diagnosticCause.name.replace('_', ' ')
            else -> manifest.stopReason ?: view.outcome.name
        }

        return CrashReport(
            directory = dir,
            classification = classification,
            summary = summary,
            cause = cause,
            sources = sources,
            gameTitle = manifest.gameTitleSnapshot,
            titleId = manifest.titleId,
            gameIconPath = manifest.gameIconSnapshotPath,
            sessionId = manifest.sessionId,
            revision = manifest.revision,
            rawStopReason = manifest.stopReason,
            captureState = manifest.captureState,
            diagnostics = view,
            exitDiagnosis = exitDiagnosis,
        )
    }

    fun collectSourceFiles(context: Context, session: EmulationSessionRecord?): Map<String, File> {
        val manifest = session?.sessionId?.let { LogSessionStore.read(context, it) }
        return sessionOwnedSources(context, manifest)
    }

    private fun sessionOwnedSources(context: Context, manifest: LogSessionManifest?): Map<String, File> {
        if (manifest == null) return emptyMap()
        val files = LinkedHashMap<String, File>()
        val dir = LogSessionStore.sessionDir(context, manifest.sessionId)
        fun add(key: String, file: File) {
            if (file.isFile && file.length() > 0 && !files.containsKey(key)) files[key] = file
        }
        File(dir, "manifest.json").let { add("manifest.json", it) }
        manifest.gameIconSnapshotPath?.let { add("game_icon.bin", File(it)) }
        for (artifact in manifest.artifacts) {
            val file = LogBroker.artifactFile(context, manifest.sessionId, artifact) ?: continue
            val key = artifact.id.ifBlank { artifact.originalFilename.ifBlank { file.name } }
            add(key, file)
        }
        File(dir, "lifecycle.jsonl").takeIf { it.isFile }?.let { add("lifecycle.jsonl", it) }
        File(dir, "capture-health.json").takeIf { it.isFile }?.let { add("capture-health.json", it) }
        return files
    }
}
