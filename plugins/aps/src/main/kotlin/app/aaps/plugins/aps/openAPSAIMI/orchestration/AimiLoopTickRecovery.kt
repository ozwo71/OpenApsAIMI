package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT

/**
 * Builds safe [RT] responses when a loop tick cannot complete normally.
 * Never throws — used to keep the APS process alive after lock contention or unexpected errors.
 */
object AimiLoopTickRecovery {

    private const val MAX_CONSOLE_TAIL = 64

    fun skippedPriorTickStillRunning(ctx: AimiTickContext): RT {
        val log = mutableListOf(
            "AIMI tick skipped: prior determine_basal still running (exclusive lock timeout)",
        )
        return minimalRt(
            ctx = ctx,
            log = log,
            errors = mutableListOf(),
            reasonLine = "AIMI safe skip: prior tick still running; no insulin change this cycle.",
        )
    }

    fun safeResultAfterUnhandledError(
        ctx: AimiTickContext,
        error: Throwable,
        consoleLog: List<String>?,
        consoleError: List<String>?,
    ): RT {
        val errName = error::class.simpleName ?: "Throwable"
        val msg = error.message?.take(200).orEmpty()
        val mergedLog = (consoleLog?.takeLast(MAX_CONSOLE_TAIL)?.toMutableList() ?: mutableListOf()).apply {
            add("AIMI tick recovered: $errName${if (msg.isNotEmpty()) " — $msg" else ""}")
        }
        val mergedErr = (consoleError?.toMutableList() ?: mutableListOf()).apply {
            add("Unhandled tick error ($errName): $msg")
        }
        return minimalRt(
            ctx = ctx,
            log = mergedLog,
            errors = mergedErr,
            reasonLine = "AIMI safe hold: $errName — no insulin change this tick.",
        )
    }

    private fun minimalRt(
        ctx: AimiTickContext,
        log: MutableList<String>,
        errors: MutableList<String>,
        reasonLine: String,
    ): RT =
        RT(
            algorithm = APSResult.Algorithm.AIMI,
            runningDynamicIsf = ctx.dynIsfMode,
            timestamp = ctx.currentTime,
            bg = ctx.glucoseStatus.glucose,
            deliverAt = ctx.currentTime,
            IOB = ctx.iobDataArray.firstOrNull()?.iob,
            COB = ctx.mealData.mealCOB,
            reason = StringBuilder(reasonLine),
            consoleLog = log,
            consoleError = errors,
        )
}
