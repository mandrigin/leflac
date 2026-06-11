package app.nogarbo.leflac.service

data class SpectrumState(
    val bass: Float = 0f,
    val mid: Float = 0f,
    val treble: Float = 0f,
    val isDramatic: Boolean = false,
    val isMatrixGlitch: Boolean = false,
    // Debug Metrics
    val avgEnergy: Float = 0f,
    val fluxRatio: Float = 0f,
    val isFullBand: Boolean = false,
    val isFluxDrop: Boolean = false,
    val isLoudEnough: Boolean = false,
    val isHighEndHeavy: Boolean = false,
    // Instruments (7-Band Semantic)
    val kick: Float = 0f,    // 20-80Hz
    val bassGuitar: Float = 0f,    // 80-250Hz
    val snare: Float = 0f,   // 250-2kHz (Spec: Snare/Guitar)
    val guitar: Float = 0f,  // Merged into Snare/Guitar in Spec but kept for compat
    val vocal: Float = 0f,   // 2k-4kHz
    val synth: Float = 0f,   // 4k-8kHz
    val cymbals: Float = 0f  // 6k-22kHz
)
