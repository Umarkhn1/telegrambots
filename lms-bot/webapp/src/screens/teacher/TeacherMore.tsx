import { motion } from 'framer-motion';
import {
  BookOpen,
  CalendarClock,
  ChevronLeft,
  ChevronRight,
  FileText,
  FolderOpen,
  LoaderCircle,
  MapPin,
  Plus,
  Trash2,
  Trophy,
  UserRound,
} from 'lucide-react';
import { useRef, useState, type FormEvent, type ReactNode } from 'react';
import { FileRow } from '../../components/FileRow';
import { SemesterSheet } from '../../components/SemesterSheet';
import { Sheet } from '../../components/Sheet';
import { useToast } from '../../components/Toast';
import { Chips, Empty, ErrorState, ListSkeleton, listItem, Screen, Section, SemesterChip, Segmented, StreamChip } from '../../components/ui';
import { api } from '../../lib/api';
import { useApp } from '../../lib/app';
import { useI18n } from '../../lib/i18n';
import { useQuery } from '../../lib/query';
import { useTT } from '../../lib/ti18n';
import { confirmDialog, haptic } from '../../lib/tg';
import type { AppealPage, Option, Select2Item, TSubject, TTopic } from '../../lib/types';

function Stacked({ title, sub, onBack, onRefresh, children }: { title: ReactNode; sub?: ReactNode; onBack: () => void; onRefresh?: () => Promise<unknown>; children: ReactNode }) {
  const { t } = useI18n();
  return (
    <Screen stacked onRefresh={onRefresh}>
      <div className="back-head">
        <button className="icon-btn" onClick={onBack} aria-label={t('back')}>
          <ChevronLeft size={22} />
        </button>
      </div>
      <h1 className="page-title" style={{ fontSize: 25 }}>{title}</h1>
      {sub && <div className="page-sub" style={{ marginTop: 6 }}>{sub}</div>}
      <div style={{ marginTop: 18 }}>{children}</div>
    </Screen>
  );
}

/* ─── Исправление НБ ─── */

const APPEAL_TONE: Record<number, string> = { 0: 'accent', 1: 'success', 2: 'danger' };

export function Appeals({ onBack }: { onBack: () => void }) {
  const tt = useTT();
  const toast = useToast();
  const q = useQuery('tappeals', (f) => api.t.appeals(f));
  const [creating, setCreating] = useState(false);
  const [busy, setBusy] = useState<number | null>(null);

  const remove = async (id: number) => {
    haptic('warning');
    if (!(await confirmDialog(tt('delete_confirm', { name: '#' + id })))) return;
    setBusy(id);
    const res = await api.t.deleteAppeal(id).catch(() => null);
    setBusy(null);
    if (!res?.ok) return toast(res?.errors.join(', ') || tt('lms_rejected'), 'error');
    toast(tt('deleted'));
    q.refresh();
  };

  const page = q.data?.page;
  return (
    <Stacked title={page?.title || tt('appeals')} onBack={onBack} onRefresh={q.refresh}>
      <button className="btn tinted" onClick={() => setCreating(true)} disabled={!page}>
        <Plus size={18} /> {tt('new_appeal')}
      </button>
      {page?.instructions && (
        <div className="card" style={{ marginTop: 12 }}>
          <div className="row-sub" style={{ whiteSpace: 'pre-line', marginTop: 0 }}>{page.instructions}</div>
        </div>
      )}
      <Section>
        {q.loading ? (
          <ListSkeleton rows={3} tall />
        ) : q.error ? (
          <ErrorState error={q.error} onRetry={q.refresh} />
        ) : !q.data?.items.length ? (
          <div className="card"><Empty icon={FileText} title={tt('no_appeals')} /></div>
        ) : (
          <div className="stack" style={{ gap: 10 }}>
            {q.data.items.map((a) => (
              <div className="card" key={a.id}>
                <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
                  <div className="row-main">
                    <div className="meta">
                      <span className="tabular">#{a.id}</span>
                      {a.stream && <StreamChip stream={a.stream} />}
                      <span className="tabular">{a.date}</span>
                      {a.pair && <span>{page?.labels.pair}: {a.pair}</span>}
                    </div>
                    {a.theme && <div className="row-title clamp-2" style={{ marginTop: 6, fontSize: 15 }}>{a.theme}</div>}
                    {a.students && <div className="row-sub" style={{ marginTop: 4 }}><UserRound size={12} /> {a.students}</div>}
                    <span className={'badge ' + (APPEAL_TONE[a.status] ?? 'neutral')} style={{ marginTop: 8 }}>{a.statusText}</span>
                  </div>
                  {a.status === 0 && (
                    <button className="icon-btn" style={{ width: 38, height: 38 }} onClick={() => remove(a.id)} disabled={busy === a.id} aria-label={tt('delete')}>
                      {busy === a.id ? <LoaderCircle size={17} className="spin" /> : <Trash2 size={17} color="var(--danger)" />}
                    </button>
                  )}
                </div>
                {a.status === 1 && <AppealPdf id={a.id} />}
              </div>
            ))}
          </div>
        )}
      </Section>
      {page && (
        <AppealForm
          page={page}
          open={creating}
          onClose={() => setCreating(false)}
          onDone={() => {
            setCreating(false);
            toast(tt('appeal_sent'));
            q.refresh();
          }}
        />
      )}
    </Stacked>
  );
}

