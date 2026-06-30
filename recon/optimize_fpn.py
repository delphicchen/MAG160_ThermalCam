import numpy as np
import glob
import os

# Find the raw file in /tmp/
raw_files = glob.glob('/tmp/mag_*_raw.npy')
if not raw_files:
    print("No raw file found!")
    exit(1)

# Sort by modification time to get the latest one
latest_raw = max(raw_files, key=os.path.getmtime)
print("Using raw file:", latest_raw)

f_raw = np.load(latest_raw).astype(np.float32)

# Load factory grid
factory_grid = np.load('/home/delphic/win_share/Delphic/thermal_cam/linux_app/factory_grid_high_gain.npy')

# We want to find the optimal scale for each of the 3 parameter blocks to minimize horizontal gradient variance (lines)
# Variance of lines = variance of difference between adjacent columns

def line_variance(img):
    # Calculate the variance of the horizontal gradient
    # A smooth image has low horizontal gradient variance. Vertical lines have high variance.
    grad_x = np.diff(img, axis=1)
    return np.var(grad_x)

for param_idx in range(3):
    # Use Block 2, 3, or 4 (which are param 0, 1, 2)
    offset_map = factory_grid[param_idx, 0] # T=0 is fine for noise structure
    
    # We want to minimize line_variance(f_raw - scale * offset_map)
    # We can do this analytically or via simple grid search
    scales = np.linspace(-5.0, 5.0, 1000)
    best_scale = 1.0
    min_var = float('inf')
    
    for s in scales:
        img_clean = f_raw - s * offset_map
        var = line_variance(img_clean)
        if var < min_var:
            min_var = var
            best_scale = s
            
    print(f"Param {param_idx}: Best scale = {best_scale:.4f}, min variance = {min_var:.2f}")

    # Also test flipped just in case
    offset_map_flipped = np.fliplr(offset_map)
    scales = np.linspace(-5.0, 5.0, 1000)
    best_scale_flip = 1.0
    min_var_flip = float('inf')
    for s in scales:
        img_clean = f_raw - s * offset_map_flipped
        var = line_variance(img_clean)
        if var < min_var_flip:
            min_var_flip = var
            best_scale_flip = s
            
    print(f"Param {param_idx} (FLIPPED X): Best scale = {best_scale_flip:.4f}, min variance = {min_var_flip:.2f}")
    
# Print original variance
print(f"Original Raw Variance (lines): {line_variance(f_raw):.2f}")

