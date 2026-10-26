// Box tests for TranslateConfig.kt (pure Kotlin, the same file the app compiles): the language list against
// ML Kit's TranslateLanguage in the AAR the app builds with, and the line/sentence chunking. Run via run.sh.
import com.anoop.dailywhisper.TranslateConfig

var pass = 0; var fail = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; println("  ok   $name") } else { fail++; println("  FAIL $name $detail") }
}

fun main(args: Array<String>) {
    val mlkitCodes = args[0].split(' ').filter { it.isNotBlank() }.toSet()

    println("languages")
    val ours = TranslateConfig.LANGUAGES.map { it.code }.toSet()
    check("codes match TranslateLanguage in the ML Kit AAR (${mlkitCodes.size})", ours == mlkitCodes, "diff=${(ours - mlkitCodes) + (mlkitCodes - ours)}")
    check("codes unique", ours.size == TranslateConfig.LANGUAGES.size)
    check("names sorted", TranslateConfig.LANGUAGES.map { it.name } == TranslateConfig.LANGUAGES.map { it.name }.sortedBy { it.lowercase() })
    check("defaults are listed", listOf(TranslateConfig.DEFAULT_SOURCE, TranslateConfig.DEFAULT_TARGET).all { TranslateConfig.lang(it) != null })
    check("options() = every language", TranslateConfig.options().size == TranslateConfig.LANGUAGES.size)
    check("unknown code -> no language, name falls back to code", TranslateConfig.lang("bo") == null && TranslateConfig.languageName("xx") == "xx")

    println("chunks")
    val max = TranslateConfig.CHUNK_CHARS
    val samples = listOf("", "one line", "a\nb\n\nc\n", "  lead\n\n\ttabs  \n", "x".repeat(5000),
        ("This is a sentence. ".repeat(300)).trim(), "Para one.\n\n" + "Long words ".repeat(400) + "\nlast",
        "नमस्ते दुनिया। ".repeat(200), "emoji 😀 test\r\nwindows line")
    for ((i, s) in samples.withIndex()) {
        val c = TranslateConfig.chunks(s, max)
        check("sample $i round-trips (${c.size} pieces)", c.joinToString("") { it.text + it.sep } == s)
        check("sample $i pieces <= $max", c.all { it.text.length <= max }, c.map { it.text.length }.toString())
    }
    val c2 = TranslateConfig.chunks("Hello there.\n\nSecond para.", max)
    check("paragraphs kept apart", c2.size == 2 && c2[0].sep == "\n\n" && c2[1].text == "Second para.", c2.toString())
    val c3 = TranslateConfig.chunks(("Short sentence here. ".repeat(50)).trim(), 200)
    check("long line cut at sentence ends", c3.dropLast(1).all { it.text.endsWith(".") }, c3.map { it.text.takeLast(3) }.toString())

    println("\n$pass passed, $fail failed")
    if (fail > 0) System.exit(1)
}
