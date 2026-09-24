import { AnimatePresence, motion } from 'framer-motion';
import {
  CalendarCheck2,
  CalendarClock,
  ChevronLeft,
  ChevronRight,
  CircleCheck,
  CircleDashed,
  Clock3,
  FileX2,
  Hourglass,
  ListChecks,
  LoaderCircle,
  NotebookText,
  Plus,
  Trash2,
  UserX,
  UsersRound,
  X,
} from 'lucide-react';
import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { FileRow } from '../../components/FileRow';
import { Sheet } from '../../components/Sheet';
import { useToast } from '../../components/Toast';
import { Chips, Empty, ErrorState, ListSkeleton, listItem, Screen, Section, Segmented, StreamChip } from '../../components/ui';
import { api, ApiError } from '../../lib/api';
import { useI18n } from '../../lib/i18n';
import { invalidate, useQuery } from '../../lib/query';
import { useTT } from '../../lib/ti18n';
import { confirmDialog, haptic } from '../../lib/tg';
import type { GCell, GradeForm, GradeSheet, GRow, TActivities, TCourse, TLesson } from '../../lib/types';

type Tab = 'sheet' | 'plan' | 'activities';

export function TeacherCourse({ course, column, onBack }: { course: TCourse; column?: number; onBack: () => void }) {
  const { t } = useI18n();
  const tt = useTT();
  const [tab, setTab] = useState<Tab>('sheet');
  const sheet = useQuery(`tsheet:${course.id}`, (f) => api.t.sheet(course.id, f));

  return (
    <Screen stacked onRefresh={() => sheet.refresh()}>
      <div className="back-head">
        <button className="icon-btn" onClick={onBack} aria-label={t('back')}>
          <ChevronLeft size={22} />
        </button>
      </div>
      <h1 className="page-title" style={{ fontSize: 25 }}>{course.subject}</h1>
      <div className="meta" style={{ marginTop: 8 }}>
        <span className="badge accent" style={{ height: 22 }}>{course.type}</span>
        {course.stream && <StreamChip stream={course.stream} />}
        <span>
          <UsersRound size={13} /> {tt('students_n', { n: course.students })}
        </span>
      </div>

      <Segmented<Tab>
        value={tab}
        onChange={setTab}
        style={{ marginTop: 18 }}
        options={[
          { value: 'sheet', label: tt('tab_sheet') },
          { value: 'plan', label: tt('tab_plan') },
          { value: 'activities', label: tt('tab_activities') },
        ]}
      />

      <AnimatePresence mode="wait" initial={false}>
        <motion.div
          key={tab}
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -6 }}
          transition={{ duration: 0.18 }}
          style={{ marginTop: 14 }}
        >
          {tab === 'sheet' && <SheetTab course={course} q={sheet} initial={column} />}
          {tab === 'plan' && <PlanTab course={course} />}
          {tab === 'activities' && <ActivitiesTab course={course} />}
        </motion.div>
      </AnimatePresence>
    </Screen>
  );
}

/* ─── Ведомость ─── */

const pending = (c: GCell) => c.submitted && !c.grade;
const graded = (c: GCell) => !!c.grade;
type Filter = 'all' | 'pending' | 'graded' | 'none';

function cellTone(c: GCell) {
  return pending(c) ? 'warning' : graded(c) ? 'success' : c.submitted ? 'accent' : 'neutral';
}

function SheetTab({ course, q, initial }: { course: TCourse; q: ReturnType<typeof useQuery<GradeSheet>>; initial?: number }) {
  const tt = useTT();
  const [col, setCol] = useState<string | null>(initial != null ? String(initial) : null);
  const [filter, setFilter] = useState<Filter>('all');
  const [open, setOpen] = useState<GRow | null>(null);
  const s = q.data;

  const current = useMemo(() => {
    if (!s || !s.columns.length) return 0;
    if (col === 'totals') return -1;
    if (col != null && Number(col) < s.columns.length) return Number(col);
    // По умолчанию — активность, где больше всего непроверенных работ.
    let best = 0, max = -1;
    s.columns.forEach((_, k) => {
      const n = s.rows.filter((r) => pending(r.cells[k])).length;
      if (n > max) { max = n; best = k; }
    });
    return best;
  }, [s, col]);

  if (q.loading) return <ListSkeleton rows={5} />;
  if (q.error) return <ErrorState error={q.error} onRetry={q.refresh} />;
  if (!s) return null;
  if (!s.columns.length) return <div className="card"><Empty icon={ListChecks} title={tt('no_columns')} /></div>;

  const colInfo = current >= 0 ? s.columns[current] : null;
  const rows = current >= 0 ? orderRows(s, current) : s.rows;
  const count = (f: Filter) =>
    current < 0 ? 0 : rows.filter((r) => matches(r.cells[current], f)).length;
  const shown = current >= 0 ? rows.filter((r) => matches(r.cells[current], filter)) : rows;

  return (
    <>
      {s.note && <div className="row-sub" style={{ margin: '0 4px 12px' }}>{s.note}</div>}
      <Chips<string>
        value={current < 0 ? 'totals' : String(current)}
        onChange={(v) => {
          setCol(v);
          setFilter('all');
        }}
        options={[
          ...s.columns.map((c, k) => ({ value: String(k), label: c.name, count: s.rows.filter((r) => pending(r.cells[k])).length || undefined })),
          { value: 'totals', label: tt('totals') },
        ]}
      />

      {colInfo && (
        <div className="card" style={{ marginTop: 12 }}>
          <div className="meta">
            {colInfo.studentDeadline && (
              <span><CalendarClock size={13} /> {tt('student_deadline')}: <b className="tabular">{colInfo.studentDeadline}</b></span>
            )}
            {colInfo.teacherDeadline && (
              <span><Clock3 size={13} /> {tt('teacher_deadline')}: <b className="tabular">{colInfo.teacherDeadline}</b></span>
            )}
            {colInfo.max && <span>{s.labels.max} <b>{colInfo.max}</b></span>}
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 10, marginTop: 12 }}>
            {[
              { label: tt('submitted'), v: rows.filter((r) => r.cells[current].submitted).length, tone: 'accent' },
              { label: tt('graded'), v: count('graded'), tone: 'success' },
              { label: tt('pending'), v: count('pending'), tone: 'warning' },
            ].map((x) => (
              <div key={x.label}>
                <div className="stat-label" style={{ marginTop: 0 }}>{x.label}</div>
                <div className="stat-value tabular" style={{ fontSize: 22, marginTop: 2, color: `var(--${x.tone})` }}>{x.v}</div>
              </div>
            ))}
          </div>
        </div>
      )}

      {current >= 0 && (
        <div style={{ marginTop: 12 }}>
          <Chips<Filter>
            value={filter}
            onChange={setFilter}
            options={[
              { value: 'all', label: '∑', count: rows.length },
              { value: 'pending', label: tt('pending'), count: count('pending') },
              { value: 'graded', label: tt('graded'), count: count('graded') },
              { value: 'none', label: tt('not_submitted'), count: count('none') },
            ]}
          />
        </div>
      )}

      <div className="list" style={{ marginTop: 12 }}>
        {shown.map((r, i) => {
          const c = current >= 0 ? r.cells[current] : null;
          return (
            <motion.button
              key={r.number}
              className="row with-icon pressable"
              variants={listItem}
              initial="hidden"
              animate="show"
              custom={i}
              disabled={!c || c.studentId == null || c.activityId == null}
              onClick={() => {
                haptic('light');
                setOpen(r);
              }}
            >
              <span className={'tile tabular ' + (c ? cellTone(c) : 'neutral')} style={{ fontWeight: 700, fontSize: 14 }}>
                {r.number}
              </span>
              <div className="row-main">
                <div className="row-title clamp-2" style={{ fontSize: 15 }}>{r.name}</div>
                {r.group && <div className="row-sub">{r.group}</div>}
              </div>
              {c ? (
                c.grade ? (
                  <span className="badge success tabular">{c.grade}</span>
                ) : c.submitted ? (
                  <span className="badge warning"><Hourglass size={12} /></span>
                ) : null
              ) : (
                <div style={{ textAlign: 'right' }}>
                  <div className="row-title tabular" style={{ fontSize: 15 }}>{r.total}</div>
                  <div className="row-sub tabular">{r.percent}</div>
                </div>
              )}
              {c && <ChevronRight size={18} className="chev" />}
            </motion.button>
          );
        })}
        {!shown.length && (
          <div className="row"><div className="row-sub">—</div></div>
        )}
      </div>

      {current >= 0 && (
        <GradeSheetModal
          course={course}
          sheet={s}
          col={current}
          order={rows}
          row={open}
          onMove={setOpen}
          onClose={() => setOpen(null)}
          onSaved={() => {
            invalidate('tgrading');
            q.refresh();
          }}
        />
      )}
    </>
  );
}

