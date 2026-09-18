export type Lang = 'ru' | 'uz_lat' | 'uz_cyr';

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
  semesters?: Semester[];
  currentSemesterId?: number | null;
}

export interface OneIdResponse {
  status: 'OK' | 'NEED_SMS' | 'ERROR';
  message?: string | null;
  reason?: 'link' | 'phone';
  phone?: string;
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
  total?: number | null;
  paid?: number | null;
  debt?: number | null;
}
