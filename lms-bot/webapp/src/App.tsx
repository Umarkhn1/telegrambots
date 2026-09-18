import { AnimatePresence, motion } from 'framer-motion';
import { CircleAlert, GraduationCap, LoaderCircle, RefreshCw, Send } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { BottomNav } from './components/BottomNav';
import { Empty } from './components/ui';
import { api, SESSION_LOST } from './lib/api';
import { AppContext, TABS, type AppState, type Route, type Tab, type ThemePref } from './lib/app';
import { LangContext, makeT } from './lib/i18n';
import { invalidate } from './lib/query';
import { haptic, initData, paintChrome, tg, useBackButton } from './lib/tg';
import type { Lang, Me } from './lib/types';
import { CourseDetail } from './screens/CourseDetail';
import { Courses } from './screens/Courses';
import { Deadlines } from './screens/Deadlines';
import { Grades } from './screens/Grades';
import { Home } from './screens/Home';
import { Login } from './screens/Login';
import { Profile } from './screens/Profile';
import { Schedule } from './screens/Schedule';

const store = {
  get(key: string) {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  },
  set(key: string, v: string) {
    try {
      localStorage.setItem(key, v);
    } catch {
      /* приватный режим — не страшно */
    }
  },
};

function guessLang(code?: string): Lang {
  if (code?.startsWith('ru')) return 'ru';
  return 'uz_lat';
}

