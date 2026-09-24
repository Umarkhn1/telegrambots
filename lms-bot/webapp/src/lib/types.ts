export type Lang = 'ru' | 'uz_lat' | 'uz_cyr' | 'en';

export interface Semester {
  id: number;
  name: string;
  year: string;
}

export interface Me {
  user: { id: number; firstName: string; lastName: string; username: string; photoUrl: string; languageCode: string };
  lang: Lang | null;
  loggedIn: boolean;
  botUsername: string;
  admin?: boolean;
  /** Роль по меню LMS: у преподавателя свой набор разделов. */
  role?: 'student' | 'teacher';
  /** Преподавателю дана группа — есть режим тьютора. */
  tutor?: boolean;
  semesters?: Semester[];
  currentSemesterId?: number | null;
}

export interface OneIdResponse {
  status: 'OK' | 'NEED_SMS' | 'ERROR';
  message?: string | null;
  reason?: 'link' | 'phone';
  phone?: string;
}

/** Вход по QR-коду OneID: картинку рисует сервер, новый код приходит сам, пока идёт опрос. */
export interface QrResponse {
  status: 'PENDING' | 'OK' | 'ERROR';
  image?: string;
  expiresAt?: number;
  reason?: 'link' | 'unreachable';
}

export interface Course {
  id: number;
  subject: string;
  attendance: number;
  failed: boolean;
  teachers: { name: string; stream: string }[];
}

export interface FileRef {
  url: string;
  name: string;
}

export type ActivityStatus = 'uploaded' | 'graded' | 'open' | 'missed' | 'unknown';

export interface Activity {
  index: number;
  type: string;
  lecture: boolean;
  teacher: string;
  task: string;
  deadline: string;
  deadlineTs: number | null;
  earned: string | null;
  max: string | null;
  criteria: string | null;
  criteriaItems?: { name: string; points: string | null }[];
  sample: FileRef | null;
  uploaded: FileRef | null;
  activityId: string | null;
  canUpload: boolean;
  status: ActivityStatus;
}

export interface DeadlineItem extends Activity {
  courseId: number;
  course: string;
}

export interface ActivitiesResponse {
  earned: string | null;
  maxScore: string | null;
  progress: string | null;
  grade: string | null;
  activities: Activity[];
}

export interface AttendanceResponse {
  missed: number;
  items: { date: string; type: string; lecture: boolean; topic: string; excused: boolean }[];
}

export interface CalendarFile {
  name: string;
  url: string;
  type: 'pdf' | 'ppt' | 'doc' | 'video' | 'url' | 'file';
}

export interface CalendarEntry {
  number: number;
  topic: string;
  date: string;
  files: CalendarFile[];
}

export interface CalendarTab {
  key: string;
  entries: CalendarEntry[];
}

export interface Lesson {
  date: string;
  time: string;
  ts: number;
  subject: string;
  kind: 'lecture' | 'practice' | 'lab';
  room: string;
  teacher: string;
  stream: string | null;
  topic: string | null;
  courseId: number | null;
  last: boolean;
}

export interface ScheduleResponse {
  start?: string;
  end?: string;
  weeks?: number;
  currentWeek?: number;
  lessons: Lesson[];
}

export interface StudyPlanSubject {
  name: string;
  credits: number;
  grade: number | null;
  semester: number;
}

export interface FinalExam {
  subject: string;
  stream: string;
  date: string;
  from: string;
  room: string;
  grade: string;
}

export interface StudentInfo {
  fullName?: string;
  birthDate?: string;
  gender?: string;
  recordBook?: string;
  address?: string;
  direction?: string;
  language?: string;
  degree?: string;
  studyType?: string;
  course?: string;
  group?: string;
  curator?: string;
  scholarship?: string;
}

export interface Contract {
  found: boolean;
  /** Сообщение LMS вместо сумм, например «данные ещё не сформированы». */
  notice?: string | null;
  /** Текущий учебный год, например «2026-2027». */
  year?: string;
  total?: number | null;
  paid?: number | null;
  debt?: number | null;
}

export interface AdminStudent {
  telegramId: number;
  tgName: string | null;
  tgUsername: string | null;
  fullName: string | null;
  recordBook: string | null;
  group: string | null;
  direction: string | null;
  course: string | null;
  gender: string | null;
  birthDate: string | null;
  curator: string | null;
  studyType: string | null;
  language: string | null;
  gpa: number | null;
  role?: 'student' | 'teacher' | null;
  department?: string | null;
  firstSeen: number;
  lastSeen: number;
}

export type AdminFilterKey = 'group' | 'course' | 'direction' | 'gender' | 'studyType' | 'language';

