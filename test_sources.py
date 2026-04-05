import requests

sources = [
    ("Baozi", "https://www.baozimh.com"),
    ("CopyManga", "https://api.mangacopy.com/api/v3/ranks"),
    ("DuManWu", "https://www.dumanwu.com"),
    ("Manhuagui", "https://www.manhuagui.com"),
    ("JMComic", "https://jmcomic.me")
]

headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}

for name, url in sources:
    try:
        r = requests.get(url, headers=headers, timeout=5)
        print(f"[OK] {name}: {r.status_code}")
    except Exception as e:
        print(f"[FAIL] {name}: {type(e).__name__}")
