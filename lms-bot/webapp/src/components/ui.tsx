import { motion, type Variants } from 'framer-motion';
import { ChevronDown, CircleAlert, RefreshCw, type LucideIcon } from 'lucide-react';
import { useId, useRef, useState, type ReactNode } from 'react';
import { ApiError } from '../lib/api';
import { shortSemester, semesterName, useApp } from '../lib/app';
import { useI18n } from '../lib/i18n';
import { haptic } from '../lib/tg';

/* ─── Экран с pull-to-refresh ─── */

export function Screen({
  children,
  onRefresh,
  stacked,
}: {
  children: ReactNode;
  onRefresh?: () => Promise<unknown>;
  stacked?: boolean;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const start = useRef<number | null>(null);
  const [pull, setPull] = useState(0);
  const [busy, setBusy] = useState(false);
  const armed = pull > 64;

  const onTouchStart = (e: React.TouchEvent) => {
    if (!onRefresh || busy) return;
    start.current = (ref.current?.scrollTop ?? 1) <= 0 ? e.touches[0].clientY : null;
  };
  const onTouchMove = (e: React.TouchEvent) => {
    if (start.current == null) return;
    const dy = e.touches[0].clientY - start.current;
    if (dy <= 0) return setPull(0);
    const next = Math.min(dy * 0.45, 96);
    if (next > 64 && pull <= 64) haptic('soft');
    setPull(next);
  };
  const onTouchEnd = async () => {
    if (start.current == null) return;
    start.current = null;
    if (armed && onRefresh) {
      setBusy(true);
      setPull(56);
      try {
        await onRefresh();
      } finally {
        setBusy(false);
        setPull(0);
      }
    } else setPull(0);
  };

  return (
    <div
      ref={ref}
      className={'screen' + (stacked ? ' stacked' : '')}
      onTouchStart={onTouchStart}
      onTouchMove={onTouchMove}
      onTouchEnd={onTouchEnd}
    >
      {onRefresh && (
        <div className="ptr" aria-hidden>
          <motion.div
            animate={{ y: pull - 34, opacity: Math.min(1, pull / 50), rotate: busy ? 360 : pull * 3.2 }}
            transition={busy ? { rotate: { repeat: Infinity, duration: 0.8, ease: 'linear' }, default: { duration: 0.15 } } : { duration: 0 }}
          >
            <RefreshCw size={17} color={armed || busy ? 'var(--accent)' : 'currentColor'} />
          </motion.div>
        </div>
      )}
      <motion.div
        animate={{ y: pull * 0.55 }}
        transition={start.current != null ? { duration: 0 } : { type: 'spring', stiffness: 400, damping: 36 }}
      >
        {children}
      </motion.div>
    </div>
  );
}

export function PageHead({ title, sub, right }: { title: ReactNode; sub?: ReactNode; right?: ReactNode }) {
  return (
    <div className="page-head">
      <div style={{ minWidth: 0 }}>
        <h1 className="page-title">{title}</h1>
        {sub && <div className="page-sub">{sub}</div>}
      </div>
      {right}
    </div>
  );
}

export function Section({ title, action, children, first }: { title?: ReactNode; action?: ReactNode; children: ReactNode; first?: boolean }) {
  return (
    <section className="section" style={first ? { marginTop: 0 } : undefined}>
      {(title || action) && (
        <div className="section-head">
          <span className="section-title">{title}</span>
          {action}
        </div>
      )}
      {children}
    </section>
  );
}

export function Tile({ icon: Icon, tone, size = 19 }: { icon: LucideIcon; tone?: string; size?: number }) {
  return (
    <span className={'tile ' + (tone ?? '')}>
      <Icon size={size} strokeWidth={2} />
    </span>
  );
}

export function Empty({ icon, title, sub, tone = 'neutral', action }: { icon: LucideIcon; title: string; sub?: string; tone?: string; action?: ReactNode }) {
  return (
    <div className="empty">
      <Tile icon={icon} tone={tone} size={24} />
      <div className="empty-title">{title}</div>
      {sub && <div className="empty-sub">{sub}</div>}
      {action && <div style={{ marginTop: 16 }}>{action}</div>}
    </div>
  );
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const { t } = useI18n();
  const code = error instanceof ApiError ? error.code : '';
  const sub = code === 'network' ? t('error_network') : code === 'no_semester' ? t('no_semester') : t('error_lms');
  return (
    <div className="card">
      <Empty
        icon={CircleAlert}
        tone="danger"
        title={t('error_generic')}
        sub={sub}
        action={
          <button className="btn small tinted" onClick={onRetry} style={{ width: 'auto' }}>
            <RefreshCw size={16} /> {t('retry')}
          </button>
        }
      />
    </div>
  );
}

export function Skeleton({ h = 16, w = '100%', r = 10, style }: { h?: number; w?: number | string; r?: number; style?: React.CSSProperties }) {
  return <div className="skeleton" style={{ height: h, width: w, borderRadius: r, ...style }} />;
}

export function ListSkeleton({ rows = 4, tall }: { rows?: number; tall?: boolean }) {
  return (
    <div className="list">
      {Array.from({ length: rows }).map((_, i) => (
        <div className="row with-icon" key={i} style={{ minHeight: tall ? 76 : 60 }}>
          <Skeleton h={38} w={38} r={12} />
          <div className="row-main">
            <Skeleton h={14} w={`${70 - (i % 3) * 12}%`} />
            <Skeleton h={11} w="40%" style={{ marginTop: 8 }} />
          </div>
        </div>
      ))}
    </div>
  );
}

/* ─── Сегментированный переключатель с плавающим ползунком ─── */

export function Segmented<T extends string>({
  value,
  onChange,
  options,
  style,
}: {
  value: T;
  onChange: (v: T) => void;
  options: { value: T; label: ReactNode }[];
  style?: React.CSSProperties;
}) {
  const id = useId();
  return (
    <div className="segmented" style={style}>
      {options.map((o) => (
        <button
          key={o.value}
          className={o.value === value ? 'active' : ''}
          onClick={() => {
            if (o.value !== value) {
              haptic('selection');
              onChange(o.value);
            }
          }}
        >
          {o.value === value && (
            <motion.span layoutId={'seg' + id} className="thumb" transition={{ type: 'spring', stiffness: 500, damping: 38 }} />
          )}
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Chips<T extends string>({
  value,
  onChange,
  options,
}: {
  value: T;
  onChange: (v: T) => void;
  options: { value: T; label: ReactNode; count?: number }[];
}) {
  const id = useId();
  return (
    <div className="chips">
      {options.map((o) => (
        <button
          key={o.value}
          className={'chip' + (o.value === value ? ' active' : '')}
          onClick={() => {
            if (o.value !== value) {
              haptic('selection');
              onChange(o.value);
            }
          }}
        >
          {o.value === value && (
            <motion.span layoutId={'chip' + id} className="chip-bg" transition={{ type: 'spring', stiffness: 500, damping: 38 }} />
          )}
          <span>
            {o.label}
            {o.count != null && <span className="count">{o.count}</span>}
          </span>
        </button>
      ))}
    </div>
  );
}

export function SemesterChip({ onClick }: { onClick: () => void }) {
  const { semesters, semesterId } = useApp();
  const name = shortSemester(semesterName(semesters, semesterId));
  if (!semesters.length) return null;
  return (
    <button className="semester-chip" onClick={onClick}>
      <span className="ellipsis">{name}</span>
      <ChevronDown size={15} strokeWidth={2.4} />
    </button>
  );
}

/** Появление элементов списка лесенкой. */
export const listItem: Variants = {
  hidden: { opacity: 0, y: 8 },
  show: (i: number) => ({ opacity: 1, y: 0, transition: { delay: Math.min(i, 10) * 0.025, duration: 0.28, ease: [0.2, 0.8, 0.2, 1] as const } }),
};
