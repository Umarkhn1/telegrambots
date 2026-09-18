import { motion } from 'framer-motion';
import { Bell, ChevronRight, CircleCheck, Globe, Hourglass, KeyRound, LoaderCircle, LogOut, Palette, ShieldCheck, Wallet } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { LanguageSheet } from '../components/LanguageSheet';
import { Sheet } from '../components/Sheet';
import { useToast } from '../components/Toast';
import { ErrorState, PageHead, Screen, Section, Segmented, Skeleton, Tile } from '../components/ui';
import { api } from '../lib/api';
import { useApp, type ThemePref } from '../lib/app';
import { initials } from '../lib/format';
import { LANG_NAMES, useI18n, type Key } from '../lib/i18n';
import { useQuery } from '../lib/query';
import { confirmDialog, haptic, tg } from '../lib/tg';
import type { Contract, StudentInfo } from '../lib/types';

const PERSONAL: [keyof StudentInfo, Key][] = [
  ['birthDate', 'birth'],
  ['gender', 'gender'],
  ['address', 'address'],
];

const STUDY: [keyof StudentInfo, Key][] = [
  ['direction', 'direction'],
  ['course', 'course_year'],
  ['group', 'group'],
  ['language', 'study_lang'],
  ['degree', 'degree'],
  ['studyType', 'study_type'],
  ['curator', 'curator'],
  ['scholarship', 'scholarship'],
];

function money(v: number | null | undefined): string {
  return v == null ? '—' : v.toLocaleString('ru-RU').replace(/,/g, ' ');
}

