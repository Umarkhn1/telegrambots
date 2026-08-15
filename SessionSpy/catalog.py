# -*- coding: utf-8 -*-
"""Общий каталог поверх cinematica.uz и gtickets.uz.

Наружу отдаёт одинаковые структуры, откуда бы они ни пришли:

  фильм   {"key", "title", "poster", "src": {"cm": id, "gt": id}}
  площадка {"src", "cinema_id", "title"}
  сеанс   {"src", "sid", "cinema_id", "cinema", "hall_id", "hall",
           "date" (ISO), "time", "price", "raw"}
"""

import re
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

import cinematica as cm
import gtickets as gt

CM_LABEL = "CINEMATICA"

# Пулы живут всё время работы процесса: потоки переиспользуются, а вместе
# с ними и открытые соединения к сайтам. Свежий поток платит за рукопожатие
# TLS около секунды — на семи датах это разница между 2 с и 0.4 с.
# Пула два, чтобы верхняя задача не ждала свободного места под вложенную.
_TOP = ThreadPoolExecutor(max_workers=4, thread_name_prefix="cat-top")
_IO = ThreadPoolExecutor(max_workers=8, thread_name_prefix="cat-io")


# ------------------------------------------------------------------ фильмы

def norm(title):
    """Ключ для склейки одного фильма из двух афиш."""
    t = (title or "").lower()
    t = re.sub(r"\b(2d|3d|imax|atmos|4dx|eng|rus|sub|o[`'’]?zbek|uzbek|tili)\b", " ", t)
    t = re.sub(r"[^\w\s]", " ", t, flags=re.UNICODE)
    return re.sub(r"\s+", " ", t).strip()


_tashkent = {"ids": None, "ts": 0}


def tashkent_cinemas():
    """Только ташкентские площадки gtickets — чужой город нам не нужен."""
    import time as _t
    if _tashkent["ids"] is None or _t.time() - _tashkent["ts"] > 3600:
        try:
            _tashkent["ids"] = {c["id"] for c in gt.cinemas()
                                if c.get("city_id") == gt.CITY}
            _tashkent["ts"] = _t.time()
        except Exception:
            return None                      # не смогли проверить — не отсекаем
    return _tashkent["ids"]


def _cm_info(m):
    det = {d.get("title"): d.get("value") for d in (m.get("details") or [])}
    return {"age": m.get("age_restriction"),
            "genre": det.get("Жанр") or det.get("Жанры"),
            "year": det.get("Год"),
            "director": det.get("Режиссёр"),
            "cast": det.get("Актеры") or det.get("Актёры"),
            "duration": det.get("Продолжительность"),
            "rating": m.get("rating") or m.get("imdb") or m.get("kinopoisk"),
            "desc": re.sub(r"<[^>]+>", " ", m.get("long_desc") or "")}


def _cm_movies(kind):
    out = []
    for m in (cm.movies_today() if kind == "t" else cm.movies_soon()):
        name = cm.movie_name(m)
        if name:
            out.append({"title": name, "poster": cm.poster_url(m), "id": m["id"],
                        "info": _cm_info(m)})
    return out


def _gt_movies(kind):
    out = []
    for r in gt.releases():
        n = (r.get("cinema_seances") or {}).get("seances_count", 0)
        if (kind == "t") != (n > 0):
            continue
        out.append({"title": (r.get("title") or "").strip(),
                    "poster": r.get("poster_original") or r.get("poster"),
                    "id": r["id"],
                    "info": {"age": r.get("age_rating"),
                             "genre": ", ".join(x.strip() for x in (r.get("genres") or []) if x),
                             "format": ", ".join(r.get("formats") or []),
                             "rating": r.get("rating")}})
    return out


def movies(kind):
    """Афиша обоих сайтов, склеенная по названию."""
    a = _TOP.submit(_cm_movies, kind)
    b = _TOP.submit(_gt_movies, kind)
    cm_list = _safe(a)
    gt_list = _safe(b)

    merged = {}
    for src, lst in (("cm", cm_list), ("gt", gt_list)):
        for m in lst:
            key = norm(m["title"])
            if not key:
                continue
            item = merged.setdefault(key, {"key": key, "title": m["title"],
                                           "poster": None, "info": {}, "src": {}})
            item["src"].setdefault(src, m["id"])
            if not item["poster"]:
                item["poster"] = m["poster"]
            for k, v in (m.get("info") or {}).items():
                if v and not item["info"].get(k):
                    item["info"][k] = v
            # название покажем то, что короче и без технических хвостов
            if len(m["title"]) < len(item["title"]):
                item["title"] = m["title"]
    return sorted(merged.values(), key=lambda x: x["title"].lower())


