package info.nightscout.androidaps.plugins.pump.omnipod.dash.driver.comm.session

import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.plugins.pump.omnipod.dash.driver.comm.exceptions.MessageIOException
import info.nightscout.androidaps.utils.extensions.toHex
import java.nio.ByteBuffer

enum class EapCode(val code: Byte) {
    REQUEST(1),
    RESPONSE(2),
    SUCCESS(3),
    FAILURE(4);

    companion object {

        fun byValue(value: Byte): EapCode =
            EapCode.values().firstOrNull { it.code == value }
                ?: throw IllegalArgumentException("Unknown EAP-AKA attribute type: $value")
    }
}

data class EapMessage(
    val code: EapCode,
    val identifier: Byte,
    val attributes: Array<EapAkaAttribute>,
) {

    fun toByteArray(): ByteArray {

        val serializedAttributes = attributes.flatMap { it.toByteArray().asIterable() }
        val joinedAttributes = serializedAttributes.toTypedArray().toByteArray()

        val attrSize = joinedAttributes.size
        if (attrSize == 0) {
            return byteArrayOf(code.code, identifier, 0, 4)
        }
        val totalSize = HEADER_SIZE + attrSize

        var bb = ByteBuffer
            .allocate(totalSize)
            .put(code.code)
            .put(identifier)
            .put(((totalSize ushr 1) and 0XFF).toByte())
            .put((totalSize and 0XFF).toByte())
            .put(AKA_PACKET_TYPE)
            .put(SUBTYPE_AKA_CHALLENGE)
            .put(byteArrayOf(0, 0))
            .put(joinedAttributes)

        val ret = bb.array()
        return ret.copyOfRange(0, ret.size)
    }

    companion object {

        private const val HEADER_SIZE = 8
        private const val SUBTYPE_AKA_CHALLENGE = 1.toByte()
        private const val AKA_PACKET_TYPE = 0x17.toByte()

        fun parse(aapsLogger: AAPSLogger, payload: ByteArray): EapMessage {
            if (payload.size < 4) {
                throw MessageIOException("Invalid eap payload: ${payload.toHex()}")
            }
            val totalSize = (payload[2].toInt() shl 1) or payload[3].toInt()
            if (totalSize > payload.size) {
                throw MessageIOException("Invalid eap payload. Too short: ${payload.toHex()}")
            }
            if (payload.size == 4) { // SUCCESS/FAILURE
                return EapMessage(
                    code = EapCode.byValue(payload[0]),
                    identifier = payload[1],
                    attributes = arrayOf()
                )
            }
            if (totalSize > 0 && payload[4] != AKA_PACKET_TYPE) {
                throw MessageIOException("Invalid eap payload. Expected AKA packet type: ${payload.toHex()}")
            }
            val attributesPayload = payload.copyOfRange(8, totalSize)
            aapsLogger.debug(LTag.PUMPBTCOMM, "parsing EAP payload: ${payload.toHex()}")
            return EapMessage(
                code = EapCode.byValue(payload[0]),
                identifier = payload[1],
                attributes = EapAkaAttribute.parseAttributes(aapsLogger, attributesPayload).toTypedArray()
            )
        }
    }
}
