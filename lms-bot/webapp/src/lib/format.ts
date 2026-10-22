import { dayMonth, type TFn } from './i18n';
import type { Lang, StudyPlanSubject } from './types';

/** «2026-09-18» → локальная дата без сдвига по поясу. */
export function parseISODate(s: string): Date {
  const [y, m, d] = s.split('-').map(Number);
  return new Date(y, m - 1, d);
}

export function isoDate(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export function addDays(d: Date, n: number): Date {
  const r = new Date(d);
  r.setDate(r.getDate() + n);
  return r;
}

export function mondayOf(d: Date): Date {
  const r = new Date(d.getFullYear(), d.getMonth(), d.getDate());
  const dow = (r.getDay() + 6) % 7;
  r.setDate(r.getDate() - dow);
  return r;
}

/** Сколько осталось: «45 мин», «5 ч 20 мин», «3 дн 4 ч». */
export function duration(ms: number, t: TFn): string {
  const min = Math.max(0, Math.round(ms / 60000));
  if (min < 60) return `${min} ${t('u_min')}`;
  const h = Math.floor(min / 60);
  if (h < 24) {
    const m = min % 60;
    return m ? `${h} ${t('u_h')} ${m} ${t('u_min')}` : `${h} ${t('u_h')}`;
  }
  const d = Math.floor(h / 24);
  const rh = h % 24;
  return rh ? `${d} ${t('u_d')} ${rh} ${t('u_h')}` : `${d} ${t('u_d')}`;
}

/** Дата и время дедлайна: «24 мар, 23:59». */
export function deadlineLabel(ts: number, lang: Lang): string {
  const d = new Date(ts);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${dayMonth(d, lang, true)}, ${hh}:${mm}`;
}

export type Urgency = 'danger' | 'warning' | 'accent' | 'neutral';

export function urgency(ts: number | null, now = Date.now()): Urgency {
  if (!ts) return 'neutral';
  const left = ts - now;
  if (left < 0) return 'neutral';
  if (left < 24 * 3600e3) return 'danger';
  if (left < 3 * 24 * 3600e3) return 'warning';
  return 'accent';
}

export function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase() ?? '')
    .join('');
}

export function roman(n: number): string {
  const map: [number, string][] = [[10, 'X'], [9, 'IX'], [5, 'V'], [4, 'IV'], [1, 'I']];
  let out = '';
  for (const [v, s] of map) while (n >= v) { out += s; n -= v; }
  return out;
}

/** Число из строки LMS: «4,5» → 4.5; null, если числа нет. */
export function num(s: string | null | undefined): number | null {
  if (s == null) return null;
  const m = String(s).replace(',', '.').match(/-?\d+(\.\d+)?/);
  return m ? Number(m[0]) : null;
}

/* ─── GPA ─── */

/** Текущий семестр — первый, где есть предмет без оценки (как в боте). */
export function currentPlanSemester(subjects: StudyPlanSubject[]): number {
  const sems = [...new Set(subjects.map((s) => s.semester))].sort((a, b) => a - b);
  for (const s of sems) if (subjects.some((x) => x.semester === s && x.grade == null)) return s;
  return sems[sems.length - 1] ?? 0;
}

export function gpa(subjects: StudyPlanSubject[], withZero: boolean) {
  const current = currentPlanSemester(subjects);
  let credits = 0;
  let points = 0;
  for (const s of subjects) {
    let g: number;
    if (s.grade != null) g = s.grade;
    else if (withZero && s.semester === current) g = 0;
    else continue;
    credits += s.credits;
    points += g * s.credits;
  }
  return { value: credits ? points / credits : null, credits };
}

export function gpaComment(v: number, t: TFn): string {
  if (v >= 4.5) return t('gpa_5');
  if (v >= 4) return t('gpa_4');
  if (v >= 3.5) return t('gpa_35');
  if (v >= 3) return t('gpa_3');
  return t('gpa_2');
}

/** Название вкладки плана занятий: ключи LMS — lecture, practice, laboratory… */
export function tabLabel(key: string, t: TFn): string {
  const k = key.toLowerCase();
  if (k.includes('lec') || k.includes('ruza')) return t('lecture');
  if (k.includes('lab')) return t('laboratory');
  if (k.includes('sem')) return t('seminar');
  if (k.includes('prac') || k.includes('amal')) return t('practice');
  return key.charAt(0).toUpperCase() + key.slice(1);
}