function matches(c: GCell, f: Filter) {
  return f === 'all' ? true : f === 'pending' ? pending(c) : f === 'graded' ? graded(c) : !c.submitted && !graded(c);
}

/** Сначала сданные без оценки, потом оценённые, затем остальные. */
function orderRows(s: GradeSheet, col: number): GRow[] {
  const rank = (r: GRow) => (pending(r.cells[col]) ? 0 : graded(r.cells[col]) ? 1 : 2);
  return [...s.rows].sort((a, b) => rank(a) - rank(b) || a.number - b.number);
}

/* ─── Окно оценивания ─── */

function GradeSheetModal({
  course,
  sheet,
  col,
  order,
  row,
  onMove,
  onClose,
  onSaved,
}: {
  course: TCourse;
  sheet: GradeSheet;
  col: number;
  order: GRow[];
  row: GRow | null;
  onMove: (r: GRow) => void;
  onClose: () => void;
  onSaved: () => void;
}) {
  const tt = useTT();
  const { t } = useI18n();
  const toast = useToast();
  const L = sheet.labels;
  const cell = row ? row.cells[col] : null;
  const [form, setForm] = useState<GradeForm | null>(null);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [grade, setGrade] = useState('');
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState<'save' | 'clear' | null>(null);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  const last = useRef<GRow | null>(null);
  if (row) last.current = row;
  const shown = last.current;

  const fill = (f: GradeForm) => {
    setForm(f);
    setValues(Object.fromEntries(f.criteria.map((c) => [c.id, c.value])));
    setGrade(f.grade);
    setComment(f.comment);
  };

  useEffect(() => {
    if (!cell?.studentId || !cell.activityId) return;
    let alive = true;
    setForm(null);
    setLoadError(null);
    setError('');
    api.t
      .gradeForm(cell.studentId, cell.activityId)
      .then((f) => alive && fill(f))
      .catch((e) => alive && setLoadError(e));
    return () => {
      alive = false;
    };
  }, [cell?.studentId, cell?.activityId, reload]);

  const idx = row ? order.findIndex((r) => r.number === row.number) : -1;
  const move = (d: number) => {
    if (idx < 0 || !order.length) return;
    haptic('selection');
    onMove(order[(idx + d + order.length) % order.length]);
  };

  const total = form && !form.module && form.criteria.length
    ? form.criteria.reduce((s, c) => s + (Number(values[c.id]?.replace(',', '.')) || 0), 0)
    : null;

  const validate = (): string | null => {
    if (!form) return null;
    const check = (v: string, max: string) => {
      if (v.trim() === '') return tt('bad_number');
      const n = Number(v.replace(',', '.'));
      if (Number.isNaN(n)) return tt('bad_number');
      if (n < 0 || (max && n > Number(max))) return tt('out_of_range');
      return null;
    };
    if (form.module || !form.criteria.length) return check(grade, form.maxPoint);
    for (const c of form.criteria) {
      const e = check(values[c.id] ?? '', c.max);
      if (e) return `${c.name}: ${e}`;
    }
    return null;
  };

  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (!form || !cell?.studentId || !cell.activityId) return;
    const err = validate();
    if (err) return setError(err);
    setBusy('save');
    setError('');
    try {
      const res = await api.t.setGrade(cell.studentId, cell.activityId, {
        values: form.module ? undefined : Object.fromEntries(Object.entries(values).map(([k, v]) => [k, v.replace(',', '.')])),
        grade: form.module || !form.criteria.length ? grade.replace(',', '.') : undefined,
        comment,
      });
      if (!res.ok) return setError(res.errors.join('\n') || tt('lms_rejected'));
      if (res.form) fill(res.form);
      toast(tt('saved'));
      onSaved();
    } catch (ex) {
      setError(ex instanceof ApiError && ex.code === 'out_of_range' ? tt('out_of_range') : t('error_lms'));
    } finally {
      setBusy(null);
    }
  };

  const clear = async () => {
    if (!cell?.studentId || !cell.activityId) return;
    haptic('warning');
    if (!(await confirmDialog(tt('clear_confirm')))) return;
    setBusy('clear');
    try {
      const res = await api.t.clearGrade(cell.studentId, cell.activityId);
      if (!res.ok) return setError(res.errors.join('\n'));
      toast(tt('cleared'));
      fill(await api.t.gradeForm(cell.studentId, cell.activityId));
      onSaved();
    } catch {
      setError(t('error_lms'));
    } finally {
      setBusy(null);
    }
  };

  const colInfo = sheet.columns[col];

  return (
    <Sheet
      open={!!row}
      onClose={onClose}
      title={shown?.name}
      subtitle={`${colInfo?.name ?? ''} · ${course.stream ?? course.subject}`}
    >
      {loadError ? (
        <ErrorState error={loadError} onRetry={() => setReload((n) => n + 1)} />
      ) : !form ? (
        <div style={{ display: 'grid', placeItems: 'center', padding: 30 }}>
          <LoaderCircle size={22} className="spin" color="var(--text-3)" />
        </div>
      ) : (
        <form onSubmit={save} className="stack" style={{ gap: 14 }}>
          <div className="list" style={{ background: 'var(--surface-2)', boxShadow: 'none' }}>
            <div className="kv"><span className="kv-label">{tt('student_deadline')}</span><span className="kv-value tabular">{form.deadline || '—'}</span></div>
            <div className="kv"><span className="kv-label">{L.max}</span><span className="kv-value tabular">{form.maxPoint}</span></div>
          </div>

          {form.files.length ? (
            <div className="list">
              {form.files.map((f) => <FileRow key={f.url} name={f.name} url={f.url} />)}
            </div>
          ) : (
            <div className="row-sub" style={{ display: 'flex', gap: 6, alignItems: 'center', marginLeft: 4 }}>
              <FileX2 size={14} /> {form.fileText || tt('file_none')}
            </div>
          )}

          {!form.editable && <div className="error-text" style={{ color: 'var(--warning)' }}>{tt('locked')}</div>}

          {form.module || !form.criteria.length ? (
            <label className="field">
              <span className="field-label">{L.grade ?? 'grade'} (0 – {form.maxPoint})</span>
              <input
                className="input plain"
                inputMode="decimal"
                value={grade}
                disabled={!form.editable}
                onChange={(e) => setGrade(e.target.value)}
              />
            </label>
          ) : (
            <div>
              <div className="crit-row field-label" style={{ marginBottom: 6 }}>
                <span>{L.criterion}</span>
                <span style={{ textAlign: 'right' }}>{L.points} / {L.max}</span>
              </div>
              <div className="stack" style={{ gap: 8 }}>
                {form.criteria.map((c) => (
                  <div className="crit-row" key={c.id}>
                    <span className="row-title" style={{ fontSize: 14.5, alignSelf: 'center' }}>{c.name}</span>
                    <span className="input-wrap">
                      <input
                        className="input plain tabular"
                        inputMode="decimal"
                        style={{ textAlign: 'center', paddingRight: 34 }}
                        value={values[c.id] ?? ''}
                        disabled={!form.editable}
                        onChange={(e) => setValues((v) => ({ ...v, [c.id]: e.target.value }))}
                      />
                      <span className="crit-max tabular">/{c.max}</span>
                    </span>
                  </div>
                ))}
              </div>
              {total != null && (
                <div className="row-title tabular" style={{ textAlign: 'right', marginTop: 10 }}>
                  {L.total}: {Math.round(total * 100) / 100} / {form.maxPoint}
                </div>
              )}
            </div>
          )}

          <label className="field">
            <span className="field-label">{L.comment ?? ''}</span>
            <textarea
              className="input plain"
              rows={3}
              placeholder={tt('comment_ph')}
              value={comment}
              disabled={!form.editable}
              onChange={(e) => setComment(e.target.value)}
            />
          </label>
          {sheet.hint && <div className="row-sub" style={{ marginLeft: 4 }}>{sheet.hint}</div>}
          {error && <div className="error-text" style={{ whiteSpace: 'pre-line' }}>{error}</div>}

          {form.editable && (
            <div style={{ display: 'flex', gap: 8 }}>
              {form.canClear && form.grade && (
                <button type="button" className="btn danger" style={{ width: 'auto' }} onClick={clear} disabled={!!busy}>
                  {busy === 'clear' ? <LoaderCircle size={18} className="spin" /> : <Trash2 size={18} />}
                </button>
              )}
              <button className="btn" type="submit" disabled={!!busy}>
                {busy === 'save' && <LoaderCircle size={18} className="spin" />}
                {L.save ?? 'OK'}
              </button>
            </div>
          )}

          <div style={{ display: 'flex', gap: 8 }}>
            <button type="button" className="btn secondary small" onClick={() => move(-1)}>
              <ChevronLeft size={16} /> {tt('prev')}
            </button>
            <button type="button" className="btn secondary small" onClick={() => move(1)}>
              {tt('next')} <ChevronRight size={16} />
            </button>
          </div>
        </form>
      )}
    </Sheet>
  );
}

