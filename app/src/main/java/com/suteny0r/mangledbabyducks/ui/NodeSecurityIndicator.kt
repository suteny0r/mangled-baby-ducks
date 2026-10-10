package com.suteny0r.mangledbabyducks.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.suteny0r.mangledbabyducks.ui.theme.IosGreen
import com.suteny0r.mangledbabyducks.ui.theme.IosOrange
import com.suteny0r.mangledbabyducks.ui.theme.IosRed

/**
 * Port of NodeSecurityIndicator.swift (+ SignedNodeIcon / VerifiedContactIcon): which security
 * glyph a node row shows beside the node's name.
 *
 * Firmware 2.8 introduced signed NodeInfo broadcasts, and whether the radio verified a node's
 * signature says more about that node's security than whether a public key happens to be on
 * file. Rows for nodes on 2.8 or newer therefore show signing state instead of the PKI lock.
 * Nodes on older firmware, or with no reported version, keep the locks. A key mismatch is a
 * real warning at any version and is never hidden.
 *
 * iOS draws a custom "radio with a shield badge" for SIGNED and SF's filled person-shield for
 * VERIFIED; the closest Material glyphs are the shield-check and the person-in-shield.
 */
enum class NodeSecurityIndicator(val icon: ImageVector, val tint: Color) {
    /** 2.8+: the user verified this node's key in person (contact QR exchange). Strongest. */
    VERIFIED(Icons.Filled.AdminPanelSettings, IosGreen),
    /** 2.8+: the node's NodeInfo broadcast carried an XEdDSA signature the radio verified. */
    SIGNED(Icons.Filled.VerifiedUser, IosGreen),
    /** Pre-2.8 or unknown firmware: a public key is on file and matches. */
    PUBLIC_KEY(Icons.Filled.Lock, IosGreen),
    /** Pre-2.8 or unknown firmware: no public key on file; DMs use the channel key. */
    SHARED_KEY(Icons.Filled.LockOpen, IosOrange),
    /** Any version: the node's latest key does not match the stored one. Always shown. */
    KEY_MISMATCH(Icons.Filled.KeyOff, IosRed);

    companion object {
        /**
         * True when the reported firmware version is known and is 2.8.0 or newer. Strict on
         * unknown: a node with no reported version keeps the familiar locks rather than being
         * credited with signing support it may not have.
         */
        fun supportsSigning(firmwareVersion: String?): Boolean {
            if (firmwareVersion.isNullOrBlank()) return false
            val parts = firmwareVersion.trim().split('.')
            val major = parts.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: return false
            val minor = parts.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            return major > 2 || (major == 2 && minor >= 8)
        }

        /** Decides the indicator for one row from stored fields alone. */
        fun status(
            firmwareVersion: String?,
            pkiEncrypted: Boolean,
            keyMatch: Boolean,
            signed: Boolean = false,
            verified: Boolean = false,
            isOwnNode: Boolean = false,
        ): NodeSecurityIndicator {
            // A stored key that stopped matching is a warning regardless of firmware version,
            // most of all for a contact the user personally verified.
            if (pkiEncrypted && !keyMatch) return KEY_MISMATCH
            // The connected radio is the user's own device: they hold its key, so its identity
            // needs no third-party verification.
            if (verified || isOwnNode) return VERIFIED
            // Every 2.8 node signs its broadcasts, and a signature the radio has already
            // verified says so outright, which is what a node reporting no version still tells us.
            if (signed || supportsSigning(firmwareVersion)) return SIGNED
            return if (pkiEncrypted) PUBLIC_KEY else SHARED_KEY
        }
    }
}
