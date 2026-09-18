import { initData } from './tg';
import type {
  ActivitiesResponse,
  AttendanceResponse,
  CalendarTab,
  Contract,
  Course,
  DeadlineItem,
  FinalExam,
  Me,
  OneIdResponse,
  ScheduleResponse,
  StudentInfo,
  StudyPlanSubject,
} from './types';

export class ApiError extends Error {
  constructor(public status: number, public code: string) {
    super(code);
  }
}

/** Сессия LMS пропала — приложение возвращается на экран входа. */
export const SESSION_LOST = 'lms:session-lost';

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('X-Telegram-Init-Data', initData);
  if (init.body && typeof init.body === 'string') headers.set('Content-Type', 'application/json');

  let res: Response;
  try {
    res = await fetch(path, { ...init, headers });
  } catch {
    throw new ApiError(0, 'network');
  }
  let body: unknown = null;
  try {
    body = await res.json();
  } catch {
    /* пустой ответ */
  }
  if (!res.ok) {
    const code = (body as { error?: string } | null)?.error ?? 'http_' + res.status;
    if (code === 'not_logged_in') window.dispatchEvent(new Event(SESSION_LOST));
    throw new ApiError(res.status, code);
  }
  return body as T;
}

const get = <T>(path: string, fresh?: boolean) =>
  request<T>(fresh ? path + (path.includes('?') ? '&' : '?') + 'fresh=1' : path);

const post = <T>(path: string, data?: unknown) =>
  request<T>(path, { method: 'POST', body: JSON.stringify(data ?? {}) });

export const api = {
  me: () => get<Me>('/api/me'),

  loginLms: (login: string, password: string) => post<{ ok: boolean }>('/api/auth/lms', { login, password }),
  loginOneId: (login: string, password: string) => post<OneIdResponse>('/api/auth/oneid', { login, password }),
  confirmOneId: (login: string, code: string) => post<OneIdResponse>('/api/auth/oneid/confirm', { login, code }),
  mobileSend: (phone: string) => post<OneIdResponse>('/api/auth/mobile/send', { phone }),
  mobileConfirm: (code: string) => post<OneIdResponse>('/api/auth/mobile/confirm', { code }),
  logout: () => post('/api/auth/logout'),

  courses: (sem: number, fresh?: boolean) => get<Course[]>(`/api/courses?semester=${sem}`, fresh),
  activities: (courseId: number, fresh?: boolean) => get<ActivitiesResponse>(`/api/courses/${courseId}/activities`, fresh),
  attendance: (courseId: number, sem: number, subject: string, fresh?: boolean) =>
    get<AttendanceResponse>(
      `/api/courses/${courseId}/attendance?semester=${sem}&subject=${encodeURIComponent(subject)}`,
      fresh,
    ),
  calendar: (courseId: number, fresh?: boolean) => get<CalendarTab[]>(`/api/courses/${courseId}/calendar`, fresh),
  schedule: (sem: number, fresh?: boolean) => get<ScheduleResponse>(`/api/schedule?semester=${sem}`, fresh),
  deadlines: (sem: number, fresh?: boolean) => get<DeadlineItem[]>(`/api/deadlines?semester=${sem}`, fresh),
  studyPlan: (fresh?: boolean) => get<StudyPlanSubject[]>('/api/study-plan', fresh),
  finals: (sem: number, fresh?: boolean) => get<FinalExam[]>(`/api/finals?semester=${sem}`, fresh),
  profile: (fresh?: boolean) => get<StudentInfo>('/api/profile', fresh),
  photo: () => get<{ dataUrl: string | null }>('/api/profile/photo'),
  contract: (fresh?: boolean) => get<Contract>('/api/contract', fresh),
  changePassword: (old: string, next: string, confirm: string) =>
    post<{ ok: boolean }>('/api/profile/password', { old, new: next, confirm }),
  setLang: (lang: string) => post('/api/settings', { lang }),

  sendFile: (url: string, name: string) => post('/api/files/send', { url, name }),
  fileLink: (url: string, name: string) => post<{ path: string }>('/api/files/link', { url, name }),

  /** Загрузка через XHR — ради прогресса, которого нет у fetch. */
  upload(courseId: number, activityId: string, file: File, onProgress: (p: number) => void): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `/api/upload?course=${courseId}&activity=${encodeURIComponent(activityId)}`);
      xhr.setRequestHeader('X-Telegram-Init-Data', initData);
      xhr.setRequestHeader('X-File-Name', encodeURIComponent(file.name));
      xhr.setRequestHeader('Content-Type', 'application/octet-stream');
      xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total);
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) return resolve();
        let code = 'http_' + xhr.status;
        try {
          code = JSON.parse(xhr.responseText).error ?? code;
        } catch {
          /* ignore */
        }
        reject(new ApiError(xhr.status, code));
      };
      xhr.onerror = () => reject(new ApiError(0, 'network'));
      xhr.send(file);
    });
  },
};
