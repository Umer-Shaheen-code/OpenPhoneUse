package com.personal.agent.core

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Spoken arithmetic and unit conversion: "18 percent of 2450", "5 times 7 plus 2", "5 miles in km". */
object Calc {
    data class Result(val value: Double, val unit: String? = null, val spoken: String)

    private val OPS = mapOf(
        "plus" to "+", "+" to "+", "add" to "+", "minus" to "-", "-" to "-", "less" to "-",
        "times" to "*", "x" to "*", "multiplied" to "*", "into" to "*", "*" to "*",
        "divided" to "/", "over" to "/", "/" to "/",
    )

    fun evaluate(t0: List<String>): Result? {
        val t = t0.filter { it != "by" && it != "is" && it != "equals" }
        if (t.isEmpty()) return null
        convert(t)?.let { return it }
        // "X percent of Y"
        if (t.size == 4 && t[1] in setOf("percent", "%") && t[2] == "of") {
            val p = t[0].toDoubleOrNull() ?: return null; val v = t[3].toDoubleOrNull() ?: return null
            return result(p / 100 * v)
        }
        if (t.size == 3 && t[0].endsWith("%") && t[1] == "of") {
            val p = t[0].removeSuffix("%").toDoubleOrNull() ?: return null; val v = t[2].toDoubleOrNull() ?: return null
            return result(p / 100 * v)
        }
        if (t.size == 3 && t[0] == "square" && t[1] == "root" ) t[2].toDoubleOrNull()?.let { return result(sqrt(it)) }
        if (t.size == 4 && t[0] == "square" && t[1] == "root" && t[2] == "of") t[3].toDoubleOrNull()?.let { return result(sqrt(it)) }
        if (t.size == 2 && t[1] == "squared") t[0].toDoubleOrNull()?.let { return result(it * it) }
        // binary chain with * / precedence
        val nums = ArrayList<Double>(); val ops = ArrayList<String>()
        var expectNum = true
        for (w in t) {
            if (expectNum) { nums += w.toDoubleOrNull() ?: return null; expectNum = false }
            else { ops += OPS[w] ?: return null; expectNum = true }
        }
        if (expectNum || ops.isEmpty()) return null
        // apply * and /
        val n2 = arrayListOf(nums[0]); val o2 = ArrayList<String>()
        for (i in ops.indices) {
            when (ops[i]) {
                "*" -> n2[n2.size - 1] = n2.last() * nums[i + 1]
                "/" -> { if (nums[i + 1] == 0.0) return null; n2[n2.size - 1] = n2.last() / nums[i + 1] }
                else -> { o2 += ops[i]; n2 += nums[i + 1] }
            }
        }
        var acc = n2[0]
        for (i in o2.indices) acc = if (o2[i] == "+") acc + n2[i + 1] else acc - n2[i + 1]
        return result(acc)
    }

    private data class U(val names: Set<String>, val dim: String, val toBase: (Double) -> Double, val fromBase: (Double) -> Double, val spoken: String)

    private val UNITS = listOf(
        U(setOf("km", "kms", "kilometer", "kilometers", "kilometre", "kilometres"), "len", { it * 1000 }, { it / 1000 }, "kilometers"),
        U(setOf("mile", "miles", "mi"), "len", { it * 1609.344 }, { it / 1609.344 }, "miles"),
        U(setOf("m", "meter", "meters", "metre", "metres"), "len", { it }, { it }, "meters"),
        U(setOf("ft", "foot", "feet"), "len", { it * 0.3048 }, { it / 0.3048 }, "feet"),
        U(setOf("inch", "inches", "in"), "len", { it * 0.0254 }, { it / 0.0254 }, "inches"),
        U(setOf("cm", "centimeter", "centimeters", "centimetre", "centimetres"), "len", { it / 100 }, { it * 100 }, "centimeters"),
        U(setOf("kg", "kgs", "kilo", "kilos", "kilogram", "kilograms"), "mass", { it }, { it }, "kilograms"),
        U(setOf("lb", "lbs", "pound", "pounds"), "mass", { it * 0.45359237 }, { it / 0.45359237 }, "pounds"),
        U(setOf("g", "gram", "grams"), "mass", { it / 1000 }, { it * 1000 }, "grams"),
        U(setOf("celsius", "c", "centigrade"), "temp", { it }, { it }, "degrees Celsius"),
        U(setOf("fahrenheit", "f"), "temp", { (it - 32) * 5 / 9 }, { it * 9 / 5 + 32 }, "degrees Fahrenheit"),
        U(setOf("liter", "liters", "litre", "litres", "l"), "vol", { it }, { it }, "liters"),
        U(setOf("gallon", "gallons"), "vol", { it * 3.78541 }, { it / 3.78541 }, "gallons"),
    )

    private fun unit(w: String) = UNITS.firstOrNull { w in it.names }

    /** "5 miles in km", "100 fahrenheit to celsius", "how many km is 5 miles" (normalized by caller patterns). */
    private fun convert(t0: List<String>): Result? {
        val t = t0.filter { it != "degrees" && it != "degree" }
        if (t.size != 4 || t[2] !in setOf("in", "to", "into")) return null
        val v = t[0].toDoubleOrNull() ?: return null
        val from = unit(t[1]) ?: return null; val to = unit(t[3]) ?: return null
        if (from.dim != to.dim) return null
        val out = to.fromBase(from.toBase(v))
        return Result(out, to.spoken, "${fmt(v)} ${from.spoken} is ${fmt(out)} ${to.spoken}")
    }

    private fun result(v: Double) = Result(v, null, "It's ${fmt(v)}")

    fun fmt(v: Double): String {
        if (abs(v - v.roundToLong()) < 1e-9) return v.roundToLong().toString()
        val r = (v * 100).roundToLong() / 100.0
        return r.toString()
    }
}
