package os.deckbridge.manager.organize

import java.io.File
import os.deckbridge.manager.importer.ImportException
import os.deckbridge.manager.importer.ImportPrep
import os.deckbridge.manager.importer.PreparedTrack
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.volume.FileOverlay

/** Resultado da análise de uma faixa que já está no pendrive. O áudio é só lido. */
class AnalysisOutcome(val track: TrackItem, val prepared: PreparedTrack?, val error: String?)

object OnDemand {
    /** Lê e analisa o áudio de [track] em [root]. Pesado: rode fora da interface. */
    fun analyze(root: File, track: TrackItem): AnalysisOutcome {
        val rel = track.filePath.replace('\\', '/').trimStart('/')
        val real = FileOverlay(root).onDisk(rel) ?: return AnalysisOutcome(track, null, "O áudio não está no pendrive: $rel")
        val file = File(root, real)
        return try {
            AnalysisOutcome(track, ImportPrep.prepare(file), null)
        } catch (e: ImportException) {
            AnalysisOutcome(track, null, e.message)
        }
    }

    /** Sugestão da análise: gera o ANLZ (e o tom/andamento que faltam). Não sobrescreve o que o rekordbox já analisou, a menos que [force]. */
    fun suggestion(outcome: AnalysisOutcome, hasAnalysis: Boolean, force: Boolean): Suggestion? {
        val p = outcome.prepared ?: return null
        val t = outcome.track
        if (hasAnalysis && !force) return null
        val setTempo = force || t.bpm <= 0.0
        val setKey = force || t.key.isBlank()
        val what = buildList {
            add("grade, ondas e cues sugeridos")
            if (setTempo) add("BPM %.2f".format(p.read.bpm))
            if (setKey && p.read.keyName != "—") add("tom ${p.read.keyName}")
        }.joinToString(", ")
        return Suggestion(Rule.ANALYSIS, listOf(t.id), "\"${t.title}\"", "Análise do app: $what. O arquivo de análise antigo, se houver, não é tocado.", Edit.AttachAnalysis(t.id, p, p.suggestedCues, setTempo, setKey))
    }
}
