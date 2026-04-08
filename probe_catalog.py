import requests

r = requests.get('https://www.linovelib.com', allow_redirects=True, timeout=10)
domain = r.url.split('/')[2]

desktop_ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'
url = f'https://{domain}/novel/2837/catalog'
r2 = requests.get(url, headers={'User-Agent': desktop_ua}, timeout=15)
html = r2.text

from bs4 import BeautifulSoup
soup = BeautifulSoup(html, 'html.parser')

# Show complete inner HTML of first volume div
vol1 = soup.select_one('div.volume')
if vol1:
    print('=== FULL Volume 1 div HTML ===')
    print(str(vol1)[:3000])
    print()
    
    # Check for ul INSIDE volume div
    inner_uls = vol1.select('ul')
    print(f'Inner <ul> count: {len(inner_uls)}')
    for ul in inner_uls:
        lis = ul.select('li a')
        print(f'  <ul> with {len(lis)} links')
        for li in lis[:3]:
            print(f'    {li.get_text(strip=True)} → {li.get("href")}')

# Also check: is there a single container wrapping everything?
containers = soup.select('.book-catalog, #book-catalog, .catalog-list, #volumes')
print(f'\nContainers found: {len(containers)}')
for c in containers:
    print(f'  <{c.name} class="{c.get("class")}">')
