# -*- coding: utf-8 -*-
"""Собирает справку по средним местам всех залов в файл СРЕДНИЕ-МЕСТА.md.

Считает на пустой схеме зала — то есть показывает места, которые бот
займёт, если ничего ещё не продано. Запуск: python report_seats.py
"""

import io
import os
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

import bot
import catalog as cat
import cinematica as c

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "СРЕДНИЕ-МЕСТА.md")


def empty(d):
    """Схема без единой продажи — чтобы увидеть идеальный выбор."""
    d = dict(d)
    d["vacant_seats"] = [{"id": s["id"], "price": 1.0, "type": "1"}
                         for row in d["scheme"]["rows"] for s in row["seats"]]
    return d


def collect():
    """По одному сеансу на каждый зал обеих сетей."""
    halls = {}
    for m in cat.movies("t"):
        for s in cat.sessions(m):
            halls.setdefault((s["src"], s["cinema"], s["hall"]), s)
    return halls


def scheme_of(item):
    key, s = item
    try:
        return key, empty(cat.scheme(s))
    except Exception:
        return key, None


def row_for(key, d):
    src, cinema, hall = key
    rows = d["scheme"]["rows"]
    seats_max = max(len(r["seats"]) for r in rows)
    pref, center = bot.anchor_for(hall, src)
    picks = [c.seats_text(c.pick_seats(d, n, pref, center)) for n in (1, 2, 3, 4)]
    if not pref:
        rule = "геометрия"
    elif c.pick_anchored(d, 2, pref, center):
        rule = "якорь %s@%s" % (",".join(map(str, pref)), center)
    else:
        # ряда из якоря в этом зале просто нет — считаем геометрией
        rule = "геометрия (якорь не подошёл)"
    return {"src": src, "cinema": cinema, "hall": hall,
            "size": "%d × %d" % (len(rows), seats_max),
            "rule": rule, "picks": picks}


def only(text):
    """'ряд 7, места 13, 14' -> 'ряд 7: 13, 14'"""
    return text.replace(", места ", ": ").replace(", место ", ": ")


def main():
    halls = collect()
    print("залов найдено:", len(halls))
    rows = []
    with ThreadPoolExecutor(max_workers=8) as ex:
        for key, d in ex.map(scheme_of, halls.items()):
            if d:
                rows.append(row_for(key, d))
    print("схем прочитано:", len(rows))

    out = ["# Средние места по залам", "",
           "Места, которые бот занимает при автоброни. Посчитано на пустом "
           "зале — то есть это идеальный выбор, когда ещё ничего не продано. "
           "Если середина уже разобрана, бот берёт ближайшее к ней, а в угол "
           "зала не уходит.", "",
           "Замер: %s. Пересчитать — `python report_seats.py`."
           % datetime.now().strftime("%d.%m.%Y"), ""]

    for src, title, note in (
        ("cm", "CINEMATICA",
         "Здесь работают якоря из `.env` — любимые ряды, заданные вручную. "
         "Если в первом ряду места заняты, бот идёт во второй, потом в третий."),
        ("gt", "gtickets",
         "Здесь якорей нет, середина считается по геометрии зала: центр "
         "каждого ряда и примерно 62 % глубины от экрана. Боковые ложи "
         "за проходом в расчёт не берутся."),
    ):
        part = sorted([r for r in rows if r["src"] == src],
                      key=lambda r: (r["cinema"], r["hall"]))
        if not part:
            continue
        out += ["## %s" % title, "", note, "",
                "| Кинотеатр | Зал | Ряды × мест | 1 место | 2 места | 3 места | 4 места | Правило |",
                "|---|---|---|---|---|---|---|---|"]
        for r in part:
            out.append("| %s | %s | %s | %s | %s | %s | %s | %s |" % (
                r["cinema"], r["hall"], r["size"],
                only(r["picks"][0]), only(r["picks"][1]),
                only(r["picks"][2]), only(r["picks"][3]), r["rule"]))
        out.append("")

    out += ["## Как менять",
            "",
            "Якоря живут в `.env`, строка `SEAT_ANCHORS`. Формат: кусок "
            "названия зала, ряды по убыванию желания, после `@` — "
            "центральное место.",
            "",
            "```",
            "SEAT_ANCHORS=imax:7,8,6@13.5; atmos:5,6,4@10.5; *:4,5@5",
            "```",
            "",
            "`13.5` значит «между 13 и 14»: два места дадут 13 и 14, четыре — "
            "с 12 по 15. Правило `*` действует только на CINEMATICA; чтобы "
            "задать любимые места в зале gtickets, добавьте строку с куском "
            "его названия.", ""]

    io.open(OUT, "w", encoding="utf-8").write("\n".join(out))
    print("записано:", OUT)


if __name__ == "__main__":
    main()
