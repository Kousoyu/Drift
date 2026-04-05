import re
html = open('baozi_img.html', 'r', encoding='utf-8').read()
match = re.search(r'data-astro-cid-[\w]+>(.*?)<\/script>', html)
if match:
    print('FOUND SCRIPT:', match.group(1)[:200])

for s in re.findall(r'<script.*?>.*?</script>', html, re.DOTALL):
    if '1782702' in s or '32973' in s:
        print('SCRIPT WITH IDS:', s[:200])
