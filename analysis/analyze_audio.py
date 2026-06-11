import argparse
import numpy as np
import librosa
import matplotlib.pyplot as plt
import soundfile as sf
import os

# --- KOTLIN LOGIC MIRROR ---

def hypot(r, i):
    return np.sqrt(r**2 + i**2)

class SpectrumState:
    def __init__(self):
        self.avg_energy = 0.3
        self.history = []

    def process(self, magnitudes):
        # 1. Logarithmic Binning (16 Bands)
        # 0Hz to 22kHz
        # Naive mapping to match Kotlin's simplified log10 approximation
        n_bins = 16
        bands = np.zeros(n_bins)
        
        # Determine mapping based on size
        n_fft_bins = len(magnitudes)
        
        # Kotlin Logic:
        # val logIdx = (log10(i.toDouble()) / log10(magnitudes.size.toDouble()) * bandCount).toInt().coerceIn(0, bandCount - 1)
        
        # Kotlin Logic:
        # val logIdx = (log10(i.toDouble()) / log10(magnitudes.size.toDouble()) * bandCount).toInt().coerceIn(0, bandCount - 1)
        
        # Match Kotlin: use 0-based 'i' that corresponds to 1-based magnitude index?
        # Kotlin: for i in 1..size-1: logIdx(i). bands[logIdx] += mag[i]
        
        for i in range(1, n_fft_bins):
             # Log Index based on i
             log_t = i
             log_max = n_fft_bins
             val_idx = i
             
             log_idx = int(np.log10(log_t) / np.log10(log_max) * n_bins)
             log_idx = max(0, min(n_bins - 1, log_idx))
             
             bands[log_idx] += magnitudes[val_idx]
            
        # 2. Normalize & Smooth
        # Kotlin: bands[i] = (bands[i] / 10f * (1f + i * 0.1f)).coerceIn(0f, 1f)
        for i in range(n_bins):
            bands[i] = (bands[i] / 10.0 * (1.0 + i * 0.1))
            bands[i] = np.clip(bands[i], 0.0, 1.0)
            
        # 3. Map to 7-Instruments
        # Kick (0-1), Bass (2-3), Snare (4-5), Guitar (6-8), Vocal (9-11), Synth (12-13), Cymbal (14-15)
        
        def avg(rng):
            sl = bands[rng]
            if len(sl) == 0: return 0.0
            return np.mean(sl)
            
        val_kick = np.clip(avg(slice(0, 2)), 0, 1)
        val_bass = np.clip(avg(slice(2, 4)), 0, 1)
        val_snare = np.clip(avg(slice(4, 6)), 0, 1)
        val_guitar = np.clip(avg(slice(6, 9)), 0, 1)
        val_vocal = np.clip(avg(slice(9, 12)), 0, 1)
        val_synth = np.clip(avg(slice(12, 14)), 0, 1) # slice is exclusive in python? 12,13 -> 12:14
        val_cymbal = np.clip(avg(slice(14, 16)), 0, 1)
        
        # 4. Drama Logic (Refined "Fire Trigger")
        current_energy = (val_kick + val_snare + val_cymbal + val_guitar) / 4.0
        
        learning_rate = 0.05 if current_energy > self.avg_energy else 0.01 # Fast attack, slow decay
        self.avg_energy = self.avg_energy * (1 - learning_rate) + current_energy * learning_rate
        
        # A. Flux (Sudden Onset)
        # Drop needs to be significantly louder than the recent average
        is_flux_spike = current_energy > (self.avg_energy * 1.35) # Reduced from 1.5 to catch subtler drops
        
        # B. Genre Signatures
        # 1. "Driving Rock" (Kick + Snare)
        is_driving_beat = val_kick > 0.6 and val_snare > 0.5
        
        # 2. "Wall of Sound" (Metal/Shoegaze - Vore)
        # High Kick, High Guitar, High Cymbal
        is_wall_of_sound = val_kick > 0.55 and val_guitar > 0.55 and val_cymbal > 0.5
        
        # 3. "Trap/Bass Drop" (Huge Low End)
        is_bass_drop = val_kick > 0.8 and val_bass > 0.7
        
        # C. Suppressors (False Positive Prevention)
        # 1. "Vocal Chorus" (Loud Singing without driving beat)
        # If Vocals are the loudest element and Drums are weak -> Suppress
        # STRICTER: If Vocal is very high (>0.8) and Kick is low (<0.4), suppress hard.
        is_vocal_dominant = (val_vocal > 0.7 and val_kick < 0.5 and val_snare < 0.5) or \
                            (val_vocal > 0.85 and val_kick < 0.4)

        # D. Gate
        # Raised from 0.35 to 0.48 to filter out "passionate but quiet" vocal swells (London Grammar issue)
        is_loud_enough = current_energy > 0.48
        
        # E. Composite Decision
        # Must be Loud AND (Flux Spike OR Sustained Wall) AND NOT Vocal Dominant
        # We allow "Sustained Wall" to trigger even without Flux Spike if it's massive.
        
        is_trigger_event = (is_flux_spike and (is_driving_beat or is_bass_drop)) or is_wall_of_sound
        
        is_dramatic = is_trigger_event and is_loud_enough and not is_vocal_dominant
        
        return {
            'timestamp': 0, # set by caller
            'kick': val_kick,
            'bass': val_bass,
            'snare': val_snare,
            'guitar': val_guitar,
            'vocal': val_vocal,
            'synth': val_synth,
            'cymbal': val_cymbal,
            'is_dramatic': is_dramatic,
            'avg_energy': self.avg_energy,
            'energy': current_energy
        }

