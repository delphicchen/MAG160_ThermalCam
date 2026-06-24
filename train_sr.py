#!/usr/bin/env python3
"""Self-supervised super-resolution training for the Magnity thermal camera.

Trains a tiny ESPCN-style network (~6K params) to upscale 80×60 → 160×120
single-channel thermal frames.  The approach is self-supervised: real 160×120
frames are the HR ground truth, and LR inputs are created by bicubic
downsampling to 80×60.

Usage:
    python train_sr.py --collect            # capture ~500 frames from camera
    python train_sr.py --train              # train on collected frames
    python train_sr.py --export-untrained   # export random-weights ONNX model
"""
import argparse
import os
import sys
import time

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import Dataset, DataLoader


# ---------------------------------------------------------------------------
# Model
# ---------------------------------------------------------------------------
class ThermalESPCN(nn.Module):
    """Tiny ESPCN for 2× single-channel thermal super-resolution.

    Architecture:
        Conv2d(1, 32, 5, pad=2) → ReLU
        Conv2d(32, 16, 3, pad=1) → ReLU
        Conv2d(16,  4, 3, pad=1) → PixelShuffle(2)

    ~6 K parameters.  Input: [N, 1, H, W] float32, output: [N, 1, 2H, 2W].
    """
    def __init__(self):
        super().__init__()
        self.conv1 = nn.Conv2d(1, 32, kernel_size=5, padding=2)
        self.conv2 = nn.Conv2d(32, 16, kernel_size=3, padding=1)
        self.conv3 = nn.Conv2d(16, 4, kernel_size=3, padding=1)  # 4 = 1 * 2^2
        self.shuffle = nn.PixelShuffle(2)
        self._init_weights()

    def _init_weights(self):
        """ICNR initialisation for PixelShuffle — all sub-pixel copies share the
        same kernel so the initial output is a smooth bilinear-like upscale
        instead of a checkerboard of independent random channels."""
        # early layers: Kaiming (small fan-in keeps the initial response mild)
        for m in [self.conv1, self.conv2]:
            nn.init.kaiming_normal_(m.weight, a=0, mode='fan_in', nonlinearity='relu')
            nn.init.zeros_(m.bias)
        # last conv → PixelShuffle: ICNR init
        scale = 2
        c_out, c_in, kh, kw = self.conv3.weight.shape   # (4, 16, 3, 3)
        c_base = c_out // (scale * scale)                # 1 (single output channel)
        # initialise a (c_base, c_in, kh, kw) kernel with Kaiming
        base_kernel = torch.empty(c_base, c_in, kh, kw)
        nn.init.kaiming_normal_(base_kernel, a=0, mode='fan_in', nonlinearity='relu')
        # replicate across the scale² sub-pixel channels
        with torch.no_grad():
            self.conv3.weight.copy_(base_kernel.repeat(scale * scale, 1, 1, 1))
            nn.init.zeros_(self.conv3.bias)

    def forward(self, x):
        """x: [N, 1, H, W] → [N, 1, 2H, 2W]"""
        x = F.relu(self.conv1(x))
        x = F.relu(self.conv2(x))
        x = self.conv3(x)
        x = self.shuffle(x)
        return x


# ---------------------------------------------------------------------------
# Dataset
# ---------------------------------------------------------------------------
class ThermalSRDataset(Dataset):
    """Generates (LR, HR) pairs from a stack of 160×120 thermal frames.

    HR = normalised real frame [0, 1].
    LR = bicubic downsample to 80×60.
    Augmentations: random horizontal/vertical flips, 90° rotations.
    """
    def __init__(self, frames_f32, augment=True):
        """frames_f32: [N, 120, 160] float32 already normalised to [0, 1]."""
        self.frames = frames_f32
        self.augment = augment

    def __len__(self):
        return len(self.frames)

    def __getitem__(self, idx):
        hr = self.frames[idx]  # (120, 160)

        # augmentation (on numpy, before tensor conversion)
        if self.augment:
            if np.random.rand() > 0.5:
                hr = np.flip(hr, axis=1).copy()   # horizontal flip
            if np.random.rand() > 0.5:
                hr = np.flip(hr, axis=0).copy()   # vertical flip
            k = np.random.randint(0, 4)
            hr = np.rot90(hr, k).copy()            # 0/90/180/270°

        hr_t = torch.from_numpy(hr).unsqueeze(0).float()  # [1, H, W]

        # create LR by bicubic downsampling (factor 2)
        H, W = hr_t.shape[1], hr_t.shape[2]
        lr_t = F.interpolate(
            hr_t.unsqueeze(0),
            size=(H // 2, W // 2),
            mode="bicubic",
            align_corners=False,
        ).squeeze(0)  # [1, H/2, W/2]

        return lr_t, hr_t


# ---------------------------------------------------------------------------
# Loss
# ---------------------------------------------------------------------------
def gradient_loss(pred, target):
    """Spatial gradient (Sobel-like) L1 loss – preserves thermal edges."""
    # horizontal
    dx_pred = pred[:, :, :, 1:] - pred[:, :, :, :-1]
    dx_tgt = target[:, :, :, 1:] - target[:, :, :, :-1]
    # vertical
    dy_pred = pred[:, :, 1:, :] - pred[:, :, :-1, :]
    dy_tgt = target[:, :, 1:, :] - target[:, :, :-1, :]
    return F.l1_loss(dx_pred, dx_tgt) + F.l1_loss(dy_pred, dy_tgt)


def combined_loss(pred, target, grad_weight=0.5):
    """L1 pixel loss + weighted gradient loss."""
    return F.l1_loss(pred, target) + grad_weight * gradient_loss(pred, target)


# ---------------------------------------------------------------------------
# Metrics
# ---------------------------------------------------------------------------
def psnr(pred, target):
    """Peak signal-to-noise ratio (data range [0, 1])."""
    mse = F.mse_loss(pred, target).item()
    if mse < 1e-10:
        return 100.0
    return 10.0 * np.log10(1.0 / mse)


# ---------------------------------------------------------------------------
# Collect mode – capture frames from camera
# ---------------------------------------------------------------------------
FRAMES_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "sr_training_frames.npy")

