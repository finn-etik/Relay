package dev.field.lore

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.CountDownLatch

/**
 * The language model, loaded. The only file that touches LiteRT-LM, Google's
 * on-device runtime; everything else sees "text in, text out".
 *
 * One answer at a time: callers run [answer] on a single worker thread.
 */
class Llm private constructor(private val engine: Engine, val gpu: Boolean) {

    @Volatile private var running: Conversation? = null

    /**
     * Writes the reply to [prompt], given the [system] instruction and the
     * earlier exchanges in [history]. Blocks until the reply is complete,
     * stopped or failed (then throws). [onText] receives the reply so far,
     * on a runtime thread.
     */
    fun answer(system: String, history: List<Pair<String, String>>, prompt: String, onText: (String) -> Unit): String {
        val earlier = history.flatMap { (question, reply) -> listOf(Message.user(question), Message.model(reply)) }
        val config = ConversationConfig(
            systemInstruction = Contents.of(system),
            initialMessages = earlier,
            // Low temperature: the job is to report what the sources say, not to be inventive.
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3),
        )
        val conversation = engine.createConversation(config)
        running = conversation
        val text = StringBuilder()
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        try {
            conversation.sendMessageAsync(
                Message.user(prompt),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val piece = message.toString()
                        if (piece.isEmpty()) return
                        val soFar = synchronized(text) { text.append(piece).toString() }
                        onText(soFar)
                    }

                    override fun onDone() = done.countDown()

                    override fun onError(throwable: Throwable) {
                        failure = throwable
                        done.countDown()
                    }
                },
            )
            done.await()
        } finally {
            running = null
            runCatching { conversation.close() }
        }
        val reply = synchronized(text) { text.toString() }
        // A stop arrives as an error from the runtime; what was written until then still counts.
        failure?.let { if (reply.isEmpty()) throw it }
        return reply
    }

    /** Ends the answer being written, if any. Safe from any thread. */
    fun stop() {
        runCatching { running?.cancelProcess() }
    }

    fun close() {
        stop()
        runCatching { engine.close() }
    }

    companion object {
        /**
         * Loads the model file at [path]. Takes seconds (the first time on a
         * GPU, longer: the runtime compiles and caches its programs in
         * [cacheDir]). Throws if the model cannot run on the chosen backend.
         */
        fun load(path: String, cacheDir: String, maxTokens: Int?, gpu: Boolean): Llm {
            val engine = Engine(
                EngineConfig(
                    modelPath = path,
                    backend = if (gpu) Backend.GPU() else Backend.CPU(),
                    maxNumTokens = maxTokens,
                    cacheDir = cacheDir,
                )
            )
            try {
                engine.initialize()
            } catch (e: Throwable) {
                runCatching { engine.close() }
                throw e
            }
            return Llm(engine, gpu)
        }
    }
}