function AppealPdf({ id }: { id: number }) {
  const q = useQuery(`tappeal-pdf:${id}`, () => api.t.appealPdf(id), 60 * 60_000);
  if (!q.data) return null;
  return (
    <div className="list" style={{ marginTop: 10 }}>
      <FileRow name={`#${id}.pdf`} url={q.data.url} type="pdf" />
    </div>
  );
}

function Picker<T>({ items, render, onPick, empty }: { items: T[]; render: (x: T) => ReactNode; onPick: (x: T) => void; empty: string }) {
  if (!items.length) return <div className="card"><Empty icon={FileText} title={empty} /></div>;
  return (
    <div className="list">
      {items.map((x, i) => (
        <button key={i} className="row pressable" onClick={() => { haptic('selection'); onPick(x); }}>
          <div className="row-main">{render(x)}</div>
          <ChevronRight size={18} className="chev" />
        </button>
      ))}
    </div>
  );
}

function AppealForm({ page, open, onClose, onDone }: { page: AppealPage; open: boolean; onClose: () => void; onDone: () => void }) {
  const tt = useTT();
  const { t } = useI18n();
  const L = page.labels;
  const [stream, setStream] = useState<Option | null>(null);
  const [lessons, setLessons] = useState<Select2Item[] | null>(null);
  const [lesson, setLesson] = useState<Select2Item | null>(null);
  const [students, setStudents] = useState<Select2Item[] | null>(null);
  const [student, setStudent] = useState<Select2Item | null>(null);
  const [pair, setPair] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const reset = () => {
    setStream(null);
    setLessons(null);
    setLesson(null);
    setStudents(null);
    setStudent(null);
    setPair('');
    setError('');
  };
  const close = () => {
    reset();
    onClose();
  };

  const pickStream = async (o: Option) => {
    setStream(o);
    setLessons(null);
    setError('');
    try { setLessons(await api.t.appealLessons(o.id)); } catch { setError(t('error_lms')); }
  };
  const pickLesson = async (l: Select2Item) => {
    setLesson(l);
    setStudents(null);
    setError('');
    try {
      const list = await api.t.appealStudents(l.id);
      // Сначала те, у кого НБ.
      setStudents([...list].sort((a, b) => Number(a.present !== false) - Number(b.present !== false)));
    } catch {
      setError(t('error_lms'));
    }
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!stream || !lesson || !student || !/^\d{1,2}$/.test(pair)) return;
    setBusy(true);
    setError('');
    try {
      const res = await api.t.createAppeal({ stream: stream.id, lesson: lesson.id, student: student.id, pair });
      if (!res.ok) return setError(res.errors.join('\n') || tt('lms_rejected'));
      reset();
      onDone();
    } catch {
      setError(t('error_lms'));
    } finally {
      setBusy(false);
    }
  };

  const step = !stream ? 'stream' : !lesson ? 'lesson' : !student ? 'student' : 'pair';
  const back = () => (step === 'lesson' ? setStream(null) : step === 'student' ? setLesson(null) : setStudent(null));

  return (
    <Sheet open={open} onClose={close} title={tt('new_appeal')} subtitle={[stream?.text, lesson?.text, student?.text].filter(Boolean).join(' · ')}>
      {step !== 'stream' && (
        <button className="link-btn" onClick={back} style={{ marginBottom: 10 }}>
          <ChevronLeft size={16} /> {t('back')}
        </button>
      )}
      <div className="field-label" style={{ margin: '0 4px 8px' }}>
        {step === 'stream' ? L.course_part_id : step === 'lesson' ? L.teacher_calendar_id : step === 'student' ? L['students[]'] : L.pair}
      </div>
      {step === 'stream' && <Picker items={page.streams} render={(o) => <div className="row-title">{o.text}</div>} onPick={pickStream} empty="—" />}
      {step === 'lesson' && (lessons ? (
        <Picker items={lessons} render={(l) => <div className="row-title clamp-2" style={{ fontSize: 14.5 }}>{l.text}</div>} onPick={pickLesson} empty="—" />
      ) : <ListSkeleton rows={4} />)}
      {step === 'student' && (students ? (
        <Picker
          items={students}
          render={(s) => (
            <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <span className={'badge ' + (s.present === false ? 'danger' : 'success')} style={{ height: 22 }}>{s.present === false ? tt('absent') : '+'}</span>
              <span className="row-title" style={{ fontSize: 14.5 }}>{s.text}</span>
            </div>
          )}
          onPick={setStudent}
          empty="—"
        />
      ) : <ListSkeleton rows={5} />)}
      {step === 'pair' && (
        <form onSubmit={submit} className="stack" style={{ gap: 14 }}>
          <input className="input plain" inputMode="numeric" value={pair} onChange={(e) => setPair(e.target.value.replace(/\D/g, '').slice(0, 2))} autoFocus />
          <button className="btn" type="submit" disabled={busy || !pair}>
            {busy && <LoaderCircle size={18} className="spin" />}
            {tt('submit')}
          </button>
        </form>
      )}
      {error && <div className="error-text" style={{ whiteSpace: 'pre-line', marginTop: 12 }}>{error}</div>}
    </Sheet>
  );
}