export interface AdminStudentsResponse {
  items: AdminStudent[];
  total: number;
  page: number;
  pages: number;
  size: number;
  stats: { total: number; active24h: number; active7d: number; avgGpa: number | null };
  facets: Record<AdminFilterKey, string[]>;
}

/* ─── Кабинет преподавателя: поля приходят из LMS как есть ─── */

export interface TCourse {
  id: number;
  subject: string;
  type: string;
  students: number;
  rejected: number;
  stream: string | null;
}

export interface TLesson {
  number: number;
  topic: string;
  date: string;
  lessonId: number | null;
  marked: boolean;
  markedAt: string | null;
  moved: boolean;
}

export interface TCalendar {
  stream: string;
  note: string | null;
  lessons: TLesson[];
}

export interface TAttendance {
  topic: string;
  header: string;
  students: { number: number; name: string; faculty: string; direction: string; group: string; present: boolean | null }[];
}

export interface GColumn {
  activityId: number | null;
  name: string;
  studentDeadline: string | null;
  teacherDeadline: string | null;
  max: string;
}

export interface GCell {
  activityId: number | null;
  studentId: number | null;
  grade: string;
  submitted: boolean;
}

export interface GRow {
  number: number;
  name: string;
  group: string;
  studentId: number | null;
  cells: GCell[];
  total: string;
  percent: string;
}

export interface GradeSheet {
  title: string;
  note: string | null;
  columns: GColumn[];
  rows: GRow[];
  /** Подписи окна «Оценивание» из LMS: grade, comment, criterion, points, max, total, clear, save, title. */
  labels: Record<string, string>;
  hint: string | null;
}

export interface GradeForm {
  editable: boolean;
  canClear: boolean;
  grade: string;
  module: boolean;
  name: string;
  student: string;
  maxPoint: string;
  files: { name: string; url: string }[];
  fileText: string | null;
  deadline: string;
  comment: string;
  criteria: { id: string; name: string; value: string; max: string }[];
}

export interface FormResult {
  ok: boolean;
  errors: string[];
}

export interface TActivity {
  id: number;
  name: string;
  deadline: string;
  maxPoint: string;
  status: number;
  hasActivities: boolean;
  module: boolean;
  criteria: string[][];
  sampleUrl: string | null;
  sampleName: string | null;
  comment: string;
}

export interface TActivities {
  items: TActivity[];
  budget: { used: string; total: string; left: string } | null;
  budgetText: string | null;
  note: string | null;
  statusLabels: Record<string, string>;
  labels: Record<string, string>;
  fileHints: string[];
}

export interface GradingItem {
  courseId: number;
  subject: string;
  activity: string;
  stream: string;
  column: number;
  activityId: number | null;
  studentDeadline: string;
  teacherDeadline: string | null;
  studentTs: number;
  teacherTs: number | null;
  submitted: number;
  graded: number;
  pending: number;
}

export interface TFinal {
  id: number;
  subjects: string;
  streams: string;
  date: string;
  from: string;
  room: string;
  hasStreams: boolean;
}

export interface TeacherInfo {
  fullName: string | null;
  fields: string[][];
}

export interface Option {
  id: string;
  text: string;
}

export interface AppealPage {
  streams: Option[];
  instructions: string | null;
  labels: Record<string, string>;
  title: string | null;
}

export interface TAppeal {
  id: number;
  stream: string;
  date: string;
  pair: string;
  theme: string;
  students: string;
  statusText: string;
  status: number;
}

export interface Select2Item {
  id: string;
  text: string;
  present: boolean | null;
}

export interface TSubject {
  id: number;
  code: string;
  subject: string;
  language: string;
  department: string;
}

export interface MaterialPage {
  title: string | null;
  lessonTypes: Option[];
  contentTypes: Option[];
  labels: Record<string, string>;
}

export interface TResource {
  id: number;
  type: string;
  name: string;
  url: string;
  pastDate: boolean;
}

export interface TTopic {
  id: number;
  number: string;
  name: string;
  resources: TResource[];
}

export interface TGroup {
  id: number;
  name: string;
  speciality: string;
}

export interface TStudent {
  id: number;
  fio: string;
  attendance: number;
}

export interface TutorStudent {
  studentId: number;
  userId: number | null;
  title: string;
  semesters: Option[];
  currentSemester: string | null;
}

export interface TStudentCourse {
  id: number;
  subjectId: number | null;
  subject: string;
  teachers: string[][];
  attendance: number;
  failed: boolean;
}

export interface ScheduleEvent {
  title: string;
  start: string;
  type: number;
}

export interface TutorSchedule {
  events: ScheduleEvent[];
  meta: { oddWeek: string | null; evenWeek: string | null; lessonMinutes: number };
}