def collect_frames(n_frames=500, settle=3.0):
    """Capture *n_frames* from the Magnity camera and save to .npy."""
    # import here so the rest of the script has no camera dependency
    from magcam import MagCamera

    print(f"Opening camera …")
    cam = MagCamera()
    cam.open()
    print(f"  sensor {cam.W}×{cam.H} @ {cam.fps} fps")
    cam.start()

    print(f"  settling for {settle:.0f}s …")
    time.sleep(settle)

    # optional FFC for cleaner training data
    print("  triggering FFC (shutter) …")
    cam.trigger_ffc()
    time.sleep(0.5)

    frames = []
    last_count = cam.frame_count
    t0 = time.time()
    print(f"  capturing {n_frames} frames – move the camera around slowly …")
    while len(frames) < n_frames:
        fr = cam.get_raw(timeout=1.0)
        if fr is None:
            continue
        # skip duplicate frames
        if cam.frame_count == last_count:
            time.sleep(0.01)
            continue
        last_count = cam.frame_count
        frames.append(fr.copy())
        if len(frames) % 50 == 0:
            elapsed = time.time() - t0
            print(f"    {len(frames)}/{n_frames}  ({elapsed:.1f}s)")

    cam.stop()
    cam.close()

    stack = np.stack(frames)  # [N, 120, 160] uint16
    np.save(FRAMES_PATH, stack)
    print(f"Saved {stack.shape} uint16 frames to {FRAMES_PATH}")
    print(f"  value range: [{stack.min()}, {stack.max()}]")
    return stack


# ---------------------------------------------------------------------------
# Train mode
# ---------------------------------------------------------------------------
def load_and_normalise(path=FRAMES_PATH):
    """Load frames and normalise to [0, 1] float32."""
    if not os.path.exists(path):
        print(f"ERROR: {path} not found.  Run with --collect first.")
        sys.exit(1)
    raw = np.load(path)  # uint16 or int32
    raw = raw.astype(np.float32)
    # normalise per-frame to [0, 1]
    fmin = raw.min(axis=(1, 2), keepdims=True)
    fmax = raw.max(axis=(1, 2), keepdims=True)
    normed = (raw - fmin) / np.maximum(fmax - fmin, 1e-6)
    print(f"Loaded {normed.shape[0]} frames, normalised to [0, 1]")
    return normed


