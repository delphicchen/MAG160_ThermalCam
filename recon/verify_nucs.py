import numpy as np

maps = []
for i in range(12):
    maps.append(np.load(f'factory_flat_{i}.npy'))

for i in range(12):
    is_identical_to_prev = False
    diff_from_prev = 0
    if i > 0:
        diff_from_prev = np.sum(np.abs(maps[i] - maps[i-1]))
        if diff_from_prev == 0:
            is_identical_to_prev = True
            
    # Calculate some stats to show they are unique
    min_val = np.min(maps[i])
    max_val = np.max(maps[i])
    std_val = np.std(maps[i])
    
    if is_identical_to_prev:
        print(f"Map {i:2d}: ⚠️ EXACTLY IDENTICAL to Map {i-1}")
    else:
        diff_str = f"Diff from prev: {diff_from_prev:8.1f}" if i > 0 else "Diff from prev: N/A"
        print(f"Map {i:2d}: Min={min_val:6.1f}, Max={max_val:6.1f}, Std={std_val:6.1f} | {diff_str}")