/* ─── Календарный план и посещаемость ─── */

function parseDmy(s: string): Date | null {
  const m = s.match(/(\d{2})-(\d{2})-(\d{4})/);
  return m ? new Date(Number(m[3]), Number(m[2]) - 1, Number(m[1])) : null;
}

function PlanTab({ course }: { course: TCourse }) {
  const tt = useTT();
  const q = useQuery(`tcal:${course.id}`, (f) => api.t.calendar(course.id, f));
  const [open, setOpen] = useState<TLesson | null>(null);
  const last = useRef<TLesson | null>(null);
  if (open) last.current = open;

  if (q.loading) return <ListSkeleton rows={6} />;
  if (q.error) return <ErrorState error={q.error} onRetry={q.refresh} />;
  const cal = q.data;
  if (!cal?.lessons.length) return <div className="card"><Empty icon={NotebookText} title={tt('plan_empty')} /></div>;

  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const regular = cal.lessons.filter((l) => !l.moved);
  const moved = cal.lessons.filter((l) => l.moved);

  const list = (items: TLesson[]) => (
    <div className="list">
      {items.map((l, i) => {
        const d = parseDmy(l.date);
        const overdue = !l.marked && d != null && d < today;
        const isToday = d != null && d.getTime() === today.getTime();
        const tone = l.marked ? 'success' : overdue ? 'danger' : isToday ? 'accent' : 'neutral';
        const Icon = l.marked ? CalendarCheck2 : overdue ? UserX : CircleDashed;
        return (
          <motion.button
            key={l.number + l.date}
            className="row with-icon pressable"
            style={{ alignItems: 'flex-start' }}
            variants={listItem}
            initial="hidden"
            animate="show"
            custom={i}
            disabled={!l.lessonId}
            onClick={() => l.lessonId && setOpen(l)}
          >
            <span className={'tile ' + tone}><Icon size={18} /></span>
            <div className="row-main">
              <div className="row-title clamp-2">{l.number}. {l.topic}</div>
              <div className="meta" style={{ marginTop: 5 }}>
                <span className="tabular">{l.date}</span>
                <span className={'badge ' + tone} style={{ height: 22 }}>
                  {l.marked ? tt('marked') : overdue ? tt('not_marked') : tt('upcoming')}
                </span>
              </div>
            </div>
            {l.lessonId && <ChevronRight size={18} className="chev" style={{ alignSelf: 'center' }} />}
          </motion.button>
        );
      })}
    </div>
  );

  return (
    <>
      {cal.note && <div className="row-sub" style={{ margin: '0 4px 12px' }}>{cal.note}</div>}
      {list(regular)}
      {moved.length > 0 && <Section title={tt('moved')}>{list(moved)}</Section>}
      <AttendanceSheet course={course} lesson={open ?? last.current} open={!!open} onClose={() => setOpen(null)} />
    </>
  );
}

