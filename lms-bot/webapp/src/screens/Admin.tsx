import { AnimatePresence, motion } from 'framer-motion';
import {
  ArrowDown,
  ArrowUp,
  ArrowUpDown,
  ChevronLeft,
  ChevronRight,
  Clock3,
  ExternalLink,
  SlidersHorizontal,
  Search,
  TrendingUp,
  UserCheck,
  Users,
  UsersRound,
  X,
} from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Sheet } from '../components/Sheet';
import { Empty, ErrorState, Screen, Segmented, Skeleton } from '../components/ui';
import { api } from '../lib/api';
import { dayMonth, useI18n, type Key, type TFn } from '../lib/i18n';
import { useQuery } from '../lib/query';
import { haptic, tg } from '../lib/tg';
import type { AdminFilterKey, AdminStudent, AdminStudentsResponse, Lang } from '../lib/types';

type SortKey = 'fullName' | 'group' | 'direction' | 'course' | 'gender' | 'birthDate' | 'curator' | 'studyType' | 'language' | 'gpa' | 'lastSeen';

const COLUMNS: { key: SortKey; label: Key; width: number; num?: boolean }[] = [
  { key: 'fullName', label: 'col_name', width: 210 },
  { key: 'group', label: 'group', width: 96 },
  { key: 'direction', label: 'direction', width: 200 },
  { key: 'course', label: 'course_year', width: 70, num: true },
  { key: 'gender', label: 'gender', width: 90 },
  { key: 'birthDate', label: 'col_birth', width: 108, num: true },
  { key: 'curator', label: 'curator', width: 170 },
  { key: 'studyType', label: 'col_type', width: 100 },
  { key: 'language', label: 'col_lang', width: 96 },
  { key: 'gpa', label: 'stat_gpa', width: 70, num: true },
  { key: 'lastSeen', label: 'col_seen', width: 130, num: true },
];

const FILTERS: { key: AdminFilterKey; label: Key }[] = [
  { key: 'group', label: 'group' },
  { key: 'course', label: 'course_year' },
  { key: 'direction', label: 'direction' },
  { key: 'gender', label: 'gender' },
  { key: 'studyType', label: 'col_type' },
  { key: 'language', label: 'col_lang' },
];

/** «5 мин назад», «3 ч назад», «2 дн назад», дальше — дата. */
export function ago(ts: number, t: TFn, lang: Lang): string {
  if (!ts) return '—';
  const min = Math.floor((Date.now() - ts) / 60000);
  if (min < 1) return t('just_now');
  if (min < 60) return t('ago_min', { n: min });
  const h = Math.floor(min / 60);
  if (h < 24) return t('ago_h', { n: h });
  const d = Math.floor(h / 24);
  if (d < 7) return t('ago_d', { n: d });
  const date = new Date(ts);
  return dayMonth(date, lang, true) + (date.getFullYear() !== new Date().getFullYear() ? ' ' + date.getFullYear() : '');
}

function cell(s: AdminStudent, key: SortKey, t: TFn, lang: Lang): string {
  switch (key) {
    case 'fullName':
      return s.fullName || s.tgName || '—';
    case 'gpa':
      return s.gpa != null ? s.gpa.toFixed(2) : '—';
    case 'lastSeen':
      return ago(s.lastSeen, t, lang);
    default:
      return (s[key] as string | null) || '—';
  }
}

function gpaTone(g: number | null) {
  if (g == null) return '';
  if (g >= 4.5) return 'success';
  if (g >= 4) return 'accent';
  if (g >= 3) return 'warning';
  return 'danger';
}

function useDebounced<T>(value: T, ms: number) {
  const [v, setV] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setV(value), ms);
    return () => clearTimeout(id);
  }, [value, ms]);
  return v;
}

