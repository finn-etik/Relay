package dev.field.lore

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Runs the real models through LORE's own [Llm] wrapper and prompt, on the
 * build machine's processor (LiteRT-LM has a desktop build with the same
 * API and engine as the Android one). It proves what no fixture can: that
 * the catalogue's files load in this runtime version, that streaming, stop
 * and the context setting work as [Llm] assumes, and what the answers to a
 * sourced question actually look like. Phone GPUs are not covered.
 *
 * Needs the network and several GB of downloads: only with `LORE_LIVE=1`.
 */
class ModelLiveTest {
    private val live = System.getenv("LORE_LIVE") == "1"
    private val report = File("build/lore-live.txt")

    private fun note(line: String) {
        report.parentFile?.mkdirs()
        report.appendText(line + "\n")
    }

    private fun fetch(url: String, to: File) {
        to.parentFile?.mkdirs()
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 120_000
        c.inputStream.use { input -> to.outputStream().use { input.copyTo(it, 1 shl 20) } }
    }

    private val sources = listOf(
        Source(
            Origin.PACK, "Marie Curie", "Early life",
            "Maria Salomea Skłodowska was born in Warsaw, in Congress Poland in the Russian Empire, on 7 November 1867, " +
                "the fifth and youngest child of well-known teachers Bronisława and Władysław Skłodowski.",
            "wikipedia\tMarie Curie",
        ),
        Source(
            Origin.NOTE, "abisko-trip.md", "Packing",
            "For the Abisko trip in March: bring the blue tent, two stoves and the down jackets. Train leaves Stockholm at 18:10.",
            "content://notes/abisko-trip.md",
        ),
    )
    private val system = Ask.system("Sunday 22 November 2026")

    private fun exercise(id: String, mustBeRight: Boolean) {
        val model = Catalog.model(id)
        val file = File("build/models/${model.file}")
        if (!file.exists()) fetch(model.url, file)
        val cache = File("build/litert-cache").apply { mkdirs() }.path

        var at = System.nanoTime()
        fun lap(): Long = ((System.nanoTime() - at) / 1_000_000).also { at = System.nanoTime() }

        val llm = try {
            Llm.load(file.path, cache, model.maxTokens, gpu = false)
        } catch (e: Throwable) {
            note("model $id: DID NOT LOAD with maxTokens=${model.maxTokens}: $e")
            Llm.load(file.path, cache, null, gpu = false)
        }
        note("model $id: ${Format.bytes(file.length())}, loaded in ${lap()} ms (maxTokens=${model.maxTokens})")

        try {
            // A sourced question with one earlier exchange, as the app sends it.
            var updates = 0
            var grows = true
            var length = 0
            val first = llm.answer(system, listOf("Hello" to "Hello. What would you like to know?"), Ask.prompt("Where and when was Marie Curie born?", sources)) { text ->
                updates++
                if (text.length < length) grows = false
                length = text.length
            }
            note("  [sourced, ${lap()} ms, $updates updates, grows=$grows] ${Ask.clean(first)}")
            assertTrue("$id: empty answer", Ask.clean(first).isNotEmpty())
            assertTrue("$id: no streaming", updates > 0 && grows)

            val second = Ask.clean(llm.answer(system, emptyList(), Ask.prompt("Which tent should I bring to Abisko, and when does the train leave?", sources)) {})
            note("  [from the note, ${lap()} ms] $second")

            val third = Ask.clean(llm.answer(system, emptyList(), Ask.prompt("How deep is Lake Vänern?", sources)) {})
            note("  [not in the sources, ${lap()} ms] $third")

            val fourth = Ask.clean(llm.answer(system, emptyList(), Ask.prompt("What is the capital of Sweden?", emptyList())) {})
            note("  [no sources, ${lap()} ms] $fourth")

            val titles = Ask.titlesFrom(Ask.clean(llm.answer(Ask.TITLE_SYSTEM, emptyList(), Ask.titlePrompt("Why is the sky blue?")) {}))
            note("  [article titles for 'Why is the sky blue?', ${lap()} ms] $titles")

            // STOP, pressed from another thread shortly after writing starts.
            val started = CountDownLatch(1)
            thread {
                started.await()
                Thread.sleep(300)
                llm.stop()
            }
            val partial = llm.answer(system, emptyList(), "Write a very long, detailed essay about the history of Sweden.") { started.countDown() }
            started.countDown()
            note("  [stopped after ${lap()} ms] ${partial.length} characters written")

            // And it still answers afterwards.
            val after = Ask.clean(llm.answer(system, emptyList(), "Say OK.") {})
            note("  [after the stop, ${lap()} ms] $after")
            assertTrue("$id: nothing after a stop", after.isNotEmpty())

            if (mustBeRight) {
                assertTrue("$id: wrong place or year: $first", first.contains("Warsaw") && first.contains("1867"))
                assertTrue("$id: missed the note: $second", second.contains("blue", ignoreCase = true) && second.contains("18:10"))
                assertTrue("$id: wrong capital: $fourth", fourth.contains("Stockholm"))
            }
        } finally {
            llm.close()
        }
    }

    @Test fun theSmallestModelRuns() {
        assumeTrue(live)
        exercise("qwen3-0.6b", mustBeRight = false)
    }

    @Test fun theDefaultModelAnswersFromItsSources() {
        assumeTrue(live)
        exercise("gemma-4-e2b", mustBeRight = true)
    }
}
