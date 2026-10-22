import { CircleCheck, CircleDashed, CircleX, Clock3, ListChecks, Lock, LoaderCircle, Upload, type LucideIcon } from 'lucide-react';
import { useRef, useState } from 'react';
import { api, ApiError } from '../lib/api';
import { deadlineLabel, duration } from '../lib/format';
import { useI18n, type Key } from '../lib/i18n';
import { invalidate } from '../lib/query';
import { haptic } from '../lib/tg';
import type { Activity, ActivityStatus } from '../lib/types';
import { FileRow } from './FileRow';
import { Sheet } from './Sheet';
import { useToast } from './Toast';

export const STATUS: Record<ActivityStatus, { label: Key; tone: string; icon: LucideIcon }> = {
  uploaded: { label: 'status_uploaded', tone: 'success', icon: CircleCheck },
  graded: { label: 'status_graded', tone: 'success', icon: CircleCheck },
  open: { label: 'status_open', tone: 'warning', icon: Clock3 },
  missed: { label: 'status_missed', tone: 'danger', icon: CircleX },
  unknown: { label: 'status_unknown', tone: 'neutral', icon: CircleDashed },
};

const ACCEPT = '.jpg,.jpeg,.png,.doc,.docx,.pdf,.ppt,.pptx,.zip,.rar';
const LIMIT = 50 * 1024 * 1024;

export function scoreText(a: Pick<Activity, 'earned' | 'max'>) {
  return `${a.earned ?? '—'} / ${a.max ?? '—'}`;
}

export function ActivitySheet({
  activity,
  courseId,
  course,
  onClose,
}: {
  activity: Activity | null;
  courseId: number;
  course?: string;
  onClose: () => void;
}) {
  const { t, lang } = useI18n();
  const toast = useToast();
  const input = useRef<HTMLInputElement>(null);
  const [progress, setProgress] = useState<number | null>(null);
  // Пока шторка уезжает вниз, показываем последнее задание, а не пустоту.
  const last = useRef(activity);
  if (activity) last.current = activity;
  const a = last.current;

  const onFile = async (file: File | undefined) => {
    if (!file || !a?.activityId) return;
    const ext = file.name.split('.').pop()?.toLowerCase() ?? '';
    if (!ACCEPT.includes('.' + ext)) return toast(t('bad_type'), 'error');
    if (file.size > LIMIT) return toast(t('too_large'), 'error');
    setProgress(0);
    try {
      await api.upload(courseId, a.activityId, file, setProgress);
      invalidate(`act:${courseId}`);
      invalidate('dl:');
      toast(t('upload_done'));
      onClose();
    } catch (e) {
      const code = e instanceof ApiError ? e.code : '';
      toast(code === 'bad_type' ? t('bad_type') : code === 'too_large' ? t('too_large') : t('upload_failed'), 'error');
    } finally {
      setProgress(null);
      if (input.current) input.current.value = '';
    }
  };

  const st = a ? STATUS[a.status] : null;
  const now = Date.now();

  return (
    <Sheet open={!!activity} onClose={onClose} title={a?.task} subtitle={course}>
      {a && st && (
        <div className="stack" style={{ gap: 14 }}>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
            <span className={'badge ' + st.tone}>
              <st.icon size={13} strokeWidth={2.4} />
              {t(st.label)}
            </span>
            <span className={'badge ' + (a.lecture ? 'accent' : 'violet')}>{a.type || t(a.lecture ? 'lecture' : 'practice')}</span>
          </div>

          <div className="list">
            <div className="kv">
              <span className="kv-label">{t('deadline')}</span>
              <span className="kv-value tabular">
                {a.deadlineTs ? deadlineLabel(a.deadlineTs, lang) : a.deadline || '—'}
                {a.deadlineTs && a.deadlineTs > now && (
                  <div className="row-sub" style={{ fontWeight: 500 }}>{t('left', { x: duration(a.deadlineTs - now, t) })}</div>
                )}
              </span>
            </div>
            <div className="kv">
              <span className="kv-label">{t('score')}</span>
              <span className="kv-value tabular">{scoreText(a)}</span>
            </div>
            {a.teacher && (
              <div className="kv">
                <span className="kv-label">{t('teacher')}</span>
                <span className="kv-value">{a.teacher}</span>
              </div>
            )}
          </div>

          {a.criteria && <CriteriaList a={a} />}

          {(a.sample || a.uploaded) && (
            <div className="list">
              {a.sample && <FileRow name={a.sample.name} url={a.sample.url} label={t('task_file')} />}
              {a.uploaded && <FileRow name={a.uploaded.name} url={a.uploaded.url} label={t('my_upload')} />}
            </div>
          )}

          {a.canUpload ? (
            <div>
              <input ref={input} type="file" accept={ACCEPT} hidden onChange={(e) => onFile(e.target.files?.[0])} />
              <button
                className="btn"
                disabled={progress != null}
                onClick={() => {
                  haptic('light');
                  input.current?.click();
                }}
              >
                {progress != null ? <LoaderCircle size={18} className="spin" /> : <Upload size={18} />}
                {progress != null ? t('uploading', { p: Math.round(progress * 100) }) : a.uploaded ? t('reupload') : t('upload')}
              </button>
              {progress != null ? (
                <div className="upload-progress">
                  <div style={{ width: `${Math.round(progress * 100)}%` }} />
                </div>
              ) : (
                <div className="row-sub" style={{ textAlign: 'center', marginTop: 8 }}>{t('allowed_types')}</div>
              )}
            </div>
          ) : (
            a.status === 'missed' && (
              <div className="meta" style={{ justifyContent: 'center' }}>
                <span>
                  <Lock size={14} /> {t('upload_closed')}
                </span>
              </div>
            )
          )}
        </div>
      )}
    </Sheet>
  );
}

/** Критерии оценивания: пункт на строку, баллы справа, внизу — сумма. */
function CriteriaList({ a }: { a: Activity }) {
  const { t } = useI18n();
  const items = a.criteriaItems?.length
    ? a.criteriaItems
    : (a.criteria ?? '').split(/\n/).filter(Boolean).map((name) => ({ name, points: null as string | null }));
  const scored = items.length > 1 && items.every((i) => i.points != null);
  const total = scored ? items.reduce((sum, i) => sum + Number(i.points), 0) : null;
  return (
    <div>
      <div className="section-title" style={{ margin: '0 4px 8px', display: 'flex', alignItems: 'center', gap: 6 }}>
        <ListChecks size={14} /> {t('criteria')}
      </div>
      <div className="list">
        {items.map((it, i) => (
          <div className="row" key={i} style={{ minHeight: 46, paddingTop: 10, paddingBottom: 10, gap: 12 }}>
            <span className="crit-num tabular">{i + 1}</span>
            <div className="row-main" style={{ fontSize: 14.5, lineHeight: 1.35 }}>{it.name}</div>
            {it.points != null && <span className="crit-pts tabular">{it.points}</span>}
          </div>
        ))}
        {total != null && (
          <div className="row" style={{ minHeight: 44, paddingTop: 9, paddingBottom: 9 }}>
            <div className="row-main row-sub" style={{ marginTop: 0, fontWeight: 600 }}>{t('criteria_total')}</div>
            <span className="crit-pts total tabular">{Number.isInteger(total) ? total : total.toFixed(1)}</span>
          </div>
        )}
      </div>
    </div>
  );
}