/* ─── Учебные материалы ─── */

export function Materials({ onBack }: { onBack: () => void }) {
  const { semesterId, push } = useApp();
  const tt = useTT();
  const [semOpen, setSemOpen] = useState(false);
  const sem = semesterId ?? 0;
  const q = useQuery(sem ? `tsubjects:${sem}` : null, (f) => api.t.subjects(sem, f));

  return (
    <Stacked title={tt('materials')} onBack={onBack} onRefresh={q.refresh}>
      <div style={{ marginBottom: 14 }}>
        <SemesterChip onClick={() => setSemOpen(true)} />
      </div>
      {q.loading ? (
        <ListSkeleton rows={4} tall />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={q.refresh} />
      ) : !q.data?.length ? (
        <div className="card"><Empty icon={FolderOpen} title={tt('no_subjects')} /></div>
      ) : (
        <div className="list">
          {q.data.map((s, i) => (
            <motion.button key={s.id + s.language} className="row with-icon pressable" variants={listItem} initial="hidden" animate="show" custom={i}
              onClick={() => { haptic('light'); push({ name: 'material', subject: s }); }}>
              <span className="tile"><BookOpen size={18} /></span>
              <div className="row-main">
                <div className="row-title">{s.subject}</div>
                <div className="meta" style={{ marginTop: 5 }}>
                  <span className="badge accent" style={{ height: 22 }}>{s.language}</span>
                  <span>{s.code}</span>
                </div>
                {s.department && <div className="row-sub ellipsis">{s.department}</div>}
              </div>
              <ChevronRight size={18} className="chev" />
            </motion.button>
          ))}
        </div>
      )}
      <SemesterSheet open={semOpen} onClose={() => setSemOpen(false)} />
    </Stacked>
  );
}

