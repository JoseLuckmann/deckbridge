package os.deckbridge.logic

/**
 * Chave compartilhada da exportação Device Library Plus / OneLibrary.
 *
 * A documentação pública do pyrekordbox (MIT) descreve que `exportLibrary.db`
 * usa SQLCipher com a mesma chave em toda exportação, sem depender de licença
 * nem de máquina. Esta constante é essa chave publicada. Não é a chave do
 * `master.db` do computador. O app abre só uma cópia local, somente leitura.
 */
const val ONE_LIBRARY_PUBLIC_KEY = "r8gddnr4k847830ar6cqzbkk0el6qytmb3trbbx805jm74vez64i5o8fnrqryqls"

data class OneLibraryRead(val tables: List<String>, val contentRows: Int)

fun interface OneLibraryOpener {
    fun open(bytes: ByteArray): OneLibraryRead?
}

fun oneLibraryLooksLikePlainSqlite(bytes: ByteArray): Boolean {
    if (bytes.size < 16) return false
    return bytes.copyOfRange(0, 15).toString(Charsets.US_ASCII) == "SQLite format 3"
}