function useTheme() {
  const [pref, setPref] = useState<ThemePref>(() => (store.get('theme') as ThemePref) || 'system');
  const [scheme, setScheme] = useState<'light' | 'dark'>(() =>
    tg?.colorScheme ?? (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'),
  );

  useEffect(() => {
    const onTg = () => tg && setScheme(tg.colorScheme);
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const onMq = () => !tg && setScheme(mq.matches ? 'dark' : 'light');
    tg?.onEvent('themeChanged', onTg);
    mq.addEventListener('change', onMq);
    return () => {
      tg?.offEvent('themeChanged', onTg);
      mq.removeEventListener('change', onMq);
    };
  }, []);

  const resolved = pref === 'system' ? scheme : pref;
  useEffect(() => {
    document.documentElement.dataset.theme = resolved;
    const bg = getComputedStyle(document.documentElement).getPropertyValue('--bg').trim();
    paintChrome(bg);
  }, [resolved]);

  const set = (p: ThemePref) => {
    store.set('theme', p);
    setPref(p);
  };
  return [pref, set] as const;
}

export function App() {
  const [me, setMe] = useState<Me | null>(null);
  const [bootError, setBootError] = useState(false);
  const [lang, setLangState] = useState<Lang>(() => (store.get('lang') as Lang) || guessLang(tg?.initDataUnsafe.user?.language_code));
  const [theme, setTheme] = useTheme();
  const [tab, setTabState] = useState<Tab>('home');
  const [stack, setStack] = useState<Route[]>([]);
  const [semesterId, setSemesterId] = useState<number | null>(null);
  const prevTab = useRef<Tab>('home');

  const t = useMemo(() => makeT(lang), [lang]);
  const noTelegram = !initData && !import.meta.env.DEV;

  const loadMe = useCallback(async () => {
    setBootError(false);
    try {
      const m = await api.me();
      setMe(m);
      if (m.lang) {
        setLangState(m.lang);
        store.set('lang', m.lang);
      }
      setSemesterId((cur) => (cur && m.semesters?.some((s) => s.id === cur) ? cur : m.currentSemesterId ?? m.semesters?.[0]?.id ?? null));
    } catch {
      setBootError(true);
    }
  }, []);

  useEffect(() => {
    if (!noTelegram) loadMe();
  }, [loadMe, noTelegram]);

  useEffect(() => {
    const onLost = () => setMe((m) => (m ? { ...m, loggedIn: false } : m));
    window.addEventListener(SESSION_LOST, onLost);
    return () => window.removeEventListener(SESSION_LOST, onLost);
  }, []);

  const setLang = useCallback((l: Lang) => {
    haptic('selection');
    setLangState(l);
    store.set('lang', l);
    api.setLang(l).catch(() => {});
  }, []);

  const pop = useCallback(() => setStack((s) => s.slice(0, -1)), []);
  useBackButton(stack.length > 0, pop);

  const app: AppState | null = me && {
    me,
    semesters: me.semesters ?? [],
    semesterId,
    setSemesterId,
    tab,
    setTab: (next) => {
      prevTab.current = tab;
      setStack([]);
      setTabState(next);
    },
    push: (r) => setStack((s) => [...s, r]),
    pop,
    theme,
    setTheme,
    logout: async () => {
      await api.logout().catch(() => {});
      invalidate('');
      setStack([]);
      setTabState('home');
      setMe((m) => (m ? { ...m, loggedIn: false } : m));
    },
  };

  const langCtx = useMemo(() => ({ lang, t, setLang }), [lang, t, setLang]);

  let body;
  if (noTelegram) {
    body = (
      <div className="screen" style={{ display: 'grid', placeItems: 'center' }}>
        <Empty icon={Send} tone="" title={t('open_in_tg')} sub={t('open_in_tg_sub', { bot: 'lmstgbot' })} />
      </div>
    );
  } else if (bootError) {
    body = (
      <div className="screen" style={{ display: 'grid', placeItems: 'center' }}>
        <Empty
          icon={CircleAlert}
          tone="danger"
          title={t('error_generic')}
          sub={t('error_network')}
          action={
            <button className="btn small tinted" style={{ width: 'auto' }} onClick={loadMe}>
              <RefreshCw size={16} /> {t('retry')}
            </button>
          }
        />
      </div>
    );
  } else if (!me || !app) {
    body = (
      <div className="screen" style={{ display: 'grid', placeItems: 'center' }}>
        <motion.div initial={{ opacity: 0, scale: 0.92 }} animate={{ opacity: 1, scale: 1 }} style={{ display: 'grid', justifyItems: 'center', gap: 18 }}>
          <div className="brand"><GraduationCap size={32} strokeWidth={1.9} /></div>
          <LoaderCircle size={22} className="spin" color="var(--text-3)" />
        </motion.div>
      </div>
    );
  } else if (!me.loggedIn) {
    body = (
      <Login
        onDone={async () => {
          invalidate('');
          await loadMe();
        }}
      />
    );
  } else {
    const dir = TABS.indexOf(tab) >= TABS.indexOf(prevTab.current) ? 1 : -1;
    const top = stack[stack.length - 1];
    body = (
      <AppContext.Provider value={app}>
        <AnimatePresence initial={false} custom={dir}>
          <motion.div
            key={tab}
            custom={dir}
            style={{ position: 'absolute', inset: 0 }}
            initial={{ opacity: 0, x: dir * 28 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: dir * -28 }}
            transition={{ duration: 0.24, ease: [0.2, 0.8, 0.2, 1] }}
          >
            {tab === 'home' && <Home />}
            {tab === 'courses' && <Courses />}
            {tab === 'schedule' && <Schedule />}
            {tab === 'grades' && <Grades />}
            {tab === 'profile' && <Profile />}
          </motion.div>
        </AnimatePresence>

        <AnimatePresence>
          {top && (
            <motion.div
              key={stack.length + top.name}
              style={{ position: 'absolute', inset: 0, zIndex: 20 }}
              initial={{ x: '100%' }}
              animate={{ x: 0 }}
              exit={{ x: '100%' }}
              transition={{ type: 'spring', stiffness: 380, damping: 40 }}
            >
              {top.name === 'course' && <CourseDetail course={top.course} onBack={pop} />}
              {top.name === 'deadlines' && <Deadlines onBack={pop} />}
            </motion.div>
          )}
        </AnimatePresence>

        <BottomNav tab={tab} onChange={app.setTab} hidden={stack.length > 0} />
      </AppContext.Provider>
    );
  }

  return (
    <LangContext.Provider value={langCtx}>
      <div className="app">{body}</div>
    </LangContext.Provider>
  );
}
