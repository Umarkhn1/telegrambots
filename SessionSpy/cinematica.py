"""API кинотеатров cinematica.uz + выбор лучших мест в зале."""

import base64
import json
import os
import threading
import time

import requests

BASE = "https://cinematica.uz"
API = BASE + "/api/v1"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"

# Одна сессия на поток: переиспользование соединения экономит ~0.3 с
# на каждом запросе (иначе каждый раз новое TLS-рукопожатие).
_local = threading.local()


def _session():
    s = getattr(_local, "s", None)
    if s is None:
        s = requests.Session()
        s.headers.update({"User-Agent": UA, "Accept": "application/json",
                          "X-Language": "ru"})
        adapter = requests.adapters.HTTPAdapter(pool_connections=4, pool_maxsize=8)
        s.mount("https://", adapter)
        _local.s = s
    return s


def _req(path, payload=None, token=None, timeout=30, retries=3):
    url = path if path.startswith("http") else API + path
    headers = {}
    if token:
        headers["Authorization"] = "Basic " + base64.b64encode(
            ("web:" + token).encode("utf-8")).decode("ascii")
    last = None
    for i in range(retries):
        try:
            s = _session()
            if payload is None:
                r = s.get(url, headers=headers, timeout=(6, timeout))
            else:
                r = s.post(url,
                           data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                           headers=dict(headers, **{"Content-Type": "application/json"}),
                           timeout=(6, timeout))
            r.raise_for_status()
            return json.loads(r.content.decode("utf-8"))
        except Exception as e:
            last = e
            _local.s = None            # соединение могло оборваться
            if i < retries - 1:
                time.sleep(0.3)
    raise last


def get(path, **kw):
    return _req(path, **kw)


def post(path, payload, **kw):
    return _req(path, payload=payload, **kw)


# ---------------------------------------------------------------- каталог

def movies_today():
    return get("/movies/today").get("list", [])


def movies_soon():
    return get("/movies/soon").get("list", [])


def repertory(movie_id):
    """Все сеансы фильма: дата DD.MM.YY, время, зал, цена, id сеанса."""
    d = get("/repertory/movie/%s/grouped" % movie_id)
    return d.get("list", []) if d.get("result") == 0 else []


def seats(cinema_id, hall_id, movie_id, repertory_id):
    return get("/repertory/seats/%s/%s/%s/%s" % (cinema_id, hall_id, movie_id, repertory_id))


def poster_url(movie):
    p = movie.get("file_poster") or movie.get("file_poster_vertical")
    return (BASE + p) if p else None


def movie_name(m):
    return (m.get("name") or m.get("i18n", {}).get("name_ru") or "").strip()


# ------------------------------------------------------- выбор мест в зале

def _rows_from_screen(scheme):
    """Ряды по порядку от экрана вглубь. Ряд 1 всегда ближе всех к экрану."""
    rows = []
    for r in scheme.get("rows", []):
        ss = sorted(r.get("seats", []), key=lambda s: s["x"])
        if ss:
            rows.append({"order": r.get("order"), "seats": ss,
                         "y": sum(s["y"] for s in ss) / len(ss)})
    nums = []
    for r in rows:
        try:
            nums.append(int(str(r["order"]).strip()))
        except (TypeError, ValueError):
            nums.append(None)
    if all(n is not None for n in nums):
        order = sorted(range(len(rows)), key=lambda i: nums[i])
    else:
        # запасной вариант: чем больше y, тем ближе к экрану (экран снизу схемы)
        order = sorted(range(len(rows)), key=lambda i: -rows[i]["y"])
    return [rows[i] for i in order]


