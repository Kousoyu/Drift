import requests
from bs4 import BeautifulSoup

desktop_ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'

r = requests.get('https://www.linovelib.com/?s=%E6%95%99%E5%AE%A4', headers={'User-Agent': desktop_ua}, timeout=10)
soup = BeautifulSoup(r.text, 'html.parser')

# Look for search result containers
print("=== Search result structure ===")
# Common patterns: .search-result, .book-list, .search-list, .result-list
for sel in ['.search-result', '.book-list', '.search-list', '.result-list', '.book-ol', '#search-result', '.hot-article']:
    found = soup.select(sel)
    if found:
        print(f'\n{sel}: {len(found)} elements')
        for el in found[:2]:
            print(str(el)[:500])

# Just show all <a href="/novel/N.html"> with their parent context
print("\n=== Novel detail links ===")
import re
for a in soup.select('a'):
    href = a.get('href', '')
    if re.match(r'/novel/\d+\.html$', href):
        text = a.get_text(strip=True)
        parent = a.parent
        # Look for cover img nearby
        container = a.find_parent(['div', 'li', 'article'])
        cover = ''
        author = ''
        if container:
            img = container.select_one('img')
            if img:
                cover = img.get('data-original', '') or img.get('data-src', '') or img.get('src', '')
            auth = container.select_one('.author a, .book-author')
            if auth:
                author = auth.get_text(strip=True)
        print(f'  "{text}" href={href} cover={cover} author={author}')
        if container:
            print(f'    Container: <{container.name} class="{container.get("class")}">')