function AttendanceSheet({ course, lesson, open, onClose }: { course: TCourse; lesson: TLesson | null; open: boolean; onClose: () => void }) {
  const tt = useTT();
  const q = useQuery(open && lesson?.lessonId ? `tatt:${course.id}:${lesson.lessonId}` : null, (f) => api.t.attendance(course.id, lesson!.lessonId!, f));
  const a = q.data;
  const absent = a?.students.filter((s) => s.present === false) ?? [];
  const present = a?.students.filter((s) => s.present === true) ?? [];
  const none = a?.students.filter((s) => s.present == null) ?? [];

  return (
    <Sheet open={open} onClose={onClose} title={lesson ? `${lesson.number}. ${lesson.topic}` : ''} subtitle={a?.header ?? lesson?.date}>
      {q.loading ? (
        <ListSkeleton rows={5} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={q.refresh} />
      ) : a ? (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginBottom: 12 }}>
            <div className="card" style={{ padding: 14 }}>
              <div className="stat-label" style={{ marginTop: 0 }}>{tt('present')}</div>
              <div className="stat-value tabular" style={{ color: 'var(--success)', fontSize: 24 }}>{present.length}</div>
            </div>
            <div className="card" style={{ padding: 14 }}>
              <div className="stat-label" style={{ marginTop: 0 }}>{tt('absent')}</div>
              <div className="stat-value tabular" style={{ color: absent.length ? 'var(--danger)' : undefined, fontSize: 24 }}>{absent.length}</div>
            </div>
          </div>
          <div className="list">
            {[...absent, ...none, ...present].map((s) => (
              <div className="row with-icon" key={s.number}>
                <span className={'tile ' + (s.present === false ? 'danger' : s.present ? 'success' : 'neutral')}>
                  {s.present === false ? <X size={18} /> : s.present ? <CircleCheck size={18} /> : <CircleDashed size={18} />}
                </span>
                <div className="row-main">
                  <div className="row-title" style={{ fontSize: 14.5 }}>{s.number}. {s.name}</div>
                  <div className="row-sub">{s.group}</div>
                </div>
              </div>
            ))}
          </div>
        </>
      ) : null}
    </Sheet>
  );
}

