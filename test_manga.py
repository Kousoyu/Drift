import re
import execjs
import lzstring
import json

html = open("test.html", "r", encoding="utf-8").read()
packed_regex = re.compile(r'window\[".*?"\]\((function(?:.*)\s*\{[\s\S]+\}\s*\(.*\))\)')
match = packed_regex.search(html)

if not match:
    print("Cannot find packed JS code!")
else:
    js_code = match.group(1)
    
    def repl(m):
        base64_str = m.group(1)
        lz = lzstring.LZString()
        decoded = lz.decompressFromBase64(base64_str)
        return f"'{decoded}'.split('|')"

    js_code = re.sub(r'[\'"]([0-9A-Za-z+/=]+)[\'"]\[[\'"].*?[\'"]\]\([\'"].*?[\'"]\)', repl, js_code)
    js_code = js_code.replace("\\'", "-")
    
    try:
        ctx = execjs.compile("""
        function doUnpack() {
            var raw = """ + js_code + """;
            return raw;
        }
        """)
        unpacked = ctx.call("doUnpack")
        cc_match = re.search(r'\{.*\}', unpacked)
        if cc_match:
            print("JSON: ", cc_match.group(0)[:500])
    except Exception as e:
        print("Error evaluating:", e)
