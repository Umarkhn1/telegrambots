# -*- coding: utf-8 -*-
"""Крошечная страничка оплаты.

cinematica.uz не отдаёт ссылку на кассу: её страница скрытой формой
отправляет заказ на свой шлюз, и только тогда появляется Payme. Кнопка
в Telegram так не умеет — она открывает адрес, а не отправляет форму.
Поэтому бот показывает свою страничку: она делает ровно ту же отправку,
но уже руками браузера.

Адрес страницы содержит orderid — случайный GUID заказа, подобрать его
нельзя, а чужие заказы через неё не открыть.
"""

import html
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PAGE = u"""<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Оплата брони</title>
<style>
  body {{ margin:0; min-height:100vh; display:grid; place-content:center;
         font:16px/1.5 -apple-system, Segoe UI, Roboto, sans-serif;
         background:#0f1115; color:#e7e9ee; text-align:center; padding:24px }}
  .card {{ max-width:340px }}
  h1 {{ font-size:20px; margin:0 0 8px }}
  p {{ color:#9aa1ad; margin:0 0 20px }}
  button {{ font:inherit; padding:14px 28px; border:0; border-radius:10px;
            background:#3d7dff; color:#fff; cursor:pointer }}
</style></head>
<body><div class="card">
  <h1>{title}</h1>
  <p>{note}</p>
  <form id="f" method="post" action="{action}">
{fields}
    <button type="submit">Перейти к оплате</button>
  </form>
</div>
<script>setTimeout(function(){{document.getElementById('f').submit()}}, 150)</script>
</body></html>"""

GONE = u"""<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Бронь не найдена</title></head>
<body style="font:16px sans-serif;padding:40px;background:#0f1115;color:#e7e9ee">
Бронь уже отменена или оплачена. Вернитесь в бот.</body></html>"""


def _page(hold):
    fields = "\n".join(
        '    <input type="hidden" name="%s" value="%s">'
        % (html.escape(str(k), True), html.escape(str(v), True))
        for k, v in (hold.get("params") or {}).items())
    return PAGE.format(
        title=html.escape(hold.get("title") or "Оплата брони"),
        note=html.escape(hold.get("note") or ""),
        action=html.escape(hold.get("url") or "", True),
        fields=fields)


def make_handler(lookup):
    class Handler(BaseHTTPRequestHandler):
        server_version = "cinema-bot"

        def do_GET(self):
            path = self.path.split("?")[0].rstrip("/")
            if not path.startswith("/pay/"):
                self._send(404, GONE)
                return
            hold = lookup(path[len("/pay/"):])
            if not hold or not hold.get("params"):
                self._send(404, GONE)
                return
            self._send(200, _page(hold))

        def _send(self, code, body):
            data = body.encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *a):
            pass                      # свой лог у бота

    return Handler


def serve(lookup, port):
    """Поднимает сервер в фоне и возвращает его."""
    srv = ThreadingHTTPServer(("0.0.0.0", port), make_handler(lookup))
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv
