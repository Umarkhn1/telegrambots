import { motion } from 'framer-motion';
import { BookOpen, CalendarDays, ClipboardCheck, GraduationCap, House, UserRound, type LucideIcon } from 'lucide-react';
import { type Tab } from '../lib/app';
import { useI18n, type Key } from '../lib/i18n';
import { useTT } from '../lib/ti18n';
import { haptic } from '../lib/tg';

const ITEMS: Record<Exclude<Tab, 'grading'>, { icon: LucideIcon; label: Key }> = {
  home: { icon: House, label: 'nav_home' },
  courses: { icon: BookOpen, label: 'nav_courses' },
  schedule: { icon: CalendarDays, label: 'nav_schedule' },
  grades: { icon: GraduationCap, label: 'nav_grades' },
  profile: { icon: UserRound, label: 'nav_profile' },
};

/** Плавающая нижняя панель: закруглённая, с ползунком, который переезжает к активному разделу. */
export function BottomNav({ tabs, tab, onChange, hidden }: { tabs: Tab[]; tab: Tab; onChange: (t: Tab) => void; hidden: boolean }) {
  const { t } = useI18n();
  const tt = useTT();
  return (
    <motion.nav
      className="nav"
      initial={{ y: 120, opacity: 0 }}
      animate={hidden ? { y: 120, opacity: 0 } : { y: 0, opacity: 1 }}
      transition={{ type: 'spring', stiffness: 360, damping: 34 }}
    >
      {tabs.map((key) => {
        const Icon = key === 'grading' ? ClipboardCheck : ITEMS[key].icon;
        const text = key === 'grading' ? tt('nav_grading') : t(ITEMS[key].label);
        const active = key === tab;
        return (
          <button
            key={key}
            className={'nav-item' + (active ? ' active' : '')}
            onClick={() => {
              if (!active) haptic('selection');
              onChange(key);
            }}
            aria-current={active ? 'page' : undefined}
          >
            {active && (
              <motion.span layoutId="nav-pill" className="nav-pill" transition={{ type: 'spring', stiffness: 520, damping: 40 }} />
            )}
            <motion.span
              style={{ display: 'grid', position: 'relative', zIndex: 1 }}
              animate={{ y: active ? -1 : 0, scale: active ? 1.06 : 1 }}
              transition={{ type: 'spring', stiffness: 500, damping: 30 }}
            >
              <Icon size={22} strokeWidth={active ? 2.3 : 1.9} />
            </motion.span>
            <span className="nav-label">{text}</span>
          </button>
        );
      })}
    </motion.nav>
  );
}
