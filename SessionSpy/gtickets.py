"""API gtickets.uz — афиша, кинотеатры и сеансы.

Схемы зала сайт наружу не отдаёт (проверены seanceinfo, hall, playbill3 и
чанки фронтенда), поэтому выбрать конкретные места и забронировать их
здесь нельзя — только смотреть сеансы и число свободных мест.
"""

import json
import threading
import time

import requests

API = "https://api.gtickets.uz/api"
AGG = 6
CITY = 1
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"

_local = threading.local()


def _session():
    s = getattr(_local, "s", None)
    if s is None:
        s = requests.Session()
        s.headers.update({"User-Agent": UA, "Accept": "application/json"})
        s.mount("https://", requests.adapters.HTTPAdapter(
            pool_connections=4, pool_maxsize=8))
        _local.s = s
    return s


def get(path, **params):
    params.setdefault("aggregatorid", AGG)
    last = None
    for i in range(3):
        try:
            r = _session().get(API + path, params=params, timeout=(6, 30))
            r.raise_for_status()
            return json.loads(r.content.decode("utf-8"))
        except Exception as e:
            last = e
            _local.s = None
            if i < 2:
                time.sleep(0.3)
    raise last


def post(path, body, retries=1, **params):
    """POST. По умолчанию без повторов: заказ нельзя создавать дважды."""
    params.setdefault("aggregatorid", AGG)
    last = None
    for i in range(retries):
        try:
            r = _session().post(API + path, params=params, json=body, timeout=(6, 40))
            r.raise_for_status()
            return json.loads(r.content.decode("utf-8"))
        except requests.HTTPError as e:
            body_text = ""
            try:
                body_text = e.response.text[:300]
            except Exception:
                pass
            raise RuntimeError("%s: %s" % (e, body_text))
        except Exception as e:
            last = e
            _local.s = None
            if i < retries - 1:
                time.sleep(0.3)
    raise last


def releases():
    """Вся афиша. seances_count == 0 значит «скоро», сеансов ещё нет."""
    return (get("/playbill3", cityid=CITY) or {}).get("releases", [])


def dates():
    """Даты, на которые вообще есть сеансы."""
    return get("/actualseancedates", cityid=CITY) or []


def cinemas():
    return get("/cinema", cityid=CITY) or []


def release_day(movie_id, date):
    """Сеансы фильма на дату, сгруппированные по кинотеатрам."""
    return get("/releaseinfo2/%s" % movie_id, cityid=CITY, date=date) or {}


def free_places(seance_id, cinema_id):
    try:
        d = get("/seanceinfo", seanceid=seance_id, cinemaid=cinema_id)
        return d.get("free_places_count")
    except Exception:
        return None


def widget(seance_id, cinema_id):
    """Схема зала: ряды, места, координаты, занятость.

    Этим же ответом живёт страница выбора мест на pay.gtickets.uz.
    """
    return get("/kinowidget", seanceid=seance_id, cinemaid=cinema_id)


def scheme(seance_id, cinema_id):
    """Схема в том же виде, что у cinematica — чтобы выбирать места одним кодом."""
    d = widget(seance_id, cinema_id)
    rows, vacant = [], []
    for r in (d.get("Rows") or []):
        seats = []
        for s in (r.get("Seats") or []):
            sid = str(s.get("Id"))
            seats.append({"id": sid, "row": str(r.get("Index")),
                          "number": s.get("Number"),
                          "x": s.get("X") or 0,
                          "y": r.get("Y") or 0,
                          "type": "1"})
            if s.get("IsAvailable"):
                vacant.append({"id": sid, "price": s.get("Price"), "type": "1"})
        if seats:
            rows.append({"order": str(r.get("Index")), "seats": seats})
    sizes = [s.get("Width") for r in (d.get("Rows") or [])
             for s in (r.get("Seats") or []) if s.get("Width")]
    return {"scheme": {"rows": rows, "width": d.get("Width"),
                       "height": d.get("Height"),
                       "seat_size": sizes[0] if sizes else 36},
            "vacant_seats": vacant,
            "hall": d.get("Title"),
            "release_id": d.get("ReleaseId"),
            "free": d.get("RemainingCapacity")}


WIDGET = "https://pay.gtickets.uz/"


def widget_url(seance_id, cinema_id):
    return "%s?seanceid=%s&cinemaid=%s&aggregatorid=%s" % (
        WIDGET, seance_id, cinema_id, AGG)


def order(seance_id, cinema_id, release_id, picked, phone, email):
    """Оформляет заказ и возвращает (номер, ссылка на оплату, срок брони).

    Места держатся до ApproveDeadlineUtc, дальше заказ сгорает сам.
    """
    body = {
        "email": email or "",
        "phone": phone,
        "seanceid": seance_id,
        "releaseid": release_id,
        "cinemaid": cinema_id,
        "aggregatorid": AGG,
        "referalid": None,
        "afisha_orderid": None,
        "return_url": widget_url(seance_id, cinema_id),
        "ticket_url": widget_url(seance_id, cinema_id),
        "standing_seats": None,
        "seats": [{"id": s["id"], "row": str(s["row"]),
                   "seat": str(s["number"]), "price": s.get("price")}
                  for s in picked],
        "goods": None,
        "fingerprint": None,
        "external_user_id": None,
        "userid": None,
    }
    d = post("/order", body)
    pays = d.get("Payments") or []
    link = next((p.get("PayUrl") for p in pays if p.get("PayUrl")), None)
    return d.get("OrderId"), link or widget_url(seance_id, cinema_id), \
        d.get("ApproveDeadlineUtc")


def cancel_order(order_id, phone):
    try:
        post("/cancelorder", {"orderid": order_id, "phone": phone})
        return True
    except Exception:
        return False


def order_status(order_id):
    try:
        return get("/orderstatus/%s" % order_id)
    except Exception:
        return None


def poster(movie_id):
    return "%s/poster/big/%s" % (API.rsplit("/api", 1)[0], movie_id)
