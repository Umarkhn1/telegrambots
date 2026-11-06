package uz.tuit.lms.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uz.tuit.lms.data.Prefs
import uz.tuit.lms.data.Repo
import uz.tuit.lms.data.rememberQuery
import uz.tuit.lms.ui.LocalNav
import uz.tuit.lms.ui.Route
import uz.tuit.lms.ui.components.EmptyState
import uz.tuit.lms.ui.components.ErrorState
import uz.tuit.lms.ui.components.IconTile
import uz.tuit.lms.ui.components.ListGroup
import uz.tuit.lms.ui.components.ListRow
import uz.tuit.lms.ui.components.ListSkeleton
import uz.tuit.lms.ui.components.LocalTr
import uz.tuit.lms.ui.components.MetaRow
import uz.tuit.lms.ui.components.OfflineBanner
import uz.tuit.lms.ui.components.Pill
import uz.tuit.lms.ui.components.RowSub
import uz.tuit.lms.ui.components.RowTitle
import uz.tuit.lms.ui.components.SemesterChip
import uz.tuit.lms.ui.components.SemesterSheet
import uz.tuit.lms.ui.components.StreamChip
import uz.tuit.lms.ui.components.TabScreen
import uz.tuit.lms.ui.components.Tone

/** Цвет плитки предмета — стабильный по id, чтобы список не «мигал» при обновлении. */
val TONES = listOf(Tone.ACCENT, Tone.VIOLET, Tone.SUCCESS, Tone.WARNING)

@Composable
fun CoursesScreen() {
    val t = LocalTr.current
    val nav = LocalNav.current
    val semState by Prefs.semester.collectAsState()
    val sem = remember(semState) { Prefs.semesterId() }
    var semOpen by remember { mutableStateOf(false) }
    val q = rememberQuery("courses:$sem") { Repo.courses(sem) }

    TabScreen(
        title = t("courses_title"),
        subtitle = q.data?.let { t("subjects", "n" to it.size) },
        refreshing = q.refreshing,
        onRefresh = q::refresh,
    ) {
        item { androidx.compose.foundation.layout.Box(Modifier.padding(bottom = 14.dp)) { SemesterChip { semOpen = true } } }
        item { OfflineBanner(q.error != null && q.data != null, q::refresh) }
        val list = q.data
        when {
            q.initialLoading -> item { ListSkeleton(6, tall = true) }
            list == null -> item { ErrorState(q.error, q::refresh) }
            list.isEmpty() -> item { EmptyState(Icons.Rounded.MenuBook, t("no_courses")) }
            else -> item {
                ListGroup(list, key = { it.id }) { _, c, shape ->
                    ListRow(
                        shape = shape,
                        onClick = { nav.push(Route.Course(c)) },
                        leading = { IconTile(Icons.Rounded.MenuBook, TONES[c.id % TONES.size]) },
                        verticalAlignment = Alignment.Top,
                    ) {
                        RowTitle(c.subject)
                        if (c.teachers.isNotEmpty()) RowSub(c.teachers.joinToString(", ") { it.name }, maxLines = 2)
                        Spacer(Modifier.height(8.dp))
                        MetaRow {
                            c.teachers.map { it.stream }.filter { it.isNotBlank() }.distinct().forEach { StreamChip(it) }
                            Pill("${t("nb")}: ${c.attendance}", if (c.attendance > 0) Tone.DANGER else Tone.SUCCESS)
                            if (c.failed) Pill(t("failed"), Tone.WARNING, Icons.Rounded.WarningAmber)
                        }
                    }
                }
            }
        }
    }
    SemesterSheet(semOpen) { semOpen = false }
}
