#!/usr/bin/env python3
"""A fake Stalker portal, for testing imports without a real one.

Two modes.

    python3 tools/fake-portal.py [port] [delay] [categories]     invented data
    python3 tools/fake-portal.py [port] [delay] --real            replay real payloads

Invented mode serves a small live lineup instantly and a deliberately slow film library, so the
difference between "channels wait for the films" and "channels first, films after" is measurable.

Real mode replays the JSON captured from an actual portal, in app/src/test/resources/stalker:
153 categories, nine of them censored, a 3,759 item movie category and a 7,777 item series
category, plus seasons, episodes and movie lookups. That is what a real import has to chew
through, and it is the only way to exercise deep paging without a provider.

Every request is appended to /tmp/fakeportal/requests.log with a timestamp. That log is the
point. A healthy import asks for stb/handshake, stb/get_profile, itv/get_genres and
itv/get_all_channels first, and only then starts on vod/get_ordered_list. If the vod lines come
first, or the channel list takes minutes to appear, the split has regressed.

On an emulator the host is 10.0.2.2, so use http://10.0.2.2:8099 as the portal URL.
"""
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

ARGS = sys.argv[1:]
PORT = int(ARGS[0]) if ARGS and ARGS[0].isdigit() else 8099
REAL = "--real" in ARGS
_nums = [a for a in ARGS if a.isdigit()][1:] if ARGS and ARGS[0].isdigit() else []
DELAY = float(ARGS[1]) if len(ARGS) > 1 and ARGS[1] not in ("--real",) else 1.5
VOD_CATEGORIES = int(_nums[0]) if _nums else 30
ITEMS_PER_PAGE = 14
LOG = "/tmp/fakeportal/requests.log"
FIXTURES = Path(__file__).resolve().parent.parent / "app/src/test/resources/stalker"

lock = threading.Lock()


def log(line):
    with lock:
        with open(LOG, "a") as f:
            f.write(f"{time.time():.2f} {line}\n")


def fixture(name):
    with open(FIXTURES / name) as f:
        return json.load(f)


def channels():
    return {
        "total_items": 4,
        "data": [
            {"id": "1", "name": "Fake Sports 1", "number": "1", "cmd": f"ffmpeg http://127.0.0.1:{PORT}/live/1.ts",
             "logo": "", "tv_genre_id": "1", "xmltv_id": "fake1"},
            {"id": "2", "name": "Fake Sports 2", "number": "2", "cmd": f"ffmpeg http://127.0.0.1:{PORT}/live/2.ts",
             "logo": "", "tv_genre_id": "1", "xmltv_id": "fake2"},
            {"id": "3", "name": "Fake News 1", "number": "3", "cmd": f"ffmpeg http://127.0.0.1:{PORT}/live/3.ts",
             "logo": "", "tv_genre_id": "2", "xmltv_id": "fake3"},
            {"id": "4", "name": "Fake News 2", "number": "4", "cmd": f"ffmpeg http://127.0.0.1:{PORT}/live/4.ts",
             "logo": "", "tv_genre_id": "2", "xmltv_id": "fake4"},
        ],
    }


def vod_items(category_id, page):
    start = (page - 1) * ITEMS_PER_PAGE
    items = []
    for i in range(ITEMS_PER_PAGE):
        n = start + i + 1
        series = n % 4 == 0
        items.append({
            "id": f"{category_id}{n:04d}",
            "name": f"Fake {'Series' if series else 'Movie'} {category_id}-{n}",
            "o_name": f"Fake {'Series' if series else 'Movie'} {category_id}-{n}",
            "is_series": series,
            "is_movie": True,
            "category_id": str(category_id),
            "description": "Test item",
            "year": "2024",
            "genres_str": "Drama",
            "rating_imdb": "7.1",
            "time": "95",
            "screenshot_uri": f"http://127.0.0.1:{PORT}/poster/{category_id}{n:04d}.jpg",
        })
    return {"total_items": "120", "max_page_items": str(ITEMS_PER_PAGE), "data": items}


# --- real payloads -------------------------------------------------------------------------

REAL_CATEGORIES = None
DEEP = {"5": "real-movies-cat-5.json", "42": "real-series-cat-42.json"}  # the big two


def real_categories():
    global REAL_CATEGORIES
    if REAL_CATEGORIES is None:
        REAL_CATEGORIES = fixture("real-vod-get-categories.json")["js"]
    return REAL_CATEGORIES


def real_page(category_id, page):
    """Real items on page one, the rest synthesised so the claimed totals are actually there."""
    name = DEEP.get(str(category_id))
    if name is None:
        base = fixture("real-movies-cat-5.json")["js"]
        total = len(base["data"])
        if page > 1:
            return {"total_items": str(total), "max_page_items": str(ITEMS_PER_PAGE), "data": []}
        items = [dict(it, id=f"{category_id}{i:04d}", category_id=str(category_id))
                 for i, it in enumerate(base["data"])]
        return {"total_items": str(total), "max_page_items": str(ITEMS_PER_PAGE), "data": items}

    base = fixture(name)["js"]
    total = int(base["total_items"])
    start = (page - 1) * ITEMS_PER_PAGE
    if start >= total:
        return {"total_items": str(total), "max_page_items": str(ITEMS_PER_PAGE), "data": []}
    template = base["data"]
    items = []
    for i in range(ITEMS_PER_PAGE):
        n = start + i
        if n >= total:
            break
        src = template[n % len(template)]
        items.append(dict(src, id=f"{category_id}{n:06d}", category_id=str(category_id),
                          name=f"{src.get('name', 'Item')} {n + 1}"))
    return {"total_items": str(total), "max_page_items": str(ITEMS_PER_PAGE), "data": items}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):
        pass

    def send_json(self, obj, code=200):
        body = json.dumps({"js": obj}).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        t, a = q.get("type"), q.get("action")

        if u.path != "/portal.php":
            self.send_response(204)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        log(f"{t}/{a} cat={q.get('category', '')} p={q.get('p', '')}")

        if (t, a) == ("stb", "handshake"):
            self.send_json({"token": "faketoken", "not_valid": 0})
        elif (t, a) == ("stb", "get_profile"):
            self.send_json({"id": "1", "name": "fake", "login": "fake"})
        elif (t, a) == ("itv", "get_genres"):
            self.send_json([{"id": "1", "title": "Sports"}, {"id": "2", "title": "News"}])
        elif (t, a) == ("itv", "get_all_channels"):
            self.send_json(channels())
        elif (t, a) == ("vod", "get_categories"):
            self.send_json(real_categories() if REAL else
                           [{"id": str(i), "title": f"VOD Category {i}", "censored": False}
                            for i in range(1, VOD_CATEGORIES + 1)])
        elif (t, a) == ("vod", "get_ordered_list"):
            if q.get("movie_id") or q.get("episode_id"):
                self.send_json(fixture("real-movie-491960.json")["js"] if REAL else
                               {"data": [{"cmd": f"http://127.0.0.1:{PORT}/vod/play.ts"}]})
                return
            time.sleep(DELAY)
            cat, page = q.get("category", "0"), int(q.get("p", "1"))
            self.send_json(real_page(cat, page) if REAL else vod_items(cat, page))
        else:
            self.send_json({"error": "unknown"}, 404)


if __name__ == "__main__":
    os.makedirs("/tmp/fakeportal", exist_ok=True)
    open(LOG, "w").close()
    mode = "real payloads" if REAL else f"{VOD_CATEGORIES} invented categories"
    print(f"fake portal on {PORT}, {DELAY}s per VOD page, {mode}, log at {LOG}", flush=True)
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.daemon_threads = True
    server.serve_forever()
