package com.magnity.thermalcam.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistence for the temperature calibration — same JSON schema as the Linux app's
 * `calibration.json` ({points, calibrated, a, b, lut_idx}), stored in app files.
 */
class CalibrationStore(private val file: File) {

    data class State(
        val points: MutableList<Pair<Double, Double>> = mutableListOf(),  // (raw, °C)
        var calibrated: Boolean = false,
        var a: Double = 1.0,
        var b: Double = 0.0,
        var lutIdx: Int = 0,
    )

    fun load(): State {
        val s = State()
        if (!file.exists()) return s
        try {
            val d = JSONObject(file.readText())
            val pts = d.optJSONArray("points") ?: JSONArray()
            for (i in 0 until pts.length()) {
                val p = pts.getJSONArray(i)
                s.points.add(p.getDouble(0) to p.getDouble(1))
            }
            s.calibrated = d.optBoolean("calibrated", false)
            s.a = d.optDouble("a", 1.0)
            s.b = d.optDouble("b", 0.0)
            s.lutIdx = d.optInt("lut_idx", 0)
        } catch (_: Exception) {
            // corrupted file -> start fresh
        }
        return s
    }

    fun save(state: State) {
        val d = JSONObject()
        val pts = JSONArray()
        for ((raw, t) in state.points) pts.put(JSONArray().put(raw).put(t))
        d.put("points", pts)
        d.put("calibrated", state.calibrated)
        d.put("a", state.a)
        d.put("b", state.b)
        d.put("lut_idx", state.lutIdx)
        file.writeText(d.toString(2))
    }
}