import json

def analyze_track(filepath, dump_vectors=False):
    print(f"Analyzing: {filepath}")
    
    # Load Audio
    y, sr = librosa.load(filepath, sr=44100, mono=True)
    
    # FFT Parameters
    n_fft = 1024 # Reduced to match Android default capture size
    hop_length = 512 # Keep overlap similar
    
    # STFT
    D = librosa.stft(y, n_fft=n_fft, hop_length=hop_length, window='hann')
    
    # Magnitude
    magnitudes = np.abs(D) 
    
    # ...
    
    processor = SpectrumState()
    results = []
    
    vectors = []
    
    num_frames = magnitudes.shape[1]
    
    print(f"Frames: {num_frames} (~{num_frames * hop_length / sr:.1f}s)")
    
    for t in range(num_frames):
        # 1. Simulate Android FFT Bytes
        # Android returns bytes. Max magnitude is ~180.
        # We need to scale our normalized audio FFT to this byte range.
        # This is tricky without knowing exact Android gain.
        # BUT, the user asked to verify that "Logic matches".
        # So we can just Generate floats -> Quantize -> Dequantize -> Run Logic?
        # Or: Trust that our Python logic mirrors the Kotlin logic's math.
        
        # Let's create specific test vectors for the Kotlin side:
        # We will Store: The RAW MAGNITUDES (floats) or Simulated Bytes.
        
        # Simulating Byte FFT:
        complex_frame = D[:, t]
        
        # Scale to avoid tiny values (Audio is -1..1, FFT can be larger)
        # Let's normalize frame peak to 127 for simulation valid range?
        # Or just use a fixed scalar.
        scalar = 50.0 
        
        android_fft_bytes = []
        for k in range(len(complex_frame)):
             # Downsample to N/2 if needed (Android Visualizer usually sends N/2 + 1 or N)
             # We only need up to N/2 for magnitude.
             # Actually Android Visualizer getFft() returns size captureSize.
             # Contents: [Re0, Im0, Re1, Im1 ... Re(N/2-1), Im(N/2-1)]
             # Indices 0 and 1 are Re(0) and Im(0)? Doc says index 0 is DC, index 1 is Re(N/2) (Nyquist) sometimes?
             # Standard: Re(k), Im(k).
             
             re = int(np.real(complex_frame[k]) * scalar)
             im = int(np.imag(complex_frame[k]) * scalar)
             
             # Clamp to byte
             re = max(-128, min(127, re))
             im = max(-128, min(127, im))
             
             android_fft_bytes.append(re)
             android_fft_bytes.append(im)
             
             # Optimization: Don't dump 2048 bytes per frame for 10k frames. Too big.
             # We should only dump INTERESTING frames (Transitions).
        
        # Process using the Sim-Bytes to be "Fair"
        # We need to recalculate magnitude from these bytes like Kotlin does
        # magnitude[k] = hypot(re, im)
        
        sim_magnitudes = np.zeros(len(complex_frame))
        for k in range(len(complex_frame)):
            re = android_fft_bytes[2*k]
            im = android_fft_bytes[2*k+1]
            sim_magnitudes[k] = np.hypot(re, im)
        
        # Run Logic
        # Note: logic expects floats? No, python logic class I wrote handles the raw magnitudes.
        # Wait, the python script earlier had `frame_mag = frame_mag * 0.1` 
        # I need to sync the Python script to use the EXACT same logic class structure as Kotlin.
        
        # ... logic ...
        # (Using the loop in existing script, but modified to export)
        frame_mag = magnitudes[:, t] * 0.1 # Existing scaling
        
        res = processor.process(frame_mag)
        res['timestamp'] = t * hop_length / sr
        results.append(res)
        
        if dump_vectors:
             # Logic to save vector:
             # Save one frame per second? Or only when state changes?
             # Saving 15000 frames is 30MB JSON. Acceptable? Maybe.
             # Let's save every 10th frame (4Hz resolution) for sanity.
             if t % 10 == 0:
                 vectors.append({
                     "t": res['timestamp'],
                     # We can't easily reproduce the BYTE input that leads to these *exact* floats 
                     # because we applied arbitrary scaling in Python.
                     # Better approach: Export the RESULTING 'Bands' calculated in Python,
                     # and assert that Kotlin calculates similar Bands?
                     # No, user wants IS_DRAMATIC match.
                     
                     # Allow Kotlin to ingest the Raw Magnitudes directly?
                     # Let's export the Magnitude array (1024 floats) - compacted
                     "mags": [round(float(x), 3) for x in frame_mag[:128]], # Only save first 128 bins (to ~5kHz) for space? 
                     # No, we need full spectrum.
                     # Let's just save the inputs needed for "Golden Master":
                     # "bands": [b0..b15]
                     # "expected_dramatic": bool
                     # Logic Test: Feed "bands" to internal logic? 
                     # But Kotlin takes FFT.
                     
                     # OK, providing full magnitude array 512 floats is ~2KB per frame. 1000 frames = 2MB. Fine.
                     "mags": [round(float(x), 4) for x in frame_mag[:256]], # First 256 bins covers up to 10kHz.
                     # Wait, Cymbal check needs 8k-20k (Bins 200+).
                     # Let's save sparse representation or full.
                     # Full 512 bins.
                     "mags_full": [round(float(x), 4) for x in frame_mag[:512]],
                     "dramatic": bool(res['is_dramatic']),
                     "kick": float(res['kick']),
                     "snare": float(res['snare'])
                 })
                 
    if dump_vectors:
        out_json = filepath + "_vectors.json"
        with open(out_json, 'w') as f:
            json.dump(vectors, f)
        print(f"Saved vectors to {out_json}")
        
    return results

