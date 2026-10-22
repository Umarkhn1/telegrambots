import { motion } from 'framer-motion';
import { Bell, ChevronRight, Globe, KeyRound, LogOut, Palette, ShieldCheck } from 'lucide-react';
import { useState } from 'react';
import { LanguageSheet } from '../../components/LanguageSheet';
import { Sheet } from '../../components/Sheet';
import { ErrorState, PageHead, Screen, Section, Segmented, Skeleton, Tile } from '../../components/ui';
import { api } from '../../lib/api';
import { useApp, type ThemePref } from '../../lib/app';
import { initials } from '../../lib/format';
import { LANG_NAMES, useI18n } from '../../lib/i18n';
import { useQuery } from '../../lib/query';
import { useTT } from '../../lib/ti18n';
import { confirmDialog, haptic, tg } from '../../lib/tg';
import { PasswordSheet } from '../Profile';

/** Профиль преподавателя: поля и подписи — со страницы LMS «Информация». */
export function TeacherProfile() {
  const { me, theme, setTheme, logout, push } = useApp();
  const { t, lang } = useI18n();
  const tt = useTT();
  const [langOpen, setLangOpen] = useState(false);
  const [pwOpen, setPwOpen] = useState(false);
  const [photoOpen, setPhotoOpen] = useState(false);
  const info = useQuery('tprofile', (f) => api.t.profile(f));
  const photo = useQuery('photo', () => api.photo(), 30 * 60_000);

  const name = info.data?.fullName || [me.user.firstName, me.user.lastName].filter(Boolean).join(' ');
  const avatar = photo.data?.dataUrl || me.user.photoUrl;
  const fields = (info.data?.fields ?? []).slice(1);

  return (
    <Screen onRefresh={() => info.refresh()}>
      <PageHead title={t('profile_title')} />

      <div className="card" style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
        {avatar ? (
          <motion.button className="photo" initial={{ scale: 0.94, opacity: 0 }} animate={{ scale: 1, opacity: 1 }}
            onClick={() => { haptic('light'); setPhotoOpen(true); }} aria-label={t('photo')}>
            <img src={avatar} alt="" />
          </motion.button>
        ) : photo.loading ? (
          <Skeleton h={122} w={92} r={16} />
        ) : (
          <div className="avatar large">{initials(name)}</div>
        )}
        <div style={{ minWidth: 0, flex: 1 }}>
          {info.loading ? (
            <Skeleton h={18} w="80%" />
          ) : (
            <>
              <div className="row-title" style={{ fontSize: 18, fontWeight: 650, lineHeight: 1.25 }}>{name}</div>
              <span className="badge accent" style={{ marginTop: 8 }}>
                {tt('role_teacher')}{me.tutor ? ` · ${tt('role_tutor')}` : ''}
              </span>
            </>
          )}
        </div>
      </div>

      {info.error ? (
        <Section><ErrorState error={info.error} onRetry={info.refresh} /></Section>
      ) : fields.length > 0 ? (
        <Section title={tt('info')}>
          <div className="list">
            {fields.map(([label, value]) => (
              <div className="kv" key={label}>
                <span className="kv-label">{label}</span>
                <span className="kv-value">{value}</span>
              </div>
            ))}
          </div>
        </Section>
      ) : null}

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
          <button className="row with-icon pressable" style={{ alignItems: 'flex-start' }}
            onClick={() => tg?.openTelegramLink(`https://t.me/${me.botUsername}`)}>
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
        <button className="btn danger" onClick={async () => {
          haptic('warning');
          if (await confirmDialog(t('logout_confirm'))) await logout();
        }}>
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
