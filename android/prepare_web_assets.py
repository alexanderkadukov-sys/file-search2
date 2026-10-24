#!/usr/bin/env python3
"""Bundle the existing public OpenChat site into the APK without adding secrets.

Run from the repository root. CI downloads the public Supabase browser SDK
separately into assets/chat/vendor/supabase.min.js.
"""
from pathlib import Path
import re
import shutil

source = Path("chat")
target = Path("android/app/src/main/assets/chat")
target.mkdir(parents=True, exist_ok=True)

web_assets = sorted(
    {p for pattern in ("*.html", "*.js", "*.css", "*.svg", "*.webmanifest")
     for p in source.glob(pattern) if p.is_file()}
)
if not web_assets:
    raise SystemExit("No frontend files found in chat/")

for path in web_assets:
    shutil.copy2(path, target / path.name)

index = target / "index.html"
html = index.read_text(encoding="utf-8")
original = "https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2/dist/umd/supabase.min.js"
assert html.count(original) == 1, "Supabase CDN reference changed; review bundle"
html = html.replace(original, "./vendor/supabase.min.js")
index.write_text(html, encoding="utf-8")

# Google Fonts can block in limited networks. System font fallback is already
# present in every frontend stylesheet, so don't fetch extra font files at app start.
for name in ("style.css",):
    path = target / name
    css = path.read_text(encoding="utf-8")
    css = re.sub(r"^@import url\(['\"]https://fonts\.googleapis\.com/[^\n]+\n",
                 "", css, flags=re.MULTILINE)
    path.write_text(css, encoding="utf-8")

# Share the PUBLIC website instead of the local Android application URL.
app = target / "app.js"
code = app.read_text(encoding="utf-8")
before = "const url = window.location.origin + window.location.pathname;"
assert code.count(before) == 1, "Share link code changed; review local bundle"
code = code.replace(before,
                    'const url = "https://alexanderkadukov-sys.github.io/file-search2/chat/";')
app.write_text(code, encoding="utf-8")

# Verify all relative script/style links are in the APK.
for link in re.findall(r'(?:src|href)="(\./[^"]+)"', html):
    relative = link.split("?", 1)[0][2:]
    if not (target / relative).exists() and not relative.startswith("vendor/"):
        raise SystemExit(f"Missing APK web asset: {link}")
if not (target / "config.js").is_file():
    raise SystemExit("Missing public Supabase browser settings")
print("Bundled", len(web_assets), "public site files into", target)
