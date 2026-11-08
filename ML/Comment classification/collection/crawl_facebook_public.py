"""Read public Facebook comments through Chromium's normal page interface."""

import argparse
import base64
from pathlib import Path
import re
import sys
from urllib.parse import parse_qs, urlsplit, urlunsplit

from bs4 import BeautifulSoup, NavigableString

from crawl_comments import CrawlError, Output, read_source_urls, record, relative_path


COMMENTS = '[role="article"][aria-label^="Comment by "]'
REPLIES = re.compile(r"^(View (all |more )?(\d+ )?repl(?:y|ies)|.*\breplied\b.*\breplies\b)", re.I | re.S)


def facebook_url(value):
    parts = urlsplit(value.strip())
    if parts.scheme != "https" or parts.hostname not in {"facebook.com", "www.facebook.com", "m.facebook.com"} or parts.username or parts.password or parts.port not in (None, 443):
        raise CrawlError("Use HTTPS facebook.com post URLs without credentials or custom ports.")
    if not ("/posts/" in parts.path or parts.path.startswith("/share/p/") or parts.path == "/permalink.php"):
        raise CrawlError("Expected a Facebook post/share URL, not a profile, login page or home feed.")
    return value.strip()


def canonical_url(value):
    parts = urlsplit(facebook_url(value))
    query = ""
    if parts.path == "/permalink.php":
        from urllib.parse import urlencode
        query = urlencode({k: v[0] for k, v in parse_qs(parts.query).items() if k in {"story_fbid", "id"}})
    return urlunsplit(("https", "www.facebook.com", parts.path.rstrip("/"), query, ""))


def numeric_id(value):
    if re.fullmatch(r"\d+", value):
        return value
    try:
        decoded = base64.b64decode(value, validate=True).decode("utf-8")
    except (ValueError, UnicodeError):
        return ""
    match = re.fullmatch(r"comment:\d+_(\d+)", decoded)
    return match[1] if match else ""


def body_text(node):
    if isinstance(node, NavigableString):
        return str(node)
    if node.name == "img":
        return node.get("alt", "")
    if node.name == "br":
        return "\n"
    return "".join(body_text(child) for child in node.children) + ("\n" if node.name in {"div", "p"} else "")


def parse_comments(html, post_url, title):
    """Parse only rendered comment articles; never use author/profile text as body."""
    soup = BeautifulSoup(html, "html.parser")
    document = canonical_url(post_url)
    for article in soup.select(COMMENTS):
        bodies = [n for n in article.select("span[lang]") if n.find_parent(attrs={"role": "article"}) is article]
        if not bodies:
            continue
        if any(n.get_text(strip=True) in {"See more", "Xem thêm"} for body in bodies for n in body.select('[role="button"]')):
            continue
        item_id, parent_id = "", ""
        for link in article.select("a[href]"):
            if link.find_parent(attrs={"role": "article"}) is not article:
                continue
            params = parse_qs(urlsplit(link["href"]).query)
            comment_id = numeric_id(params.get("comment_id", [""])[0])
            reply_id = numeric_id(params.get("reply_comment_id", [""])[0])
            if reply_id:
                item_id, parent_id = reply_id, comment_id
                break
            if comment_id:
                item_id = comment_id
        text = "\n".join(body_text(n).strip() for n in bodies).strip()
        if not item_id or not text:
            continue
        separator = "&" if "?" in document else "?"
        url = document + separator + "comment_id=" + (parent_id or item_id)
        if parent_id:
            url += "&reply_comment_id=" + item_id
        yield record({"type": "facebook", "name": "facebook_public_browser"}, item_id, document, text, parent=parent_id, url=url, context=title)


def prepare_page(page, url):
    page.goto(url, wait_until="domcontentloaded", timeout=45000)
    page.wait_for_timeout(2000)
    comments = page.locator(COMMENTS)
    # Dismiss only the optional login overlay; never submit a login or bypass a gate.
    login = page.get_by_role("button", name="Log in to Facebook", exact=True)
    if login.count() and login.first.is_visible():
        close = page.get_by_role("button", name="Close", exact=True)
        if close.count():
            close.first.click(timeout=3000)
    comments.first.wait_for(state="attached", timeout=15000)
    facebook_url(page.url)
    ordering = page.get_by_role("button", name=re.compile(r"^(Most relevant|Newest)"))
    if ordering.count() and ordering.first.is_visible():
        ordering.first.click(timeout=3000)
        option = page.get_by_text(re.compile(r"^All comments"))
        if not option.count():
            page.keyboard.press("Escape")
            raise CrawlError("Cannot select All comments; refusing to silently collect a relevance-filtered sample.")
        option.last.click(timeout=3000)
        page.wait_for_timeout(2000)


