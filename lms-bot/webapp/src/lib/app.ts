import { createContext, useContext } from 'react';
import type { Course, Me, Semester } from './types';

export type Tab = 'home' | 'courses' | 'schedule' | 'grades' | 'profile';
export const TABS: Tab[] = ['home', 'courses', 'schedule', 'grades', 'profile'];

/** Экраны, которые открываются поверх вкладки и закрываются «Назад». */
export type Route = { name: 'course'; course: Course } | { name: 'deadlines' } | { name: 'admin' };

export type ThemePref = 'system' | 'light' | 'dark';

export interface AppState {
  me: Me;
  semesters: Semester[];
  semesterId: number | null;
  setSemesterId: (id: number) => void;
  tab: Tab;
  setTab: (t: Tab) => void;
  push: (r: Route) => void;
  pop: () => void;
  theme: ThemePref;
  setTheme: (p: ThemePref) => void;
  logout: () => Promise<void>;
}

export const AppContext = createContext<AppState>(null as unknown as AppState);
export const useApp = () => useContext(AppContext);

export function semesterName(list: Semester[], id: number | null): string {
  return list.find((s) => s.id === id)?.name ?? '';
}

/** Короткое имя семестра для чипа: «2025-2026 · Первый семестр» без лишних пробелов. */
export function shortSemester(name: string): string {
  return name.replace(/\s+/g, ' ').replace(/\s*(учебный|o'quv)\s*(год|yili)?/gi, '').trim();
}
