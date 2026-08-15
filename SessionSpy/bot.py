# -*- coding: utf-8 -*-
"""Телеграм-бот уведомлений о киносеансах — cinematica.uz + gtickets.uz.

Путь пользователя: раздел (сегодня / скоро) → фильм → площадка →
сеансы → подписка на дату, которой ещё нет в расписании.

Автобронь работает только в CINEMATICA: gtickets схему зала наружу не
отдаёт, выбрать конкретные места там нечем.
"""

import hashlib
import html
import json
import os
import random
import re
import sys
import threading
import time
import traceback
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta

import requests

import catalog as cat
import cinematica as cm
import gtickets as gt

HERE = os.path.dirname(os.path.abspath(__file__))


# ------------------------------------------------------------------ настройки

def load_env():
    path = os.path.join(HERE, ".env")
    if not os.path.exists(path):
        return
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip())


load_env()

TOKEN = os.environ.get("BOT_TOKEN", "").strip()
OWNER_ID = int(os.environ.get("OWNER_ID", "0") or 0)
POLL_SECONDS = int(os.environ.get("POLL_SECONDS", "90"))
REMIND_SECONDS = int(os.environ.get("REMIND_SECONDS", "3600"))
MAX_REMINDERS = int(os.environ.get("MAX_REMINDERS", "12"))
HOLD_SECONDS = int(os.environ.get("HOLD_SECONDS", "600"))
DAYS_AHEAD = int(os.environ.get("DAYS_AHEAD", "21"))
BOOK_PHONE = os.environ.get("BOOK_PHONE", "").strip()
BOOK_EMAIL = os.environ.get("BOOK_EMAIL", "").strip()
CM_LOGIN = os.environ.get("CM_LOGIN", "").strip()
CM_PASSWORD = os.environ.get("CM_PASSWORD", "").strip()
# На Render файловая система стирается при каждом деплое, поэтому память
# бота выносится на подключённый диск: STATE_DIR=/var/data
STATE_DIR = os.environ.get("STATE_DIR", "").strip() or HERE
if not os.path.isdir(STATE_DIR):
    os.makedirs(STATE_DIR, exist_ok=True)
STATE_PATH = os.path.join(STATE_DIR, "state.json")
MEDIA = os.path.join(HERE, "media")

API = "https://api.telegram.org/bot%s/" % TOKEN

WEEKDAYS = ["понедельник", "вторник", "среда", "четверг", "пятница",
            "суббота", "воскресенье"]
WD_SHORT = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"]
MONTHS = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля",
          "августа", "сентября", "октября", "ноября", "декабря"]
DOTS = ["🔴", "🟠", "🟡", "🟢", "🔵", "🟣", "🟤", "⚫", "⚪"]


def _movie_videos():
    """MOVIE_VIDEOS=948:imax.mp4 -> {948: 'imax.mp4'} (id фильма cinematica)"""
    out = {}
    for part in (os.environ.get("MOVIE_VIDEOS", "") or "").split(","):
        part = part.strip()
        if ":" in part:
            mid, fn = part.split(":", 1)
            try:
                out[int(mid)] = fn.strip()
            except ValueError:
                pass
    return out


MOVIE_VIDEOS = _movie_videos()

VIDEO_CAPTION = (
    "<b>Почему именно IMAX</b>\n\n"
    "<blockquote>Фильм снимали под IMAX: на обычном экране кадр обрезан "
    "сверху и снизу, на IMAX он раскрывается целиком — видно заметно "
    "больше картинки. Разницу видно на видео.</blockquote>"
)


def _seat_anchors():
    """'imax:7,8,6@13.5; *:4,5@5' -> [('imax', [7,8,6], 13.5), ...]"""
    out = []
    for part in (os.environ.get("SEAT_ANCHORS", "") or "").split(";"):
        part = part.strip()
        if ":" not in part or "@" not in part:
            continue
        name, rest = part.split(":", 1)
        rows, center = rest.rsplit("@", 1)
        try:
            out.append((name.strip().lower(),
                        [int(r) for r in rows.split(",") if r.strip()],
                        float(center)))
        except ValueError:
            continue
    return out


SEAT_ANCHORS = _seat_anchors()


def anchor_for(hall_name, src="cm"):
    """Любимые ряды зала. Запасное правило «*» — только для CINEMATICA:
    у gtickets свои залы, там середина считается по геометрии."""
    hall = (hall_name or "").lower()
    fallback = None
    for name, rows, center in SEAT_ANCHORS:
        if name == "*":
            fallback = (rows, center)
        elif name in hall:
            return rows, center
    if src == "cm" and fallback:
        return fallback
    return None, None


INVITE = [
    "Друзья, сеансы уже открыты — забронируйте, пожалуйста, места, пока есть выбор.",
    "Коллеги, места в продаже. Успейте забронировать, хорошие ряды разбирают быстро.",
    "Ребята, билеты доступны — займите места заранее, пожалуйста.",
    "Появились сеансы. Пожалуйста, определитесь со временем и бронируйте.",
    "Билеты открыты. Кто идёт — бронируйте, будем рады всех видеть.",
    "Прошу внимания: сеансы в продаже. Забронируйте места, пожалуйста.",
]

REMIND = [
    "Небольшое напоминание: места ещё можно занять.",
    "Напоминаю про сеансы — свободные места пока есть.",
    "Тихо напоминаю: бронь всё ещё открыта.",
    "Ещё раз напомню про билеты, чтобы не забылось.",
]


if sys.stdout is None or sys.stderr is None:
    # запуск через pythonw: консоли нет, print() падал бы и рвал поток
    _logfile = open(os.path.join(STATE_DIR, "bot.log"), "a", encoding="utf-8",
                    buffering=1)
    sys.stdout = sys.stderr = _logfile


def log(*a):
    print(datetime.now().strftime("[%H:%M:%S]"), *a, flush=True)


# ----------------------------------------------------------------- состояние

_lock = threading.RLock()
STATE = {"chats": {}, "subs": {}, "next_id": 1, "offset": 0, "videos": {}}
POOL = ThreadPoolExecutor(max_workers=6)


def load_state():
    if os.path.exists(STATE_PATH):
        try:
            with open(STATE_PATH, "r", encoding="utf-8") as f:
                STATE.update(json.load(f))
        except Exception as e:
            log("не смог прочитать state.json:", e)


def save_state():
    with _lock:
        tmp = STATE_PATH + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(STATE, f, ensure_ascii=False, indent=1)
        os.replace(tmp, STATE_PATH)


# ------------------------------------------------------------------ telegram

_tg_local = threading.local()


