package os.deckbridge.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.concurrent.thread
import os.deckbridge.manager.ManagerModel
import os.deckbridge.manager.transfer.Cancelled
import os.deckbridge.manager.transfer.ExportException
import os.deckbridge.manager.transfer.FileStorage
import os.deckbridge.manager.transfer.TargetLibrary
import os.deckbridge.manager.transfer.TransferReport
import os.deckbridge.manager.transfer.UsbExporter
import os.deckbridge.manager.transfer.UsbImporter
import os.deckbridge.manager.transfer.UsbMerger

/** Estado do diálogo de exportar/trazer. [export] é verdadeiro para exportar o volume aberto, falso para trazer um pendrive. */
class TransferState(val export: Boolean) {
    var target by mutableStateOf<File?>(null)
    var existing by mutableStateOf<TargetLibrary?>(null)
    var error by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
    var report by mutableStateOf<TransferReport?>(null)
    var merged by mutableStateOf<os.deckbridge.manager.transfer.MergeReport?>(null)
    @Volatile var cancelled = false

    /** Trazer: juntar (padrão, não apaga nada) ou substituir a biblioteca aberta. */
    var merge by mutableStateOf(true)
}

/**
 * Exportar a biblioteca aberta (por exemplo, a local do computador) para um pendrive, ou trazer a de um pendrive para a
 * pasta aberta. O pendrive só é escrito na exportação, a pedido, com backup local antes.
 */
@Composable
fun TransferModal(model: ManagerModel, st: TransferState, chooser: () -> File?, onClose: () -> Unit) {
    val vol = model.volume ?: return
    val backupBase = model.backups.base
    val busy = st.status != null
    Modal(if (st.export) "Exportar para um pendrive" else "Trazer a biblioteca de um pendrive", 640.dp, if (busy) null else onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label(
                if (st.export) "Copia a biblioteca aberta (${model.view?.tracks?.size ?: 0} faixas) para o pendrive: áudio e análises que faltam e, por último, o banco. Depois relê o pendrive e confere."
                else if (st.merge) "Traz as faixas que faltam, com análise, notas e playlists. Nada é apagado; o pendrive só é lido."
                else "Substitui a biblioteca aberta pela do pendrive. A atual fica em backup. O pendrive só é lido.",
                Pal.Muted, 12.sp, maxLines = 4,
            )
            if (!st.export) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("Juntar", { st.merge = true }, primary = st.merge)
                Btn("Substituir", { st.merge = false }, primary = !st.merge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("Escolher o pendrive…", {
                    chooser()?.let { f ->
                        st.target = f
                        st.report = null
                        st.error = null
                        st.existing = try {
                            UsbExporter(backupBase).inspect(FileStorage(f))
                        } catch (e: ExportException) {
                            st.error = e.message
                            null
                        }
                    }
                }, enabled = !busy)
                Label(st.target?.path ?: "nenhum escolhido", Pal.Ink, 12.sp, modifier = Modifier.width(380.dp))
            }
            st.existing?.let {
                Label(
                    if (st.export) "O pendrive já tem uma biblioteca (${it.tracks} faixas, ${it.playlists} playlists). Exportar vai substituí-la; o banco antigo vai para ${backupBase.path}. Nenhum áudio é apagado."
                    else "Biblioteca no pendrive: ${it.tracks} faixas, ${it.playlists} playlists.",
                    Pal.Orange, 12.sp, maxLines = 3,
                )
            }
            if (st.export && st.target != null && st.existing == null && st.error == null) Label("Pendrive sem biblioteca: será criada do zero.", Pal.Muted, 12.sp)
            if (!st.export && st.target != null && st.existing == null && st.error == null) Label("Não achei uma biblioteca nesse pendrive.", Pal.Red, 12.sp)
            if (model.dirty) Label("Há mudanças não gravadas na fila. Aplique ou descarte antes.", Pal.Red, 12.sp)
            st.error?.let { Label(it, Pal.Red, 12.sp, maxLines = 4) }
            st.status?.let { Label(it, Pal.Blue, 12.sp, maxLines = 2) }
            st.merged?.let { r -> Label("Pronto: ${r.added} trazidas · ${r.alreadyThere} já estavam" + if (r.failed > 0) " · ${r.failed} falharam" else "", Pal.Green, 12.sp) }
            st.report?.let { r ->
                Label("Pronto: ${r.tracks} faixas, ${r.playlists} playlists, ${r.files} arquivos (${r.copied} copiados, ${r.skipped} já estavam).", Pal.Green, 12.sp, maxLines = 3)
                r.backupDir?.let { Label("Backup: ${it.path}", Pal.Muted, 11.sp, maxLines = 2) }
                if (r.orphanAudio.isNotEmpty()) Label("${r.orphanAudio.size} áudio(s) do pendrive ficaram fora da nova biblioteca (continuam lá, intactos).", Pal.Muted, 11.sp, maxLines = 2)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                val target = st.target
                val ready = target != null && !busy && !model.dirty && st.error == null && (if (st.export) true else st.existing != null)
                Btn(if (st.export) "Exportar" else "Trazer", {
                    st.status = "começando…"
                    st.cancelled = false
                    st.report = null
                    thread(isDaemon = true) {
                        try {
                            if (!st.export && st.merge) {
                                val m = UsbMerger(model.backups, File(System.getProperty("java.io.tmpdir"))).merge(FileStorage(target!!), vol.root, progress = { st.status = it }, cancelled = { st.cancelled })
                                st.merged = m
                                st.error = null
                                model.open(vol.root)
                                return@thread
                            }
                            val r = if (st.export) UsbExporter(backupBase).export(vol.root, FileStorage(target!!), replaceExisting = true, progress = { st.status = it }, cancelled = { st.cancelled })
                            else UsbImporter(backupBase).import(FileStorage(target!!), vol.root, includeAudio = true, progress = { st.status = it }, cancelled = { st.cancelled })
                            st.report = r
                            st.error = null
                            if (st.export) st.existing = UsbExporter(backupBase).inspect(FileStorage(target)) else model.open(vol.root)
                        } catch (_: Cancelled) {
                            st.error = "Cancelado."
                        } catch (e: ExportException) {
                            st.error = e.message
                        } catch (e: Exception) {
                            st.error = os.deckbridge.manager.studio.friendlyError(e)
                        } finally {
                            st.status = null
                        }
                    }
                }, primary = true, enabled = ready)
                if (busy) Btn("Cancelar", { st.cancelled = true }) else Btn("Fechar", onClose)
            }
        }
    }
}
