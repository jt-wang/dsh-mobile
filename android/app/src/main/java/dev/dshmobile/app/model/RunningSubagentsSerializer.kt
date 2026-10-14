package dev.dshmobile.app.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Optional v1 display hint only. Unsupported values never alter main-turn execution semantics. */
internal object RunningSubagentsSerializer : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("RunningSubagents", PrimitiveKind.INT)
    override fun deserialize(decoder: Decoder): Int {
        val primitive = (decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive ?: return 0
        return if (primitive.isString) 0 else primitive.intOrNull?.takeIf { it >= 0 } ?: 0
    }
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value.coerceAtLeast(0))
}
