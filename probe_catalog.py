import requests
from bs4 import BeautifulSoup

# Try both domains
for domain in ['www.bilinovel.com', 'www.linovelib.com']:
    desktop_ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'
    
    # Try top/monthvisit to find the novel
    url = f'https://{domain}/top/monthvisit/1.html'
    try:
        r = requests.get(url, headers={'User-Agent': desktop_ua}, timeout=10, allow_redirects=True)
        print(f'Domain: {domain} -> {r.url} (status {r.status_code})')
        soup = BeautifulSoup(r.text, 'html.parser')
        
        for a in soup.select('a[href*="/novel/"]')[:30]:
            text = a.get_text(strip=True)
            href = a.get('href', '')
            if '实力' in text or '教室' in text or '欢迎' in text:
                # Extract novel ID
                import re
                m = re.search(r'/novel/(\d+)', href)
                if m:
                    nid = m.group(1)
                    print(f'  Found: "{text}" -> ID={nid}')
                    
                    # Now fetch its catalog
                    cat_url = f'https://{domain}/novel/{nid}/catalog'
                    r2 = requests.get(cat_url, headers={'User-Agent': desktop_ua}, timeout=15, allow_redirects=True)
                    print(f'  Catalog URL: {r2.url} (status {r2.status_code})')
                    s2 = BeautifulSoup(r2.text, 'html.parser')
                    vols = s2.select('div.volume')
                    print(f'  div.volume count: {len(vols)}')
                    for i, v in enumerate(vols[:3]):
                        h2 = v.select_one('h2')
                        img = v.select_one('img[data-original]')
                        cover = img['data-original'] if img else 'NONE'
                        chs = len(v.select('ul li a'))
                        print(f'    Vol {i+1}: {h2.get_text(strip=True) if h2 else "?"} | cover={cover} | chapters={chs}')
                    break
    except Exception as e:
        print(f'Domain: {domain} -> Error: {e}')
