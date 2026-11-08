"""Collect unlabelled comments from explicit videos, Page posts and HTML pages."""

import argparse
import csv
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sys
import time
from datetime import datetime, timezone
from urllib.parse import parse_qs, urlencode, urljoin, urlsplit
from urllib.robotparser import RobotFileParser

import requests
from bs4 import BeautifulSoup
from dotenv import load_dotenv
from soupsieve import SelectorSyntaxError


FIELDS = (
    "source", "source_name", "source_id", "document_id", "parent_id",
    "free_text", "published_at", "collected_at", "url", "context", "label_id",
)


class CrawlError(Exception):
    pass


def required_env(name):
    value = os.environ.get(name, "").strip()
    if not value:
        raise CrawlError(f"Missing environment variable: {name}")
    return value


def relative_path(base, value):
    path = Path(value)
    if path.is_absolute() or path.drive or re.match(r"^[A-Za-z]:", str(value)) or str(value).startswith("\\"):
        raise CrawlError("File paths in config must be relative.")
    return base / path


def read_source_urls(path, hosts):
    text = path.read_text(encoding="utf-8-sig")
    urls = re.findall(r"https?://[^\s<>\[\]()\"'`]+", text)
    return list(dict.fromkeys(url for url in urls if urlsplit(url).hostname in hosts))


def youtube_ids_from_source(path):
    urls = read_source_urls(path, {"youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be"})
    ids = []
    for url in urls:
        parts = urlsplit(url)
        if parts.scheme != "https" or parts.username or parts.password or parts.port not in (None, 443):
            raise CrawlError("YouTube source links must use HTTPS without credentials or custom ports.")
        video_id = parts.path.strip("/") if parts.hostname == "youtu.be" else parse_qs(parts.query).get("v", [""])[0] if parts.path == "/watch" else ""
        if not re.fullmatch(r"[A-Za-z0-9_-]{11}", video_id):
            raise CrawlError("Expected YouTube watch/youtu.be links with 11-character video IDs.")
        ids.append(video_id)
    if not ids:
        raise CrawlError("No YouTube video links found in the source file.")
    return list(dict.fromkeys(ids))


def origin(url):
    parts = urlsplit(url)
    if parts.scheme != "https" or not parts.hostname or parts.username or parts.password or parts.port not in (None, 443):
        raise CrawlError("Only HTTPS URLs without credentials or custom ports are supported.")
    return f"https://{parts.netloc.lower()}"


class Http:
    def __init__(self, user_agent, delay=1.0, max_requests=200):
        self.session = requests.Session()
        self.session.headers["User-Agent"] = user_agent
        self.delay = delay
        self.max_requests = max_requests
        self.count = 0
        self.last_request = 0.0

    def get(self, url, *, params=None, headers=None, delay=0, allow_missing=False):
        for attempt in range(3):
            if self.count >= self.max_requests:
                raise CrawlError("Request budget reached; collected rows were preserved.")
            time.sleep(max(0, max(self.delay, delay) - (time.monotonic() - self.last_request)))
            self.count += 1
            self.last_request = time.monotonic()
            try:
                response = self.session.get(url, params=params, headers=headers, timeout=(10, 30), allow_redirects=False)
            except requests.RequestException:
                # Exception URLs can contain API credentials; do not print them.
                raise CrawlError("Network request failed or timed out.") from None
            if response.status_code == 404 and allow_missing:
                return response
            if response.status_code == 429 or 500 <= response.status_code < 600:
                if attempt < 2:
                    retry_after = response.headers.get("Retry-After", "")
                    wait = float(retry_after) if re.fullmatch(r"\d+(\.\d+)?", retry_after) else 2 ** (attempt + 1)
                    if wait > 60:
                        raise CrawlError("Server requested a long cooldown; rerun later.")
                    time.sleep(wait)
                    continue
            if 300 <= response.status_code < 400:
                raise CrawlError("Redirect refused; configure the canonical HTTPS URL.")
            if not 200 <= response.status_code < 300:
                raise CrawlError(f"HTTP {response.status_code}; check access, quota and source IDs.")
            return response
        raise CrawlError("Request failed after retries.")

    def json(self, url, **kwargs):
        try:
            data = self.get(url, **kwargs).json()
        except ValueError:
            raise CrawlError("API response is not JSON.") from None
        if not isinstance(data, dict) or "error" in data:
            raise CrawlError("API returned an error or an unexpected response.")
        return data


