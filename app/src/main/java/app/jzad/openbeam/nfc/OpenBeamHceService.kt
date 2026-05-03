package app.jzad.openbeam.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import app.jzad.openbeam.OpenBeamPrefs

class OpenBeamHceService : HostApduService() {

    private var selected = false

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return status(0x6A, 0x80)

        return when {
            isSelectAid(commandApdu) -> {
                selected = true
                status(0x90, 0x00)
            }

            selected && isGetToken(commandApdu) -> {
                val token = OpenBeamPrefs.getOrCreateSessionToken(applicationContext)
                token.toByteArray(Charsets.UTF_8) + status(0x90, 0x00)
            }

            else -> status(0x6A, 0x82)
        }
    }

    override fun onDeactivated(reason: Int) {
        selected = false
    }

    private fun isSelectAid(apdu: ByteArray): Boolean {
        val aid = byteArrayOf(
            0xF0.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07
        )

        return apdu.size >= 5 &&
            apdu[0] == 0x00.toByte() &&
            apdu[1] == 0xA4.toByte() &&
            apdu[2] == 0x04.toByte() &&
            apdu[3] == 0x00.toByte() &&
            apdu[4] == aid.size.toByte() &&
            apdu.copyOfRange(5, 5 + aid.size).contentEquals(aid)
    }

    private fun isGetToken(apdu: ByteArray): Boolean {
        return apdu.contentEquals(byteArrayOf(0x80.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x00))
    }

    private fun status(sw1: Int, sw2: Int): ByteArray = byteArrayOf(sw1.toByte(), sw2.toByte())
}
