import { useEffect, useRef } from 'react';

/** Минимальная типизация Telegram.WebApp — только то, чем пользуется приложение. */
interface TgButton {
  show(): void;
  hide(): void;
  onClick(cb: () => void): void;
  offClick(cb: () => void): void;
}

interface TgWebApp {
  initData: string;
  version: string;
  platform: string;
  colorScheme: 'light' | 'dark';
  initDataUnsafe: { user?: { id: number; first_name?: string; language_code?: string } };
  ready(): void;
  expand(): void;
  isVersionAtLeast(v: string): boolean;
  setHeaderColor(color: string): void;
  setBackgroundColor(color: string): void;
  setBottomBarColor?(color: string): void;
  disableVerticalSwipes?(): void;
  enableClosingConfirmation?(): void;
  onEvent(event: string, cb: () => void): void;
  offEvent(event: string, cb: () => void): void;
  showConfirm(message: string, cb: (ok: boolean) => void): void;
  openLink(url: string, options?: { try_instant_view?: boolean }): void;
  openTelegramLink(url: string): void;
  downloadFile?(params: { url: string; file_name: string }, cb?: (accepted: boolean) => void): void;
  close(): void;
  BackButton: TgButton;
  HapticFeedback: {
    impactOccurred(style: 'light' | 'medium' | 'heavy' | 'rigid' | 'soft'): void;
    notificationOccurred(type: 'error' | 'success' | 'warning'): void;
    selectionChanged(): void;
  };
}

export const tg: TgWebApp | undefined = (window as unknown as { Telegram?: { WebApp?: TgWebApp } }).Telegram?.WebApp;

/** Внутри Telegram initData непустая; в обычном браузере — пустая строка. */
export const initData = tg?.initData ?? '';

export function atLeast(version: string): boolean {
  try {
    return !!tg && tg.isVersionAtLeast(version);
  } catch {
    return false;
  }
}

export function bootTelegram() {
  if (!tg) return;
  tg.ready();
  tg.expand();
  // Свайп вниз по шторке не должен сворачивать всё приложение.
  if (atLeast('7.7')) tg.disableVerticalSwipes?.();
}

export function paintChrome(bg: string) {
  if (!tg) return;
  try {
    if (atLeast('6.1')) {
      tg.setHeaderColor(bg);
      tg.setBackgroundColor(bg);
    }
    if (atLeast('7.10')) tg.setBottomBarColor?.(bg);
  } catch {
    /* старые клиенты не умеют — не критично */
  }
}

type Haptic = 'light' | 'medium' | 'soft' | 'selection' | 'success' | 'error' | 'warning';

export function haptic(kind: Haptic = 'light') {
  if (!tg || !atLeast('6.1')) return;
  try {
    if (kind === 'selection') tg.HapticFeedback.selectionChanged();
    else if (kind === 'success' || kind === 'error' || kind === 'warning') tg.HapticFeedback.notificationOccurred(kind);
    else tg.HapticFeedback.impactOccurred(kind);
  } catch {
    /* ignore */
  }
}

export function confirmDialog(message: string): Promise<boolean> {
  if (tg && atLeast('6.2')) {
    return new Promise((resolve) => tg!.showConfirm(message, (ok) => resolve(ok)));
  }
  return Promise.resolve(window.confirm(message));
}

export function openLink(url: string) {
  if (tg) tg.openLink(url);
  else window.open(url, '_blank', 'noopener');
}

export function canDownload(): boolean {
  return !!tg?.downloadFile && atLeast('8.0');
}

/*
 * Системная кнопка «Назад» Telegram одна на всё приложение, а закрывать ей нужно
 * верхний слой: шторку поверх экрана предмета, затем сам экран. Поэтому держим стек.
 */
const backStack: { current: () => void }[] = [];
let backWired = false;

function syncBack() {
  if (!tg || !atLeast('6.1')) return;
  if (backStack.length) tg.BackButton.show();
  else tg.BackButton.hide();
}

export function useBackButton(active: boolean, onBack: () => void) {
  const ref = useRef(onBack);
  ref.current = onBack;
  useEffect(() => {
    if (!active) return;
    if (tg && !backWired && atLeast('6.1')) {
      tg.BackButton.onClick(() => backStack[backStack.length - 1]?.current());
      backWired = true;
    }
    backStack.push(ref);
    syncBack();
    return () => {
      const i = backStack.lastIndexOf(ref);
      if (i >= 0) backStack.splice(i, 1);
      syncBack();
    };
  }, [active]);
}