export function Admin({ onBack }: { onBack: () => void }) {
  const { t, lang } = useI18n();
  const [query, setQuery] = useState('');
  const q = useDebounced(query.trim(), 300);
  const [sort, setSort] = useState<SortKey>('lastSeen');
  const [dir, setDir] = useState<'asc' | 'desc'>('desc');
  const [page, setPage] = useState(1);
  const [size, setSize] = useState<'20' | '50' | '100'>('20');
  const [filters, setFilters] = useState<Partial<Record<AdminFilterKey, string>>>({});
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [open, setOpen] = useState<AdminStudent | null>(null);
  const lastOpen = useRef<AdminStudent | null>(null);
  if (open) lastOpen.current = open;

  // Любое изменение условий — снова с первой страницы.
  useEffect(() => setPage(1), [q, sort, dir, size, filters]);

  const params = useMemo(() => {
    const p: Record<string, string | number> = { sort, dir, page, size };
    if (q) p.q = q;
    for (const [k, v] of Object.entries(filters)) if (v) p[k] = v;
    return p;
  }, [q, sort, dir, page, size, filters]);

  const res = useQuery('admin:' + JSON.stringify(params), (f) => api.adminStudents(params, f), 30_000);
  // Пока грузится следующая страница, показываем предыдущую, а не скелетон.
  const last = useRef<AdminStudentsResponse | undefined>(undefined);
  if (res.data) last.current = res.data;
  const data = res.data ?? last.current;
  const pending = !res.data && res.loading && !!last.current;

  const toggleSort = (key: SortKey) => {
    haptic('selection');
    if (key === sort) setDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    else {
      setSort(key);
      setDir(key === 'lastSeen' || key === 'gpa' ? 'desc' : 'asc');
    }
  };

  const activeFilters = Object.entries(filters).filter(([, v]) => v) as [AdminFilterKey, string][];
  const from = data ? (data.page - 1) * data.size + 1 : 0;
  const to = data ? Math.min(data.total, data.page * data.size) : 0;

  return (
    <Screen stacked onRefresh={res.refresh}>
      <div className="back-head">
        <button className="icon-btn" onClick={onBack} aria-label={t('back')}>
          <ChevronLeft size={22} />
        </button>
      </div>
      <h1 className="page-title">{t('students')}</h1>
      <div className="page-sub">{t('admin_sub')}</div>

      <div className="admin-stats">
        {[
          { icon: UsersRound, tone: '', value: data?.stats.total, label: t('st_total') },
          { icon: UserCheck, tone: 'success', value: data?.stats.active24h, label: t('st_24h') },
          { icon: Clock3, tone: 'violet', value: data?.stats.active7d, label: t('st_7d') },
          { icon: TrendingUp, tone: 'warning', value: data?.stats.avgGpa?.toFixed(2), label: t('st_avg_gpa') },
        ].map((s) => (
          <div className="stat card" key={s.label}>
            <span className={'tile ' + s.tone} style={{ width: 30, height: 30, borderRadius: 9 }}>
              <s.icon size={16} />
            </span>
            <div style={{ minWidth: 0 }}>
              {data ? <div className="stat-value tabular">{s.value ?? '—'}</div> : <Skeleton h={20} w={40} />}
              <div className="stat-label">{s.label}</div>
            </div>
          </div>
        ))}
      </div>

      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <div className="input-wrap" style={{ flex: 1 }}>
          <span className="input-icon"><Search size={18} /></span>
          <input
            className="input"
            style={{ height: 46, paddingRight: query ? 44 : 16 }}
            value={query}
            placeholder={t('search_ph')}
            onChange={(e) => setQuery(e.target.value)}
            enterKeyHint="search"
          />
          {query && (
            <button className="input-action" onClick={() => setQuery('')} aria-label={t('reset')}>
              <X size={17} />
            </button>
          )}
        </div>
        <button
          className={'icon-btn' + (activeFilters.length ? ' tinted-btn' : '')}
          style={{ width: 46, height: 46, position: 'relative' }}
          onClick={() => setFiltersOpen(true)}
          aria-label={t('filters')}
        >
          <SlidersHorizontal size={19} />
          {activeFilters.length > 0 && <span className="dot-count">{activeFilters.length}</span>}
        </button>
      </div>

      {activeFilters.length > 0 && (
        <div className="chips" style={{ marginTop: 10 }}>
          {activeFilters.map(([k, v]) => (
            <button
              key={k}
              className="chip active"
              onClick={() => setFilters((f) => ({ ...f, [k]: '' }))}
            >
              <span className="chip-bg" />
              <span>
                {v} <X size={14} />
              </span>
            </button>
          ))}
        </div>
      )}

      <div style={{ marginTop: 14 }}>
        {!data && res.error ? (
          <ErrorState error={res.error} onRetry={res.refresh} />
        ) : !data ? (
          <Skeleton h={320} r={18} />
        ) : data.items.length === 0 ? (
          <div className="card"><Empty icon={Users} title={t('no_students')} sub={t('no_students_sub')} /></div>
        ) : (
          <div className="table-wrap" style={{ opacity: pending ? 0.55 : 1 }}>
            <table className="table">
              <thead>
                <tr>
                  {COLUMNS.map((c) => {
                    const active = c.key === sort;
                    const Icon = active ? (dir === 'asc' ? ArrowUp : ArrowDown) : ArrowUpDown;
                    return (
                      <th key={c.key} style={{ minWidth: c.width }} className={active ? 'sorted' : ''}>
                        <button onClick={() => toggleSort(c.key)}>
                          {t(c.label)}
                          <Icon size={13} strokeWidth={2.4} />
                        </button>
                      </th>
                    );
                  })}
                </tr>
              </thead>
              <tbody>
                {data.items.map((s) => (
                  <tr key={s.telegramId} onClick={() => setOpen(s)}>
                    {COLUMNS.map((c) => (
                      <td key={c.key} className={c.num ? 'tabular' : ''}>
                        {c.key === 'gpa' && s.gpa != null ? (
                          <span className={'badge ' + gpaTone(s.gpa)} style={{ height: 22 }}>{s.gpa.toFixed(2)}</span>
                        ) : c.key === 'fullName' ? (
                          <>
                            <div className="td-name">{cell(s, c.key, t, lang)}</div>
                            {s.tgUsername && <div className="td-sub">@{s.tgUsername}</div>}
                          </>
                        ) : (
                          cell(s, c.key, t, lang)
                        )}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {data && data.total > 0 && (
        <div className="pager">
          <div className="row-sub tabular" style={{ marginTop: 0 }}>{t('shown', { from, to, total: data.total })}</div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <button className="icon-btn" style={{ width: 38, height: 38 }} disabled={data.page <= 1} onClick={() => setPage(data.page - 1)} aria-label="prev">
              <ChevronLeft size={18} />
            </button>
            {pageList(data.page, data.pages).map((p, i) =>
              p === 0 ? (
                <span key={'gap' + i} className="row-sub" style={{ marginTop: 0, padding: '0 2px' }}>…</span>
              ) : (
                <button
                  key={p}
                  className={'page-btn tabular' + (p === data.page ? ' active' : '')}
                  onClick={() => {
                    haptic('selection');
                    setPage(p);
                  }}
                >
                  {p}
                </button>
              ),
            )}
            <button className="icon-btn" style={{ width: 38, height: 38 }} disabled={data.page >= data.pages} onClick={() => setPage(data.page + 1)} aria-label="next">
              <ChevronRight size={18} />
            </button>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, width: '100%' }}>
            <span className="row-sub" style={{ marginTop: 0, whiteSpace: 'nowrap' }}>{t('per_page')}</span>
            <Segmented
              value={size}
              onChange={setSize}
              style={{ flex: 1 }}
              options={[
                { value: '20', label: '20' },
                { value: '50', label: '50' },
                { value: '100', label: '100' },
              ]}
            />
          </div>
        </div>
      )}

      <FiltersSheet
        open={filtersOpen}
        onClose={() => setFiltersOpen(false)}
        facets={data?.facets}
        value={filters}
        onApply={(f) => {
          setFilters(f);
          setFiltersOpen(false);
        }}
      />

      <Sheet open={!!open} onClose={() => setOpen(null)} title={lastOpen.current ? cell(lastOpen.current, 'fullName', t, lang) : ''} subtitle={lastOpen.current?.recordBook ? `№ ${lastOpen.current.recordBook}` : undefined}>
        {lastOpen.current && <StudentDetails s={lastOpen.current} />}
      </Sheet>
    </Screen>
  );
}

/** Номера страниц: 1 … 4 5 6 … 20 (0 — разрыв). */
function pageList(page: number, pages: number): number[] {
  if (pages <= 5) return Array.from({ length: pages }, (_, i) => i + 1);
  const set = new Set([1, pages, page - 1, page, page + 1].filter((p) => p >= 1 && p <= pages));
  const sorted = [...set].sort((a, b) => a - b);
  const out: number[] = [];
  sorted.forEach((p, i) => {
    if (i > 0 && p - sorted[i - 1] > 1) out.push(0);
    out.push(p);
  });
  return out;
}

function FiltersSheet({
  open,
  onClose,
  facets,
  value,
  onApply,
}: {
  open: boolean;
  onClose: () => void;
  facets?: Record<AdminFilterKey, string[]>;
  value: Partial<Record<AdminFilterKey, string>>;
  onApply: (v: Partial<Record<AdminFilterKey, string>>) => void;
}) {
  const { t } = useI18n();
  const [draft, setDraft] = useState(value);
  useEffect(() => {
    if (open) setDraft(value);
  }, [open, value]);

  return (
    <Sheet open={open} onClose={onClose} title={t('filters')}>
      <div className="stack" style={{ gap: 18 }}>
        {FILTERS.map((f) => {
          const options = facets?.[f.key] ?? [];
          if (!options.length) return null;
          const cur = draft[f.key] ?? '';
          return (
            <div key={f.key}>
              <div className="section-title" style={{ margin: '0 4px 8px' }}>{t(f.label)}</div>
              <div className="filter-options">
                {['', ...options].map((o) => (
                  <button
                    key={o || 'any'}
                    className={'filter-opt' + (o === cur ? ' active' : '')}
                    onClick={() => {
                      haptic('selection');
                      setDraft((d) => ({ ...d, [f.key]: o }));
                    }}
                  >
                    {o || t('any')}
                  </button>
                ))}
              </div>
            </div>
          );
        })}
        <div style={{ display: 'flex', gap: 10 }}>
          <button className="btn secondary" onClick={() => onApply({})}>{t('reset')}</button>
          <button className="btn" onClick={() => onApply(draft)}>{t('show_results')}</button>
        </div>
      </div>
    </Sheet>
  );
}

function StudentDetails({ s }: { s: AdminStudent }) {
  const { t, lang } = useI18n();
  const rows: [Key, string | null | undefined][] = [
    ['group', s.group],
    ['direction', s.direction],
    ['course_year', s.course],
    ['gender', s.gender],
    ['col_birth', s.birthDate],
    ['curator', s.curator],
    ['col_type', s.studyType],
    ['col_lang', s.language],
    ['stat_gpa', s.gpa != null ? s.gpa.toFixed(2) : null],
    ['col_seen', ago(s.lastSeen, t, lang)],
    ['first_seen', s.firstSeen ? ago(s.firstSeen, t, lang) : null],
  ];
  return (
    <AnimatePresence>
      <motion.div className="stack" style={{ gap: 14 }} initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
        <div className="list">
          {rows
            .filter(([, v]) => v)
            .map(([k, v]) => (
              <div className="kv" key={k}>
                <span className="kv-label">{t(k)}</span>
                <span className="kv-value">{v}</span>
              </div>
            ))}
        </div>
        <div className="list">
          <div className="kv">
            <span className="kv-label">{t('telegram')}</span>
            <span className="kv-value tabular">
              {s.tgName && <div>{s.tgName}</div>}
              <div className="row-sub" style={{ marginTop: 0 }}>ID {s.telegramId}</div>
            </span>
          </div>
        </div>
        {s.tgUsername && (
          <button className="btn tinted" onClick={() => tg?.openTelegramLink(`https://t.me/${s.tgUsername}`)}>
            <ExternalLink size={17} /> @{s.tgUsername}
          </button>
        )}
      </motion.div>
    </AnimatePresence>
  );
}
