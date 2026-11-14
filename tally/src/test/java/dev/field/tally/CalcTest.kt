package dev.field.tally

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CalcTest {

    private fun eval(s: String, rad: Boolean = false) = Calc.fmt(Calc.evaluate(s, rad))

    private fun err(s: String, rad: Boolean = false): String = try {
        Calc.evaluate(s, rad)
        fail("expected error for $s")
        ""
    } catch (e: Calc.Err) {
        e.msg
    }

    @Test fun arithmeticAndPrecedence() {
        assertEquals("14", eval("2+3×4"))
        assertEquals("20", eval("(2+3)×4"))
        assertEquals("-4", eval("-2^2"))
        assertEquals("0.5", eval("2^−1"))
        assertEquals("0.3", eval("0.1+0.2"))
        assertEquals("2.5", eval("5÷2"))
    }

    @Test fun percentConvention() {
        assertEquals("220", eval("200+10%"))   // was 200.1: flag lost in term()
        assertEquals("180", eval("200−10%"))
        assertEquals("5", eval("50×10%"))
        assertEquals("0.5", eval("50%"))
        assertEquals("205", eval("200+10%×50")) // 10%×50 = 5, not a bare percent
    }

    @Test fun implicitMultiplicationAndConstants() {
        assertEquals("10", eval("2(3+2)"))
        assertEquals("6.28318530718", eval("2π"))
        // lowercase e is Euler's number, never an exponent
        assertEquals("16.3096909708", eval("2e3"))
        // uppercase E (as fmt writes it) round-trips
        assertEquals("1.23E+15", eval("1.23E+15"))
    }

    @Test fun functionsAndTrigRounding() {
        assertEquals("0", eval("sin(180)"))
        assertEquals("1", eval("sin(90)"))
        assertEquals("0", eval("cos(90)"))
        assertEquals("1", eval("tan(45)").let { if (it.startsWith("0.99999999999") || it == "1") "1" else it })
        assertEquals("3", eval("√9"))
        assertEquals("2", eval("log(100)"))
        assertEquals("120", eval("5!"))
        assertEquals("0", eval("sin(π)", rad = true))
    }

    @Test fun errors() {
        assertEquals("DIV BY ZERO", err("1÷0"))
        assertEquals("DOMAIN", err("√(−1)"))
        assertEquals("DOMAIN", err("ln(0)"))
        assertEquals("DOMAIN", err("tan(90)"))
        assertEquals("DOMAIN", err("2.5!"))
        assertEquals("RANGE", err("171!"))
        assertEquals("SYNTAX", err("2+"))
        assertEquals("SYNTAX", err("1..2"))
        assertEquals("SYNTAX", err("(2"))
    }

    @Test fun formatting() {
        assertEquals("1E+20", Calc.fmt(1e20))
        assertEquals("1.5E-7", Calc.fmt(1.5e-7))
        assertEquals("0", Calc.fmt(-0.0))
        assertEquals("0.333333333333", Calc.fmt(1.0 / 3))
    }
}
