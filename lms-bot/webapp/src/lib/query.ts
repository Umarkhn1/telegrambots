import { useCallback, useEffect, useReducer } from 'react';

/**
 * Небольшой кэш запросов: данные переживают переключение вкладок,
 * повторный заход показывает сохранённое сразу и тихо обновляет устаревшее.
 */
interface Entry {
  data?: unknown;
  error?: unknown;
  ts: number;
  promise?: Promise<unknown>;
  listeners: Set<() => void>;
}

const store = new Map<string, Entry>();

function entry(key: string): Entry {
  let e = store.get(key);
  if (!e) {
    e = { ts: 0, listeners: new Set() };
    store.set(key, e);
  }
  return e;
}

function notify(e: Entry) {
  e.listeners.forEach((l) => l());
}

function load(key: string, fetcher: (fresh: boolean) => Promise<unknown>, fresh: boolean) {
  const e = entry(key);
  if (e.promise && !fresh) return e.promise;
  const p = fetcher(fresh)
    .then((data) => {
      e.data = data;
      e.error = undefined;
      e.ts = Date.now();
      return data;
    })
    .catch((err) => {
      e.error = err;
    })
    .finally(() => {
      if (e.promise === p) e.promise = undefined;
      notify(e);
    });
  e.promise = p;
  notify(e);
  return p;
}

export function useQuery<T>(key: string | null, fetcher: (fresh: boolean) => Promise<T>, staleMs = 120_000) {
  const [, force] = useReducer((x: number) => x + 1, 0);

  useEffect(() => {
    if (!key) return;
    const e = entry(key);
    e.listeners.add(force);
    if (!e.promise && (e.data === undefined || Date.now() - e.ts > staleMs)) load(key, fetcher, false);
    return () => {
      e.listeners.delete(force);
    };
    // fetcher меняется на каждом рендере; ключ однозначно описывает запрос
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  const e = key ? store.get(key) : undefined;
  const refresh = useCallback(() => (key ? load(key, fetcher, true) : Promise.resolve()), [key, fetcher]);

  return {
    data: e?.data as T | undefined,
    error: e?.data === undefined ? e?.error : undefined,
    loading: !!key && e?.data === undefined && (!!e?.promise || e?.error === undefined),
    refreshing: !!e?.promise,
    refresh,
  };
}

/** Сбрасывает кэш по префиксу ключа (после загрузки файла, смены аккаунта). */
export function invalidate(prefix = '') {
  for (const k of [...store.keys()]) {
    if (k.startsWith(prefix)) {
      const e = store.get(k)!;
      e.ts = 0;
      if (!prefix) e.data = undefined;
    }
  }
}

export function prefetch<T>(key: string, fetcher: (fresh: boolean) => Promise<T>) {
  const e = entry(key);
  if (e.data === undefined && !e.promise) load(key, fetcher, false);
}