def _safe(future):
    try:
        return future.result()
    except Exception:
        return []


def find_movie(kind, key):
    for m in movies(kind):
        if m["key"] == key:
            return m
    other = "s" if kind == "t" else "t"
    for m in movies(other):
        if m["key"] == key:
            return m
    return None


# ------------------------------------------------------------------ сеансы

def _iso(d):
    """'13.08.26' -> '2026-08-13'."""
    try:
        return datetime.strptime(d, "%d.%m.%y").strftime("%Y-%m-%d")
    except Exception:
        return d


def _cm_sessions(movie_id):
    out = []
    for s in cm.repertory(movie_id):
        out.append({
            "src": "cm",
            "sid": "cm:%s" % s["id"],
            "cinema_id": "cm",
            "cinema": CM_LABEL,
            "hall_id": str(s["hall_id"]),
            "hall": s.get("hall") or "",
            "date": _iso(s["date"]),
            "time": s["time"],
            "price": _money(s.get("price")),
            "raw": s,
        })
    return out


def _gt_sessions(movie_id, days=None):
    days = days or gt.dates()
    out = []

    def one(day):
        try:
            return day, gt.release_day(movie_id, day)
        except Exception:
            return day, {}

    allowed = tashkent_cinemas()
    for day, d in _IO.map(one, days):
        for c in (d.get("cinemas") or []):
            if allowed is not None and c.get("id") not in allowed:
                continue
            for s in (c.get("seances") or []):
                dt = s.get("datetime") or {}
                hall = s.get("hall") or {}
                price = s.get("price") or {}
                out.append({
                    "src": "gt",
                    "sid": "gt:%s" % s["id"],
                    "cinema_id": "gt%s" % c["id"],
                    "cinema": c.get("title") or "",
                    "hall_id": str(hall.get("id")),
                    "hall": hall.get("title") or "",
                    "date": dt.get("date") or day,
                    "time": dt.get("time") or "",
                    "price": (price.get("min_text") or "") + " сум",
                    "raw": {"seance_id": s["id"], "cinema_id": c["id"]},
                })
    return out


def _money(v):
    try:
        return "{:,.0f}".format(float(v)).replace(",", " ") + " сум"
    except Exception:
        return str(v or "")


def sessions(movie, only_src=None):
    """Все сеансы фильма по обоим сайтам."""
    jobs = []
    if "cm" in movie["src"] and only_src in (None, "cm"):
        jobs.append(_TOP.submit(_cm_sessions, movie["src"]["cm"]))
    if "gt" in movie["src"] and only_src in (None, "gt"):
        jobs.append(_TOP.submit(_gt_sessions, movie["src"]["gt"]))
    out = []
    for j in jobs:
        out += _safe(j)
    return out


def venues(ss):
    """Площадки из списка сеансов: CINEMATICA первой, дальше по алфавиту."""
    seen = {}
    for s in ss:
        seen[s["cinema_id"]] = s["cinema"]
    out = [{"cinema_id": k, "title": v, "src": "cm" if k == "cm" else "gt"}
           for k, v in seen.items()]
    out.sort(key=lambda v: (v["src"] != "cm", v["title"].lower()))
    return out


def scheme(s):
    """Схема зала сеанса — одинаковая структура для обоих сайтов."""
    if s["src"] == "cm":
        r = s["raw"]
        d = cm.seats(r["cinema_id"], r["hall_id"], r["movie_id"], r["id"])
        d["hall"] = r.get("hall")
        return d
    return gt.scheme(s["raw"]["seance_id"], s["raw"]["cinema_id"])


def free_seats(s):
    """Число свободных мест на сеансе."""
    try:
        if s["src"] == "cm":
            r = s["raw"]
            d = cm.seats(r["cinema_id"], r["hall_id"], r["movie_id"], r["id"])
            return len(d.get("vacant_seats") or [])
        return gt.free_places(s["raw"]["seance_id"], s["raw"]["cinema_id"])
    except Exception:
        return None
