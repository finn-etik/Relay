package dev.field.tally

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Expression engine. Recursive descent over:
 *
 *   expr    = term (("+" | "-") term)*
 *   term    = unary (("×" | "÷") unary)*
 *   unary   = ("-" | "+") unary | power
 *   power   = postfix ("^" unary)?
 *   postfix = atom ("%" | "!")*
 *   atom    = number | "(" expr ")" | "√" unary | func "(" expr ")"
 *
 * Percent follows calculator convention: "200+10%" is 220 (10% of the left
 * operand), while "50×10%" is 5. Errors are instrument words:
 * SYNTAX / DIV BY ZERO / DOMAIN / RANGE.
 */
object Calc {

    class Err(val msg: String) : Exception(msg)

    private class Tok(val k: String, val v: Double = 0.0)

    /** A value plus whether it is a bare "n%" (for the +/- percent rule). */
    private class Value(val v: Double, val isPercent: Boolean = false)

    private val FUNCS = listOf("sin", "cos", "tan", "ln", "log")

    /** Results this close to zero from trig are float noise (sin 180° = 1.2E-16). */
    private const val TRIG_EPS = 1e-12

    fun evaluate(src: String, rad: Boolean): Double {
        val t = tokenize(src)
        if (t.isEmpty()) throw Err("SYNTAX")
        var p = 0

        fun peek(): Tok? = t.getOrNull(p)
        fun eat(): Tok = t[p++]

        lateinit var exprRef: () -> Value
        lateinit var unaryRef: () -> Value

        fun atom(): Value {
            val tk = peek() ?: throw Err("SYNTAX")
            eat()
            return when (tk.k) {
                "num" -> Value(tk.v)
                "(" -> {
                    val r = exprRef().v
                    if (peek()?.k != ")") throw Err("SYNTAX")
                    eat()
                    Value(r)
                }
                "sqrt" -> {
                    val r = unaryRef().v
                    if (r < 0) throw Err("DOMAIN")
                    Value(sqrt(r))
                }
                in FUNCS -> {
                    if (peek()?.k != "(") throw Err("SYNTAX")
                    eat()
                    val r = exprRef().v
                    if (peek()?.k != ")") throw Err("SYNTAX")
                    eat()
                    Value(function(tk.k, r, rad))
                }
                else -> throw Err("SYNTAX")
            }
        }

        fun postfix(): Value {
            val a = atom()
            var v = a.v
            var percent = false
            while (peek()?.k == "%" || peek()?.k == "!") {
                if (eat().k == "%") {
                    v /= 100.0
                    percent = true
                } else {
                    v = factorial(v)
                    percent = false
                }
            }
            return Value(v, percent)
        }

        fun power(): Value {
            val base = postfix()
            if (peek()?.k != "^") return base
            eat()
            return Value(base.v.pow(unaryRef().v))
        }

        fun unary(): Value = when (peek()?.k) {
            "-" -> { eat(); Value(-unary().v) }
            "+" -> { eat(); unary() }
            else -> power()
        }
        unaryRef = ::unary

        fun term(): Value {
            var left = unary()
            while (peek()?.k == "×" || peek()?.k == "÷") {
                val op = eat().k
                val r = unary().v
                left = if (op == "×") {
                    Value(left.v * r)
                } else {
                    if (r == 0.0) throw Err("DIV BY ZERO")
                    Value(left.v / r)
                }
            }
            // A lone "n%" passes its percent flag up so expr() can apply it.
            return left
        }

        fun expr(): Value {
            var n = term().v
            while (peek()?.k == "+" || peek()?.k == "-") {
                val op = eat().k
                val r = term()
                val delta = if (r.isPercent) n * r.v else r.v
                n = if (op == "+") n + delta else n - delta
            }
            return Value(n)
        }
        exprRef = ::expr

        val r = expr().v
        if (p < t.size) throw Err("SYNTAX")
        if (!r.isFinite()) throw Err("RANGE")
        return r
    }

