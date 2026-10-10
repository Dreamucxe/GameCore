package com.gamecore.core.plugin.content

/**
 * A deliberately tiny arithmetic expression language for plugin stat modules.
 *
 * A stat module wants to show "average FPS", "K/D", "accuracy %" — numbers derived from the
 * approved stat feed. Letting a plugin ship a *formula* for that is far safer than letting it
 * ship code, but only if the formula language cannot itself become a computer: no loops, no
 * recursion, no function definitions, no variable assignment, no string manipulation, no I/O.
 * This evaluator accepts exactly `+ - * /`, parentheses, numeric literals, and references to
 * a fixed, host-supplied set of named variables, and nothing else. It is a pure calculator.
 *
 * Bounded three ways so even valid input cannot be weaponised: the source string is length-
 * capped, the token count is capped, and parenthesis nesting is depth-capped. Division by
 * zero yields a defined result (0, with a note) rather than a crash or an infinity that would
 * render as "NaN" on screen. The whole thing is recursive-descent over a token list with no
 * backtracking, so evaluation cost is linear in the (capped) token count — there is no input
 * that makes it run long.
 */
class StatExpression private constructor(private val tokens: List<Tok>) {

    sealed interface Result {
        data class Ok(val value: Double, val clampedDivByZero: Boolean) : Result
        data class Error(val message: String) : Result
    }

    /** Evaluate against [vars]; an unknown variable is an error, not a silent zero. */
    fun evaluate(vars: Map<String, Double>): Result {
        return try {
            val p = Parser(tokens, vars)
            val v = p.parseExpression()
            p.expectEnd()
            Result.Ok(v, p.divByZero)
        } catch (e: EvalException) {
            Result.Error(e.message ?: "invalid expression")
        }
    }

    private class EvalException(message: String) : RuntimeException(message)

    private sealed interface Tok {
        data class Number(val v: Double) : Tok
        data class Ident(val name: String) : Tok
        data class Op(val c: Char) : Tok
        data object LParen : Tok
        data object RParen : Tok
    }

    private class Parser(private val toks: List<Tok>, private val vars: Map<String, Double>) {
        private var i = 0
        var divByZero = false
        private var depth = 0

        fun parseExpression(): Double {
            var acc = parseTerm()
            while (peek() is Tok.Op && ((peek() as Tok.Op).c == '+' || (peek() as Tok.Op).c == '-')) {
                val op = (next() as Tok.Op).c
                val rhs = parseTerm()
                acc = if (op == '+') acc + rhs else acc - rhs
            }
            return acc
        }

        private fun parseTerm(): Double {
            var acc = parseFactor()
            while (peek() is Tok.Op && ((peek() as Tok.Op).c == '*' || (peek() as Tok.Op).c == '/')) {
                val op = (next() as Tok.Op).c
                val rhs = parseFactor()
                acc = if (op == '*') acc * rhs else {
                    if (rhs == 0.0) { divByZero = true; 0.0 } else acc / rhs
                }
            }
            return acc
        }

        private fun parseFactor(): Double {
            val t = next() ?: throw EvalException("unexpected end of expression")
            return when (t) {
                is Tok.Number -> t.v
                is Tok.Ident -> vars[t.name] ?: throw EvalException("unknown variable '${t.name}'")
                is Tok.Op -> if (t.c == '-') -parseFactor() else throw EvalException("unexpected operator '${t.c}'")
                Tok.LParen -> {
                    if (++depth > MAX_DEPTH) throw EvalException("expression nested too deeply")
                    val v = parseExpression()
                    if (next() != Tok.RParen) throw EvalException("missing ')'")
                    depth--
                    v
                }
                Tok.RParen -> throw EvalException("unexpected ')'")
            }
        }

        fun expectEnd() { if (i < toks.size) throw EvalException("trailing tokens") }
        private fun peek(): Tok? = toks.getOrNull(i)
        private fun next(): Tok? = toks.getOrNull(i)?.also { i++ }
    }

    companion object {
        const val MAX_SOURCE_LENGTH = 256
        const val MAX_TOKENS = 128
        const val MAX_DEPTH = 16

        /** Compile [source] to an evaluable expression, or return a reason it was rejected. */
        fun compile(source: String): CompileResult {
            if (source.length > MAX_SOURCE_LENGTH) return CompileResult.Error("expression too long")
            val toks = mutableListOf<Tok>()
            var i = 0
            while (i < source.length) {
                val c = source[i]
                when {
                    c.isWhitespace() -> i++
                    c == '(' -> { toks += Tok.LParen; i++ }
                    c == ')' -> { toks += Tok.RParen; i++ }
                    c in "+-*/" -> { toks += Tok.Op(c); i++ }
                    c.isDigit() || c == '.' -> {
                        val start = i
                        while (i < source.length && (source[i].isDigit() || source[i] == '.')) i++
                        val num = source.substring(start, i).toDoubleOrNull()
                            ?: return CompileResult.Error("bad number '${source.substring(start, i)}'")
                        toks += Tok.Number(num)
                    }
                    c.isLetter() || c == '_' -> {
                        val start = i
                        while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                        toks += Tok.Ident(source.substring(start, i))
                    }
                    else -> return CompileResult.Error("illegal character '$c'")
                }
                if (toks.size > MAX_TOKENS) return CompileResult.Error("expression too complex")
            }
            if (toks.isEmpty()) return CompileResult.Error("empty expression")
            return CompileResult.Ok(StatExpression(toks))
        }
    }

    sealed interface CompileResult {
        data class Ok(val expression: StatExpression) : CompileResult
        data class Error(val message: String) : CompileResult
    }
}
