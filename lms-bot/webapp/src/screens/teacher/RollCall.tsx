import { CircleCheck, CircleDashed, ClipboardCheck, ExternalLink, LoaderCircle, X } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Sheet } from '../../components/Sheet';
import { useToast } from '../../components/Toast';
import { Empty, ErrorState, ListSkeleton } from '../../components/ui';
import { api } from '../../lib/api';
import { invalidate, useQuery } from '../../lib/query';
import { useTT } from '../../lib/ti18n';
import { confirmDialog, haptic, openLink } from '../../lib/tg';
import type { TLesson } from '../../lib/types';

const LMS = 'https://lms.tuit.uz';

/** «17-09-2026» из LMS → Date (местная полночь). */
function parseDmy(s: string): Date | null {
  const m = s.match(/(\d{2})-(\d{2})-(\d{4})/);
  return m ? new Date(Number(m[3]), Number(m[2]) - 1, Number(m[1])) : null;
}

const sameDay = (a: Date, b: Date) => a.getTime() === b.getTime();

/**
 * Пара для переклички. С датой («2026-09-17» из расписания) — пара этого дня; без неё — как в боте:
 * сегодняшняя, иначе последняя неотмеченная, иначе ближайшая.
 */
export function pickLesson(lessons: TLesson[], iso?: string | null): TLesson | null {
  if (iso) {
    const [y, m, d] = iso.split('-').map(Number);
    const want = new Date(y, m - 1, d);
    return lessons.find((l) => {
      const x = parseDmy(l.date);
      return x != null && sameDay(x, want);
    }) ?? null;
  }
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const date = (l: TLesson) => parseDmy(l.date);
  const todays = lessons.find((l) => { const d = date(l); return d != null && sameDay(d, today); });
  if (todays) return todays;
  const missed = lessons.filter((l) => { const d = date(l); return d != null && d < today && !l.marked; });
  if (missed.length) return missed[missed.length - 1];
  return lessons.find((l) => { const d = date(l); return d != null && d > today; }) ?? null;
}

/**
 * Отметки занятия. Пока отметку можно менять (в LMS есть форма), тап по студенту ставит или снимает НБ,
 * а «Сохранить» отправляет всю перекличку; иначе — только просмотр и ссылка на сайт.
 */