def _tg_session():
    s = getattr(_tg_local, "s", None)
    if s is None:
        s = requests.Session()
        s.mount("https://", requests.adapters.HTTPAdapter(
            pool_connections=4, pool_maxsize=8))
        _tg_local.s = s
    return s


def tg(method, **params):
    """Запрос к Telegram с повтором: соединение до api.telegram.org
    нередко не встаёт с первого раза, и без повтора кнопка молчит."""
    body = json.dumps(params, ensure_ascii=False).encode("utf-8")
    read = 70 if method == "getUpdates" else 25
    tries = 1 if method == "getUpdates" else 3
    for i in range(tries):
        try:
            r = _tg_session().post(
                API + method, data=body,
                headers={"Content-Type": "application/json"}, timeout=(6, read))
            out = json.loads(r.content.decode("utf-8"))
            if not out.get("ok"):
                log("telegram %s -> %s" % (method, str(out)[:200]))
            return out
        except Exception as e:
            _tg_local.s = None
            if i == tries - 1:
                log("telegram %s -> %s" % (method, e))
                return {"ok": False}
            time.sleep(0.3)


def send(chat_id, text, keyboard=None, preview=False):
    p = {"chat_id": chat_id, "text": text, "parse_mode": "HTML",
         "link_preview_options": {"is_disabled": not preview}}
    if keyboard:
        p["reply_markup"] = {"inline_keyboard": keyboard}
    r = tg("sendMessage", **p)
    return (r.get("result") or {}).get("message_id") if r.get("ok") else None


def send_photo(chat_id, photo, caption, keyboard=None):
    p = {"chat_id": chat_id, "photo": photo, "caption": caption[:1024],
         "parse_mode": "HTML"}
    if keyboard:
        p["reply_markup"] = {"inline_keyboard": keyboard}
    r = tg("sendPhoto", **p)
    return (r.get("result") or {}).get("message_id") if r.get("ok") else None


def send_video(chat_id, filename, caption):
    """Ролик из папки media: первый раз файлом, дальше по file_id."""
    cached = (STATE.get("videos") or {}).get(filename)
    if cached:
        r = tg("sendVideo", chat_id=chat_id, video=cached, caption=caption,
               parse_mode="HTML", supports_streaming=True)
        if r.get("ok"):
            return True

    path = os.path.join(MEDIA, filename)
    if not os.path.exists(path):
        log("нет файла", path)
        return False
    with open(path, "rb") as f:
        blob = f.read()

    boundary = "----cinematica%d" % random.getrandbits(48)
    parts = []
    for key, val in (("chat_id", str(chat_id)), ("caption", caption),
                     ("parse_mode", "HTML"), ("supports_streaming", "true")):
        parts.append(('--%s\r\nContent-Disposition: form-data; name="%s"\r\n\r\n%s\r\n'
                      % (boundary, key, val)).encode("utf-8"))
    parts.append(('--%s\r\nContent-Disposition: form-data; name="video"; '
                  'filename="%s"\r\nContent-Type: video/mp4\r\n\r\n'
                  % (boundary, filename)).encode("utf-8"))
    parts.append(blob)
    parts.append(("\r\n--%s--\r\n" % boundary).encode("utf-8"))

    try:
        r = _tg_session().post(
            API + "sendVideo", data=b"".join(parts),
            headers={"Content-Type": "multipart/form-data; boundary=%s" % boundary},
            timeout=(6, 180))
        res = json.loads(r.content.decode("utf-8"))
    except Exception as e:
        log("sendVideo:", e)
        return False
    if not res.get("ok"):
        return False
    vid = ((res.get("result") or {}).get("video") or {}).get("file_id")
    if vid:
        with _lock:
            STATE.setdefault("videos", {})[filename] = vid
        save_state()
    return True


def edit(chat_id, message_id, text, keyboard=None):
    p = {"chat_id": chat_id, "message_id": message_id, "text": text,
         "parse_mode": "HTML", "link_preview_options": {"is_disabled": True}}
    if keyboard is not None:
        p["reply_markup"] = {"inline_keyboard": keyboard}
    return tg("editMessageText", **p)


def answer(callback_id, text=None):
    p = {"callback_query_id": callback_id}
    if text:
        p["text"] = text
    tg("answerCallbackQuery", **p)


def esc(s):
    return html.escape(str(s if s is not None else ""))


def register_commands():
    cmds = [
        {"command": "start", "description": "показать меню"},
        {"command": "new", "description": "новая напоминалка"},
        {"command": "list", "description": "мои напоминалки"},
        {"command": "test", "description": "показать пример уведомления"},
        {"command": "help", "description": "как это работает"},
    ]
    for scope in ({"type": "default"}, {"type": "all_private_chats"},
                  {"type": "all_group_chats"}, {"type": "all_chat_administrators"}):
        tg("setMyCommands", commands=cmds, scope=scope)


# ------------------------------------------------------------- участники чата

def remember_chat(chat):
    cid = str(chat["id"])
    with _lock:
        c = STATE["chats"].setdefault(cid, {"members": {}})
        c["title"] = chat.get("title") or chat.get("username") or "личный чат"
        c["type"] = chat.get("type")
    return c


def remember_user(chat_id, user):
    if not user or user.get("is_bot"):
        return
    with _lock:
        c = STATE["chats"].setdefault(str(chat_id), {"members": {}})
        c.setdefault("members", {})[str(user["id"])] = {
            "name": (user.get("first_name") or "друг").strip(),
            "username": user.get("username"),
        }


def pull_admins(chat_id):
    r = tg("getChatAdministrators", chat_id=chat_id)
    for m in (r.get("result") or []):
        remember_user(chat_id, m.get("user"))


def mentions(chat_id):
    c = STATE["chats"].get(str(chat_id)) or {}
    if c.get("type") == "private":
        return ""
    out = []
    for uid, u in (c.get("members") or {}).items():
        if u.get("username"):
            out.append("@" + u["username"])
        else:
            out.append('<a href="tg://user?id=%s">%s</a>'
                       % (uid, esc(u.get("name") or "друг")))
    return " ".join(out)


def is_owner(user_id):
    return bool(OWNER_ID) and int(user_id or 0) == OWNER_ID


# ------------------------------------------------------------------- кэш

_cache = {}
_cache_lock = threading.RLock()


def cached(key, ttl, fn):
    now = time.time()
    with _cache_lock:
        hit = _cache.get(key)
    if hit and now - hit[0] < ttl:
        return hit[1]
    val = fn()
    with _cache_lock:
        _cache[key] = (now, val)
    return val


def movies(kind):
    return cached("movies:%s" % kind, 300, lambda: cat.movies(kind))


