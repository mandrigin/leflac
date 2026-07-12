package app.nogarbo.leflac.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.nogarbo.leflac.data.AudioTrack
import app.nogarbo.leflac.data.LocalAudioLibrary
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
                val snapshot = LocalAudioLibrary(getApplication()).load()
                val allTracks = snapshot.allTracks
                val mixTracks = snapshot.mixes
                val standardTracks = snapshot.songs
                val albumFolders = snapshot.folders
                
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