export function AttendanceView({ courseId, lessonId }: { courseId: number; lessonId: number }) {
  const tt = useTT();
  const toast = useToast();
  const q = useQuery(`tatt:${courseId}:${lessonId}`, (f) => api.t.attendance(courseId, lessonId, f));
  const a = q.data;
  // Правки хранятся отдельно от данных сервера: null — ничего не менялось.
  const [edited, setEdited] = useState<Set<number> | null>(null);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  useEffect(() => setEdited(null), [a]);

  const editable = !!a?.editable && a.students.every((s) => s.id != null);
  const serverAbsent = useMemo(() => new Set((a?.students ?? []).filter((s) => s.present === false && s.id != null).map((s) => s.id!)), [a]);
  const absentIds = edited ?? serverAbsent;
  const isAbsent = (s: NonNullable<typeof a>['students'][number]) => (editable ? absentIds.has(s.id!) : s.present === false);
  // Пока перекличку не трогали и НБ ещё нет, никто не отмечен: пустое состояние, а не «все были».
  const touched = edited != null || serverAbsent.size > 0 || saved;
  const isPresent = (s: NonNullable<typeof a>['students'][number]) => (editable ? touched && !absentIds.has(s.id!) : s.present === true);

  if (q.loading) return <ListSkeleton rows={5} />;
  if (q.error) return <ErrorState error={q.error} onRetry={q.refresh} />;
  if (!a) return null;

  const absent = a.students.filter(isAbsent);
  const present = a.students.filter(isPresent);
  const none = editable ? [] : a.students.filter((s) => s.present == null);
  const ordered = editable ? a.students : [...absent, ...none, ...present];

  const toggle = (id: number) => {
    haptic('selection');
    const next = new Set(absentIds);
    if (!next.delete(id)) next.add(id);
    setEdited(next);
  };

  const save = async () => {
    haptic('warning');
    if (!(await confirmDialog(tt('att_confirm', { n: absentIds.size })))) return;
    setSaving(true);
    const res = await api.t.saveAttendance(courseId, lessonId, [...absentIds]).catch(() => null);
    setSaving(false);
    if (!res?.ok) {
      const why = res?.errors?.[0];
      return toast(why === 'closed' ? tt('att_closed') : why === 'not_saved' ? tt('att_not_saved') : res?.errors?.join(', ') || tt('lms_rejected'), 'error');
    }
    haptic('success');
    setSaved(true);
    toast(tt('att_saved'));
    invalidate(`tcal:${courseId}`);
    q.refresh();
  };

  return (
    <>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginBottom: 12 }}>
        <div className="card" style={{ padding: 14 }}>
          <div className="stat-label" style={{ marginTop: 0 }}>{tt('present')}</div>
          <div className="stat-value tabular" style={{ color: 'var(--success)', fontSize: 24 }}>{editable && !touched ? '—' : present.length}</div>
        </div>
        <div className="card" style={{ padding: 14 }}>
          <div className="stat-label" style={{ marginTop: 0 }}>{tt('absent')}</div>
          <div className="stat-value tabular" style={{ color: absent.length ? 'var(--danger)' : undefined, fontSize: 24 }}>{editable && !touched ? '—' : absent.length}</div>
        </div>
      </div>
      {editable ? (
        <>
          <div className="row-sub" style={{ margin: '0 4px 12px' }}>{tt('att_tap_hint')}</div>
          <button className="btn" style={{ marginBottom: 10 }} onClick={save} disabled={saving}>
            {saving ? <LoaderCircle size={18} className="spin" /> : <ClipboardCheck size={18} />} {tt('att_save')}
          </button>
          <button
            className="btn tinted"
            style={{ marginBottom: 12 }}
            onClick={() => {
              haptic('light');
              openLink(`${LMS}/teacher/attendance/show/${courseId}/${lessonId}`);
            }}
          >
            <ExternalLink size={18} /> {tt('mark_in_lms')}
          </button>
        </>
      ) : (
        <>
          {present.length + absent.length === 0 && <div className="row-sub" style={{ margin: '0 4px 12px' }}>{tt('roll_call_unmarked')}</div>}
          <div className="row-sub" style={{ margin: '0 4px 12px' }}>{tt('att_closed')}</div>
        </>
      )}
      <div className="list">
        {ordered.map((s) => {
          const abs = isAbsent(s);
          const pres = isPresent(s);
          const inner = (
            <>
              <span className={'tile ' + (abs ? 'danger' : pres ? 'success' : 'neutral')}>
                {abs ? <X size={18} /> : pres ? <CircleCheck size={18} /> : <CircleDashed size={18} />}
              </span>
              <div className="row-main">
                <div className="row-title" style={{ fontSize: 14.5 }}>{s.number}. {s.name}</div>
                <div className="row-sub">{s.group}</div>
              </div>
            </>
          );
          return editable ? (
            <button className="row with-icon pressable" key={s.number} onClick={() => toggle(s.id!)}>{inner}</button>
          ) : (
            <div className="row with-icon" key={s.number}>{inner}</div>
          );
        })}
      </div>
    </>
  );
}

/**
 * Перекличка пары: из расписания открывается по дате пары, из карточки потока — без даты.
 * Календарный план потока ищет нужное занятие, дальше — обычный экран посещаемости.
 */
export function RollCallSheet({ courseId, title, date, open, onClose }: {
  courseId: number | null;
  title?: string;
  date?: string | null;
  open: boolean;
  onClose: () => void;
}) {
  const tt = useTT();
  const q = useQuery(open && courseId ? `tcal:${courseId}` : null, (f) => api.t.calendar(courseId!, f));
  const lesson = useMemo(() => (q.data ? pickLesson(q.data.lessons, date) : null), [q.data, date]);

  return (
    <Sheet
      open={open}
      onClose={onClose}
      title={lesson ? `${lesson.number}. ${lesson.topic}` : title ?? tt('roll_call')}
      subtitle={q.data ? `${q.data.stream}${lesson ? ` · ${lesson.date}` : ''}` : undefined}
    >
      {q.loading ? (
        <ListSkeleton rows={5} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={q.refresh} />
      ) : lesson?.lessonId && courseId ? (
        <AttendanceView courseId={courseId} lessonId={lesson.lessonId} />
      ) : q.data ? (
        <div className="card"><Empty icon={ClipboardCheck} title={tt('roll_call_none')} sub={tt('roll_call_none_sub')} /></div>
      ) : null}
    </Sheet>
  );
}
