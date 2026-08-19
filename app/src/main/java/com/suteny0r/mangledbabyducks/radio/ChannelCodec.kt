package com.suteny0r.mangledbabyducks.radio

import android.util.Base64
import com.google.protobuf.ByteString
import com.suteny0r.mangledbabyducks.db.ChannelEntity
import org.meshtastic.proto.AppOnlyProtos
import org.meshtastic.proto.ChannelProtos
import org.meshtastic.proto.ConfigProtos

/**
 * Encode/decode the meshtastic.org/e/# channel-share URL: a URL-safe base64
 * ChannelSet (channel settings + LoRa config), the same format the QR codes carry.
 */
object ChannelCodec {

    const val URL_PREFIX = "https://meshtastic.org/e/#"

    fun toUrl(channels: List<ChannelEntity>, lora: ConfigProtos.Config.LoRaConfig?): String {
        val set = AppOnlyProtos.ChannelSet.newBuilder()
        channels.sortedBy { it.index }.forEach { ch ->
            set.addSettings(
                ChannelProtos.ChannelSettings.newBuilder()
                    .setName(ch.name ?: "")
                    .apply { ch.psk?.let { setPsk(ByteString.copyFrom(it)) } }
                    .build()
            )
        }
        lora?.let { set.setLoraConfig(it) }
        val encoded = Base64.encodeToString(
            set.build().toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        return URL_PREFIX + encoded
    }

    fun fromUrl(url: String): AppOnlyProtos.ChannelSet? {
        val fragment = url.trim().substringAfter("/e/#", "").ifEmpty { return null }
        return runCatching {
            AppOnlyProtos.ChannelSet.parseFrom(
                Base64.decode(fragment, Base64.URL_SAFE or Base64.NO_PADDING),
            )
        }.getOrNull()
    }
}
