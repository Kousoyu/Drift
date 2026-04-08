import requests
from bs4 import BeautifulSoup
import re
import time

desktop_ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'

# The sitemap has 5000 novel URLs but no titles
# We need titles to search. Two approaches:
# 1) Scrape title from each novel page (too slow for 5000)
# 2) Scrape catalog list pages which have title+cover+author (efficient)

# Key insight: /novel/1.html lists ~16 novels per page
# If we scrape the FIRST 200 pages (16 * 200 = ~3200 novels), that takes ~30s
# But for mobile, that's too heavy.

# BEST approach: Use Google/Bing Custom Search
# Test Google search API (no key needed for HTML scrape)
print("=== Google site search ===")
query = '86'
google_url = f'https://www.google.com/search?q=site:linovelib.com/novel+{query}&num=10'
try:
    r = requests.get(google_url, headers={'User-Agent': desktop_ua}, timeout=10)
    print(f'Status: {r.status_code}')
    # Google may block, but let's check
    soup = BeautifulSoup(r.text, 'html.parser')
    for a in soup.select('a'):
        href = a.get('href', '')
        if 'linovelib.com/novel/' in href:
            m = re.search(r'(https?://www\.linovelib\.com/novel/\d+\.html)', href)
            if m:
                text = a.get_text(strip=True)
                print(f'  {text} -> {m.group(1)}')
except Exception as e:
    print(f'  Error: {e}')

# Alternative: Use Bing
print("\n=== Bing site search ===")
bing_url = f'https://www.bing.com/search?q=site:linovelib.com/novel+86'
try:
    r2 = requests.get(bing_url, headers={'User-Agent': desktop_ua}, timeout=10)
    print(f'Status: {r2.status_code}')
    soup2 = BeautifulSoup(r2.text, 'html.parser')
    for a in soup2.select('a'):
        href = a.get('href', '')
        if 'linovelib.com/novel/' in href:
            m = re.search(r'(https?://www\.linovelib\.com/novel/\d+\.html)', href)
            if m:
                text = a.get_text(strip=True)[:60]
                print(f'  {text} -> {m.group(1)}')
except Exception as e:
    print(f'  Error: {e}')

# TEST: Optimal approach - download sitemap then batch fetch titles
# Fetch 50 novel pages in parallel, extract just <title>
print("\n=== Batch title extraction from sitemap IDs ===")
import concurrent.futures

# Get sitemap
r3 = requests.get('https://www.linovelib.com/sitemap.xml', headers={'User-Agent': desktop_ua}, timeout=15)
novel_urls = re.findall(r'<loc>(https://www\.linovelib\.com/novel/\d+\.html)</loc>', r3.text)
print(f'Total novel URLs in sitemap: {len(novel_urls)}')

# Fetch title only (HEAD or minimal GET)
def get_title(url):
    try:
        r = requests.get(url, headers={'User-Agent': desktop_ua}, timeout=5, stream=True)
        # Read only first 2KB to get <title>
        chunk = r.raw.read(2048).decode('utf-8', errors='ignore')
        r.close()
        title_match = re.search(r'<title>([^<]+)</title>', chunk)
        if title_match:
            title = title_match.group(1).strip()
            # Clean title: remove "- 哔哩轻小说" suffix
            title = re.sub(r'\s*[-_|]\s*哔哩轻小说.*', '', title)
            return (url, title)
    except:
        pass
    return None

# Test speed: 100 novels
start = time.time()
sample = novel_urls[:100]
with concurrent.futures.ThreadPoolExecutor(max_workers=20) as ex:
    futures = [ex.submit(get_title, u) for u in sample]
    results = [f.result() for f in concurrent.futures.as_completed(futures)]
results = [r for r in results if r]
elapsed = time.time() - start
print(f'100 titles fetched in {elapsed:.1f}s ({len(results)} succeeded)')

# Search test
for q in ['86', '教室', '转生']:
    matches = [r for r in results if q in r[1]]
    print(f'  "{q}": {[r[1] for r in matches[:3]]}')