def sessions(movie, src=None, ttl=60):
    key = "ss:%s:%s" % (movie["key"], src or "all")
    return cached(key, ttl, lambda: cat.sessions(movie, src))


def drop_sessions(movie, src=None):
    with _cache_lock:
        _cache.pop("ss:%s:%s" % (movie["key"], src or "all"), None)


def free_seats(s):
    return cached("free:%s" % s["sid"], 45, lambda: cat.free_seats(s))


def free_map(ss, limit=45):
    ss = ss[:limit]
    if not ss:
        return {}
    try:
        with ThreadPoolExecutor(max_workers=10) as ex:
            got = list(ex.map(free_seats, ss))
    except Exception:
        got = [free_seats(s) for s in ss]
    return {s["sid"]: n for s, n in zip(ss, got)}


# -------------------------------------------------------------- формат

def d_key(iso):
    try:
        return datetime.strptime(iso, "%Y-%m-%d")
    except Exception:
        return datetime.max


def d_long(iso):
    """'2026-08-13' -> 'четверг, 13 августа'"""
    try:
        dt = datetime.strptime(iso, "%Y-%m-%d")
        return "%s, %d %s" % (WEEKDAYS[dt.weekday()], dt.day, MONTHS[dt.month - 1])
    except Exception:
        return iso


def d_short(iso):
    try:
        dt = datetime.strptime(iso, "%Y-%m-%d")
        return "%02d.%02d %s" % (dt.day, dt.month, WD_SHORT[dt.weekday()])
    except Exception:
        return iso


def card(title, venue, ss, with_free=True):
    """Сеансы: каждая дата — своя цитата, каждый сеанс со своей меткой."""
    ss = sorted(ss, key=lambda x: (d_key(x["date"]), x["time"]))
    free = free_map(ss) if with_free else {}

    out = ["🎬 <b>%s</b>" % esc(title)]
    if venue:
        out.append("📍 %s" % esc(venue))

    by_date = {}
    for s in ss:
        by_date.setdefault(s["date"], []).append(s)

    i = 0
    for date in sorted(by_date, key=d_key):
        rows = []
        for s in by_date[date]:
            dot = DOTS[i % len(DOTS)]
            i += 1
            bits = [s["time"]]
            if s.get("hall"):
                bits.append(esc(s["hall"]))
            if s.get("price"):
                bits.append(esc(s["price"]))
            n = free.get(s["sid"])
            if n is not None:
                bits.append("свободно %d" % n)
            rows.append("%s <b>%s</b> · %s" % (dot, bits[0], " · ".join(bits[1:])))
        out.append("\n<b>%s</b>\n<blockquote>%s</blockquote>"
                   % (esc(d_long(date)), "\n".join(rows)))

    text = "\n".join(out)
    return text if len(text) <= 4000 else text[:3900] + "\n…"


# --------------------------------------------------------------- подписки

def mid_hash(key):
    return hashlib.md5(key.encode("utf-8")).hexdigest()[:8]


def find_movie(kind, h):
    for k in (kind, "s" if kind == "t" else "t"):
        for m in movies(k):
            if mid_hash(m["key"]) == h:
                return m
    return None


def new_sub(chat_id, movie, kind, venue, date=None, hall_id=None, hall=None,
            time_=None, seats=None, owner=None):
    with _lock:
        sid = str(STATE["next_id"])
        STATE["next_id"] += 1
        STATE["subs"][sid] = {
            "id": sid,
            "chat_id": chat_id,
            "owner": owner,
            "kind": kind,
            "key": movie["key"],
            "title": movie["title"],
            "poster": movie.get("poster"),
            "srcs": movie["src"],
            "src": venue["src"],
            "cinema_id": venue["cinema_id"],
            "cinema": venue["title"],
            "date": date,
            "hall_id": hall_id,
            "hall": hall,
            "time": time_,
            "seats": seats,
            "seen": [],
            "reminders": 0,
            "last_remind": 0,
            "hold": None,
            "created": time.time(),
        }
    save_state()
    return STATE["subs"][sid]


def sub_movie(sub):
    return {"key": sub["key"], "title": sub["title"],
            "poster": sub.get("poster"), "src": sub["srcs"]}


def sub_matches(sub, s):
    if s["cinema_id"] != sub["cinema_id"]:
        return False
    if sub.get("date") and s["date"] != sub["date"]:
        return False
    if sub.get("hall_id") and str(s["hall_id"]) != str(sub["hall_id"]):
        return False
    if sub.get("time") and s["time"] != sub["time"]:
        return False
    return True


def sub_title(sub):
    bits = [sub["title"], sub["cinema"]]
    bits.append(d_short(sub["date"]) if sub.get("date") else "любая новая дата")
    bits.append(sub.get("hall") or "все залы")
    bits.append(sub.get("time") or "все сеансы")
    if sub.get("seats"):
        bits.append("автобронь на %d" % sub["seats"])
    return " · ".join(bits)


def booking_ready(sub):
    """Автобронь доступна только владельцу — чужая бронь ломает сеанс другим."""
    return bool(sub.get("seats") and sub.get("date") and sub.get("hall_id")
                and sub.get("time") and is_owner(sub.get("owner")))


# ------------------------------------------------------------------- мастер
#
# Весь выбор зашит в саму кнопку, в памяти процесса ничего не хранится —
# поэтому кнопки работают и после перезапуска бота.
#
#   cat|t                                  раздел
#   v|t|<хэш>                              площадки фильма
#   s|t|<хэш>|<площадка>                   сеансы
#   cfg|t|<хэш>|<площадка>                 начать настройку уведомления
#   d|t|<хэш>|<площадка>|<дата|any>
#   h|...|<зал|all>
#   tm|...|<время|all>
#   n|...|<мест>

SEP = "|"


def render(chat_id, message_id, is_photo, text, kb, photo=None):
    """Перерисовывает экран мастера в том же сообщении.

    Текст правится на месте. Когда экран меняет вид — с текста на постер
    или обратно, — Telegram править не даёт, поэтому сообщение заменяется.
    """
    want_photo = bool(photo)
    if want_photo == is_photo:
        if want_photo:
            tg("editMessageCaption", chat_id=chat_id, message_id=message_id,
               caption=text[:1024], parse_mode="HTML",
               reply_markup={"inline_keyboard": kb})
        else:
            edit(chat_id, message_id, text, kb)
        return
    tg("deleteMessage", chat_id=chat_id, message_id=message_id)
    if want_photo and send_photo(chat_id, photo, text, kb):
        return
    send(chat_id, text, kb)


