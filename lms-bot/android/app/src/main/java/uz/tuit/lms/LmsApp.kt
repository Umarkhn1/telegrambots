package uz.tuit.lms

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import uz.tuit.lms.data.I18n
import uz.tuit.lms.data.Prefs
import uz.tuit.lms.data.Repo
import uz.tuit.lms.notify.Notifier
import uz.tuit.lms.notify.Reminders
import uz.tuit.lms.notify.SyncWorker
import uz.tuit.lms.widget.Widgets

class LmsApp : Application() {

    /** Фоновые дела процесса: обновить виджеты и будильники, когда пришли новые данные. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        I18n.init(this)
        Repo.init(this)
        Notifier.createChannels(this)
        SyncWorker.schedule(this)

        scope.launch {
            Repo.changes
                .filter { it == "*" || it.startsWith("sched:") || it.startsWith("dl:") || it.startsWith("courses:") || it == "plan" || it == "grading_summary" }
                .debounce(800)
                .collect {
                    Widgets.updateAll(this@LmsApp)
                    Reminders.reschedule(this@LmsApp)
                }
        }
        scope.launch { Prefs.lang.collect { Widgets.updateAll(this@LmsApp) } }
    }

    companion object {
        lateinit var instance: LmsApp
            private set
    }
}
