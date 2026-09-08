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
)

object CrashEvidenceCollector {
    fun collectSummary(context: Context, session: EmulationSessionRecord?, evidenceHint: String = ""): CrashReport {
        val logSession = session?.sessionId?.let { LogSessionStore.read(context, it) }
        val view = logSession?.let {
            SessionDiagnostics.project(
                it,
                fatalEventId = session.fatalEventId,
                evidenceHint = evidenceHint,
            )
        }
        val classification = view?.classification ?: CrashClassifier.classify(
            evidenceHint,
            session != null,
            cleanStop = false,
            fatalEventId = session?.fatalEventId,
            frontendReason = session?.stopReason ?: logSession?.stopReason,
        )
        val sources = sessionOwnedSources(context, logSession)
        val dir = File(context.filesDir, "crash_reports/${session?.sessionId ?: "report"}")
        return CrashReport(
            directory = dir,
            classification = classification,
            summary = view?.outcome?.name?.replace('_', ' ') ?: classification.name.replace('_', ' '),
            cause = view?.diagnosticCause?.name?.replace('_', ' ') ?: CrashClassifier.likelyCause(evidenceHint),
            sources = sources,
            gameTitle = logSession?.gameTitleSnapshot ?: session?.gameName,
            titleId = logSession?.titleId ?: session?.titleId,
            gameIconPath = logSession?.gameIconSnapshotPath,
            sessionId = session?.sessionId ?: logSession?.sessionId,
            revision = logSession?.revision ?: 0L,
            rawStopReason = logSession?.stopReason ?: session?.stopReason,
            captureState = logSession?.captureState,
            diagnostics = view,
        )
    }

    fun collect(context: Context, session: EmulationSessionRecord?, evidenceHint: String = ""): CrashReport {
        return collectSummary(context, session, evidenceHint)
    }

    fun reportForManifest(context: Context, manifest: LogSessionManifest): CrashReport {
        val view = SessionDiagnostics.project(manifest)
        val sources = sessionOwnedSources(context, manifest)
        val dir = LogSessionStore.sessionDir(context, manifest.sessionId)
        return CrashReport(
            directory = dir,
            classification = view.classification,
            summary = view.outcome.name.replace('_', ' '),
            cause = view.diagnosticCause?.name?.replace('_', ' ') ?: (manifest.stopReason ?: view.outcome.name),
            sources = sources,
            gameTitle = manifest.gameTitleSnapshot,
            titleId = manifest.titleId,
            gameIconPath = manifest.gameIconSnapshotPath,
            sessionId = manifest.sessionId,
            revision = manifest.revision,
            rawStopReason = manifest.stopReason,
            captureState = manifest.captureState,
            diagnostics = view,
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
