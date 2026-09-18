import { AnimatePresence, motion } from 'framer-motion';
import { CircleAlert, Eye, EyeOff, Globe, KeyRound, LoaderCircle, Smartphone, UserRound } from 'lucide-react';
import { useState, type FormEvent, type ReactNode } from 'react';
import { LanguageSheet } from '../components/LanguageSheet';
import { Emblem, Segmented } from '../components/ui';
import { api, ApiError } from '../lib/api';
import { useI18n } from '../lib/i18n';
import { haptic, useBackButton } from '../lib/tg';
import type { OneIdResponse } from '../lib/types';

type Method = 'lms' | 'oneid';
type OneIdWay = 'password' | 'mobile';
type Step = 'form' | 'code';

function Field({
  label,
  icon,
  children,
}: {
  label: string;
  icon: ReactNode;
  children: ReactNode;
}) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      <span className="input-wrap">
        <span className="input-icon">{icon}</span>
        {children}
      </span>
    </label>
  );
}

function PasswordInput({ value, onChange, autoComplete }: { value: string; onChange: (v: string) => void; autoComplete: string }) {
  const [shown, setShown] = useState(false);
  return (
    <>
      <input
        className="input"
        type={shown ? 'text' : 'password'}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        autoComplete={autoComplete}
        style={{ paddingRight: 48 }}
      />
      <button type="button" className="input-action" onClick={() => setShown((s) => !s)} tabIndex={-1} aria-label="toggle">
        {shown ? <EyeOff size={18} /> : <Eye size={18} />}
      </button>
    </>
  );
}

/** Маска +998 XX XXX XX XX: цифры после кода страны. */
function formatPhone(raw: string): string {
  let d = raw.replace(/\D/g, '');
  if (d.startsWith('998')) d = d.slice(3);
  d = d.slice(0, 9);
  const parts = [d.slice(0, 2), d.slice(2, 5), d.slice(5, 7), d.slice(7, 9)].filter(Boolean);
  return '+998 ' + parts.join(' ');
}

