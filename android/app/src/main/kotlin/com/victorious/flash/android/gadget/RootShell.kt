package com.victorious.flash.android.gadget

/** A root command runner. Real impl runs `su -c`; tests inject a fake. */
interface RootShell {
    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
    }
    /** Runs one shell command as root. */
    fun run(command: String): Result
}

/** Runs commands via the system `su` binary. Never interpolates untrusted data without quoting. */
class SuRootShell(private val suPath: String = "su") : RootShell {
    override fun run(command: String): RootShell.Result {
        return try {
            val p = ProcessBuilder(listOf(suPath, "-c", command)).redirectErrorStream(false).start()
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val code = p.waitFor()
            RootShell.Result(code, out, err)
        } catch (e: Exception) {
            RootShell.Result(127, "", e.message ?: "su failed")
        }
    }
}
