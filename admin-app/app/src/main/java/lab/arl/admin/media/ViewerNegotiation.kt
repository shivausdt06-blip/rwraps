package lab.arl.admin.media

/**
 * Admin is the WebRTC offerer (SCREEN). Applying a Target offer after a local
 * offer is already set is glare and aborts SDP — ignore the late remote offer.
 */
object ViewerNegotiation {
    fun shouldSendLocalOffer(localOfferSent: Boolean, remoteDescriptionSet: Boolean): Boolean =
        !localOfferSent && !remoteDescriptionSet

    fun shouldApplyRemoteOffer(localOfferSent: Boolean): Boolean = !localOfferSent

    fun shouldAttachRemoteTrack(hasRenderer: Boolean, hasTrack: Boolean): Boolean =
        hasRenderer && hasTrack
}