def train(epochs=200, batch_size=16, lr=2e-3, val_split=0.1, grad_weight=0.5):
    """Self-supervised training loop."""
    frames = load_and_normalise()
    n = len(frames)
    n_val = max(1, int(n * val_split))
    n_train = n - n_val

    # shuffle & split
    idx = np.random.permutation(n)
    train_frames = frames[idx[:n_train]]
    val_frames = frames[idx[n_train:]]
    print(f"  train: {n_train}  val: {n_val}")

    train_ds = ThermalSRDataset(train_frames, augment=True)
    val_ds = ThermalSRDataset(val_frames, augment=False)
    train_dl = DataLoader(train_ds, batch_size=batch_size, shuffle=True,
                          num_workers=0, drop_last=True)
    val_dl = DataLoader(val_ds, batch_size=batch_size, shuffle=False,
                        num_workers=0)

    model = ThermalESPCN()
    nparams = sum(p.numel() for p in model.parameters())
    print(f"  model parameters: {nparams:,}")

    optimiser = torch.optim.Adam(model.parameters(), lr=lr)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimiser, T_max=epochs)

    best_val_loss = float("inf")
    ckpt_path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                             "thermal_espcn_2x.pt")

    t0 = time.time()
    for ep in range(1, epochs + 1):
        # ---- train ----
        model.train()
        ep_loss = 0.0
        for lr_batch, hr_batch in train_dl:
            pred = model(lr_batch)
            loss = combined_loss(pred, hr_batch, grad_weight)
            optimiser.zero_grad()
            loss.backward()
            optimiser.step()
            ep_loss += loss.item()
        scheduler.step()
        ep_loss /= max(len(train_dl), 1)

        # ---- validate ----
        model.eval()
        val_loss = 0.0
        val_psnr = 0.0
        with torch.no_grad():
            for lr_b, hr_b in val_dl:
                pred = model(lr_b)
                val_loss += combined_loss(pred, hr_b, grad_weight).item()
                val_psnr += psnr(pred, hr_b)
        val_loss /= max(len(val_dl), 1)
        val_psnr /= max(len(val_dl), 1)

        if ep % 20 == 0 or ep == 1:
            elapsed = time.time() - t0
            print(f"  epoch {ep:3d}/{epochs}  train_loss={ep_loss:.5f}  "
                  f"val_loss={val_loss:.5f}  val_PSNR={val_psnr:.2f} dB  "
                  f"lr={scheduler.get_last_lr()[0]:.2e}  [{elapsed:.0f}s]")

        if val_loss < best_val_loss:
            best_val_loss = val_loss
            torch.save(model.state_dict(), ckpt_path)

    # reload best
    model.load_state_dict(torch.load(ckpt_path, weights_only=True))
    model.eval()

    # final PSNR
    total_psnr = 0.0
    n_batches = 0
    with torch.no_grad():
        for lr_b, hr_b in val_dl:
            pred = model(lr_b)
            total_psnr += psnr(pred, hr_b)
            n_batches += 1
    final_psnr = total_psnr / max(n_batches, 1)
    print(f"\nTraining complete.  Best val PSNR: {final_psnr:.2f} dB")
    print(f"Checkpoint saved to {ckpt_path}")

    # also export ONNX
    export_onnx(model, trained=True)


# ---------------------------------------------------------------------------
# ONNX export
# ---------------------------------------------------------------------------
ONNX_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                         "thermal_espcn_2x.onnx")

def export_onnx(model=None, trained=True):
    """Export model to ONNX with dynamic input shapes."""
    if model is None:
        model = ThermalESPCN()
        if trained:
            ckpt = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "thermal_espcn_2x.pt")
            if not os.path.exists(ckpt):
                print(f"ERROR: checkpoint {ckpt} not found.  Train first.")
                sys.exit(1)
            model.load_state_dict(torch.load(ckpt, weights_only=True))
    model.eval()

    dummy = torch.randn(1, 1, 60, 80)
    torch.onnx.export(
        model,
        dummy,
        ONNX_PATH,
        opset_version=18,
        input_names=["input"],
        output_names=["output"],
        dynamic_axes={
            "input":  {0: "batch", 2: "height", 3: "width"},
            "output": {0: "batch", 2: "height2", 3: "width2"},
        },
    )
    nparams = sum(p.numel() for p in model.parameters())
    tag = "ICNR-initialised (untrained)" if not trained else "trained"
    print(f"ONNX exported ({tag}) → {ONNX_PATH}")
    print(f"  params: {nparams:,}   input: [N,1,H,W]   output: [N,1,2H,2W]")
    size_kb = os.path.getsize(ONNX_PATH) / 1024
    print(f"  file size: {size_kb:.1f} KB")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(
        description="Self-supervised SR training for Magnity thermal camera")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--collect", action="store_true",
                       help="Capture ~500 frames from the camera")
    group.add_argument("--train", action="store_true",
                       help="Train on collected frames")
    group.add_argument("--export-untrained", action="store_true",
                       help="Export random-weights ONNX model for testing")
    group.add_argument("--export-trained", action="store_true",
                       help="Re-export trained model to ONNX")

    parser.add_argument("--frames", type=int, default=500,
                        help="Number of frames to collect (default: 500)")
    parser.add_argument("--epochs", type=int, default=200,
                        help="Training epochs (default: 200)")
    parser.add_argument("--batch-size", type=int, default=16,
                        help="Batch size (default: 16)")
    parser.add_argument("--lr", type=float, default=2e-3,
                        help="Learning rate (default: 2e-3)")

    args = parser.parse_args()

    if args.collect:
        collect_frames(n_frames=args.frames)
    elif args.train:
        train(epochs=args.epochs, batch_size=args.batch_size, lr=args.lr)
    elif args.export_untrained:
        export_onnx(trained=False)
    elif args.export_trained:
        export_onnx(trained=True)


if __name__ == "__main__":
    main()