def kb_categories():
    return [[{"text": "🎬 Сегодня в кино", "callback_data": "cat|t"}],
            [{"text": "🗓 Скоро в кино", "callback_data": "cat|s"}]]


def show_start(chat_id, message_id=None, is_photo=False, with_menu=False):
    text = ("🎟 <b>Афиша и уведомления о сеансах</b>\n\n"
            "<blockquote>Смотрю за CINEMATICA и кинотеатрами gtickets: "
            "Riviera, Premier, Parus, Next, Magic, Compass, CinemaPlex, "
            "Sergeli.\n\n"
            "<b>Сегодня в кино</b> — что идёт прямо сейчас.\n"
            "<b>Скоро в кино</b> — фильмы, у которых сеансов ещё нет."
            "</blockquote>\n\nВыберите раздел:")
    if message_id:
        render(chat_id, message_id, is_photo, text, kb_categories())
    else:
        send(chat_id, text, kb_categories())


def show_hello(chat_id):
    """Приветствие вместе с меню — одним сообщением, без пояснений."""
    tg("sendMessage", chat_id=chat_id, parse_mode="HTML", reply_markup=MENU,
       text="🎟 <b>Афиша и напоминалки о сеансах</b>\n\n"
            "<blockquote>CINEMATICA, Riviera, Premier, Parus, Next, Magic, "
            "Compass, CinemaPlex, Sergeli.</blockquote>\n\n"
            "Жмите <b>«🎬 Новая напоминалка»</b>.")


def short(s, n=40):
    s = re.sub(r"\s+", " ", s or "").strip()
    return s if len(s) <= n else s[:n - 1] + "…"


def video_button(sub_or_movie):
    """Кнопка «Почему IMAX» — если к фильму привязан ролик."""
    srcs = sub_or_movie.get("srcs") or sub_or_movie.get("src") or {}
    cm_id = srcs.get("cm") if isinstance(srcs, dict) else None
    if cm_id and int(cm_id) in MOVIE_VIDEOS:
        return [{"text": "🎥 Почему IMAX", "callback_data": "vid|%s" % cm_id}]
    return None


def pager(page, pages, cb):
    """Строка листалки: ‹ 2/5 ›. cb(page) собирает callback_data."""
    if pages <= 1:
        return None
    row = []
    if page > 0:
        row.append({"text": "‹", "callback_data": cb(page - 1)})
    row.append({"text": "%d/%d" % (page + 1, pages), "callback_data": "noop"})
    if page < pages - 1:
        row.append({"text": "›", "callback_data": cb(page + 1)})
    return row


MOVIES_PER_PAGE = 8
DATES_PER_PAGE = 12


