package uz.tuit.lms.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uz.tuit.lms.data.ActivityUi
import uz.tuit.lms.data.Prefs
import uz.tuit.lms.data.Repo
import uz.tuit.lms.data.rememberQuery
import uz.tuit.lms.ui.components.ActivityRow
import uz.tuit.lms.ui.components.ActivitySheet
import uz.tuit.lms.ui.components.DetailScreen
import uz.tuit.lms.ui.components.EmptyState
import uz.tuit.lms.ui.components.ErrorState
import uz.tuit.lms.ui.components.ListGroup
import uz.tuit.lms.ui.components.ListSkeleton
import uz.tuit.lms.ui.components.LocalTr
import uz.tuit.lms.ui.components.OfflineBanner
import uz.tuit.lms.ui.components.Opt
import uz.tuit.lms.ui.components.SectionHeader
import uz.tuit.lms.ui.components.Segmented
import uz.tuit.lms.ui.components.Tone

private const val DAY = 24 * 3600_000L

@Composable
fun DeadlinesScreen() {
    val t = LocalTr.current
    val sem = remember { Prefs.semesterId() }
    var mode by rememberSaveable { mutableStateOf("open") }
    var open by remember { mutableStateOf<ActivityUi?>(null) }
    val q = rememberQuery("dl:$sem") { Repo.deadlines(sem) }

    val now = System.currentTimeMillis()
    val all = q.data ?: emptyList()
    val items = if (mode == "all") all else all.filter { (mode == "done") == it.submitted }
    // Несданные — по срочности (просроченные отдельно), сданные — своей группой.
    val notDone = items.filter { !it.submitted }
    fun left(d: ActivityUi) = (d.deadlineTs ?: Long.MAX_VALUE) - now
    val groups = listOf(
        "group_overdue" to notDone.filter { left(it) < 0 },
        "group_24h" to notDone.filter { left(it) in 0 until DAY },
        "group_3d" to notDone.filter { left(it) in DAY until 3 * DAY },
        "group_later" to notDone.filter { left(it) >= 3 * DAY },
        // Сданные: оценённые и те, что ещё ждут оценки, — свежие сверху.
        "group_graded" to items.filter { it.submitted && it.graded }.sortedByDescending { it.deadlineTs ?: 0 },
        "group_done" to items.filter { it.submitted && !it.graded }.sortedByDescending { it.deadlineTs ?: 0 },
    ).filter { it.second.isNotEmpty() }

    DetailScreen(title = t("deadlines_title"), refreshing = q.refreshing, onRefresh = q::refresh) {
        item {
            Segmented(
                listOf(Opt("open", t("only_pending")), Opt("done", t("only_done")), Opt("all", t("all_items"))),
                mode, { mode = it }, Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }
        item { OfflineBanner(q.error != null && q.data != null, q::refresh) }
        when {
            q.initialLoading -> item { androidx.compose.foundation.layout.Box(Modifier.padding(top = 16.dp)) { ListSkeleton(5, tall = true) } }
            q.data == null -> item { ErrorState(q.error, q::refresh, Modifier.padding(top = 16.dp)) }
            items.isEmpty() -> item { EmptyState(Icons.Rounded.TaskAlt, t("no_deadlines"), t("no_deadlines_sub"), Tone.SUCCESS, Modifier.padding(top = 16.dp)) }
            else -> groups.forEach { (key, list) ->
                item(key = key + "h") { SectionHeader("${t(key)} · ${list.size}") }
                item(key = key) { ListGroup(list, key = { it.key }) { _, d, shape -> ActivityRow(d, shape, showCourse = true) { open = d } } }
            }
        }
    }
    ActivitySheet(open, open?.courseId ?: 0, open?.course) { open = null }
}