def plot_analysis(results, filename):
    # ... (existing plot code) ...
    times = [r['timestamp'] for r in results]
    
    # Extract all bands
    kicks = [r['kick'] for r in results]
    s_basses = [r['bass'] for r in results] # Renamed to avoid local var clash
    snares = [r['snare'] for r in results]
    guitars = [r['guitar'] for r in results]
    vocals = [r['vocal'] for r in results]
    synths = [r['synth'] for r in results]
    cymbals = [r['cymbal'] for r in results]
    
    dramas = [r['is_dramatic'] for r in results]
    
    plt.figure(figsize=(15, 8)) # Taller for more data
    
    # Plot Stacked/Layered Lines with Fill for readability
    # Low End
    plt.plot(times, kicks, label='Kick (Sub)', color='#FFD700', linewidth=1.5, alpha=0.9) # Gold
    plt.plot(times, s_basses, label='Bass', color='#FF8C00', linewidth=1.0, alpha=0.7) # Dark Orange
    
    # Mids
    plt.plot(times, snares, label='Snare', color='#FF00FF', linewidth=1.5, alpha=0.8) # Magenta
    plt.plot(times, guitars, label='Guitar', color='#32CD32', linewidth=1.0, alpha=0.6) # Lime Green
    plt.plot(times, vocals, label='Vocal', color='#1E90FF', linewidth=1.0, alpha=0.6) # Dodger Blue
    
    # Highs
    plt.plot(times, synths, label='Synth', color='#9370DB', linewidth=1.0, alpha=0.6) # Medium Purple
    plt.plot(times, cymbals, label='Cymbal', color='#00FFFF', linewidth=1.5, alpha=0.8) # Cyan
    
    # Plot Dramas
    drama_times = [t for t, d in zip(times, dramas) if d]
    drama_y = [1.1] * len(drama_times)
    plt.scatter(drama_times, drama_y, color='red', marker='*', s=80, label='FIRE TRIGGER', zorder=10)
    
    plt.title(f"Full Spectrum Analysis: {os.path.basename(filename)}")
    plt.xlabel("Time (s)")
    plt.ylabel("Normalized Level")
    plt.legend(loc='upper right', ncol=2)
    plt.ylim(0, 1.25)
    plt.grid(True, alpha=0.2)
    
    # Dark Mode Background for "Cyber" feel
    plt.gca().set_facecolor('#1a1a1a')
    plt.gcf().set_facecolor('#1a1a1a')
    plt.gca().tick_params(colors='white')
    plt.gca().xaxis.label.set_color('white')
    plt.gca().yaxis.label.set_color('white')
    plt.gca().title.set_color('white')
    # Update legend text to white
    plt.setp(plt.gca().get_legend().get_texts(), color='black') # Legend box is usually white background by default in mpl

    
    out_path = filename + "_analysis.png"
    plt.savefig(out_path)
    print(f"Saved plot to {out_path}")
    
    # Generate Report
    report_path = filename + "_report.md"
    with open(report_path, 'w') as f:
        f.write(f"# Analysis Report: {os.path.basename(filename)}\n\n")
        f.write("| Time | Trigger | K | S | C | Energy |\n")
        f.write("|---|---|---|---|---|---|\n")
        for r in results:
            if r['is_dramatic']:
                f.write(f"| {r['timestamp']:.2f}s | **FIRE** | {r['kick']:.2f} | {r['snare']:.2f} | {r['cymbal']:.2f} | {r['energy']:.2f} |\n")
    print(f"Saved report to {report_path}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("files", nargs='+', help="FLAC files to analyze")
    parser.add_argument("--dump-vectors", action="store_true", help="Dump JSON test vectors")
    args = parser.parse_args()
    
    for f in args.files:
        data = analyze_track(f, args.dump_vectors)
        plot_analysis(data, f)

