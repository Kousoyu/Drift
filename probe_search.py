import requests, re

headers = {'User-Agent': 'Mozilla/5.0 (Linux; Android 11; SM-G991B) AppleWebKit/537.36'}
r = requests.get('https://www.baozimh.org/?s=%E4%BD%A0', headers=headers, timeout=12)
print('Status:', r.status_code, 'Length:', len(r.text))

# Save to file for inspection
with open('search_result.html', 'w', encoding='utf-8') as f:
    f.write(r.text)

# Find manga links
links = re.findall(r'href="(/manga/[^"]+)"', r.text)
print('manga links:', len(links), links[:5])

# Find any link with common search result wrappers
links2 = re.findall(r'<article[^>]*>.*?href="([^"]+)"[^>]*>([^<]+)', r.text[:50000], re.DOTALL)
print('article links:', len(links2), links2[:3])

# Look for img tags in potential result blocks
covers = re.findall(r'<img[^>]+src="([^"]+)"[^>]+class="[^"]*wp-post-image[^"]*"', r.text)
print('cover imgs:', len(covers), covers[:3])

# Check for any href patterns matching manga
all_hrefs = re.findall(r'href="(https://www\.baozimh\.org[^"]+)"', r.text)
print('full baozimh hrefs:', len(all_hrefs), all_hrefs[:10])
