package io.github.jqssun.airplay.bridge

interface RaopCallbackHandler {
    fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean)
    fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean)
    fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float)
    // airplay volume in dB: -30..0, -144 = mute
    fun onClientVolume(db: Float)
    // /info initialVolume
    fun onClientVolumeQuery(): Float
    fun onAudioTeardown()
    fun onConnectionInit()
    fun onConnectionDestroy()
    fun onConnectionReset(reason: Int)
    // client heartbeat (POST /feedback), about every 2 s while a client is connected
    fun onConnectionFeedback()
    // a client completed PIN pairing; its Ed25519 key (base64) is trusted without a PIN from now on
    fun onClientRegistered(publicKey: String, name: ByteArray)
    fun onDisplayPin(pin: String)
    fun onMetadata(data: ByteArray)
    fun onCoverArt(data: ByteArray)
    fun onProgress(start: Long, curr: Long, end: Long)
    fun onDacpId(dacpId: String, activeRemote: String)
    fun onMirrorRunning(running: Boolean)
    // video (hls), distinct from mirroring and raop audio
    fun onVideoPlay(location: String, startPositionSeconds: Float)
    fun onVideoScrub(positionSeconds: Float)
    fun onVideoRate(rate: Float)
    fun onVideoStop()
    // fired per sender GET /playback-info poll; polling starts before /play
    fun onVideoSessionPoll()
}
