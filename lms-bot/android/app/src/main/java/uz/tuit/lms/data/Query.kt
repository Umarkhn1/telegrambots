package uz.tuit.lms.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Загрузка данных для экрана: сразу показывает сохранённое (даже без сети),
 * затем при необходимости обновляет из LMS. Как useQuery в мини-приложении.
 */
@Stable
class Query<T>(private val scope: CoroutineScope) {
    var data by mutableStateOf<T?>(null)
        internal set
    var loading by mutableStateOf(false)
        internal set
    var refreshing by mutableStateOf(false)
        internal set
    var error by mutableStateOf<Throwable?>(null)
        internal set
    var updatedAt by mutableStateOf(0L)
        internal set

    internal var res: Res<T>? = null
    private var job: Job? = null

    /** Первая загрузка ещё идёт и показать нечего. */
    val initialLoading get() = data == null && (loading || (res != null && error == null))

    internal fun start(r: Res<T>?) {
        res = r
        job?.cancel()
        data = null
        error = null
        updatedAt = 0
        if (r == null) {
            loading = false
            return
        }
        val cached = Repo.peek(r)
        if (cached != null) {
            data = cached.value
            updatedAt = cached.ts
        }
        val stale = cached == null || System.currentTimeMillis() - cached.ts >= r.ttl
        if (stale) load(fresh = false, pull = false)
    }

    private fun load(fresh: Boolean, pull: Boolean): Job? {
        val r = res ?: return null
        job?.cancel()
        loading = true
        if (pull) refreshing = true
        return scope.launch {
            try {
                val v = Repo.get(r, fresh)
                data = v
                updatedAt = System.currentTimeMillis()
                error = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e
            } finally {
                loading = false
                refreshing = false
            }
        }.also { job = it }
    }

    /** Потянуть вниз / «Повторить»: всегда свежий запрос в LMS. */
    fun refresh() { load(fresh = true, pull = true) }

    suspend fun refreshAndWait() { load(fresh = true, pull = true)?.join() }

    internal fun reloadSilently() { load(fresh = false, pull = false) }
}

@Composable
fun <T> rememberQuery(key: String?, res: () -> Res<T>): Query<T> {
    val scope = rememberCoroutineScope()
    val q = remember { Query<T>(scope) }
    LaunchedEffect(key) { q.start(if (key == null) null else res()) }
    // Сброс кэша (после загрузки работы, оценки, смены роли) — перечитываем.
    LaunchedEffect(q) {
        Repo.invalidations.collect { prefix ->
            val k = q.res?.key ?: return@collect
            if (k.startsWith(prefix)) q.reloadSilently()
        }
    }
    return q
}

/** Ошибка → ключ текста. */
fun errorKey(e: Throwable?): String = when (e) {
    is LmsUnavailableException -> if (e.message == "offline") "error_network" else "error_lms"
    is ApiException -> when (e.code) {
        "no_semester" -> "no_semester"
        else -> "error_lms"
    }
    is java.io.IOException -> "error_network"
    else -> "error_generic"
}

@Composable
fun OnDispose(block: () -> Unit) {
    DisposableEffect(Unit) { onDispose(block) }
}
