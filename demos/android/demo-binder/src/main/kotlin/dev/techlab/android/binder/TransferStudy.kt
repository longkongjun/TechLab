package dev.techlab.android.binder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.AtomicFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.ceil

private const val WARMUPS = 2
private const val ROUNDS = 20

internal suspend fun runStudy(app: Context, progress: (String) -> Unit): String = withContext(Dispatchers.IO) {
    val ready = CompletableDeferred<ITransferSource>()
    val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            ready.complete(ITransferSource.Stub.asInterface(binder))
        }
        override fun onServiceDisconnected(name: ComponentName) {
            ready.completeExceptionally(IOException("数据进程已断开"))
        }
    }
    check(app.bindService(Intent(app, TransferService::class.java), conn, Context.BIND_AUTO_CREATE))
    try {
        val source = withTimeout(10000) { ready.await() }
        val pid = source.diagnostics().getInt("pid")
        check(pid != Process.myPid())
        progress("正在校验输入样本，并检查中断和取消后的资源回收")
        val checks = functionalChecks(source)
        val strategies = availableStrategies()
        val inputs = (binarySizes.keys + LIVE_QUESTION).map(TransferInput::StoredFile) + TransferInput.FreshPng
        val plan = inputs.associateWith { input -> strategies.filter { it.supports(input) } }
        val rows = mutableListOf<Timing>()
        for ((input, routes) in plan) {
            val meta = source.describe(input.sourceId)
            check(meta.getString("id") == input.sourceId)
            progress("正在测量 ${input.displayName} · ${meta.getInt("length")} 字节")
            for (round in -WARMUPS until ROUNDS) {
                for (i in routes.indices) {
                    currentCoroutineContext().ensureActive()
                    val route = routes[(i + round + WARMUPS) % routes.size]
                    val timing = measureTransfer(source, input, meta, route, round + 1)
                    if (round >= 0) rows += timing
                }
            }
        }
        val json = JSONObject().put("implementation", "strategy-v1")
            .put("model", Build.MODEL).put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS.first())
            .put("buildId", Build.ID).put("clientPid", Process.myPid()).put("sourcePid", pid)
            .put("warmups", WARMUPS).put("rounds", ROUNDS).put("blockBytes", BLOCK)
            .put("plan", JSONObject().apply {
                plan.forEach { (input, routes) -> put(input.reportId, JSONArray(routes.map { it.id })) }
            })
            .put("checks", checks).put("samples", JSONArray(rows.map { it.json() }))
        val file = AtomicFile(File(app.filesDir, "binder-study.json"))
        val stream = file.startWrite()
        try { stream.write(json.toString(2).toByteArray()); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        buildString {
            appendLine("${Build.MODEL} · 客户端 PID ${Process.myPid()} / 服务端 $pid")
            appendLine("样本校验与异常回收通过，${rows.size} 个计时样本")
            val groups = rows.groupBy { it.id }
            for (input in inputs) {
                val group = groups[input.reportId] ?: continue
                appendLine()
                appendLine("${input.displayName} · ${group.first().bytes} 字节")
                val byStrategy = group.groupBy { it.strategy }
                for (strategy in strategies) {
                    val times = byStrategy[strategy] ?: continue
                    fun percentile(p: Double, value: (Timing) -> Long) =
                        times.map(value).sorted()[ceil(p * times.size).toInt() - 1] / 1e6
                    appendLine(String.format(Locale.ROOT, "%s　%.2f / %.2f / %.2f ms", strategy.title,
                        percentile(.5) { it.firstNs }, percentile(.5) { it.totalNs }, percentile(.95) { it.totalNs }))
                }
            }
        }.trimEnd()
    } finally { app.unbindService(conn) }
}