/* ─── Активности ─── */

const STATUS_TONE: Record<number, string> = { 0: 'accent', 1: 'success', 2: 'danger', 3: 'warning' };

function ActivitiesTab({ course }: { course: TCourse }) {
  const tt = useTT();
  const toast = useToast();
  const q = useQuery(`tact:${course.id}`, (f) => api.t.activities(course.id, f));
  const [adding, setAdding] = useState(false);

  if (q.loading) return <ListSkeleton rows={3} tall />;
  if (q.error) return <ErrorState error={q.error} onRetry={q.refresh} />;
  const a = q.data;
  if (!a) return null;

  const remove = async (id: number, name: string) => {
    haptic('warning');
    if (!(await confirmDialog(tt('delete_confirm', { name })))) return;
    const res = await api.t.deleteActivity(course.id, id).catch(() => null);
    if (!res?.ok) return toast(res?.errors.join(', ') || tt('lms_rejected'), 'error');
    toast(tt('deleted'));
    invalidate(`tsheet:${course.id}`);
    q.refresh();
  };

  return (
    <>
      {(a.budgetText || a.note) && (
        <div className="card" style={{ marginBottom: 12 }}>
          {a.budgetText && <div className="row-title" style={{ fontSize: 15 }}>{a.budgetText}</div>}
          {a.note && <div className="row-sub" style={{ marginTop: a.budgetText ? 6 : 0 }}>{a.note}</div>}
        </div>
      )}
      <button className="btn tinted" onClick={() => setAdding(true)} style={{ marginBottom: 12 }}>
        <Plus size={18} /> {tt('add_activity')}
      </button>
      {a.items.length ? (
        <div className="stack" style={{ gap: 10 }}>
          {a.items.map((x) => (
            <div className="card" key={x.id}>
              <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
                <div className="row-main">
                  <div className="row-title">{x.name}</div>
                  <div className="meta" style={{ marginTop: 6 }}>
                    <span className={'badge ' + (STATUS_TONE[x.status] ?? 'neutral')} style={{ height: 22 }}>
                      {a.statusLabels[String(x.status)] ?? x.status}
                    </span>
                    {x.module && <span className="badge violet" style={{ height: 22 }}>{tt('module')}</span>}
                    <span className="tabular"><CalendarClock size={13} /> {x.deadline}</span>
                    <span>{a.labels.max_point} <b>{x.maxPoint}</b></span>
                  </div>
                </div>
                {!x.hasActivities && !x.module && (
                  <button className="icon-btn" style={{ width: 38, height: 38 }} onClick={() => remove(x.id, x.name)} aria-label={tt('delete')}>
                    <Trash2 size={17} color="var(--danger)" />
                  </button>
                )}
              </div>
              {x.status === 3 && x.comment && <div className="row-sub" style={{ marginTop: 8, color: 'var(--warning)' }}>{x.comment}</div>}
              {x.criteria.length > 0 && (
                <div className="list" style={{ marginTop: 10, background: 'var(--surface-2)', boxShadow: 'none' }}>
                  {x.criteria.map((c, i) => (
                    <div className="kv" key={i}><span className="kv-label">{c[0]}</span><span className="kv-value tabular">{c[1]}</span></div>
                  ))}
                </div>
              )}
              {x.sampleUrl && (
                <div className="list" style={{ marginTop: 10 }}>
                  <FileRow name={x.sampleName || 'file'} url={x.sampleUrl} />
                </div>
              )}
            </div>
          ))}
        </div>
      ) : (
        <div className="card"><Empty icon={ListChecks} title={tt('no_columns')} /></div>
      )}
      <ActivityForm
        course={course}
        meta={a}
        open={adding}
        onClose={() => setAdding(false)}
        onCreated={() => {
          setAdding(false);
          toast(tt('created'));
          invalidate(`tsheet:${course.id}`);
          q.refresh();
        }}
      />
    </>
  );
}

