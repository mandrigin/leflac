package app.nogarbo.leflac.service

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.nogarbo.leflac.util.SimpleFFT
import app.nogarbo.leflac.util.FastFFT
import java.nio.ByteBuffer
import java.io.File
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest
import android.provider.OpenableColumns
import android.database.Cursor

class AudioTrackAnalyzer(private val context: Context) {
    
    data class AnalysisFrame(
        val timestampMs: Long,
        val state: SpectrumState
    )

    companion object {
        // Shared buffers for real-time visualization (unsafe but fast for this toy)
        @Volatile var audioAmplitudes: ByteArray = ByteArray(0)
        @Volatile var audioFrequencies: IntArray = IntArray(0)

        // Epic detection tuning (validated against on-device caches)
        private const val MIN_EPIC_DYNAMICS = 0.30f
        private const val MAX_EPIC_SEGMENTS = 4

        // Mixes (>= 20 min) get cheap treatment: decimated analysis, no drama
        private const val MIX_DURATION_MS = 1_200_000L
        private const val MIX_DECIMATION = 8
    }

    // Public Cache Check
    fun checkCache(uri: Uri): List<AnalysisFrame>? {
        val cacheKey = generateCacheKey(uri) ?: return null
        val rawTimeline = loadFromCache(cacheKey)
        if (rawTimeline != null) {
            saveProfileIfMissing(uri, rawTimeline)
            // Apply post-processing to cached data for consistency
            val improvedTimeline = applyEpicDetection(rawTimeline)
            return smoothTimeline(improvedTimeline, isFinal = true)
        }
        return null
    }

    suspend fun analyze(uri: Uri, forceRegenerate: Boolean = false, onPartialResults: ((List<AnalysisFrame>) -> Unit)? = null): List<AnalysisFrame> {
        val startTime = System.currentTimeMillis()
        // 1. Check Cache
        val cacheKey = generateCacheKey(uri)
        var rawTimeline: List<AnalysisFrame>? = null
        
        if (!forceRegenerate && cacheKey != null) {
            val cached = loadFromCache(cacheKey)
            if (cached != null) {
                android.util.Log.d("FLAC_ANALYSIS", "Cache Hit! Loaded ${cached.size} raw frames for $uri")
                rawTimeline = cached
            }
        }
        
        // If no cache, perform analysis
        if (rawTimeline == null) {
            rawTimeline = performAnalysis(uri, onPartialResults)
            
            // Save RAW timeline to cache immediately
             if (cacheKey != null && !rawTimeline.isNullOrEmpty()) {
                 saveToCache(cacheKey, rawTimeline)
             }
        }
        
        val timeline = rawTimeline ?: return emptyList()
        saveProfileIfMissing(uri, timeline, replace = forceRegenerate)

        // --- POST PROCESSING (ALWAYS RUN LIVE) ---
        // 2.2. Song-relative epic detection
        val improvedTimeline = applyEpicDetection(timeline)
        
        // 2.3. Smooth
        val result = smoothTimeline(improvedTimeline, isFinal = true)
        
        val durationMs = System.currentTimeMillis() - startTime
        android.util.Log.i("FLAC_PERF", "Analysis Finished for $uri took ${durationMs}ms")
        
        return result
    }

    private suspend fun performAnalysis(uri: Uri, onPartialResults: ((List<AnalysisFrame>) -> Unit)?): List<AnalysisFrame> {
        val timeline = ArrayList<AnalysisFrame>()
        android.util.Log.i("FLAC_PERF", "Starting Analysis for $uri (Regen=true)")
        
        val extractor = MediaExtractor()
        var reachedEndOfStream = false
        
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
        
        // Find Audio Track
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
             val f = extractor.getTrackFormat(i)
             val mime = f.getString(MediaFormat.KEY_MIME)
             if (mime?.startsWith("audio/") == true) {
                 trackIndex = i
                 format = f
                 break
             }
        }
        
        if (trackIndex < 0 || format == null) {
            extractor.release()
            return emptyList()
        }
        
        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()
        
