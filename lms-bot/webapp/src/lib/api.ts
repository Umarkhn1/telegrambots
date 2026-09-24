import { initData } from './tg';
import type {
  ActivitiesResponse,
  AdminStudentsResponse,
  AttendanceResponse,
  CalendarTab,
  Contract,
  Course,
  DeadlineItem,
  FinalExam,
  Me,
  OneIdResponse,
  QrResponse,
  ScheduleResponse,
  StudentInfo,
  StudyPlanSubject,
  AppealPage,
  FormResult,
  GradeForm,
  GradeSheet,
  GradingItem,
  MaterialPage,
  Select2Item,
  TActivities,
  TAppeal,
  TAttendance,
  TCalendar,
  TCourse,
  TeacherInfo,
  TFinal,
  TGroup,
  TStudent,
  TStudentCourse,
  TSubject,
  TTopic,
  TutorSchedule,
  TutorStudent,
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

/** POST с необязательным файлом в теле (имя — в X-File-Name), ответ — JSON. */
async function sendFile<T>(path: string, file: File | null): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/octet-stream' };
  if (file) headers['X-File-Name'] = encodeURIComponent(file.name);
  return request<T>(path, { method: 'POST', body: file ?? undefined, headers });
}

export const api = {
  me: () => get<Me>('/api/me'),

  loginLms: (login: string, password: string) =>
    post<{ ok: boolean; reason?: 'oneid_required' | 'lms_unavailable' | 'wrong_credentials' | null }>('/api/auth/lms', { login, password }),
  loginOneId: (login: string, password: string) => post<OneIdResponse>('/api/auth/oneid', { login, password }),
  confirmOneId: (login: string, code: string) => post<OneIdResponse>('/api/auth/oneid/confirm', { login, code }),
  mobileSend: (phone: string) => post<OneIdResponse>('/api/auth/mobile/send', { phone }),
  mobileConfirm: (code: string) => post<OneIdResponse>('/api/auth/mobile/confirm', { code }),
  qrStart: () => post<QrResponse>('/api/auth/qr/start'),
  qrCheck: (expiresAt: number) => post<QrResponse>('/api/auth/qr/check', { expiresAt }),
  qrCancel: () => post('/api/auth/qr/cancel'),
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

  adminStudents: (params: Record<string, string | number>, fresh?: boolean) =>
    get<AdminStudentsResponse>('/api/admin/students?' + new URLSearchParams(Object.entries(params).map(([k, v]) => [k, String(v)])).toString(), fresh),

  sendFile: (url: string, name: string) => post('/api/files/send', { url, name }),
  fileLink: (url: string, name: string) => post<{ path: string }>('/api/files/link', { url, name }),

  /* ─── Кабинет преподавателя ─── */
  t: {
    courses: (sem: number, fresh?: boolean) => get<TCourse[]>(`/api/t/courses?semester=${sem}`, fresh),
    calendar: (course: number, fresh?: boolean) => get<TCalendar>(`/api/t/courses/${course}/calendar`, fresh),
    attendance: (course: number, lesson: number, fresh?: boolean) => get<TAttendance>(`/api/t/courses/${course}/attendance/${lesson}`, fresh),
    sheet: (course: number, fresh?: boolean) => get<GradeSheet>(`/api/t/courses/${course}/sheet`, fresh),
    activities: (course: number, fresh?: boolean) => get<TActivities>(`/api/t/courses/${course}/activities`, fresh),
    deleteActivity: (course: number, id: number) => post<FormResult>(`/api/t/courses/${course}/activities/${id}/delete`),
    gradeForm: (student: number, activity: number) => get<GradeForm>(`/api/t/grade/${student}/${activity}`),
    setGrade: (student: number, activity: number, body: { values?: Record<string, string>; grade?: string; comment?: string }) =>
      post<FormResult & { form?: GradeForm }>(`/api/t/grade/${student}/${activity}`, body),
    clearGrade: (student: number, activity: number) => post<FormResult>(`/api/t/grade/${student}/${activity}/clear`),
    grading: (fresh?: boolean) => get<GradingItem[]>('/api/t/grading', fresh),
    finals: (sem: number, fresh?: boolean) => get<TFinal[]>(`/api/t/finals?semester=${sem}`, fresh),
    profile: (fresh?: boolean) => get<TeacherInfo>('/api/t/profile', fresh),
    appeals: (fresh?: boolean) => get<{ page: AppealPage; items: TAppeal[] }>('/api/t/appeals', fresh),
    appealLessons: (stream: string) => get<Select2Item[]>(`/api/t/appeals/lessons?stream=${encodeURIComponent(stream)}`),
    appealStudents: (lesson: string) => get<Select2Item[]>(`/api/t/appeals/students?lesson=${encodeURIComponent(lesson)}`),
    createAppeal: (body: { stream: string; lesson: string; pair: string; student: string }) => post<FormResult>('/api/t/appeals', body),
    deleteAppeal: (id: number) => post<FormResult>(`/api/t/appeals/${id}/delete`),
    appealPdf: (id: number) => get<{ url: string }>(`/api/t/appeals/${id}/pdf`),
    subjects: (sem: number, fresh?: boolean) => get<TSubject[]>(`/api/t/materials?semester=${sem}`, fresh),
    materialPage: (subject: number, sem: number, lang: string) => get<MaterialPage>(`/api/t/materials/${subject}?semester=${sem}&lang=${lang}`),
    topics: (subject: number, sem: number, lang: string, type: string, fresh?: boolean) =>
      get<TTopic[]>(`/api/t/materials/${subject}/topics?semester=${sem}&lang=${lang}&type=${encodeURIComponent(type)}`, fresh),
    deleteMaterial: (id: number) => post<FormResult>(`/api/t/materials/resource/${id}/delete`),
    groups: (fresh?: boolean) => get<TGroup[]>('/api/t/tutor/groups', fresh),
    group: (id: number, fresh?: boolean) => get<TStudent[]>(`/api/t/tutor/groups/${id}`, fresh),
    student: (id: number) => get<TutorStudent>(`/api/t/tutor/students/${id}`),
    studentCourses: (id: number, user: number, sem: string, fresh?: boolean) =>
      get<TStudentCourse[]>(`/api/t/tutor/students/${id}/courses?user=${user}&semester=${encodeURIComponent(sem)}`, fresh),
    studentPlan: (id: number, fresh?: boolean) => get<StudyPlanSubject[]>(`/api/t/tutor/students/${id}/plan`, fresh),
    studentSchedule: (id: number, user: number | null, sem: string) =>
      get<TutorSchedule>(`/api/t/tutor/students/${id}/schedule?semester=${encodeURIComponent(sem)}${user ? `&user=${user}` : ''}`),
    /** Новая активность; поля — в адресе, необязательный файл-инструкция — телом запроса. */
    createActivity: (course: number, f: { name: string; deadline: string; max: string; criteria: { name: string; points: string }[] }, file: File | null) =>
      sendFile<FormResult>(
        `/api/t/courses/${course}/activities?` +
          new URLSearchParams({ name: f.name, deadline: f.deadline, max: f.max, criteria: JSON.stringify(f.criteria) }).toString(),
        file,
      ),
    /** Материал к теме: ссылка (url) либо файл. */
    addMaterial: (subject: number, q: { semester: number; lang: string; topic: number; type: string; name?: string; url?: string }, file: File | null) =>
      sendFile<FormResult>(
        `/api/t/materials/${subject}/add?` +
          new URLSearchParams(Object.entries(q).filter(([, v]) => v != null && v !== '').map(([k, v]) => [k, String(v)])).toString(),
        file,
      ),
  },

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