    private fun function(name: String, r: Double, rad: Boolean): Double {
        if (name == "ln" || name == "log") {
            if (r <= 0) throw Err("DOMAIN")
            return if (name == "ln") ln(r) else log10(r)
        }
        val x = if (rad) r else Math.toRadians(r)
        if (name == "tan" && abs(cos(x)) < TRIG_EPS) throw Err("DOMAIN") // tan 90°
        val y = when (name) {
            "sin" -> sin(x)
            "cos" -> cos(x)
            else -> tan(x)
        }
        return if (abs(y) < TRIG_EPS) 0.0 else y
    }

    private fun factorial(n: Double): Double {
        if (n < 0 || n != floor(n)) throw Err("DOMAIN")
        if (n > 170) throw Err("RANGE")
        var r = 1.0
        for (x in 2..n.toInt()) r *= x
        return r
    }

    private fun tokenize(src: String): List<Tok> {
        val t = ArrayList<Tok>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == ' ' -> i++
                c.isDigit() || c == '.' -> {
                    var j = i
                    while (j < src.length && (src[j].isDigit() || src[j] == '.')) j++
                    // Scientific notation, as written by fmt(): uppercase E only.
                    // Lowercase e is always Euler's number, so "2e3" = 2·e·3.
                    if (j < src.length && src[j] == 'E') {
                        val sign = if (j + 1 < src.length && (src[j + 1] == '+' || src[j + 1] == '-')) 1 else 0
                        val d = j + 1 + sign
                        if (d < src.length && src[d].isDigit()) {
                            j = d
                            while (j < src.length && src[j].isDigit()) j++
                        }
                    }
                    val n = src.substring(i, j)
                    if (n.count { it == '.' } > 1 || n == ".") throw Err("SYNTAX")
                    t.add(Tok("num", n.toDoubleOrNull() ?: throw Err("SYNTAX")))
                    i = j
                }
                else -> {
                    val f = FUNCS.firstOrNull { src.startsWith(it, i) }
                    when {
                        f != null -> { t.add(Tok(f)); i += f.length }
                        c == 'π' -> { t.add(Tok("num", Math.PI)); i++ }
                        c == 'e' -> { t.add(Tok("num", Math.E)); i++ }
                        c == '√' -> { t.add(Tok("sqrt")); i++ }
                        "+−-×÷^()%!".contains(c) -> {
                            t.add(Tok(if (c == '−') "-" else c.toString()))
                            i++
                        }
                        else -> throw Err("SYNTAX")
                    }
                }
            }
        }
        // Implicit multiplication: 2( -> 2×( ,  )3 -> )×3 ,  2π -> 2×π
        val out = ArrayList<Tok>(t.size + 4)
        fun endsValue(tk: Tok) = tk.k == "num" || tk.k == ")" || tk.k == "!" || tk.k == "%"
        fun startsValue(tk: Tok) = tk.k == "num" || tk.k == "sqrt" || tk.k == "(" || tk.k in FUNCS
        for (tk in t) {
            if (out.isNotEmpty() && endsValue(out.last()) && startsValue(tk)) out.add(Tok("×"))
            out.add(tk)
        }
        return out
    }

    /** Fixed-format readout, 12 significant digits, E notation for large/small. */
    fun fmt(x0: Double): String {
        if (!x0.isFinite()) throw Err("RANGE")
        val x = if (x0 == 0.0) 0.0 else x0 // no "-0"
        if (x == floor(x) && abs(x) < 1e15) return String.format(Locale.ROOT, "%.0f", x)
        val s = String.format(Locale.ROOT, "%.12G", x)
        val eIdx = s.indexOf('E')
        if (eIdx < 0) return s.trimEnd('0').trimEnd('.')
        val mantissa = s.substring(0, eIdx).trimEnd('0').trimEnd('.')
        val exp = s.substring(eIdx + 1)
        val neg = exp.startsWith("-")
        val digits = exp.removePrefix("-").removePrefix("+").trimStart('0').ifEmpty { "0" }
        return mantissa + "E" + (if (neg) "-" else "+") + digits
    }
}