def expand_comments(page, delay):
    for matcher in (re.compile(r"^(See more|Xem thêm)$"), REPLIES):
        # Work on comment controls only, not Like/Share or login controls.
        buttons = page.locator(COMMENTS).get_by_role("button", name=matcher) if "See more" in matcher.pattern else page.get_by_role("button", name=matcher)
        for index in range(min(buttons.count(), 3)):
            button = buttons.nth(index)
            if button.is_visible():
                button.click(timeout=3000)
                page.wait_for_timeout(delay * 1000)


def collect_post(page, url, output, limit, max_rounds, delay):
    prepare_page(page, url)
    added, idle = 0, 0
    previous_ids = set()
    for _ in range(max_rounds):
        if any(part in urlsplit(page.url).path for part in ("/login", "/checkpoint", "/challenge")):
            raise CrawlError("Facebook requires login/checkpoint; collection stopped.")
        html = page.locator(COMMENTS).evaluate_all("nodes => nodes.map(n => n.outerHTML).join('\\n')")
        rows = list(parse_comments(html, page.url, page.title()))
        ids = {row["source_id"] for row in rows}
        new_ids = ids - previous_ids
        previous_ids.update(ids)
        for row in rows:
            added += output.write(row)
            if added >= limit:
                return added, "row limit reached; partial collection"
        idle = 0 if new_ids else idle + 1
        if idle >= 5:
            return added, "no new IDs after 5 rounds; completeness is not guaranteed"
        expand_comments(page, delay)
        comments = page.locator(COMMENTS)
        if not comments.count():
            raise CrawlError("Comment list disappeared; possible login gate or interface change.")
        comments.last.evaluate("""element => {
            for (let p = element.parentElement; p; p = p.parentElement) {
                if (p.scrollHeight > p.clientHeight && /auto|scroll/.test(getComputedStyle(p).overflowY)) {
                    p.scrollBy(0, p.clientHeight); return;
                }
            }
            window.scrollBy(0, innerHeight);
        }""")
        page.wait_for_timeout(delay * 1000)
    return added, "round limit reached; partial collection"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--urls-file", default="collection/download.md", help="Markdown/text file; only Facebook post links are used")
    parser.add_argument("--output", default="data/collected/facebook_public.csv")
    parser.add_argument("--limit", type=int, default=1000, help="Maximum new rows across all posts in this run")
    parser.add_argument("--max-rounds", type=int, default=100, help="Maximum loading rounds per post")
    parser.add_argument("--delay", type=float, default=2, help="Seconds between loading actions, at least 1")
    parser.add_argument("--headless", action="store_true", help="Hide the Chromium window")
    args = parser.parse_args()
    if args.limit < 1 or args.max_rounds < 1 or not 1 <= args.delay <= 60:
        parser.error("limit/max-rounds must be positive and delay must be between 1 and 60 seconds")
    try:
        urls_path = relative_path(Path.cwd(), args.urls_file)
        urls = [facebook_url(url) for url in read_source_urls(urls_path, {"facebook.com", "www.facebook.com", "m.facebook.com"})]
        if not urls:
            raise CrawlError("No Facebook post links found in the source file.")
        output_path = relative_path(Path.cwd(), args.output)
        from playwright.sync_api import Error, sync_playwright
        with sync_playwright() as playwright:
            try:
                browser = playwright.chromium.launch(headless=args.headless)
            except Error:
                raise CrawlError("Chromium could not start; run python -m playwright install chromium.") from None
            try:
                context = browser.new_context(locale="en-US")
                context.set_default_timeout(10000)
                page = context.new_page()
                output = Output(output_path)
                failed, total = False, 0
                try:
                    for index, url in enumerate(urls, 1):
                        before = len(output.seen)
                        try:
                            _, reason = collect_post(page, url, output, args.limit - total, args.max_rounds, args.delay)
                            print(f"Post {index}: {len(output.seen) - before} new rows; {reason}.")
                        except (CrawlError, Error):
                            print(f"Post {index}: stopped (access, timeout or interface change); saved rows are preserved.", file=sys.stderr)
                            failed = True
                        total += len(output.seen) - before
                        if total >= args.limit:
                            break
                finally:
                    output.close()
                print(f"Saved {total} new rows to {args.output}. Labels remain empty.")
                return int(failed)
            finally:
                browser.close()
    except ModuleNotFoundError:
        print("Install dependencies: python -m pip install -r collection/requirements-facebook.txt", file=sys.stderr)
        return 1
    except (CrawlError, OSError, ValueError):
        print("Cannot start: check relative paths, the Facebook URL list and installed Chromium. See collection/docs/CRAWLING.md.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("Stopped; previously saved rows are preserved.", file=sys.stderr)
        sys.exit(130)