class Output:
    def __init__(self, path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.seen = set()
        if path.exists() and path.stat().st_size:
            with path.open(encoding="utf-8-sig", newline="") as stream:
                reader = csv.DictReader(stream)
                if reader.fieldnames != list(FIELDS):
                    raise CrawlError("Existing output has a different schema; use another output path.")
                for row in reader:
                    if None in row or any(value is None for value in row.values()):
                        raise CrawlError("Existing output contains an incomplete row; inspect it before rerunning.")
                    self.seen.add((row["source"], row["source_name"], row["source_id"]))
        has_header = path.exists() and path.stat().st_size > 0
        self.stream = path.open("a", encoding="utf-8", newline="")
        self.writer = csv.DictWriter(self.stream, fieldnames=FIELDS)
        if not has_header:
            self.writer.writeheader()

    def write(self, row):
        if not row["free_text"].strip():
            return False
        key = (row["source"], row["source_name"], row["source_id"])
        if key in self.seen:
            return False
        self.writer.writerow(row)
        self.stream.flush()
        self.seen.add(key)
        return True

    def close(self):
        self.stream.close()


def record(source, item_id, document_id, text, *, parent="", published="", url="", context=""):
    return dict(zip(FIELDS, (
        source["type"], source["name"], str(item_id), str(document_id), str(parent),
        text, published or "", datetime.now(timezone.utc).isoformat(), url, context, "",
    )))


def youtube(http, source):
    key = required_env("YOUTUBE_API_KEY")
    endpoint = "https://www.googleapis.com/youtube/v3/"

    def pages(method, params):
        for _ in range(source.get("max_pages", 10)):
            data = http.json(endpoint + method, params={**params, "key": key})
            if not isinstance(data.get("items"), list):
                raise CrawlError("YouTube response is missing items.")
            yield from data["items"]
            token = data.get("nextPageToken")
            if not token:
                return
            params = {**params, "pageToken": token}
        raise CrawlError("YouTube page limit reached; this source is partial.")

    for index, video_id in enumerate(source["video_ids"], 1):
        print(f"[YouTube] Video {index}/{len(source['video_ids'])}: {video_id}", flush=True)
        params = dict(part="snippet", videoId=video_id, maxResults=100, order="time", textFormat="plainText")
        for thread in pages("commentThreads", params):
            top = thread["snippet"]["topLevelComment"]
            snippet = top["snippet"]
            url = f"https://www.youtube.com/watch?{urlencode({'v': video_id})}"
            yield record(source, top["id"], video_id, snippet["textDisplay"], published=snippet.get("publishedAt"), url=url)
            if source.get("include_replies", True) and thread["snippet"].get("totalReplyCount", 0):
                for reply in pages("comments", dict(part="snippet", parentId=top["id"], maxResults=100, textFormat="plainText")):
                    snippet = reply["snippet"]
                    yield record(source, reply["id"], video_id, snippet["textDisplay"], parent=top["id"], published=snippet.get("publishedAt"), url=url, context=top["snippet"]["textDisplay"])


def facebook(http, source):
    token = required_env("FACEBOOK_PAGE_ACCESS_TOKEN")
    version = required_env("META_GRAPH_VERSION")
    if not re.fullmatch(r"v\d+\.\d+", version):
        raise CrawlError("META_GRAPH_VERSION must have the form vXX.X, matching your Meta app.")
    headers = {"Authorization": f"Bearer {token}"}

    def comments(object_id):
        params = {"fields": "id,message,created_time", "limit": 100, "filter": "toplevel"}
        for _ in range(source.get("max_pages", 10)):
            data = http.json(f"https://graph.facebook.com/{version}/{object_id}/comments", params=params, headers=headers)
            if not isinstance(data.get("data"), list):
                raise CrawlError("Facebook response is missing data.")
            yield from data["data"]
            paging = data.get("paging", {})
            if not paging.get("next"):
                return
            after = paging.get("cursors", {}).get("after")
            if not after:
                raise CrawlError("Facebook pagination cursor is missing.")
            # Rebuild the URL so access tokens in paging.next are never followed or saved.
            params = {**params, "after": after}
        raise CrawlError("Facebook page limit reached; this source is partial.")

    for post_id in source["post_ids"]:
        for top in comments(post_id):
            yield record(source, top["id"], post_id, top.get("message", ""), published=top.get("created_time"))
            if source.get("include_replies", True):
                for reply in comments(top["id"]):
                    yield record(source, reply["id"], post_id, reply.get("message", ""), parent=top["id"], published=reply.get("created_time"), context=top.get("message", ""))


def html_rows(source, content, url, document_id=None):
    document_id = document_id or url
    soup = BeautifulSoup(content, "html.parser")
    context_node = soup.select_one(source.get("context_selector", "title"))
    context = context_node.get_text(" ", strip=True) if context_node else ""
    for node in soup.select(source["comment_selector"]):
        text_node = node.select_one(source["text_selector"])
        if text_node is None:
            raise CrawlError("A comment has no matching text_selector; inspect the selectors.")
        text = text_node.get_text(" ", strip=True)
        local_id = node.get(source.get("id_attribute", "data-comment-id"))
        identity = f"{document_id}\0{local_id}" if local_id else f"{url}\0{text}"
        item_id = hashlib.sha256(identity.encode()).hexdigest()
        parent = node.get(source.get("parent_attribute", "data-parent-id"), "")
        parent = hashlib.sha256(f"{document_id}\0{parent}".encode()).hexdigest() if parent else ""
        timestamp = node.select_one(source["time_selector"]) if source.get("time_selector") else None
        published = timestamp.get("datetime", "") if timestamp else ""
        yield record(source, item_id, document_id, text, parent=parent, published=published, url=url, context=context)
    next_node = soup.select_one(source["next_selector"]) if source.get("next_selector") else None
    return urljoin(url, next_node["href"]) if next_node and next_node.get("href") else None


def website(http, source):
    robots = {}
    for start_url in source["start_urls"]:
        allowed_origin = origin(start_url)
        if allowed_origin not in robots:
            response = http.get(allowed_origin + "/robots.txt", allow_missing=True)
            rules = RobotFileParser()
            rules.parse([] if response.status_code == 404 else response.text.splitlines())
            robots[allowed_origin] = rules
        rules = robots[allowed_origin]
        url = start_url
        visited = set()
        for _ in range(source.get("max_pages", 10)):
            if origin(url) != allowed_origin:
                raise CrawlError("Website pagination left the configured origin.")
            if url in visited:
                raise CrawlError("Website pagination loop detected.")
            visited.add(url)
            if not rules.can_fetch(http.session.headers["User-Agent"], url):
                raise CrawlError("robots.txt disallows this page.")
            delay = rules.crawl_delay(http.session.headers["User-Agent"]) or 0
            rate = rules.request_rate(http.session.headers["User-Agent"])
            if rate and rate.requests > 0:
                delay = max(delay, rate.seconds / rate.requests)
            response = http.get(url, delay=delay)
            if "html" not in response.headers.get("Content-Type", "").lower():
                raise CrawlError("Website response is not HTML.")
            rows = html_rows(source, response.content, url, start_url)
            count = 0
            while True:
                try:
                    row = next(rows)
                except StopIteration as result:
                    next_url = result.value
                    break
                count += 1
                yield row
            if not count:
                raise CrawlError("No comments found: check selectors or JavaScript-loaded comments.")
            if not next_url:
                break
            url = next_url
        else:
            raise CrawlError("Website page limit reached; this source is partial.")


def validate(config, selected=None):
    if not isinstance(config, dict) or not isinstance(config.get("sources"), list) or any(not isinstance(s, dict) for s in config["sources"]):
        raise CrawlError("Config must contain a sources list of objects.")
    sources = [s for s in config["sources"] if s.get("enabled", True) and (not selected or s["name"] in selected)]
    if not sources:
        raise CrawlError("No enabled source matched. Edit config and enable at least one source.")
    if selected and set(selected) - {s["name"] for s in sources}:
        raise CrawlError("A selected source does not exist or is disabled.")
    names = set()
    for source in sources:
        name = source["name"]
        if not name or name in names:
            raise CrawlError("Enabled sources must have unique, nonempty names.")
        names.add(name)
        if type(source.get("max_pages", 10)) is not int or source.get("max_pages", 10) < 1:
            raise CrawlError("max_pages must be a positive integer.")
        if "max_comments" in source and (type(source["max_comments"]) is not int or source["max_comments"] < 1):
            raise CrawlError("max_comments must be a positive integer when specified.")
        kind = source["type"]
        if kind in ("youtube", "facebook"):
            field = "video_ids" if kind == "youtube" else "post_ids"
            pattern = r"[A-Za-z0-9_-]{11}" if kind == "youtube" else r"\d+(_\d+)?"
            ids = source[field]
            if not isinstance(ids, list) or not ids or any(not isinstance(item, str) or not re.fullmatch(pattern, item) for item in ids):
                raise CrawlError(f"{field} must be a nonempty list of valid IDs, not URLs.")
        elif kind in ("news", "forum"):
            if not isinstance(source.get("start_urls"), list) or not source["start_urls"] or not isinstance(source.get("access_basis"), str) or not source["access_basis"].strip():
                raise CrawlError("Website sources require start_urls and an access_basis note.")
            for url in source["start_urls"]:
                origin(url)
            for key in ("comment_selector", "text_selector"):
                if not source.get(key):
                    raise CrawlError(f"Website source requires {key}.")
            try:
                for key in ("comment_selector", "text_selector", "time_selector", "context_selector", "next_selector"):
                    if source.get(key):
                        BeautifulSoup("", "html.parser").select(source[key])
            except (SelectorSyntaxError, TypeError):
                raise CrawlError("Invalid website CSS selector.") from None
        else:
            raise CrawlError(f"Unknown source type: {kind}")
    return sources


def report_progress(added, limit, duplicates, requests_count):
    print(f"[Progress] Saved: {added}/{limit} new | Duplicates skipped: {duplicates} | Requests: {requests_count}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default="collection/config.json", help="Relative to the current working directory")
    parser.add_argument("--source", action="append", help="Source name; repeat to select multiple sources")
    parser.add_argument("--sources-file", help="Read YouTube video links from a relative Markdown/text file")
    parser.add_argument("--limit", type=int, default=1000, help="Maximum new rows in this run")
    parser.add_argument("--validate-only", action="store_true", help="Check config without network requests")
    args = parser.parse_args()
    try:
        load_dotenv(Path(__file__).parent.parent / ".env", override=False, encoding="utf-8-sig", interpolate=False)
        config_path = relative_path(Path.cwd(), args.config)
        config = json.loads(config_path.read_text(encoding="utf-8-sig"))
        if args.sources_file:
            video_ids = youtube_ids_from_source(relative_path(Path.cwd(), args.sources_file))
            for source in config["sources"]:
                if source["type"] == "youtube":
                    source["video_ids"] = video_ids
        sources = validate(config, args.source)
        output_path = relative_path(config_path.parent, config["output"])
        delay = config.get("delay_seconds", 1)
        budget = config.get("max_requests", 200)
        if args.limit < 1 or not isinstance(delay, (int, float)) or not math.isfinite(delay) or delay < 0 or type(budget) is not int or budget < 1:
            raise CrawlError("limit/max_requests must be positive; delay_seconds must be nonnegative.")
        if not isinstance(config.get("user_agent"), str) or not config["user_agent"].strip():
            raise CrawlError("Configure an identifiable user_agent.")
        if args.validate_only:
            print(f"Config valid: {len(sources)} enabled source(s). Credentials and site access were not checked.")
            return 0
        print(f"Starting {len(sources)} source(s). Target: up to {args.limit} new comments. Reading existing CSV...", flush=True)
        http = Http(config["user_agent"], delay, budget)
        output = Output(output_path)
        print(f"Existing IDs: {len(output.seen)}. Output: {config['output']} (relative to config).", flush=True)
        added = duplicates = processed = 0
        last_report = time.monotonic()
        failed = False
        try:
            for source in sources:
                before = added
                print(f"[{source['name']}] Starting ({source['type']}).", flush=True)
                report_progress(added, args.limit, duplicates, http.count)
                try:
                    collector = {"youtube": youtube, "facebook": facebook, "news": website, "forum": website}[source["type"]]
                    for row in collector(http, source):
                        written = output.write(row)
                        added += written
                        duplicates += int(not written and bool(row["free_text"].strip()))
                        processed += 1
                        now = time.monotonic()
                        if processed % 25 == 0 or now - last_report >= 3:
                            report_progress(added, args.limit, duplicates, http.count)
                            last_report = now
                        if added >= args.limit:
                            print(f"Row limit reached: {added} new rows; collection is partial.", flush=True)
                            return int(failed)
                        if source.get("max_comments") and added - before >= source["max_comments"]:
                            print(f"{source['name']}: configured comment limit reached.", flush=True)
                            break
                except (CrawlError, KeyError, TypeError, ValueError) as error:
                    print(f"{source['name']}: {error}", file=sys.stderr)
                    failed = True
                print(f"{source['name']}: {added - before} new rows", flush=True)
        finally:
            output.close()
            http.session.close()
            report_progress(added, args.limit, duplicates, http.count)
            print("Saved rows are in CSV. Labels remain empty.", flush=True)
        return int(failed)
    except (CrawlError, OSError, KeyError, TypeError, ValueError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("Stopped; previously flushed rows are preserved.", file=sys.stderr)
        sys.exit(130)