def show_movies(chat_id, message_id, is_photo, kind, page=0):
    lst = movies(kind)
    if not lst:
        render(chat_id, message_id, is_photo,
             "В этом разделе сейчас пусто." if kind == "s"
             else "Не удалось получить афишу, попробуйте ещё раз.",
             kb_categories())
        return
    pages = max(1, (len(lst) + MOVIES_PER_PAGE - 1) // MOVIES_PER_PAGE)
    page = max(0, min(page, pages - 1))
    chunk = lst[page * MOVIES_PER_PAGE:(page + 1) * MOVIES_PER_PAGE]

    kb = [[{"text": short(m["title"]),
            "callback_data": SEP.join(["v", kind, mid_hash(m["key"])])}]
          for m in chunk]
    nav = pager(page, pages, lambda p: SEP.join(["cat", kind, str(p)]))
    if nav:
        kb.append(nav)
    kb.append([{"text": "‹ назад", "callback_data": "home"}])
    head = "Сегодня в кино" if kind == "t" else "Скоро в кино"
    render(chat_id, message_id, is_photo,
         "<b>%s</b> · %d фильмов\n\nВыберите фильм:" % (head, len(lst)), kb)


def movie_info(m, limit=1000):
    """Подпись к постеру: название, факты, режиссёр, актёры, описание."""
    i = m.get("info") or {}
    head = "🎬 <b>%s</b>" % esc(m["title"])
    facts = []
    for key, label in (("year", ""), ("genre", ""), ("age", ""),
                       ("duration", ""), ("format", "")):
        v = i.get(key)
        if v:
            facts.append(esc(str(v).strip(" ,")))
    line2 = " · ".join(facts)
    body = []
    if i.get("director"):
        body.append("Режиссёр: %s" % esc(i["director"]))
    if i.get("cast"):
        body.append("В ролях: %s" % esc(short(i["cast"], 120)))
    if i.get("rating"):
        body.append("Рейтинг: %s" % esc(i["rating"]))
    desc = re.sub(r"\s+", " ", i.get("desc") or "").strip()
    if desc:
        body.append("\n" + esc(short(desc, 300)))

    text = head + (("\n<i>%s</i>" % line2) if line2 else "")
    if body:
        text += "\n\n<blockquote>%s</blockquote>" % "\n".join(body)
    return text[:limit]


def show_venues(chat_id, message_id, is_photo, kind, m, ss):
    """Постер с описанием и список кинотеатров — одним сообщением."""
    vs = cat.venues(ss)
    if not vs:
        kb = [[{"text": "🔔 Уведомить, когда появятся сеансы",
                "callback_data": SEP.join(["n", kind, mid_hash(m["key"]),
                                           "any", "any", "all", "all", "0"])}],
              [{"text": "‹ назад", "callback_data": "cat" + SEP + kind}]]
        render(chat_id, message_id, is_photo,
               movie_info(m, 700) + "\n\nСеансов пока нет ни в одном кинотеатре.",
               kb, m.get("poster"))
        return
    kb = []
    for v in vs:
        n = sum(1 for s in ss if s["cinema_id"] == v["cinema_id"])
        kb.append([{"text": "%s%s · %d сеансов"
                            % ("⭐ " if v["src"] == "cm" else "", short(v["title"], 28), n),
                    "callback_data": SEP.join(["s", kind, mid_hash(m["key"]),
                                               v["cinema_id"]])}])
    vb = video_button(m)
    if vb:
        kb.append(vb)
    kb.append([{"text": "‹ назад", "callback_data": "cat" + SEP + kind}])
    render(chat_id, message_id, is_photo,
           movie_info(m, 700) + "\n\nВыберите кинотеатр:", kb, m.get("poster"))


def show_sessions(chat_id, message_id, is_photo, kind, m, ss, cinema_id):
    mine = [s for s in ss if s["cinema_id"] == cinema_id]
    venue = mine[0]["cinema"] if mine else cinema_id
    h = mid_hash(m["key"])
    kb = []
    vb = video_button(m)
    if vb:
        kb.append(vb)
    kb.append([{"text": "🔔 Уведомить о новых сеансах",
                "callback_data": SEP.join(["cfg", kind, h, cinema_id])}])
    kb.append([{"text": "‹ назад", "callback_data": SEP.join(["v", kind, h])}])
    render(chat_id, message_id, is_photo, card(m["title"], venue, mine), kb)


def free_dates(ss, cinema_id):
    """Даты, на которые сеансов ещё нет — их и есть смысл ждать."""
    busy = {s["date"] for s in ss if s["cinema_id"] == cinema_id}
    today = datetime.now().date()
    out = []
    for i in range(1, DAYS_AHEAD + 1):
        iso = (today + timedelta(days=i)).strftime("%Y-%m-%d")
        if iso not in busy:
            out.append(iso)
    return out


def show_dates(chat_id, message_id, is_photo, kind, m, ss, cinema_id, page=0):
    h = mid_hash(m["key"])
    base = SEP.join(["d", kind, h, cinema_id])
    days = free_dates(ss, cinema_id)
    pages = max(1, (len(days) + DATES_PER_PAGE - 1) // DATES_PER_PAGE)
    page = max(0, min(page, pages - 1))

    kb = [[{"text": "📣 Любые новые сеансы и изменения",
            "callback_data": base + SEP + "any"}]]
    row = []
    for iso in days[page * DATES_PER_PAGE:(page + 1) * DATES_PER_PAGE]:
        row.append({"text": d_short(iso), "callback_data": base + SEP + iso})
        if len(row) == 3:
            kb.append(row)
            row = []
    if row:
        kb.append(row)
    nav = pager(page, pages, lambda p: SEP.join(["cfg", kind, h, cinema_id, str(p)]))
    if nav:
        kb.append(nav)
    kb.append([{"text": "‹ назад", "callback_data": SEP.join(["s", kind, h, cinema_id])}])
    render(chat_id, message_id, is_photo,
         "🔔 <b>%s</b>\n\n<blockquote><b>Любые новые сеансы и изменения</b> — "
         "напишу про всё, что появится в этом кинотеатре: и новый день "
         "в расписании, и новый сеанс на дне, который уже открыт.\n\n"
         "<b>Конкретная дата</b> — только про неё. В списке дни, которых "
         "в расписании ещё нет: то, что уже в продаже, вы видели "
         "экраном раньше.</blockquote>\n\nЧего ждём?" % esc(m["title"]), kb)


def halls_of(ss, cinema_id):
    out = {}
    for s in ss:
        if s["cinema_id"] == cinema_id and s.get("hall"):
            out[str(s["hall_id"])] = s["hall"]
    return out


def show_halls(chat_id, message_id, is_photo, kind, m, ss, cinema_id, date):
    h = mid_hash(m["key"])
    base = SEP.join(["h", kind, h, cinema_id, date])
    kb = [[{"text": "Любой зал", "callback_data": base + SEP + "all"}]]
    for hid, name in sorted(halls_of(ss, cinema_id).items(), key=lambda kv: kv[1]):
        kb.append([{"text": short(name, 30), "callback_data": base + SEP + hid}])
    vb = video_button(m)
    if vb:
        kb.append(vb)
    kb.append([{"text": "‹ назад", "callback_data": SEP.join(["cfg", kind, h, cinema_id])}])
    render(chat_id, message_id, is_photo,
         "🔔 <b>%s</b> · %s\n\n<blockquote>Залы взяты из тех сеансов, что "
         "этот кинотеатр ставил раньше. Зал — по желанию.</blockquote>"
         % (esc(m["title"]), esc("любая дата" if date == "any" else d_long(date))), kb)


def show_times(chat_id, message_id, is_photo, kind, m, ss, cinema_id, date, hall):
    h = mid_hash(m["key"])
    base = SEP.join(["tm", kind, h, cinema_id, date, hall])
    times = sorted({s["time"] for s in ss if s["cinema_id"] == cinema_id
                    and (hall == "all" or str(s["hall_id"]) == hall)})
    kb = [[{"text": "Любое время", "callback_data": base + SEP + "all"}]]
    row = []
    for t in times:
        row.append({"text": t, "callback_data": base + SEP + t})
        if len(row) == 4:
            kb.append(row)
            row = []
    if row:
        kb.append(row)
    kb.append([{"text": "‹ назад",
                "callback_data": SEP.join(["d", kind, h, cinema_id, date])}])
    render(chat_id, message_id, is_photo,
         "🔔 <b>%s</b> · %s · %s\n\n<blockquote>Время тоже по желанию: "
         "не выберете — пришлю все сеансы этой даты.</blockquote>"
         % (esc(m["title"]),
            esc("любая дата" if date == "any" else d_long(date)),
            esc(halls_of(ss, cinema_id).get(hall, "любой зал"))), kb)


def show_seats(chat_id, message_id, is_photo, kind, m, ss, cinema_id, date, hall, tm):
    h = mid_hash(m["key"])
    base = SEP.join(["n", kind, h, cinema_id, date, hall, tm])
    kb = [[{"text": "%d" % n, "callback_data": base + SEP + str(n)} for n in (1, 2, 3)],
          [{"text": "%d" % n, "callback_data": base + SEP + str(n)} for n in (4, 5, 6)],
          [{"text": "Без автоброни", "callback_data": base + SEP + "0"}],
          [{"text": "‹ назад",
            "callback_data": SEP.join(["h", kind, h, cinema_id, date, hall])}]]
    warn = ("" if BOOK_PHONE else
            "\n\n⚠️ Сейчас автобронь не заработает: в <code>.env</code> пустой "
            "<code>BOOK_PHONE</code>.")
    render(chat_id, message_id, is_photo,
         "🎟 <b>%s</b> · %s · %s · %s\n\n"
         "<blockquote>Автобронь: как только сеанс появится, бот займёт места "
         "в середине зала и пришлёт ссылку на оплату. Ссылка живёт 10 минут — "
         "если никто не оплатит, бот забронирует заново.</blockquote>\n"
         "Сколько мест бронировать?%s"
         % (esc(m["title"]),
            esc(d_long(date)), esc(halls_of(ss, cinema_id).get(hall, "зал")),
            esc(tm), warn), kb)


def finish(chat_id, message_id, is_photo, user_id, kind, m, ss, cinema_id,
           date="any", hall="all", tm="all", seats=0):
    vs = cat.venues(ss)
    venue = next((v for v in vs if v["cinema_id"] == cinema_id), None)
    if not venue:
        venue = {"src": "cm" if cinema_id == "cm" else "gt",
                 "cinema_id": cinema_id, "title": cat.CM_LABEL
                 if cinema_id == "cm" else "кинотеатр"}

    sub = new_sub(chat_id, m, kind, venue,
                  date=None if date == "any" else date,
                  hall_id=None if hall == "all" else hall,
                  hall=None if hall == "all" else halls_of(ss, cinema_id).get(hall),
                  time_=None if tm == "all" else tm,
                  seats=seats if (seats and is_owner(user_id)) else None,
                  owner=user_id)

    matched = [s for s in ss if sub_matches(sub, s)]
    with _lock:
        sub["seen"] = [s["sid"] for s in matched]
    save_state()

    text = ("✅ <b>Напоминалка включена</b>\n\n<blockquote>%s</blockquote>\n\n"
            % esc(sub_title(sub)))
    if not sub.get("date"):
        text += ("Слежу за этим кинотеатром целиком: напишу и про новый день "
                 "в расписании, и про новый сеанс на дне, который уже открыт.")
    elif matched:
        text += "Сеансы на эту дату уже есть — сообщу, если добавят ещё."
    else:
        text += "Сеансов на эту дату пока нет — сообщу, как только появятся."
    kb = []
    vb = video_button(sub)
    if vb:
        kb.append(vb)
    kb.append([{"text": "Отключить", "callback_data": "off%s%s" % (SEP, sub["id"])}])
    render(chat_id, message_id, is_photo, text, kb)

    if sub.get("seats") and not BOOK_PHONE:
        send(chat_id, "⚠️ Автобронь не включится: в <code>.env</code> пустой "
                      "<code>BOOK_PHONE</code>.")
    elif booking_ready(sub) and matched:
        POOL.submit(do_booking, sub)


def on_callback(cb):
    data = cb.get("data") or ""
    msg = cb.get("message") or {}
    chat = msg.get("chat") or {}
    chat_id = chat.get("id")
    mid = msg.get("message_id")
    user = cb.get("from") or {}
    uid = user.get("id")
    remember_chat(chat)
    remember_user(chat_id, user)
    is_photo = bool(msg.get("photo"))
    answer(cb["id"])

    p = data.split(SEP)
    step = p[0]
    try:
        if step == "home":
            show_start(chat_id, mid, is_photo)
            return
        if step == "noop":
            return
        if step == "cat":
            if len(p) < 3:
                edit(chat_id, mid, "Собираю афишу обоих сайтов…", [])
            show_movies(chat_id, mid, is_photo, p[1], int(p[2]) if len(p) > 2 else 0)
            return
        if step == "vid":
            send_movie_video(chat_id, int(p[1]))
            return
        if step == "off":
            with _lock:
                sub = STATE["subs"].pop(p[1], None)
            save_state()
            if sub and sub.get("hold"):
                release_hold(sub)
            edit(chat_id, mid, "Подписка отключена." if sub else "Уже отключена.", [])
            return
        if step in ("paid", "again"):
            if not is_owner(uid):
                answer(cb["id"], "Бронью управляет только владелец бота")
                return
            sub = STATE["subs"].get(p[1])
            if not sub:
                answer(cb["id"], "Подписка уже отключена")
                return
            if step == "paid":
                with _lock:
                    sub["hold"] = None
                    sub["seats"] = None
                save_state()
                send(chat_id, "👍 Принято, оплата подтверждена — автобронь "
                              "выключена. Приятного просмотра!")
            else:
                POOL.submit(do_booking, sub, True)
            return

        kind = p[1]
        m = find_movie(kind, p[2])
        if not m:
            edit(chat_id, mid, "Фильм пропал из афиши.", kb_categories())
            return
        ss = sessions(m)

        if step == "v":
            show_movie_info(chat_id, m)
            show_venues(chat_id, mid, is_photo, kind, m, ss)
        elif step == "s":
            show_sessions(chat_id, mid, is_photo, kind, m, ss, p[3])
        elif step == "cfg":
            show_dates(chat_id, mid, is_photo, kind, m, ss, p[3],
                       int(p[4]) if len(p) > 4 else 0)
        elif step == "d":
            show_halls(chat_id, mid, is_photo, kind, m, ss, p[3], p[4])
        elif step == "h":
            show_times(chat_id, mid, is_photo, kind, m, ss, p[3], p[4], p[5])
        elif step == "tm":
            cinema_id, date, hall, tm = p[3], p[4], p[5], p[6]
            can_book = (is_owner(uid) and date != "any"
                        and hall != "all" and tm != "all")
            if can_book:
                show_seats(chat_id, mid, is_photo, kind, m, ss, cinema_id, date, hall, tm)
            else:
                finish(chat_id, mid, is_photo, uid, kind, m, ss, cinema_id, date, hall, tm)
        elif step == "n":
            if p[3] == "any":          # фильм совсем без сеансов
                finish(chat_id, mid, is_photo, uid, kind, m, ss, "cm", "any", "all", "all", 0)
            else:
                if not is_owner(uid):
                    answer(cb["id"], "Автобронь доступна только владельцу")
                    return
                finish(chat_id, mid, is_photo, uid, kind, m, ss,
                       p[3], p[4], p[5], p[6], int(p[7]))
    except Exception as e:
        log("callback error:", data, e)
        traceback.print_exc()
        answer(cb["id"], "Что-то пошло не так, попробуйте ещё раз")


# ------------------------------------------------------------------ команды

HELP = (
    "<b>Как это работает</b>\n\n"
    "Жмите <b>«🎬 Новая напоминалка»</b> и дальше только кнопки:\n"
    "<blockquote>раздел → фильм → кинотеатр → сеансы → «Уведомить»</blockquote>\n"
    "<b>Два способа ждать</b>\n"
    "<blockquote><b>Любые новые сеансы и изменения</b> — сообщу про всё новое "
    "в этом кинотеатре: и про новый день, и про сеанс, добавленный на день, "
    "который уже открыт.\n\n"
    "<b>Конкретная дата</b> — только про неё. В списке те дни, которых "
    "в расписании ещё нет: остальное уже в продаже, там ждать нечего."
    "</blockquote>\n"
    "Зал и время можно не выбирать — тогда придут все сеансы дня.\n\n"
    "Автобронь (только у владельца) работает в обеих сетях: выберите день, "
    "зал, время и количество мест — бот займёт середину зала и пришлёт "
    "ссылку на оплату.\n\n"
    "<b>«🧪 Проверить»</b> покажет, как выглядит уведомление.\n"
    "<b>«🔔 Мои напоминалки»</b> — список, там же кнопка «Отключить»."
)


MENU = {
    "keyboard": [[{"text": "🎬 Новая напоминалка"}],
                 [{"text": "🔔 Мои напоминалки"}, {"text": "❓ Как это работает"}],
                 [{"text": "🧪 Проверить"}]],
    "resize_keyboard": True,
    "is_persistent": True,
}


def send_menu(chat_id):
    show_hello(chat_id)


def cmd_test(chat_id):
    """Показывает, как выглядит уведомление, на реальном ближайшем сеансе."""
    send(chat_id, "🧪 Проверка. Дальше — то же самое, что придёт при новых сеансах.")
    try:
        for m in movies("t"):
            ss = sessions(m)
            if not ss:
                continue
            v = cat.venues(ss)[0]
            mine = [s for s in ss if s["cinema_id"] == v["cinema_id"]][:4]
            demo = {"id": "0", "chat_id": chat_id, "title": m["title"],
                    "poster": m.get("poster"), "cinema": v["title"],
                    "srcs": m["src"], "kind": "t"}
            notify(demo, mine, first=True)
            send(chat_id, "Так это и выглядит. Напоминалка включается кнопкой "
                          "«🎬 Новая напоминалка».")
            return
        send(chat_id, "Сейчас нет ни одного сеанса для примера.")
    except Exception as e:
        log("test:", e)
        send(chat_id, "Не получилось собрать пример: %s" % esc(e))


def cmd_list(chat_id):
    subs = [s for s in STATE["subs"].values() if s["chat_id"] == chat_id]
    if not subs:
        send(chat_id, "Напоминалок пока нет. Нажмите «🎬 Новая напоминалка».")
        return
    for s in subs:
        send(chat_id, "<blockquote>%s</blockquote>" % esc(sub_title(s)),
             [[{"text": "Отключить", "callback_data": "off%s%s" % (SEP, s["id"])}]])


def on_message(m):
    chat = m.get("chat") or {}
    chat_id = chat.get("id")
    remember_chat(chat)
    remember_user(chat_id, m.get("from"))
    for u in (m.get("new_chat_members") or []):
        remember_user(chat_id, u)
    if m.get("new_chat_members") and chat.get("type") != "private":
        pull_admins(chat_id)
        send(chat_id, "Здравствуйте! Слежу за сеансами CINEMATICA и "
                      "кинотеатров gtickets.\n/new — подписаться, /help — подробности.")
        save_state()
        return

    text = (m.get("text") or "").strip()
    cmd = text.split()[0].split("@")[0].lower() if text else ""

    if cmd == "/start":
        if chat.get("type") != "private":
            pull_admins(chat_id)
        show_hello(chat_id)
    elif cmd == "/new" or text.startswith("🎬"):
        if chat.get("type") != "private":
            pull_admins(chat_id)
        show_start(chat_id)
    elif cmd == "/list" or text.startswith("🔔"):
        cmd_list(chat_id)
    elif cmd == "/help" or text.startswith("❓"):
        send(chat_id, HELP)
    elif cmd == "/test" or text.startswith("🧪"):
        POOL.submit(cmd_test, chat_id)
    elif cmd == "/menu":
        send_menu(chat_id)
    save_state()


def handle_update(u):
    try:
        if "message" in u:
            on_message(u["message"])
        elif "callback_query" in u:
            on_callback(u["callback_query"])
        elif "my_chat_member" in u:
            remember_chat(u["my_chat_member"]["chat"])
            save_state()
    except Exception as e:
        log("update error:", e)
        traceback.print_exc()


def poll_telegram():
    while True:
        try:
            r = tg("getUpdates", offset=STATE.get("offset", 0), timeout=50,
                   allowed_updates=["message", "callback_query", "my_chat_member"])
            if not r.get("ok"):
                time.sleep(3)
                continue
            for u in r.get("result", []):
                STATE["offset"] = u["update_id"] + 1
                POOL.submit(handle_update, u)
            if r.get("result"):
                save_state()
        except Exception as e:
            log("poll error:", e)
            time.sleep(5)


# ------------------------------------------------------------- уведомления

def send_movie_video(chat_id, cm_movie_id):
    fn = MOVIE_VIDEOS.get(int(cm_movie_id))
    if fn:
        send_video(chat_id, fn, VIDEO_CAPTION)


def notify(sub, fresh, first=False):
    chat_id = sub["chat_id"]
    text = "🔔 <b>Появились сеансы!</b>\n\n" + card(sub["title"], sub["cinema"], fresh)
    kb = []
    vb = video_button(sub)
    if vb:
        kb.append(vb)
    kb.append([{"text": "Отключить уведомления",
                "callback_data": "off%s%s" % (SEP, sub["id"])}])
    if sub.get("poster") and first:
        send_photo(chat_id, sub["poster"], text, kb)
    else:
        send(chat_id, text, kb)

    who = mentions(chat_id)
    send(chat_id, random.choice(INVITE) + ("\n\n" + who if who else ""))


def remind(sub, ss):
    chat_id = sub["chat_id"]
    send(chat_id, random.choice(REMIND) + "\n\n"
         + card(sub["title"], sub["cinema"], ss, with_free=False))
    who = mentions(chat_id)
    if who:
        send(chat_id, who)


def watch_loop():
    while True:
        try:
            for sub in list(STATE["subs"].values()):
                m = sub_movie(sub)
                drop_sessions(m, sub["src"])
                try:
                    ss = sessions(m, sub["src"])
                except Exception as e:
                    log("сеансы %s: %s" % (sub["title"], e))
                    continue
                matched = [s for s in ss if sub_matches(sub, s)]
                if not matched:
                    continue
                seen = set(sub.get("seen") or [])
                fresh = [s for s in matched if s["sid"] not in seen]
                if fresh:
                    log("новые сеансы %s: %d" % (sub["title"], len(fresh)))
                    with _lock:
                        sub["seen"] = [s["sid"] for s in matched]
                        sub["reminders"] = 0
                        sub["last_remind"] = time.time()
                    save_state()
                    notify(sub, fresh, first=not seen)
                    if booking_ready(sub):
                        do_booking(sub)
                elif (sub.get("last_remind") and not sub.get("hold")
                      and sub.get("reminders", 0) < MAX_REMINDERS
                      and time.time() - sub["last_remind"] >= REMIND_SECONDS):
                    with _lock:
                        sub["reminders"] = sub.get("reminders", 0) + 1
                        sub["last_remind"] = time.time()
                    save_state()
                    remind(sub, matched)
        except Exception as e:
            log("watch error:", e)
            traceback.print_exc()
        time.sleep(POLL_SECONDS)


# ------------------------------------------------------------- автобронь

TOKEN_CM = {"v": None}


def cm_login():
    if CM_LOGIN and CM_PASSWORD and not TOKEN_CM["v"]:
        try:
            TOKEN_CM["v"] = cm.login(CM_LOGIN, CM_PASSWORD)
            log("вошёл на cinematica.uz как", CM_LOGIN)
        except Exception as e:
            log("не смог войти на cinematica.uz:", e)


def find_session(sub):
    m = sub_movie(sub)
    drop_sessions(m, sub["src"])
    for s in sessions(m, sub["src"]):
        if sub_matches(sub, s):
            return s
    return None


def release_hold(sub):
    """Снимает текущую бронь, чтобы места не висели зря."""
    hold = sub.get("hold")
    if not hold:
        return
    if hold.get("src") == "gt":
        gt.cancel_order(hold["payment_id"], BOOK_PHONE)
    else:
        cm.cancel(hold["payment_id"], token=TOKEN_CM["v"])


def do_booking(sub, force=False):
    chat_id = sub["chat_id"]
    try:
        if not BOOK_PHONE or not booking_ready(sub):
            return
        s = find_session(sub)
        if not s:
            return
        raw = s["raw"]
        if s["src"] == "cm" and raw.get("disable_sales"):
            send(chat_id, "Продажа на этот сеанс закрыта, забронировать не получится.")
            return
        if sub.get("hold") and force:
            release_hold(sub)

        data = cat.scheme(s)
        rows_pref, center = anchor_for(s.get("hall"), s["src"])
        picked = cm.pick_seats(data, sub["seats"], rows_pref, center)
        if not picked:
            send(chat_id, "Не нашёл %d мест рядом на этом сеансе — придётся "
                          "выбирать вручную." % sub["seats"])
            return

        if s["src"] == "cm":
            pid, link = cm.book(raw["cinema_id"], raw["hall_id"], raw["id"],
                                picked, BOOK_PHONE, BOOK_EMAIL or None,
                                token=TOKEN_CM["v"])
            deadline = None
        else:
            pid, link, deadline = gt.order(
                raw["seance_id"], raw["cinema_id"], data.get("release_id"),
                picked, BOOK_PHONE, BOOK_EMAIL)
        with _lock:
            sub["hold"] = {"payment_id": pid, "url": link, "ts": time.time(),
                           "src": s["src"], "deadline": deadline,
                           "seats": cm.seats_text(picked)}
        save_state()

        total = sum(float(p.get("price") or 0) for p in picked)
        send(chat_id,
             "🎟 <b>Места забронированы</b>\n\n<blockquote>%s\n%s · %s\n%s · %s\n"
             "%s\nк оплате: %s</blockquote>\n\nСсылка живёт 10 минут. Кто "
             "оплатит — нажмите кнопку, иначе забронирую заново."
             % (esc(sub["title"]), esc(s["cinema"]), esc(s["hall"]),
                esc(d_long(s["date"])), s["time"], cm.seats_text(picked),
                cat._money(total)),
             [[{"text": "💳 Оплатить", "url": link}],
              [{"text": "✅ Я оплатил", "callback_data": "paid%s%s" % (SEP, sub["id"])},
               {"text": "🔁 Заново", "callback_data": "again%s%s" % (SEP, sub["id"])}]])
        log("бронь %s: %s" % (sub["title"], cm.seats_text(picked)))
    except Exception as e:
        log("booking error:", e)
        send(chat_id, "Не получилось забронировать: %s" % esc(e))


# Числовые коды статуса заказа gtickets в открытом виде не описаны.
# Основной сигнал — кнопка «Я оплатил»; коды логируются, чтобы уточнить.
PAID_ORDER_STATUS = {2, 3}


def _looks_paid(st):
    if not isinstance(st, dict) or st.get("result") != 0:
        return False
    for k in ("paid", "is_paid", "success", "confirmed"):
        if st.get(k) is True:
            return True
    return str(st.get("status") or st.get("state") or "").lower() in (
        "paid", "success", "ok", "completed", "confirmed")


def hold_ttl(hold):
    """Сколько бронь ещё живёт. gtickets сам присылает срок, у cinematica — 10 минут."""
    dl = hold.get("deadline")
    if dl:
        try:
            end = datetime.strptime(dl[:19], "%Y-%m-%dT%H:%M:%S")
            return (end - datetime.utcnow()).total_seconds()
        except Exception:
            pass
    return HOLD_SECONDS - (time.time() - hold["ts"])


def booking_loop():
    while True:
        try:
            for sub in list(STATE["subs"].values()):
                hold = sub.get("hold")
                if not hold or not booking_ready(sub):
                    continue
                if hold.get("src") == "gt":
                    st = gt.order_status(hold["payment_id"])
                    paid = isinstance(st, int) and st in PAID_ORDER_STATUS
                    if st is not None:
                        log("статус заказа %s: %s" % (hold["payment_id"], st))
                else:
                    paid = _looks_paid(cm.payment_status(hold["payment_id"],
                                                         token=TOKEN_CM["v"]))
                if paid:
                    with _lock:
                        sub["hold"] = None
                        sub["seats"] = None
                    save_state()
                    send(sub["chat_id"], "🎉 Оплата прошла — места ваши. "
                                         "Приятного просмотра!")
                    continue
                if hold_ttl(hold) <= 15:
                    log("бронь остыла, повторяю:", sub["title"])
                    do_booking(sub, force=True)
        except Exception as e:
            log("booking loop:", e)
        time.sleep(20)


# ---------------------------------------------------------------------- main

def selftest():
    for kind in ("t", "s"):
        lst = cat.movies(kind)
        print("%s: %d фильмов" % ("сегодня" if kind == "t" else "скоро", len(lst)))
    m = next(x for x in cat.movies("t") if len(x["src"]) > 1)
    ss = cat.sessions(m)
    print("\n%s — %d сеансов, площадки:" % (m["title"], len(ss)))
    for v in cat.venues(ss):
        print("   ", v["title"])
    v = cat.venues(ss)[0]
    print()
    print(card(m["title"], v["title"],
               [s for s in ss if s["cinema_id"] == v["cinema_id"]][:6]))


def main():
    if "--selftest" in sys.argv:
        selftest()
        return
    if not TOKEN:
        print("Нет BOT_TOKEN в .env")
        return
    load_state()
    me = tg("getMe").get("result") or {}
    log("бот запущен: @%s" % me.get("username"))
    register_commands()
    cm_login()
    threading.Thread(target=watch_loop, daemon=True).start()
    threading.Thread(target=booking_loop, daemon=True).start()
    poll_telegram()


if __name__ == "__main__":
    main()
