package app.snapedit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** User-saved "Looks" (Lightroom presets). Only global adjustments are stored, never crop/local edits. */
object Presets {
    private val excluded = setOf("crop", "rotate", "liquify", "brush", "draw", "select", "heal", "text", "frames", "redeye", "flare")

    fun portable(ops: List<Op>): List<Op> = ops.filter { it.tool !in excluded }

    private fun prefs(c: Context) = c.getSharedPreferences("presets", Context.MODE_PRIVATE)

    fun load(c: Context): List<Pair<String, List<Op>>> = runCatching {
        val arr = JSONArray(prefs(c).getString("list", "[]") ?: "[]")
        val out = ArrayList<Pair<String, List<Op>>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ops = ArrayList<Op>()
            val oa = o.getJSONArray("ops")
            for (j in 0 until oa.length()) {
                val po = oa.getJSONObject(j)
                val v = HashMap<String, Float>()
                val vo = po.getJSONObject("v")
                val keys = vo.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    v[k] = vo.getDouble(k).toFloat()
                }
                val cv = ArrayList<List<Float>>()
                val ca = po.optJSONArray("curves")
                if (ca != null) {
                    for (q in 0 until ca.length()) {
                        val a = ca.getJSONArray(q)
                        cv.add((0 until a.length()).map { a.getDouble(it).toFloat() })
                    }
                }
                ops.add(Op(po.getString("tool"), v, curves = cv))
            }
            out.add(o.getString("name") to ops)
        }
        out
    }.getOrDefault(emptyList())

    private fun write(c: Context, list: List<Pair<String, List<Op>>>) {
        val arr = JSONArray()
        for ((name, ops) in list) {
            val oa = JSONArray()
            for (op in ops) {
                val vo = JSONObject()
                for ((k, v) in op.v) vo.put(k, v.toDouble())
                val ca = JSONArray()
                for (cv in op.curves) {
                    val a = JSONArray()
                    for (f in cv) a.put(f.toDouble())
                    ca.put(a)
                }
                oa.put(JSONObject().put("tool", op.tool).put("v", vo).put("curves", ca))
            }
            arr.put(JSONObject().put("name", name).put("ops", oa))
        }
        prefs(c).edit().putString("list", arr.toString()).apply()
    }

    fun add(c: Context, name: String, ops: List<Op>) {
        val l = load(c).filter { it.first != name } + (name to portable(ops))
        write(c, l)
    }

    fun remove(c: Context, name: String) = write(c, load(c).filter { it.first != name })
}
