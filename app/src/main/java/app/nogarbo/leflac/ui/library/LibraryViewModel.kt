package app.nogarbo.leflac.ui.library

import android.app.Application
import android.content.ContentUris
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.nogarbo.leflac.data.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val _folders = MutableStateFlow<List<String>>(emptyList())
    val folders = _folders.asStateFlow()

    private val _mixes = MutableStateFlow<List<AudioTrack>>(emptyList())
    val mixes = _mixes.asStateFlow()

    private val _tracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val tracks = _tracks.asStateFlow()

    // Combined list for Playback Service (so it finds mixes too)
    private val _allTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val allTracks = _allTracks.asStateFlow()
    
    // "Folder" mode vs "Track" mode
    private val _currentFolder = MutableStateFlow<String?>(null)
    val currentFolder = _currentFolder.asStateFlow()

    // Currently "hot" tracks (recency-decayed play score, best first)
    private val _hotTrackIds = MutableStateFlow<List<String>>(emptyList())
    val hotTrackIds = _hotTrackIds.asStateFlow()

    fun enterFolder(folderName: String) {
        _currentFolder.value = folderName
        loadTracks(folderName)
    }

    fun exitFolder() {
        _currentFolder.value = null
    }

    fun scanLibrary() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                
                // Compatibility for API 28 (Pie) which lacks BUCKET_DISPLAY_NAME
                val useBucketId = android.os.Build.VERSION.SDK_INT >= 29
                
                val projection = if (useBucketId) {
                    arrayOf(
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.BUCKET_DISPLAY_NAME,
                        MediaStore.Audio.Media.SIZE,
                        MediaStore.Audio.Media.MIME_TYPE
                    )
                } else {
                     arrayOf(
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.DATA, // Fallback: Absolute Path
                        MediaStore.Audio.Media.SIZE,
                        MediaStore.Audio.Media.MIME_TYPE
                    )
                }
                
                // Only audio files and longer than 1 minute (60,000 ms), unless it's a FLAC file
                val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND (${MediaStore.Audio.Media.DURATION} >= 60000 OR ${MediaStore.Audio.Media.MIME_TYPE} LIKE '%flac%' OR ${MediaStore.Audio.Media.DATA} LIKE '%.flac')"
                val sortOrder = if (useBucketId) "${MediaStore.Audio.Media.BUCKET_DISPLAY_NAME} ASC" else null

                val cursor = getApplication<Application>().contentResolver.query(
                    collection,
                    projection,
                    selection,
                    null,
                    sortOrder
                )

                val tempTracks = mutableListOf<AudioTrack>()
                val tempFolders = mutableSetOf<String>()

                cursor?.use {
                    val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val durationCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    
                    val sizeCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    val mimeCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                    
                    // Column index depends on version
                    val bucketCol = if (useBucketId) 
                        it.getColumnIndexOrThrow(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
                    else 
                        it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                    while (it.moveToNext()) {
                        val id = it.getLong(idCol)
                        val title = it.getString(titleCol)
                        val artist = it.getString(artistCol)
                        val duration = it.getLong(durationCol)
                        val size = it.getLong(sizeCol)
                        val mime = it.getString(mimeCol) ?: "audio/*"
                        
                        var bucket = "Unknown"
                        if (useBucketId) {
                            bucket = it.getString(bucketCol) ?: "Unknown"
                        } else {
                            // Extract folder from path manually
                            val path = it.getString(bucketCol)
                            if (path != null) {
                                val file = java.io.File(path)
                                bucket = file.parentFile?.name ?: "Root"
                            }
                        }

                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                        tempTracks.add(AudioTrack(id, title, artist, duration, uri, bucket, size, mime))
                        tempFolders.add(bucket)
                    }
                }
                
                // Separation Logic: Mixes vs Albums
                // Mix > 20 mins (1,200,000 ms)
                val MIX_THRESHOLD = 20 * 60 * 1000L
                
                val allTracks = tempTracks
                val mixTracks = allTracks.filter { it.duration >= MIX_THRESHOLD }
                val standardTracks = allTracks.filter { it.duration < MIX_THRESHOLD }
                
                val albumFolders = standardTracks.map { it.folderName }.distinct().sorted()
                
                // Update state
                // Update state
                _mixes.value = mixTracks
                _folders.value = albumFolders
                _tracks.value = standardTracks // Only standard tracks shown in folders now
                _allTracks.value = allTracks
                
                // Reconcile play stats with the fresh library (legacy
                // migration + re-keying after MediaStore rescans), then
                // surface the current hot tracks.
                app.nogarbo.leflac.data.PlayStatsStore.reconcile(getApplication(), allTracks)
                _hotTrackIds.value = app.nogarbo.leflac.data.PlayStatsStore.hotTrackIds(getApplication(), standardTracks)

                // Queue Background Analysis — songs only. Mixes are analyzed
                // lazily on play, decimated (no epic detection, basic spectral).
                val repo = app.nogarbo.leflac.data.repository.AnalysisRepository.getInstance(getApplication())
                repo.queueBackground(standardTracks.map { it.uri })
            }
        }
    }
    
    private fun loadTracks(folder: String) {
        // In this simple iteration, we filter the already loaded list
        // Real implementation might query DB again if list is huge
    }
}