export function Material({ subject, onBack }: { subject: TSubject; onBack: () => void }) {
  const { semesterId } = useApp();
  const tt = useTT();
  const toast = useToast();
  const sem = semesterId ?? 0;
  const lang = subject.language.toLowerCase();
  const page = useQuery(`tmpage:${subject.id}:${sem}:${lang}`, () => api.t.materialPage(subject.id, sem, lang), 60 * 60_000);
  const [type, setType] = useState<string | null>(null);
  const lessonType = type ?? page.data?.lessonTypes[0]?.id ?? null;
  const topics = useQuery(lessonType ? `ttopics:${subject.id}:${sem}:${lang}:${lessonType}` : null, (f) => api.t.topics(subject.id, sem, lang, lessonType!, f));
  const [open, setOpen] = useState<TTopic | null>(null);
  const last = useRef<TTopic | null>(null);
  if (open) last.current = open;
  const current = open ? topics.data?.find((x) => x.id === open.id) ?? open : last.current;

  const name = (type: string) => page.data?.contentTypes.find((o) => o.id === type)?.text ?? type;

  const remove = async (id: number) => {
    haptic('warning');
    if (!(await confirmDialog(tt('delete_confirm', { name: '' + id })))) return;
    const res = await api.t.deleteMaterial(id).catch(() => null);
    if (!res?.ok) return toast(res?.errors.join(', ') || tt('lms_rejected'), 'error');
    toast(tt('deleted'));
    topics.refresh();
  };

  return (
    <Stacked title={subject.subject} sub={page.data?.title ?? undefined} onBack={onBack} onRefresh={() => Promise.all([page.refresh(), topics.refresh()])}>
      {page.data && page.data.lessonTypes.length > 1 && (
        <Segmented<string>
          value={lessonType ?? ''}
          onChange={setType}
          style={{ marginBottom: 14 }}
          options={page.data.lessonTypes.map((o) => ({ value: o.id, label: o.text }))}
        />
      )}
      {page.loading || topics.loading ? (
        <ListSkeleton rows={6} />
      ) : page.error || topics.error ? (
        <ErrorState error={page.error ?? topics.error} onRetry={() => { page.refresh(); topics.refresh(); }} />
      ) : !topics.data?.length ? (
        <div className="card"><Empty icon={FolderOpen} title={tt('no_topics')} /></div>
      ) : (
        <div className="list">
          {topics.data.map((tp, i) => (
            <motion.button key={tp.id} className="row with-icon pressable" style={{ alignItems: 'flex-start' }} variants={listItem} initial="hidden" animate="show" custom={i}
              onClick={() => setOpen(tp)}>
              <span className={'tile tabular ' + (tp.resources.length ? 'success' : 'neutral')} style={{ fontWeight: 700, fontSize: 14 }}>{tp.number}</span>
              <div className="row-main">
                <div className="row-title clamp-2">{tp.name}</div>
                {tp.resources.length > 0 && <div className="row-sub">{tp.resources.map((r) => r.name || name(r.type)).join(' · ')}</div>}
              </div>
              <ChevronRight size={18} className="chev" style={{ alignSelf: 'center' }} />
            </motion.button>
          ))}
        </div>
      )}

      <Sheet open={!!open} onClose={() => setOpen(null)} title={current ? `${current.number}. ${current.name}` : ''}>
        {current && page.data && (
          <>
            {current.resources.length ? (
              <div className="list">
                {current.resources.map((r) => (
                  <div key={r.id} style={{ display: 'flex', alignItems: 'center' }}>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <FileRow name={r.name || name(r.type)} url={r.url} type={r.type === 'url' || r.type === 'meeting' ? 'url' : undefined} label={name(r.type)} />
                    </div>
                    {!r.pastDate && (
                      <button className="icon-btn" style={{ width: 38, height: 38, marginRight: 10 }} onClick={() => remove(r.id)} aria-label={tt('delete')}>
                        <Trash2 size={16} color="var(--danger)" />
                      </button>
                    )}
                  </div>
                ))}
              </div>
            ) : (
              <div className="card"><Empty icon={FolderOpen} title={tt('no_materials')} /></div>
            )}
            <AddMaterial
              subject={subject}
              sem={sem}
              lang={lang}
              topic={current}
              types={page.data.contentTypes.filter((o) => o.id !== 'meeting')}
              labels={page.data.labels}
              onAdded={() => {
                toast(tt('material_added'));
                topics.refresh();
              }}
            />
          </>
        )}
      </Sheet>
    </Stacked>
  );
}