export function Profile() {
  const { me, theme, setTheme, logout, push } = useApp();
  const { t, lang } = useI18n();
  const [langOpen, setLangOpen] = useState(false);
  const [pwOpen, setPwOpen] = useState(false);
  const [photoOpen, setPhotoOpen] = useState(false);
  const info = useQuery('profile', (f) => api.profile(f));
  const photo = useQuery('photo', () => api.photo(), 30 * 60_000);
  const contract = useQuery('contract', (f) => api.contract(f), 10 * 60_000);

  const name = info.data?.fullName || [me.user.firstName, me.user.lastName].filter(Boolean).join(' ');
  const avatar = photo.data?.dataUrl || me.user.photoUrl;

  const rows = (fields: [keyof StudentInfo, Key][]) =>
    fields
      .filter(([k]) => info.data?.[k])
      .map(([k, label]) => (
        <div className="kv" key={k}>
          <span className="kv-label">{t(label)}</span>
          <span className="kv-value">{info.data?.[k]}</span>
        </div>
      ));

  return (
    <Screen onRefresh={() => Promise.all([info.refresh(), contract.refresh()])}>
      <PageHead title={t('profile_title')} />

      <div className="card" style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
        {avatar ? (
          <motion.button
            className="photo"
            initial={{ scale: 0.94, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            onClick={() => {
              haptic('light');
              setPhotoOpen(true);
            }}
            aria-label={t('photo')}
          >
            <img src={avatar} alt="" />
          </motion.button>
        ) : photo.loading ? (
          <Skeleton h={122} w={92} r={16} />
        ) : (
          <div className="avatar large">{initials(name)}</div>
        )}
        <div style={{ minWidth: 0, flex: 1 }}>
          {info.loading ? (
            <>
              <Skeleton h={18} w="80%" />
              <Skeleton h={13} w="50%" style={{ marginTop: 8 }} />
            </>
          ) : (
            <>
              <div className="row-title" style={{ fontSize: 18, fontWeight: 650, lineHeight: 1.25 }}>{name}</div>
              {info.data?.recordBook && (
                <div className="row-sub" style={{ marginTop: 4 }}>
                  {t('record_book')} {info.data.recordBook}
                </div>
              )}
              {info.data?.group && <span className="badge accent" style={{ marginTop: 8 }}>{info.data.group}</span>}
            </>
          )}
        </div>
      </div>

      <ContractCard q={contract} />

      {info.error ? (
        <Section><ErrorState error={info.error} onRetry={info.refresh} /></Section>
      ) : (
        <>
          {info.data && rows(STUDY).length > 0 && (
            <Section title={t('study')}>
              <div className="list">{rows(STUDY)}</div>
            </Section>
          )}
          {info.data && rows(PERSONAL).length > 0 && (
            <Section title={t('personal')}>
              <div className="list">{rows(PERSONAL)}</div>
            </Section>
          )}
        </>
      )}

      <Section title={t('settings')}>
        <div className="list">
          <button className="row with-icon pressable" onClick={() => setLangOpen(true)}>
            <Tile icon={Globe} size={18} />
            <div className="row-main"><div className="row-title">{t('language')}</div></div>
            <span className="row-value">{LANG_NAMES[lang]}</span>
            <ChevronRight size={18} className="chev" />
          </button>
          <div className="row with-icon" style={{ flexWrap: 'wrap' }}>
            <Tile icon={Palette} tone="violet" size={18} />
            <div className="row-main"><div className="row-title">{t('theme')}</div></div>
            <Segmented<ThemePref>
              value={theme}
              onChange={setTheme}
              style={{ width: '100%', marginTop: 4 }}
              options={[
                { value: 'system', label: t('theme_system') },
                { value: 'light', label: t('theme_light') },
                { value: 'dark', label: t('theme_dark') },
              ]}
            />
          </div>
          <button className="row with-icon pressable" onClick={() => setPwOpen(true)}>
            <Tile icon={KeyRound} tone="warning" size={18} />
            <div className="row-main"><div className="row-title">{t('change_password')}</div></div>
            <ChevronRight size={18} className="chev" />
          </button>
        </div>
      </Section>

      {me.admin && (
        <Section title={t('admin_panel')}>
          <div className="list">
            <button className="row with-icon pressable" onClick={() => push({ name: 'admin' })}>
              <Tile icon={ShieldCheck} tone="danger" size={18} />
              <div className="row-main">
                <div className="row-title">{t('students')}</div>
                <div className="row-sub">{t('admin_sub')}</div>
              </div>
              <ChevronRight size={18} className="chev" />
            </button>
          </div>
        </Section>
      )}

      <Section title={t('notifications')}>
        <div className="list">
          <button
            className="row with-icon pressable"
            style={{ alignItems: 'flex-start' }}
            onClick={() => tg?.openTelegramLink(`https://t.me/${me.botUsername}`)}
          >
            <Tile icon={Bell} tone="success" size={18} />
            <div className="row-main">
              <div className="row-title">{t('open_bot')}</div>
              <div className="row-sub">{t('notifications_hint')}</div>
            </div>
            <ChevronRight size={18} className="chev" style={{ alignSelf: 'center' }} />
          </button>
        </div>
      </Section>

      <Section>
        <button
          className="btn danger"
          onClick={async () => {
            haptic('warning');
            if (await confirmDialog(t('logout_confirm'))) await logout();
          }}
        >
          <LogOut size={18} /> {t('logout')}
        </button>
      </Section>

      <div className="divider-note">TUIT LMS · @{me.botUsername}</div>

      <LanguageSheet open={langOpen} onClose={() => setLangOpen(false)} />
      <PasswordSheet open={pwOpen} onClose={() => setPwOpen(false)} />
      <Sheet open={photoOpen && !!avatar} onClose={() => setPhotoOpen(false)} title={name}>
        {avatar && <img className="photo-full" src={avatar} alt="" />}
      </Sheet>
    </Screen>
  );
}

/** Шкала оплаты контракта. Если LMS не показывает контракт, карточки нет. */
function ContractCard({ q }: { q: ReturnType<typeof useQuery<Contract>> }) {
  const { t } = useI18n();
  if (q.loading) return <Skeleton h={128} r={18} style={{ marginTop: 12 }} />;
  const c = q.data;
  if (!c?.found) return null;

  // Контракт на учебный год ещё не выставлен — показываем сообщение LMS как есть.
  if (c.notice || (c.total == null && c.paid == null && c.debt == null)) {
    return (
      <Section title={t('contract')}>
        <div className="card" style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          <Tile icon={Hourglass} tone="neutral" />
          <div className="row-main">
            <div className="row-title" style={{ fontWeight: 500 }}>{c.notice || t('contract_empty')}</div>
          </div>
        </div>
      </Section>
    );
  }

  const total = c.total ?? (c.paid != null && c.debt != null ? c.paid + c.debt : null);
  const pct = total && c.paid != null ? Math.min(100, Math.round((c.paid / total) * 100)) : null;
  const done = (c.debt ?? 1) <= 0 || pct === 100;

  return (
    <Section title={t('contract')}>
      <div className="card">
        <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          <Tile icon={done ? CircleCheck : Wallet} tone={done ? 'success' : 'warning'} />
          <div className="row-main">
            <div className="stat-value tabular" style={{ marginTop: 0, fontSize: 26 }}>{pct != null ? `${pct}%` : money(c.paid)}</div>
            <div className="row-sub">{done ? t('contract_done') : `${t('contract_paid')} ${money(c.paid)} ${t('currency')}`}</div>
          </div>
        </div>
        {pct != null && (
          <div className="progress" style={{ height: 10, marginTop: 16 }}>
            <motion.div
              style={{ background: done ? 'var(--success)' : 'var(--accent)' }}
              initial={{ width: 0 }}
              animate={{ width: `${pct}%` }}
              transition={{ duration: 0.9, ease: [0.2, 0.8, 0.2, 1] }}
            />
          </div>
        )}
        <div className="list" style={{ marginTop: 14, background: 'var(--surface-2)', boxShadow: 'none' }}>
          <div className="kv">
            <span className="kv-label">{t('contract_paid')}</span>
            <span className="kv-value tabular" style={{ color: 'var(--success)' }}>{money(c.paid)} {t('currency')}</span>
          </div>
          <div className="kv">
            <span className="kv-label">{t('contract_debt')}</span>
            <span className="kv-value tabular" style={{ color: (c.debt ?? 0) > 0 ? 'var(--danger)' : undefined }}>{money(c.debt)} {t('currency')}</span>
          </div>
          <div className="kv">
            <span className="kv-label">{t('contract_total')}</span>
            <span className="kv-value tabular">{money(total)} {t('currency')}</span>
          </div>
        </div>
      </div>
    </Section>
  );
}

function PasswordSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const [old, setOld] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const close = () => {
    setOld('');
    setNext('');
    setConfirm('');
    setError('');
    onClose();
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (next !== confirm) return setError(t('password_mismatch'));
    setBusy(true);
    setError('');
    try {
      const res = await api.changePassword(old, next, confirm);
      if (res.ok) {
        toast(t('password_changed'));
        close();
      } else setError(t('password_failed'));
    } catch {
      setError(t('password_failed'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Sheet open={open} onClose={close} title={t('change_password')}>
      <form onSubmit={submit} className="stack" style={{ gap: 14 }}>
        {[
          { label: t('old_password'), v: old, set: setOld, ac: 'current-password' },
          { label: t('new_password'), v: next, set: setNext, ac: 'new-password' },
          { label: t('confirm_password'), v: confirm, set: setConfirm, ac: 'new-password' },
        ].map((f) => (
          <label className="field" key={f.label}>
            <span className="field-label">{f.label}</span>
            <span className="input-wrap">
              <span className="input-icon"><KeyRound size={18} /></span>
              <input className="input" type="password" value={f.v} autoComplete={f.ac} onChange={(e) => f.set(e.target.value)} />
            </span>
          </label>
        ))}
        {error && <div className="error-text">{error}</div>}
        <button className="btn" type="submit" disabled={busy || !old || !next || !confirm}>
          {busy && <LoaderCircle size={18} className="spin" />}
          {t('save')}
        </button>
      </form>
    </Sheet>
  );
}
