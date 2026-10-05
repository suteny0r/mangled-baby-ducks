package com.suteny0r.mangledbabyducks.ui

/**
 * Port of RoutingError.swift: the Routing.Error values the firmware reports, with the
 * label the row shows, the paragraph the status dialog shows, and whether sending the
 * same message again can plausibly work. Colour follows [canRetry] exactly as the Swift
 * `color` property does: gray for none, orange when a retry makes sense, red otherwise.
 */
enum class RoutingError(val code: Int, val display: String, val detail: String, val canRetry: Boolean) {
    NONE(
        0, "Delivered to recipient",
        "The recipient confirmed this message.", false,
    ),
    NO_ROUTE(
        1, "Failed to deliver to mesh",
        "No route to the destination node was found in the mesh. Try again when more nodes are reachable.", true,
    ),
    GOT_NAK(
        2, "Failed to deliver to mesh",
        "A node rejected this message. Try again when the route changes.", true,
    ),
    TIMEOUT(
        3, "Failed to deliver to mesh",
        "No acknowledgment was received in time. Try again when you have better signal or more mesh coverage.", true,
    ),
    NO_INTERFACE(
        4, "No radio interface",
        "The sender has no usable radio interface for this message.", true,
    ),
    MAX_RETRANSMIT(
        5, "Failed to deliver to mesh",
        "No node confirmed this message. Try again when you have better signal or more mesh coverage.", true,
    ),
    NO_CHANNEL(
        6, "Channel/key mismatch",
        "The sender or recipient could not use a matching channel/key for this message.", false,
    ),
    TOO_LARGE(
        7, "Message is too large to send",
        "Shorten the message and send it again.", false,
    ),
    NO_RESPONSE(
        8, "No app response",
        "The destination received the request, but no app or module responded. Try again when the recipient is reachable.", true,
    ),
    DUTY_CYCLE_LIMIT(
        9, "Duty cycle limit",
        "Local airtime limits are temporarily blocking sends. Wait before trying again.", true,
    ),
    BAD_REQUEST(
        32, "Invalid request",
        "The destination rejected this request as invalid.", false,
    ),
    NOT_AUTHORIZED(
        33, "Not authorized",
        "The destination refused this request because it is not authorized.", false,
    ),
    PKI_FAILED(
        34, "Could not send encrypted message",
        "The encrypted send path could not be used. Wait for node info or keys to sync, then try again.", true,
    ),
    PKI_UNKNOWN_PUBKEY(
        35, "Recipient needs your key",
        "The recipient does not know your public key yet. Your node may share its info automatically; try again after it syncs.", true,
    ),
    ADMIN_BAD_SESSION_KEY(
        36, "Admin session expired",
        "The admin session key is missing, expired, or invalid. Request a new session before trying again.", true,
    ),
    ADMIN_PUBLIC_KEY_UNAUTHORIZED(
        37, "Admin key not authorized",
        "The remote node does not authorize your admin key.", false,
    ),
    RATE_LIMIT_EXCEEDED(
        38, "Rate limited",
        "Messages are being sent too quickly. Wait before trying again.", true,
    ),
    PKI_SEND_FAIL_PUBLIC_KEY(
        39, "Recipient key unavailable",
        "Your node does not have the recipient's public key yet. Wait for node info to sync, then try again.", true,
    );

    companion object {
        fun forCode(code: Int): RoutingError? = entries.find { it.code == code }
    }
}
