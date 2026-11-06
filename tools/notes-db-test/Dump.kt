// Prints NoteSchema's SQL (and ftsQuery results) so test.py can run the app's exact statements on real SQLite.
import com.anoop.dailywhisper.NoteSchema as S

fun block(name: String, stmts: List<String>) { println("@@$name"); stmts.forEach { println(it.replace("\n", " ")) } }

fun main() {
    block("CREATE_V1", S.CREATE_V1)
    block("CREATE_V2", S.CREATE_V2)
    block("CREATE_V3", S.CREATE_V3)
    block("CREATE_V4", S.CREATE_V4)
    block("UPGRADE_1_TO_2", S.UPGRADE_1_TO_2)
    block("UPGRADE_2_TO_3", S.UPGRADE_2_TO_3)
    block("UPGRADE_3_TO_4", S.UPGRADE_3_TO_4)
    block("FTS_unicode61", S.ftsObjects("unicode61"))
    block("FTS_simple", S.ftsObjects("simple"))
    block("DROP_FTS", S.DROP_FTS)
    block("Q", listOf(S.LIST_BY_STATUS, S.LIST_BY_STATUS_KIND, S.LIST_DELETED, S.SEARCH_FTS, S.SEARCH_LIKE, S.VERSIONS))
    val inputs = listOf("milk", "Buy mil", "  café  ", "and OR not", "!!!", "dentist 3pm", "\"quoted\" words", "oat", "eggs", "dentist", "daily", "mum", "phone", "x", "long")
    block("FTSQ", inputs.map { it + "\t" + (S.ftsQuery(it) ?: "<null>") })
    block("LIKE", listOf(S.likePattern("50% off_now")))
    println("@@VERSION\n${S.VERSION}")
}