/** Добавление материала: вид — из формы LMS; для ссылки поле URL, для остальных — файл и название. */
function AddMaterial({ subject, sem, lang, topic, types, labels, onAdded }: {
  subject: TSubject; sem: number; lang: string; topic: TTopic; types: Option[]; labels: Record<string, string>; onAdded: () => void;
}) {
  const tt = useTT();
  const { t } = useI18n();
  const [type, setType] = useState(types.find((o) => o.id === 'file')?.id ?? types[0]?.id ?? '');
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const isUrl = type === 'url';
  const ready = isUrl ? /^https?:\/\/\S+$/i.test(url.trim()) : !!file;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!ready) return;
    setBusy(true);
    setError('');
    try {
      const res = await api.t.addMaterial(
        subject.id,
        { semester: sem, lang, topic: topic.id, type, name: isUrl ? undefined : name.trim() || file?.name.replace(/\.[^.]+$/, ''), url: isUrl ? url.trim() : undefined },
        isUrl ? null : file,
      );
      if (!res.ok) return setError(res.errors.join('\n') || tt('lms_rejected'));
      setName('');
      setUrl('');
      setFile(null);
      onAdded();
    } catch {
      setError(t('error_lms'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} className="stack" style={{ gap: 12, marginTop: 18 }}>
      <div className="section-title" style={{ marginLeft: 4 }}>{tt('add')}</div>
      <Chips<string> value={type} onChange={setType} options={types.map((o) => ({ value: o.id, label: o.text }))} />
      {isUrl ? (
        <label className="field">
          <span className="field-label">{labels.url ?? 'URL'}</span>
          <input className="input plain" inputMode="url" placeholder="https://" value={url} onChange={(e) => setUrl(e.target.value)} />
        </label>
      ) : (
        <>
          <label className="field">
            <span className="field-label">{labels.name}</span>
            <input className="input plain" value={name} onChange={(e) => setName(e.target.value)} placeholder={file?.name} />
          </label>
          <label className="field">
            <span className="field-label">{labels.file}</span>
            <span className="btn secondary small" style={{ justifyContent: 'flex-start' }}>
              <FileText size={16} /> <span className="ellipsis">{file ? file.name : tt('pick_file')}</span>
              <input type="file" hidden onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
            </span>
          </label>
        </>
      )}
      {error && <div className="error-text" style={{ whiteSpace: 'pre-line' }}>{error}</div>}
      <button className="btn" type="submit" disabled={!ready || busy}>
        {busy ? <LoaderCircle size={18} className="spin" /> : <Plus size={18} />}
        {tt('add')}
      </button>
    </form>
  );
}

/* ─── Итоговые экзамены ─── */

export function TeacherFinals({ onBack }: { onBack: () => void }) {
  const { semesterId } = useApp();
  const tt = useTT();
  const [semOpen, setSemOpen] = useState(false);
  const sem = semesterId ?? 0;
  const q = useQuery(sem ? `tfinals:${sem}` : null, (f) => api.t.finals(sem, f));

  return (
    <Stacked title={tt('finals')} onBack={onBack} onRefresh={q.refresh}>
      <div style={{ marginBottom: 14 }}>
        <SemesterChip onClick={() => setSemOpen(true)} />
      </div>
      {q.loading ? (
        <ListSkeleton rows={3} tall />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={q.refresh} />
      ) : !q.data?.length ? (
        <div className="card"><Empty icon={Trophy} title={tt('no_finals')} /></div>
      ) : (
        <div className="list">
          {q.data.map((f, i) => (
            <motion.div key={f.id + ':' + i} className="row with-icon" style={{ alignItems: 'flex-start' }} variants={listItem} initial="hidden" animate="show" custom={i}>
              <span className="tile warning"><Trophy size={18} /></span>
              <div className="row-main">
                <div className="row-title">{f.subjects}</div>
                {f.streams && <div className="row-sub">{f.streams}</div>}
                <div className="meta" style={{ marginTop: 6 }}>
                  <span className="tabular"><CalendarClock size={13} /> {f.date} {f.from}</span>
                  {f.room && <span><MapPin size={13} /> {f.room}</span>}
                </div>
              </div>
            </motion.div>
          ))}
        </div>
      )}
      <SemesterSheet open={semOpen} onClose={() => setSemOpen(false)} />
    </Stacked>
  );
}
