import { motion } from 'framer-motion';
import { Send } from 'lucide-react';
import { Emblem } from '../components/ui';
import { useI18n } from '../lib/i18n';
import { haptic, openLink, tg } from '../lib/tg';

/**
 * Входить в LMS в мини-приложении нельзя: пароль, код OneID и QR вводятся только в чате с ботом.
 * Не вошедшего отправляем туда — ссылка `?start=login` сразу открывает вход, после него приложение
 * подхватит ту же сессию.
 */
export function LoginInBot({ botUsername }: { botUsername: string }) {
  const { t } = useI18n();
  const url = `https://t.me/${botUsername}?start=login`;

  const go = () => {
    haptic('medium');
    const app = tg;
    if (app?.openTelegramLink) {
      app.openTelegramLink(url);
      // Чат с ботом открывается поверх, мини-приложение закрываем — после входа его откроют заново.
      setTimeout(() => app.close(), 300);
    } else {
      openLink(url);
    }
  };

  return (
    <div className="screen" style={{ paddingBottom: 'calc(var(--safe-bottom) + 24px)' }}>
      <div className="login-wrap">
        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.4, ease: [0.2, 0.8, 0.2, 1] }}
          style={{ marginTop: 18 }}
        >
          <Emblem small />
          <h1 className="page-title" style={{ marginTop: 22 }}>{t('login_in_bot_title')}</h1>
          <div className="page-sub wrap" style={{ fontSize: 15 }}>{t('login_in_bot_sub')}</div>
        </motion.div>

        <button className="btn" onClick={go} style={{ marginTop: 28 }}>
          <Send size={18} /> {t('open_bot')}
        </button>

        <div className="divider-note" style={{ marginTop: 'auto', paddingTop: 28 }}>{t('login_in_bot_note')}</div>
      </div>
    </div>
  );
}
