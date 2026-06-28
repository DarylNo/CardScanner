import requests
s = requests.Session()
s.headers["User-Agent"] = "MTGCardScannerDebug/1.0"

# Confirm mom/196 is our card
r = s.get("https://api.scryfall.com/cards/mom/196", timeout=10)
if r.ok:
    d = r.json()
    print(f"mom/196 -> {d['name']} | {d['set_name']} #{d['collector_number']} | {d['rarity']} | USD {d['prices']['usd']}")
else:
    print(f"mom/196 -> {r.status_code}")

# Also show what a set+name search returns (the fallback we'll use)
print()
r2 = s.get("https://api.scryfall.com/cards/search",
           params={"q": '!"Kami of Whispered Hopes" set:mom'}, timeout=10)
if r2.ok:
    for d in r2.json().get("data", []):
        print(f"set+name search -> {d['name']} | MOM #{d['collector_number']} | {d['rarity']} | USD {d['prices']['usd']}")
