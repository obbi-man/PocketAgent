package ru.pocketagent

import kotlin.math.*

/** Безопасный калькулятор: + - * / ^ %, скобки, sqrt, без eval. */
object Calculator {
    class CalcException(msg: String) : Exception(msg)

    fun normalize(expr: String): String {
        var s = expr.lowercase()
            .replace('×', '*').replace('·', '*').replace('÷', '/').replace('−', '-').replace('–', '-')
            .replace("корень из", "sqrt").replace("корень", "sqrt").replace("квадратный", "")
            .replace("в степени", "^").replace("умножить на", "*").replace("разделить на", "/")
            .replace("поделить на", "/").replace("плюс", "+").replace("минус", "-")
            .replace("**", "^")
        // 2x3 -> 2*3, 2 х 3 (кириллическая х) -> 2*3
        s = s.replace(Regex("(?<=[\\d)])\\s*[xх]\\s*(?=[\\d(])"), "*")
        // десятичная запятая: 3,5 -> 3.5
        s = s.replace(Regex("(?<=\\d),(?=\\d)"), ".")
        s = s.replace(Regex("(?<=[\\d)])\\s*:\\s*(?=[\\d(])"), "/")
        s = s.replace(" ", "")
        return s
    }

    fun eval(expr: String): Double {
        val p = Parser(normalize(expr))
        val v = p.parseExpr()
        if (p.pos != p.s.length) throw CalcException("непонятный символ «${p.s[p.pos]}»")
        if (v.isNaN() || v.isInfinite()) throw CalcException("результат не определён (деление на ноль?)")
        return v
    }

    fun format(v: Double): String {
        if (abs(v) < 1e15 && v == floor(v)) return v.toLong().toString()
        val s = String.format(java.util.Locale.US, "%.10g", v).trimEnd('0').trimEnd('.')
        return if (s.contains('e')) s else s
    }

    private class Parser(val s: String) {
        var pos = 0
        fun peek(): Char? = if (pos < s.length) s[pos] else null
        fun parseExpr(): Double {
            var v = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; v += parseTerm() }
                    '-' -> { pos++; v -= parseTerm() }
                    else -> return v
                }
            }
        }
        fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; v *= parseFactor() }
                    '/' -> { pos++; val d = parseFactor(); if (d == 0.0) throw CalcException("деление на ноль"); v /= d }
                    else -> return v
                }
            }
        }
        fun parseFactor(): Double {
            val base = parseUnary()
            if (peek() == '^') { pos++; return base.pow(parseFactor()) }
            return base
        }
        fun parseUnary(): Double {
            if (peek() == '-') { pos++; return -parseUnary() }
            if (peek() == '+') { pos++; return parseUnary() }
            return parsePostfix()
        }
        fun parsePostfix(): Double {
            var v = parsePrimary()
            while (peek() == '%') { pos++; v /= 100.0 }
            return v
        }
        fun parsePrimary(): Double {
            val c = peek() ?: throw CalcException("выражение оборвалось")
            if (c == '(') {
                pos++
                val v = parseExpr()
                if (peek() != ')') throw CalcException("нет закрывающей скобки")
                pos++
                return v
            }
            if (s.startsWith("sqrt", pos)) {
                pos += 4
                val v = parseUnary()
                if (v < 0) throw CalcException("корень из отрицательного числа")
                return sqrt(v)
            }
            if (s.startsWith("pi", pos)) { pos += 2; return PI }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (start == pos) throw CalcException("ожидалось число")
            return s.substring(start, pos).toDoubleOrNull() ?: throw CalcException("плохое число")
        }
    }
}