        var codec: MediaCodec? = null
        try {
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            
            val channelCount = (format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: 2)
                .coerceAtLeast(1)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE) ?: 44_100
            val frameStride = channelCount * 2
            
            val info = MediaCodec.BufferInfo()
            var isEOS = false
            var outputDone = false
            
            // PCM Accumulation
            val fftSize = 1024
            val bufferAccumulator = FloatArray(fftSize)
            var bufferIndex = 0
            var decodedPcmFrames = 0L

            // Mixes (>= 20 min) only need "basic spectral": analyze every
            // Nth FFT window instead of all of them. ~8x less CPU and cache,
            // still plenty of temporal resolution for the visualizers.
            val durationUs = try {
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            } catch (e: Exception) { 0L }
            val windowStride = if (durationUs >= MIX_DURATION_MS * 1000L) MIX_DECIMATION else 1
            var windowCounter = 0
            
            // REUSABLE BUFFERS
            val fftComplex = FloatArray(fftSize * 2)
            val fftMags = FloatArray(fftSize / 2)
            val fftNorm = FloatArray(fftSize / 2)
            var pcmChunk = ByteArray(4096 * 4) 
            
            val analysisLogic = AudioAnalysisLogic()
            analysisLogic.reset()
            
            var frameCount = 0
            