export function Login({ onDone }: { onDone: () => Promise<void> }) {
  const { t, lang } = useI18n();
  const [method, setMethod] = useState<Method>('lms');
  const [way, setWay] = useState<OneIdWay>('password');
  const [step, setStep] = useState<Step>('form');
  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [phone, setPhone] = useState('+998 ');
  const [code, setCode] = useState('');
  const [sentTo, setSentTo] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [langOpen, setLangOpen] = useState(false);

  useBackButton(step === 'code', () => {
    setStep('form');
    setCode('');
    setError('');
  });

  const fail = (msg: string) => {
    haptic('error');
    setError(msg);
  };

  const handleOneId = async (res: OneIdResponse, phoneForCode?: string) => {
    if (res.status === 'OK') {
      haptic('success');
      await onDone();
    } else if (res.status === 'NEED_SMS') {
      setSentTo(phoneForCode ?? '');
      setStep('code');
      setCode('');
    } else if (res.reason === 'link') fail(t('err_link'));
    else if (res.reason === 'phone') fail(t('err_phone'));
    else fail(res.message || t('err_oneid'));
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    setError('');
    setBusy(true);
    try {
      if (step === 'code') {
        const res = way === 'mobile' ? await api.mobileConfirm(code) : await api.confirmOneId(login, code);
        await handleOneId(res);
      } else if (method === 'lms') {
        const res = await api.loginLms(login, password);
        if (res.ok) {
          haptic('success');
          await onDone();
        } else if (res.reason === 'oneid_required') {
          // LMS пускает этот аккаунт только через OneID — сразу открываем нужную вкладку.
          setMethod('oneid');
          setWay('password');
          setPassword('');
          fail(t('err_oneid_required'));
        } else fail(res.reason === 'lms_unavailable' ? t('error_lms') : t('err_credentials'));
      } else if (way === 'password') {
        await handleOneId(await api.loginOneId(login, password));
      } else {
        const res = await api.mobileSend(phone);
        await handleOneId(res, res.phone ?? phone);
      }
    } catch (err) {
      fail(err instanceof ApiError && err.code === 'network' ? t('error_network') : t('error_generic'));
    } finally {
      setBusy(false);
    }
  };

  const canSubmit =
    step === 'code'
      ? code.trim().length >= 4
      : method === 'oneid' && way === 'mobile'
        ? phone.replace(/\D/g, '').length === 12
        : login.trim() && password;

  const formKey = step === 'code' ? 'code' : method + way;

  return (
    <div className="screen" style={{ paddingBottom: 'calc(var(--safe-bottom) + 24px)' }}>
      <div className="login-wrap">
        <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <button className="semester-chip" onClick={() => setLangOpen(true)}>
            <Globe size={15} />
            {lang === 'ru' ? 'RU' : lang === 'uz_lat' ? "O'Z" : lang === 'en' ? 'EN' : 'ЎЗ'}
          </button>
        </div>

        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.4, ease: [0.2, 0.8, 0.2, 1] }}
          style={{ marginTop: 18 }}
        >
          <Emblem small />
          <h1 className="page-title" style={{ marginTop: 22 }}>{step === 'code' ? t('f_code') : t('login_title')}</h1>
          <div className="page-sub" style={{ fontSize: 15 }}>
            {step === 'code' ? (way === 'mobile' ? t('code_sent', { phone: sentTo }) : t('code_2fa')) : t('login_sub')}
          </div>
        </motion.div>

        <form onSubmit={submit} style={{ marginTop: 26, display: 'flex', flexDirection: 'column', gap: 16 }}>
          {step === 'form' && (
            <Segmented<Method>
              value={method}
              onChange={(m) => {
                setMethod(m);
                setError('');
              }}
              options={[
                { value: 'lms', label: t('method_lms') },
                { value: 'oneid', label: t('method_oneid') },
              ]}
            />
          )}

          <AnimatePresence mode="wait" initial={false}>
            <motion.div
              key={formKey}
              initial={{ opacity: 0, x: 14 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -14 }}
              transition={{ duration: 0.2 }}
              style={{ display: 'flex', flexDirection: 'column', gap: 14 }}
            >
              {step === 'code' ? (
                <input
                  className="input code tabular"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  autoFocus
                  maxLength={8}
                  value={code}
                  onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                  placeholder="••••••"
                />
              ) : method === 'lms' ? (
                <>
                  <Field label={t('f_login')} icon={<UserRound size={18} />}>
                    <input
                      className="input"
                      value={login}
                      onChange={(e) => setLogin(e.target.value)}
                      placeholder={t('f_login_ph')}
                      autoCapitalize="none"
                      autoCorrect="off"
                      autoComplete="username"
                    />
                  </Field>
                  <Field label={t('f_password')} icon={<KeyRound size={18} />}>
                    <PasswordInput value={password} onChange={setPassword} autoComplete="current-password" />
                  </Field>
                </>
              ) : (
                <>
                  <Segmented<OneIdWay>
                    value={way}
                    onChange={(w) => {
                      setWay(w);
                      setError('');
                    }}
                    options={[
                      { value: 'password', label: t('oneid_password') },
                      { value: 'mobile', label: t('oneid_mobile') },
                    ]}
                  />
                  {way === 'password' ? (
                    <>
                      <Field label={t('f_oneid_login')} icon={<UserRound size={18} />}>
                        <input
                          className="input"
                          value={login}
                          onChange={(e) => setLogin(e.target.value)}
                          autoCapitalize="none"
                          autoCorrect="off"
                          autoComplete="username"
                        />
                      </Field>
                      <Field label={t('f_password')} icon={<KeyRound size={18} />}>
                        <PasswordInput value={password} onChange={setPassword} autoComplete="current-password" />
                      </Field>
                    </>
                  ) : (
                    <Field label={t('f_phone')} icon={<Smartphone size={18} />}>
                      <input
                        className="input tabular"
                        inputMode="tel"
                        autoComplete="tel"
                        value={phone}
                        onChange={(e) => setPhone(formatPhone(e.target.value))}
                      />
                    </Field>
                  )}
                </>
              )}
            </motion.div>
          </AnimatePresence>

          <AnimatePresence>
            {error && (
              <motion.div
                className="error-text"
                initial={{ opacity: 0, height: 0 }}
                animate={{ opacity: 1, height: 'auto' }}
                exit={{ opacity: 0, height: 0 }}
              >
                <CircleAlert size={16} style={{ marginTop: 1 }} />
                <span>{error}</span>
              </motion.div>
            )}
          </AnimatePresence>

          <button className="btn" type="submit" disabled={!canSubmit || busy} style={{ marginTop: 4 }}>
            {busy && <LoaderCircle size={18} className="spin" />}
            {step === 'code' ? t('confirm') : method === 'oneid' && way === 'mobile' ? t('get_code') : t('sign_in')}
          </button>

          {step === 'code' && (
            <button
              type="button"
              className="btn secondary"
              onClick={() => {
                setStep('form');
                setError('');
              }}
            >
              {t('back')}
            </button>
          )}
        </form>

        <div className="divider-note" style={{ marginTop: 'auto', paddingTop: 28 }}>{t('login_note')}</div>
      </div>
      <LanguageSheet open={langOpen} onClose={() => setLangOpen(false)} />
    </div>
  );
}
