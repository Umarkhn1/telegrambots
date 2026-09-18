import {
  Download,
  ExternalLink,
  File as FileIcon,
  FileText,
  LoaderCircle,
  Presentation,
  Send,
  Video,
  type LucideIcon,
} from 'lucide-react';
import { useState } from 'react';
import { api, ApiError } from '../lib/api';
import { useI18n } from '../lib/i18n';
import { canDownload, openLink, tg } from '../lib/tg';
import { useToast } from './Toast';

const ICONS: Record<string, LucideIcon> = {
  pdf: FileText,
  doc: FileText,
  ppt: Presentation,
  video: Video,
  url: ExternalLink,
  file: FileIcon,
};

function guessType(name: string): string {
  const n = name.toLowerCase();
  if (n.endsWith('.pdf')) return 'pdf';
  if (/\.(docx?|rtf|txt)$/.test(n)) return 'doc';
  if (/\.pptx?$/.test(n)) return 'ppt';
  if (/\.(mp4|avi|mov|mkv)$/.test(n)) return 'video';
  return 'file';
}

/**
 * Файл из LMS. «В чат» — бот присылает документ в переписку (работает везде);
 * «Скачать» — системная загрузка Telegram (Bot API 8.0+). Внешние ссылки открываются в браузере.
 */
export function FileRow({ name, url, type, label }: { name: string; url: string; type?: string; label?: string }) {
  const { t } = useI18n();
  const toast = useToast();
  const [busy, setBusy] = useState<'send' | 'dl' | null>(null);
  const kind = type ?? guessType(name);
  const Icon = ICONS[kind] ?? FileIcon;
  const external = kind === 'url';

  const fail = (e: unknown) =>
    toast(e instanceof ApiError && e.code === 'chat_unavailable' ? t('chat_unavailable') : t('download_failed'), 'error');

  const send = async () => {
    setBusy('send');
    try {
      await api.sendFile(url, name);
      toast(t('sent_to_chat'));
    } catch (e) {
      fail(e);
    } finally {
      setBusy(null);
    }
  };

  const download = async () => {
    setBusy('dl');
    try {
      const { path } = await api.fileLink(url, name);
      tg!.downloadFile!({ url: new URL(path, location.origin).toString(), file_name: name });
    } catch (e) {
      fail(e);
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="row with-icon">
      <span className={'tile ' + (external ? 'violet' : '')}>
        <Icon size={18} />
      </span>
      <div className="row-main">
        {label && <div className="row-sub" style={{ marginTop: 0, marginBottom: 2 }}>{label}</div>}
        <div className="row-title clamp-2" style={{ fontSize: 14.5 }}>{name}</div>
      </div>
      {external ? (
        <button className="btn small tinted" style={{ width: 'auto' }} onClick={() => openLink(url)}>
          {t('open_link')}
        </button>
      ) : (
        <div style={{ display: 'flex', gap: 6 }}>
          {canDownload() && (
            <button className="icon-btn" style={{ width: 38, height: 38 }} onClick={download} disabled={!!busy} aria-label={t('download')}>
              {busy === 'dl' ? <LoaderCircle size={17} className="spin" /> : <Download size={17} />}
            </button>
          )}
          <button className="btn small tinted" style={{ width: 'auto', paddingLeft: 12 }} onClick={send} disabled={!!busy}>
            {busy === 'send' ? <LoaderCircle size={16} className="spin" /> : <Send size={15} />}
            {t('send_to_chat')}
          </button>
        </div>
      )}
    </div>
  );
}