            // SUSPEND AND CANCELLATION CHECK
            // We use withContext or yield() to check for cancellation
            while (!outputDone) {
                // ACTIVE CHECK: Throws CancellationException if job cancelled
                kotlinx.coroutines.yield()
                
                if (!isEOS) {
                    val inputIndex = codec.dequeueInputBuffer(500)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                             val size = extractor.readSampleData(inputBuffer, 0)
                             if (size < 0) {
                                 codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                 isEOS = true
                             } else {
                                 val time = extractor.sampleTime
                                 codec.queueInputBuffer(inputIndex, 0, size, time, 0)
                                 extractor.advance()
                             }
                        }
                    }
                }

                // 2. Output
                var outputIndex = codec.dequeueOutputBuffer(info, 500)
                while (outputIndex >= 0) {
                     // ... same processing logic ...
                     frameCount++
                     
                     val outputBuffer = codec.getOutputBuffer(outputIndex)
                     if (outputBuffer != null) {
                         // Read PCM
                         if (pcmChunk.size < info.size) {
                             pcmChunk = ByteArray(info.size + 1024)
                         }
                         outputBuffer.position(info.offset)
                         outputBuffer.limit(info.offset + info.size)
                         outputBuffer.get(pcmChunk, 0, info.size)
                         outputBuffer.clear()
                         
                         var i = 0
                         while (i <= info.size - frameStride) { 
                             // Downmix every decoded channel. Reading only the
                             // left channel made workout profiles depend on a
                             // track's stereo placement.
                             var sample = 0f
                             for (channel in 0 until channelCount) {
                                 val offset = i + channel * 2
                                 val low = pcmChunk[offset].toInt() and 0xFF
                                 val high = pcmChunk[offset + 1].toInt()
                                 val value = (high shl 8) or low
                                 sample += value / 32768.0f
                             }
                             sample /= channelCount

                             bufferAccumulator[bufferIndex] = sample
                             bufferIndex++
                             decodedPcmFrames++
                             
                             if (bufferIndex >= fftSize) {
                                 bufferIndex = 0
                                 windowCounter++
                                 if (windowCounter % windowStride != 0) {
                                     i += frameStride
                                     continue
                                 }
                                  // Analyze...
                                  FastFFT.computeMagnitudes(bufferAccumulator, fftComplex, fftMags)

                                 for (k in fftMags.indices) {
                                     fftNorm[k] = fftMags[k] / 5.0f
                                 }

                                  val state = analysisLogic.processMagnitudes(fftNorm, sampleRate)
                                 // Codec PTS belongs to the whole output buffer;
                                 // sample count gives each FFT window its real
                                 // position and stabilizes tempo estimation.
                                 val timeMs = decodedPcmFrames * 1_000L / sampleRate

                                 timeline.add(AnalysisFrame(timeMs, state))
                                 
                                 if (onPartialResults != null && timeline.size % 200 == 0) {
                                     // Check cancellation again before expensive copy
                                     kotlinx.coroutines.yield()
                                     // Apply epic detection to partial results for consistency
                                     val partialTimeline = ArrayList(timeline)
                                     val improvedPartial = applyEpicDetection(partialTimeline)
                                     onPartialResults(smoothTimeline(improvedPartial, isFinal = false))
                                 }
                             }
                             
                             i += frameStride
                         }
                     }
                     codec.releaseOutputBuffer(outputIndex, false)
                     
                     if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                         outputDone = true
                         reachedEndOfStream = true
                     }
                     outputIndex = codec.dequeueOutputBuffer(info, 500)
                }
            }
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            android.util.Log.w("FLAC_ANALYSIS", "Analysis CANCELLED for $uri")
            // RETHROW so we don't save cache
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            // Do NOT rethrow generic exceptions during decoding, but do NOT save cache.
        } finally {
            try { codec?.stop(); codec?.release() } catch(e: Exception){}
            extractor.release()
        }
        
        // Never cache/profile a decoder prefix as if it were a whole song.
        if (!reachedEndOfStream || timeline.isEmpty()) return emptyList()

        return timeline
    }

    /** Activity profile derived from the cached spectral pass. */
    private fun saveProfileIfMissing(
        uri: Uri,
        frames: List<AnalysisFrame>,
        replace: Boolean = false
    ) {
        try {
            val key = uri.toString()
            if (!replace && app.nogarbo.leflac.data.TrackProfileStore.has(context, key)) return
            val profile = app.nogarbo.leflac.data.WorkoutProfileAnalyzer.analyze(
                frames.map { frame ->
                    val state = frame.state
                    app.nogarbo.leflac.data.WorkoutFrame(
                        timestampMs = frame.timestampMs,
                        // The kick body's upper harmonics live in the adjacent
                        // bass band; blend both instead of trusting one ~43 Hz bin.
                        kick = (0.65f * state.kick + 0.35f * state.bassGuitar)
                            .coerceIn(0f, 1f),
                        snare = state.snare,
                        cymbals = state.cymbals,
                        vocal = state.vocal,
                        synth = state.synth
                    )
                }
            ) ?: return
            app.nogarbo.leflac.data.TrackProfileStore.put(
                context,
                key,
                profile
            )
            android.util.Log.i(
                "FLAC_PROFILE",
                "$key e=${profile.e} bpm=${profile.bpm} rhythm=${profile.r} impact=${profile.k}"
            )
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun generateCacheKey(uri: Uri): String? {
        try {
            val projection = arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME)
            var size = -1L
            var name = uri.lastPathSegment ?: "unknown"
            
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
                    
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1) name = cursor.getString(nameIdx)
                }
            }
            
            val rawKey = "${uri.toString()}_${name}_$size"
            return md5(rawKey)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    // Simple MD5
    private fun md5(s: String): String {
        val CACHE_VERSION = "delta_v9_GZ" // FFT/timestamps/downmix + workout profile reset
        val versioned = "$s|$CACHE_VERSION"
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(versioned.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    private fun loadFromCache(key: String): List<AnalysisFrame>? {
        try {
            val cacheFile = File(context.cacheDir, "analysis_$key.json.gz")
            if (!cacheFile.exists()) return null
            val json = java.util.zip.GZIPInputStream(cacheFile.inputStream()).bufferedReader().readText()
            val type = object : TypeToken<List<AnalysisFrame>>() {}.type
            return Gson().fromJson(json, type)
        } catch (e: Exception) {
            return null
        }
    }

    private fun saveToCache(key: String, frames: List<AnalysisFrame>) {
        try {
            val cacheFile = File(context.cacheDir, "analysis_$key.json.gz")
            val json = Gson().toJson(frames)
            java.util.zip.GZIPOutputStream(cacheFile.outputStream()).bufferedWriter().use { it.write(json) }
            android.util.Log.d("FLAC_ANALYSIS", "Saved cache: ${cacheFile.absolutePath} (${cacheFile.length()} bytes gz)")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** One-time sweep: the v7 plain-JSON caches are superseded and huge. */
    fun purgeLegacyCaches() {
        try {
            context.cacheDir.listFiles()?.forEach {
                if (it.name.startsWith("analysis_") && it.name.endsWith(".json")) it.delete()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun smoothTimeline(frames: List<AnalysisFrame>, isFinal: Boolean): List<AnalysisFrame> {
        if (frames.isEmpty()) return emptyList()
        
        // We act on a copy to avoid mutating the accumulation buffer if it's shared (though we usually copy before calling this)
        // But since 'frames' is a List (read-only interface), we map to new objects.
        val smoothed = ArrayList<AnalysisFrame>(frames.size)
        val minDurationMs = 0L // Disabled per user request (was 2000L/5000L). Raw analysis.
        
        var i = 0
        while (i < frames.size) {
            val frame = frames[i]
            if (!frame.state.isDramatic) {
                smoothed.add(frame)
                i++
            } else {
                // Start of a dramatic block
                val startIndex = i
                var endIndex = i
                
                // Find end of block
                while (endIndex < frames.size && frames[endIndex].state.isDramatic) {
                    endIndex++
                }
                
                // Block is [startIndex, endIndex - 1]
                val startTime = frames[startIndex].timestampMs
                val endTime = frames[endIndex - 1].timestampMs
                val duration = endTime - startTime
                
                // Check if we should keep it
                // We ALWAYS enforce the minimum duration, even for the last block during streaming.
                // This means the "Fire" will be delayed by 5 seconds, but it prevents 
                // false positives that disappear later (scaring the user).
                val keep = duration >= minDurationMs
                
                // Apply decision
                for (k in startIndex until endIndex) {
                    val original = frames[k]
                    if (keep) {
                        smoothed.add(original)
                    } else {
                        // Suppress drama
                        smoothed.add(original.copy(state = original.state.copy(isDramatic = false)))
                    }
                }
                
                i = endIndex
            }
        }
        return smoothed
    }

    /**
     * Song-relative epic detection, replacing the old absolute-threshold +
     * look-ahead pipeline (which marked entire loudness-war masters as
     * dramatic). The raw per-frame isDramatic flags are IGNORED; instead the
     * stored instrument levels are judged against the song's own energy
     * distribution:
     *
     * 1. Energy curve (kick/snare/cymbal blend) smoothed over ~1s.
     * 2. Song statistics: P25 = the song's floor, P90 = its peak level.
     * 3. Dynamics gate: if P90 - P25 < 0.30 the song is flat — loud all the
     *    time means loud is not special — and gets NO epic moments at all.
     * 4. Hysteresis segmentation between entry (floor + 0.8 * range) and
     *    exit (floor + 0.55 * range), gaps <= 6s merged, segments < 10s
     *    dropped, capped at the 4 longest.
     *
     * Validated against the on-device analysis caches: Sleep Token epics
     * keep their climaxes, flat indie masters drop from ~90%+ dramatic to 0.
     */
    private fun applyEpicDetection(frames: List<AnalysisFrame>): List<AnalysisFrame> {
        if (frames.isEmpty()) return frames
        val n = frames.size

        // 1. Smoothed energy curve (centered ~1s window, two-pointer)
        val ts = LongArray(n) { frames[it].timestampMs }
        val raw = FloatArray(n) {
            val s = frames[it].state
            (s.kick * 0.8f + s.snare + s.cymbals * 0.8f) / 2.6f
        }
        val energy = FloatArray(n)
        var left = 0
        var right = 0
        var acc = 0f
        for (i in 0 until n) {
            while (right < n && ts[right] <= ts[i] + 500) { acc += raw[right]; right++ }
            while (ts[left] < ts[i] - 500) { acc -= raw[left]; left++ }
            energy[i] = acc / (right - left)
        }

        // 2. Song-relative statistics
        val sorted = energy.clone().also { it.sort() }
        val p25 = sorted[(0.25 * (n - 1)).toInt()]
        val p90 = sorted[(0.90 * (n - 1)).toInt()]
        val range = p90 - p25

        // 3. Dynamics gate. Mixes never get epic moments: they are long DJ
        // sets where "the loud part" is most of the runtime.
        val epic = BooleanArray(n)
        val isMix = ts[n - 1] >= MIX_DURATION_MS
        if (!isMix && range >= MIN_EPIC_DYNAMICS) {
            val entry = p25 + 0.80f * range
            val exit = p25 + 0.55f * range

            // 4a. Hysteresis segmentation (frame index ranges)
            val segments = mutableListOf<IntArray>() // [startIdx, endIdx] inclusive
            var start = -1
            for (i in 0 until n) {
                if (start < 0 && energy[i] >= entry) {
                    start = i
                } else if (start >= 0 && energy[i] < exit) {
                    segments.add(intArrayOf(start, i - 1))
                    start = -1
                }
            }
            if (start >= 0) segments.add(intArrayOf(start, n - 1))

            // 4b. Merge gaps <= 6s
            val merged = mutableListOf<IntArray>()
            for (seg in segments) {
                val last = merged.lastOrNull()
                if (last != null && ts[seg[0]] - ts[last[1]] <= 6_000L) {
                    last[1] = seg[1]
                } else {
                    merged.add(seg)
                }
            }

            // 4c. Min duration 10s, keep the 4 longest
            val kept = merged
                .filter { ts[it[1]] - ts[it[0]] >= 10_000L }
                .sortedByDescending { ts[it[1]] - ts[it[0]] }
                .take(MAX_EPIC_SEGMENTS)

            // 4d. Onset snap: the smoothed threshold crossing lags the actual
            // hit, so shift each segment start to the strongest novelty peak
            // (positive band-level change) within [-2s, +3s] of the crossing.
            // The drama then begins exactly on the drop.
            if (kept.isNotEmpty()) {
                val novRaw = FloatArray(n)
                for (i in 1 until n) {
                    val a = frames[i - 1].state
                    val b = frames[i].state
                    novRaw[i] = maxOf(b.kick - a.kick, 0f) +
                        maxOf(b.snare - a.snare, 0f) +
                        maxOf(b.cymbals - a.cymbals, 0f) +
                        maxOf(b.vocal - a.vocal, 0f) +
                        maxOf(b.synth - a.synth, 0f) +
                        maxOf(b.bassGuitar - a.bassGuitar, 0f)
                }
                // smooth ~±250ms, same two-pointer trick
                val novelty = FloatArray(n)
                var nl = 0; var nr = 0; var nacc = 0f
                for (i in 0 until n) {
                    while (nr < n && ts[nr] <= ts[i] + 250) { nacc += novRaw[nr]; nr++ }
                    while (ts[nl] < ts[i] - 250) { nacc -= novRaw[nl]; nl++ }
                    novelty[i] = nacc / (nr - nl)
                }
                for (seg in kept) {
                    var lo = seg[0]
                    while (lo > 0 && ts[lo - 1] >= ts[seg[0]] - 2_000L) lo--
                    var hi = seg[0]
                    while (hi < n - 1 && ts[hi + 1] <= ts[seg[0]] + 3_000L) hi++
                    hi = minOf(hi, seg[1])
                    var best = lo
                    for (i in lo..hi) if (novelty[i] > novelty[best]) best = i
                    seg[0] = best
                }
            }

            for (seg in kept) {
                for (i in seg[0]..seg[1]) epic[i] = true
            }
        }

        return frames.mapIndexed { i, frame ->
            if (frame.state.isDramatic != epic[i]) {
                frame.copy(state = frame.state.copy(isDramatic = epic[i]))
            } else {
                frame
            }
        }
    }

}
