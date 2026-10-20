package dev.dshmobile.app.model

import kotlinx.serialization.Serializable

@Serializable data class AttachmentRef(val id: String, val name: String, val size: Long, val mime: String, val kind: String)

/** A corrupt history ref is discarded independently, not promoted into a snapshot failure. */
object AttachmentRefsSerializer : kotlinx.serialization.KSerializer<List<AttachmentRef>> {
    override val descriptor = kotlinx.serialization.builtins.ListSerializer(AttachmentRef.serializer()).descriptor
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: List<AttachmentRef>) =
        encoder.encodeSerializableValue(kotlinx.serialization.builtins.ListSerializer(AttachmentRef.serializer()), value)
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): List<AttachmentRef> {
        val input = decoder as kotlinx.serialization.json.JsonDecoder
        val array = input.decodeJsonElement() as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.take(100).mapNotNull { runCatching { input.json.decodeFromJsonElement(AttachmentRef.serializer(), it) }.getOrNull() }
    }
}
@Serializable data class QueueItem(val id: String, val text: String, val delivery: String, val requestId: String? = null, @Serializable(with = AttachmentRefsSerializer::class) val attachments: List<AttachmentRef> = emptyList())
@Serializable data class QuestionOption(val label: String, val description: String? = null, val recommended: Boolean = false)
@Serializable data class SessionModel(val provider: String, val id: String, val reasoningEffort: String? = null) {
    fun selection() = ModelSelection(provider, id, reasoningEffort)
}
@Serializable data class ResolvedQuestion(val callId: String, val outcome: String, val answers: List<AnswerItem>)
@Serializable data class QuestionItem(val id: String, val question: String, val header: String? = null, val detail: String? = null,
    val options: List<QuestionOption> = emptyList(), val multiSelect: Boolean = false, val allowFreeText: Boolean = false)
@Serializable data class PendingQuestion(val callId: String, val state: String, val questions: List<QuestionItem>)
@Serializable data class AnswerItem(val id: String, val selected: List<String>, val custom: String? = null)
@Serializable data class QuestionAnswer(val answers: List<AnswerItem>)
@Serializable data class ModelSelection(val provider: String, val model: String, val reasoningEffort: String? = null)
@Serializable data class ReasoningEffort(val id: String, val name: String)
@Serializable data class CatalogModel(val provider: String, val id: String, val name: String, val supportsImages: Boolean? = null,
    val reasoningEfforts: List<ReasoningEffort> = emptyList())
@Serializable data class ModelCatalog(val items: List<CatalogModel>, val default: ModelSelection? = null)

/** Local URI metadata only; upload bytes are streamed and never written into chat state. */
@Serializable data class DraftAttachment(val localId: String, val uri: String, val name: String, val size: Long, val mime: String,
    val uploadId: String? = null, val expiresAt: Long = 0, val temporary: Boolean = false)
data class UploadState(val attachment: DraftAttachment, val progress: Int = 0, val uploading: Boolean = false, val error: Boolean = false)

object PhoneRules {
    const val FILE_LIMIT = 20L * 1024 * 1024
    const val MESSAGE_LIMIT = 200L * 1024 * 1024
    const val ATTACHMENT_LIMIT = 20
    const val ANSWER_BYTES = 32768
    fun boundedAnswerText(text: String): String {
        val result = StringBuilder(); var bytes = 0
        val points = text.codePoints().iterator()
        while (points.hasNext()) {
            val point = points.nextInt()
            if (point == 0) continue
            val chars = String(Character.toChars(point)); val count = chars.toByteArray(Charsets.UTF_8).size
            if (bytes + count > ANSWER_BYTES) break
            result.append(chars); bytes += count
        }
        return result.toString()
    }
    val imageMimes = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    fun uploadMime(mime: String?) = mime?.takeIf { it in imageMimes || it in setOf("application/pdf", "text/plain", "application/octet-stream") } ?: "application/octet-stream"
    fun canAddAttachment(sizes: List<Long>, size: Long) = size in 1..FILE_LIMIT && sizes.size < ATTACHMENT_LIMIT && sizes.sum() + size <= MESSAGE_LIMIT
    fun validTitle(title: String) = title.isNotBlank() && title.toByteArray(Charsets.UTF_8).size <= 256 && title.none { it.isISOControl() }
    fun validAnswer(pending: PendingQuestion, answer: QuestionAnswer, allowSkip: Boolean = false): Boolean {
        if (answer.answers.size != pending.questions.size || answer.answers.map { it.id }.distinct().size != answer.answers.size) return false
        return pending.questions.all { q ->
            val a = answer.answers.singleOrNull { it.id == q.id } ?: return@all false
            a.selected.size <= 100 && a.selected.distinct().size == a.selected.size &&
                a.selected.all { s -> q.options.any { it.label == s } && s.toByteArray().size <= 4096 } &&
                (q.multiSelect || a.selected.size + (if (a.custom != null) 1 else 0) <= 1) &&
                (a.custom == null || (q.allowFreeText && a.custom.isNotBlank() && a.custom.toByteArray().size <= 32768 && '\u0000' !in a.custom)) &&
                (allowSkip || a.selected.isNotEmpty() || !a.custom.isNullOrBlank())
        }
    }
    fun canMutate(state: MobileState) = state.connection == ConnectionState.ONLINE && !state.busy && !state.snapshotStale && state.pending == null &&
        state.snapshot?.let { s -> s.session.canExecute && state.workspaces.any { it.id == s.session.workspaceId && it.canExecute } } == true
}
