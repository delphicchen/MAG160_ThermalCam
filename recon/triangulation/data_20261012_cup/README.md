# B2 round 3 — hot cup, laser-measured distances (2026-10-12 evening)

Captured with the 10-frame burst build (before B3); denoise was off (`.dn.f32` == `.f32`).
One burst per position, in capture order: 0.3 / 0.4 / 0.5 / 0.7 / 1.0 / 1.5 / 2.5 / 4.0 m
(laser rangefinder, phone square to the cup).

Result (PLAN_TRIANGULATION.md): the new curve at the measured distance lines the cup up at all
eight positions (`out/near_check.png`, `out/far_check.png`); the edge matcher missed at 0.4–0.7 m.
The 0.3 m lens reading (1.65) is below the 0.4 m one (2.40) — AF failed on the cup there.
