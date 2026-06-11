import numpy as np
import soundfile as sf
import os

def generate_tone(freq, duration, sr=44100):
    t = np.linspace(0, duration, int(sr * duration), endpoint=False)
    return 0.5 * np.sin(2 * np.pi * freq * t)

def generate_sweep(start_freq, end_freq, duration, sr=44100):
    t = np.linspace(0, duration, int(sr * duration), endpoint=False)
    freqs = np.linspace(start_freq, end_freq, len(t))
    return 0.5 * np.sin(2 * np.pi * freqs * t)

def generate_kick(sr=44100):
    # Simple 60Hz decay
    duration = 0.5
    t = np.linspace(0, duration, int(sr * duration), endpoint=False)
    
    # Pitch drop 150 -> 50
    freq = np.linspace(150, 50, len(t))
    signal = np.sin(2 * np.pi * freq * t)
    
    # Envelope
    envelope = np.exp(-10 * t)
    return signal * envelope

def generate_snare(sr=44100):
    # Noise + Tone
    duration = 0.3
    t = np.linspace(0, duration, int(sr * duration), endpoint=False)
    
    noise = np.random.normal(0, 1, len(t))
    tone = np.sin(2 * np.pi * 200 * t)
    
    signal = noise * 0.7 + tone * 0.3
    envelope = np.exp(-15 * t)
    return signal * envelope

def main():
    sr = 44100
    out_dir = "analysis/debug_tracks"
    os.makedirs(out_dir, exist_ok=True)
    
    # 1. Frequency Sweep (20Hz -> 20kHz)
    sweep = generate_sweep(20, 20000, 5.0, sr)
    sf.write(f"{out_dir}/test_sweep.flac", sweep, sr)
    print("Generated test_sweep.flac")
    
    # 2. Rock Pattern (Kick ... Snare ... Kick Snare)
    kick = generate_kick(sr)
    snare = generate_snare(sr)
    silence = np.zeros(int(sr * 0.2))
    
    # K - S - K S
    pattern = np.concatenate([
        kick, silence, 
        snare, silence, 
        kick, snare
    ])
    
    sf.write(f"{out_dir}/test_drum_pattern.flac", pattern, sr)
    print("Generated test_drum_pattern.flac")

if __name__ == "__main__":
    main()