def _step(scheme):
    """Типичное расстояние между соседними креслами — чтобы ловить проходы."""
    gaps = []
    for r in scheme.get("rows", []):
        xs = sorted(s["x"] for s in r.get("seats", []))
        gaps += [b - a for a, b in zip(xs, xs[1:]) if b > a]
    if not gaps:
        return max(scheme.get("seat_size", 25), 1)
    gaps.sort()
    return gaps[len(gaps) // 2]


def _main_group(seats, step):
    """Самая длинная группа кресел подряд — основной блок ряда без лож."""
    groups, cur = [], [seats[0]]
    for a, b in zip(seats, seats[1:]):
        if b["x"] - a["x"] > step * 1.6:
            groups.append(cur)
            cur = []
        cur.append(b)
    groups.append(cur)
    return max(groups, key=len)


def _free_blocks(seats, vacant, count, step):
    """Все сплошные блоки из count свободных мест подряд (без прохода внутри)."""
    out = []
    for i in range(len(seats) - count + 1):
        block = seats[i:i + count]
        if any(str(s["id"]) not in vacant for s in block):
            continue
        if any(b["x"] - a["x"] > step * 1.6 for a, b in zip(block, block[1:])):
            continue
        out.append(block)
    return out


def _row_num(seats):
    try:
        return int(str(seats[0]["row"]).strip())
    except (ValueError, TypeError, IndexError, KeyError):
        return None


def _num_center(block):
    return sum(int(s["number"]) for s in block) / float(len(block))


def _fmt(block, vacant):
    out = []
    for s in block:
        v = vacant[str(s["id"])]
        out.append({"id": str(s["id"]), "row": str(s["row"]), "number": s["number"],
                    "type": v.get("type") or s.get("type") or "1",
                    "price": v.get("price")})
    return out


def pick_anchored(data, count, rows_pref, center, tolerance=3):
    """Места вокруг заданного центра ряда.

    Порядок такой: точное попадание в любимых рядах, затем небольшое
    смещение вбок в тех же рядах, затем те же места в соседних рядах.
    Середина важнее ряда: если центр ряда 7 разобран, бот уходит в ряд 8,
    а не садится с краю седьмого.

    rows_pref — номера рядов по убыванию желания, напр. [7, 8, 6].
    center    — номер центрального места (13.5 = между 13 и 14).
    """
    scheme = data.get("scheme") or {}
    vacant = {str(v["id"]): v for v in data.get("vacant_seats", [])}
    if not vacant or count < 1:
        return None
    step = _step(scheme)

    by_num = {}
    for r in scheme.get("rows", []):
        try:
            by_num[int(str(r.get("order")).strip())] = sorted(
                r.get("seats", []), key=lambda s: s["x"])
        except (TypeError, ValueError):
            continue

    # 1) ровно те места, что просили
    for rn in rows_pref:
        for block in _free_blocks(by_num.get(rn) or [], vacant, count, step):
            if abs(_num_center(block) - center) < 0.01:
                return _fmt(block, vacant)

    # 2) те же ряды, но с небольшим смещением вбок
    # 3) если и там середина занята — соседние ряды, от ближнего к дальнему
    # если и в любимых рядах середина занята — уходим глубже в зал:
    # сначала ряды за якорем, и только потом ближе к экрану
    anchor = rows_pref[0]
    others = sorted((n for n in by_num
                     if n not in rows_pref and abs(n - anchor) <= 3),
                    key=lambda n: (0 if n > anchor else 1, abs(n - anchor)))
    for rn in list(rows_pref) + others:
        best, best_dev = None, None
        for block in _free_blocks(by_num.get(rn) or [], vacant, count, step):
            dev = abs(_num_center(block) - center)
            if dev <= tolerance and (best_dev is None or dev < best_dev):
                best, best_dev = block, dev
        if best:
            return _fmt(best, vacant)
    return None


def pick_seats(data, count, rows_pref=None, center=None):
    """Лучшие `count` мест рядом.

    Если заданы rows_pref/center — сначала пробует их (жёсткая привязка к
    залу), иначе считает геометрически: центр по ширине, ~62 % вглубь.
    """
    if rows_pref and center is not None:
        got = pick_anchored(data, count, rows_pref, center)
        if got:
            return got
        # ближе к экрану, чем любимые ряды, не садимся: лучше глубже и сбоку,
        # чем в третьем ряду по центру
        got = _pick_geometric(data, count, min_row=min(rows_pref))
        if got:
            return got

    return _pick_geometric(data, count)


def _pick_geometric(data, count, min_row=None):
    """Середина зала по геометрии: центр ряда и ~62 % глубины от экрана."""

    scheme = data.get("scheme") or {}
    vacant = {str(v["id"]): v for v in data.get("vacant_seats", [])}
    if not vacant or count < 1:
        return None

    rows = _rows_from_screen(scheme)
    if not rows:
        return None
    step = _step(scheme)

    # золотая середина зала — примерно 62% глубины от экрана
    ideal_row = (len(rows) - 1) * 0.62

    best, best_cost = None, None
    for depth, row in enumerate(rows):
        ss = row["seats"]
        if min_row is not None and _row_num(ss) is not None and _row_num(ss) < min_row:
            continue
        # Центр считаем по самому ряду: у gtickets ряды разной длины
        # выровнены по краю, и общий центр зала там врёт. Боковые ложи
        # за проходом в расчёт не берём — центр по самой длинной группе.
        main = _main_group(ss, step)
        center_x = (main[0]["x"] + main[-1]["x"]) / 2.0
        for i in range(len(ss) - count + 1):
            block = ss[i:i + count]
            if any(str(s["id"]) not in vacant for s in block):
                continue
            # не должно быть прохода внутри блока
            if any(b["x"] - a["x"] > step * 1.6 for a, b in zip(block, block[1:])):
                continue
            bx = sum(s["x"] for s in block) / float(count)
            d_side = abs(bx - center_x) / float(step)          # в креслах
            d_row = depth - ideal_row                          # в рядах
            # край ряда хуже, чем чужой ряд: середина важнее глубины,
            # поэтому боковое отклонение весит вдвое
            cost = d_side * 2.0 + (d_row * 1.2 if d_row >= 0 else -d_row * 1.9)
            if best_cost is None or cost < best_cost:
                best_cost, best = cost, block

    return _fmt(best, vacant) if best else None


def is_central(data, picked, limit=2.5):
    """Правда ли выбранные места около середины своего ряда.

    Нужно, чтобы честно предупредить: середина разобрана, взяли что было.
    """
    scheme = data.get("scheme") or {}
    step = _step(scheme)
    ids = {str(s["id"]) for s in picked or []}
    for row in scheme.get("rows", []):
        ss = sorted(row.get("seats", []), key=lambda s: s["x"])
        block = [s for s in ss if str(s["id"]) in ids]
        if len(block) != len(ids):
            continue
        main = _main_group(ss, step)
        center_x = (main[0]["x"] + main[-1]["x"]) / 2.0
        bx = sum(s["x"] for s in block) / float(len(block))
        return abs(bx - center_x) / float(step) <= limit
    return True


def seats_text(picked):
    if not picked:
        return "—"
    row = picked[0]["row"]
    nums = ", ".join(str(n) for n in sorted(int(s["number"]) for s in picked))
    return "ряд %s, мест%s %s" % (row, "о" if len(picked) == 1 else "а", nums)


# ------------------------------------------------------------ бронирование

# Сайт не отдаёт ссылку на оплату: он скрытой формой постит заказ на свой
# шлюз, а тот уже открывает Payme. Кнопка в Telegram так не умеет, поэтому
# ссылку на кассу Payme собираем сами — данные те же, что шлюз кладёт в чек.
PAYME = "https://checkout.paycom.uz/"
PAYME_MERCHANT = os.environ.get("PAYME_MERCHANT", "654ca0fc41a367e34fca5c48")
PAYME_CALLBACK = "https://cinematica.gtickets.uz/Payme/Result?orderid=%s"


def payme_link(params):
    """Касса Payme для созданной брони. Сумма в чеке — в тийинах."""
    order = (params or {}).get("orderid")
    amount = (params or {}).get("amount")
    if not order or not amount:
        return None
    fields = "m=%s;ac.orderid=%s;a=%d;c=%s;l=ru" % (
        PAYME_MERCHANT, order, int(round(float(amount) * 100)),
        PAYME_CALLBACK % order)
    return PAYME + base64.b64encode(fields.encode("utf-8")).decode("ascii")


def book(cinema_id, hall_id, repertory_id, picked, phone, email, token=None):
    """Создаёт бронь и возвращает (payment_id, ссылка на оплату).

    Сайт держит места, пока идёт 10-минутный таймер оплаты.
    """
    payload = {
        "email": email,
        "phone": phone,
        "seats": [{"row": s["row"], "number": s["number"], "type": s["type"], "d": ""}
                  for s in picked],
        "repertory_id": repertory_id,
        "hall_id": hall_id,
        "cinema_id": cinema_id,
        "discount_card_code": None,
        "platform": "web",
    }
    # без повтора: если запрос всё же дошёл, второй создаст лишнюю бронь
    d = post("/payment/dis", payload, token=token, retries=1)
    if d.get("result") != 0:
        raise RuntimeError(d.get("message") or "не удалось забронировать")
    params = d.get("params") or {}
    # ticket_url — это страница «после оплаты», открывать её саму бесполезно.
    # Настоящая оплата начинается с отправки params формой на d["url"] —
    # этим занимается страничка бота (web.py).
    link = payme_link(params) or params.get("ticket_url")
    return d.get("payment_id"), link, {"url": d.get("url"), "params": params}


def payment_status(payment_id, token=None):
    try:
        return get("/payment/status/%s" % payment_id, token=token)
    except Exception:
        return None


def cancel(payment_id, token=None):
    for path, body in (("/payment/dis.cancel", {"payment_id": payment_id}),
                       ("/payment/cancel/%s" % payment_id, {})):
        try:
            post(path, body, token=token)
            return True
        except Exception:
            continue
    return False


def login(user, password):
    d = post("/user/auth", {"login": user, "password": password, "isWeb": True})
    return d.get("token")