function ActivityForm({ course, meta, open, onClose, onCreated }: { course: TCourse; meta: TActivities; open: boolean; onClose: () => void; onCreated: () => void }) {
  const tt = useTT();
  const { t } = useI18n();
  const L = meta.labels;
  const [name, setName] = useState('');
  const [date, setDate] = useState('');
  const [max, setMax] = useState('');
  const [crit, setCrit] = useState<{ name: string; points: string }[]>([{ name: '', points: '' }]);
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<string[]>([]);

  const sum = crit.reduce((s, c) => s + (Number(c.points.replace(',', '.')) || 0), 0);
  const maxN = Number(max.replace(',', '.'));
  const ready = name.trim() && date && maxN > 0 && crit.every((c) => c.name.trim() && Number(c.points.replace(',', '.')) > 0) && Math.abs(sum - maxN) < 1e-9;

  const reset = () => {
    setName('');
    setDate('');
    setMax('');
    setCrit([{ name: '', points: '' }]);
    setFile(null);
    setErrors([]);
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!ready) return;
    const [y, m, d] = date.split('-');
    setBusy(true);
    setErrors([]);
    try {
      const res = await api.t.createActivity(
        course.id,
        { name: name.trim(), deadline: `${d}-${m}-${y}`, max: max.replace(',', '.'), criteria: crit.map((c) => ({ name: c.name.trim(), points: c.points.replace(',', '.') })) },
        file,
      );
      if (!res.ok) return setErrors(res.errors.length ? res.errors : [tt('lms_rejected')]);
      reset();
      onCreated();
    } catch {
      setErrors([t('error_lms')]);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Sheet open={open} onClose={onClose} title={tt('add_activity')} subtitle={`${course.subject}${course.stream ? ' · ' + course.stream : ''}`}>
      <form onSubmit={submit} className="stack" style={{ gap: 14 }}>
        <label className="field">
          <span className="field-label">{L.name}</span>
          <input className="input plain" value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <label className="field">
          <span className="field-label">{L.deadline}</span>
          <input className="input plain" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        </label>
        {meta.note && <div className="row-sub" style={{ marginTop: -6, marginLeft: 4 }}>{meta.note}</div>}
        <label className="field">
          <span className="field-label">{L.max_point}</span>
          <input className="input plain" inputMode="decimal" value={max} onChange={(e) => setMax(e.target.value)} />
        </label>
        {meta.budgetText && <div className="row-sub" style={{ marginTop: -6, marginLeft: 4 }}>{meta.budgetText}</div>}

        <div>
          <div className="crit-row field-label" style={{ marginBottom: 6 }}>
            <span>{L.criterion}</span>
            <span style={{ textAlign: 'right' }}>{L.points}</span>
          </div>
          <div className="stack" style={{ gap: 8 }}>
            {crit.map((c, i) => (
              <div className="crit-row" key={i} style={{ gridTemplateColumns: '1fr 86px 38px' }}>
                <input
                  className="input plain"
                  value={c.name}
                  onChange={(e) => setCrit((l) => l.map((x, k) => (k === i ? { ...x, name: e.target.value } : x)))}
                />
                <input
                  className="input plain tabular"
                  inputMode="decimal"
                  style={{ textAlign: 'center' }}
                  value={c.points}
                  onChange={(e) => setCrit((l) => l.map((x, k) => (k === i ? { ...x, points: e.target.value } : x)))}
                />
                <button type="button" className="icon-btn" style={{ width: 38, height: 52 }} disabled={crit.length === 1}
                  onClick={() => setCrit((l) => l.filter((_, k) => k !== i))} aria-label={tt('delete')}>
                  <X size={16} />
                </button>
              </div>
            ))}
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 8 }}>
            <button type="button" className="link-btn" onClick={() => setCrit((l) => [...l, { name: '', points: '' }])}>
              <Plus size={15} /> {tt('add_criterion')}
            </button>
            <span className="row-sub tabular" style={{ color: max && Math.abs(sum - maxN) > 1e-9 ? 'var(--danger)' : undefined }}>
              {tt('criteria_sum', { sum: Math.round(sum * 100) / 100, max: max || '—' })}
            </span>
          </div>
        </div>

        <label className="field">
          <span className="field-label">{L.file}</span>
          <span className="btn secondary small" style={{ justifyContent: 'flex-start' }}>
            <NotebookText size={16} /> <span className="ellipsis">{file ? file.name : tt('pick_file')}</span>
            <input type="file" hidden onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          </span>
        </label>
        {meta.fileHints.length > 0 && (
          <div className="row-sub" style={{ marginTop: -6, marginLeft: 4 }}>{meta.fileHints.join(' · ')}</div>
        )}

        {errors.length > 0 && <div className="error-text" style={{ whiteSpace: 'pre-line' }}>{errors.join('\n')}</div>}
        <button className="btn" type="submit" disabled={!ready || busy}>
          {busy && <LoaderCircle size={18} className="spin" />}
          {tt('create')}
        </button>
      </form>
    </Sheet>
  );
}
