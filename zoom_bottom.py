#!/usr/bin/env python3
"""
Ultra-zoom capture of card bottom strip for stamp + collector-info analysis.
Saves multiple very-high-zoom crops and asks the vision model to describe them.
"""
import sys, cv2, numpy as np, base64

# ── camera capture ─────────────────────────────────────────────────────────────
cap = cv2.VideoCapture(0)
cap.set(cv2.CAP_PROP_FRAME_WIDTH, 1280)
cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 720)
for _ in range(15):
    cap.read()
ret, frame = cap.read()
cap.release()
if not ret:
    sys.exit("ERROR: no frame")

from mtg_card_scanner.card_detect import extract_card, CARD_W, CARD_H

card, detected = extract_card(frame)
print(f"Card detected: {detected}  size: {card.shape[1]}x{card.shape[0]}")

h, w = card.shape[:2]

def save_crop(y0f, y1f, x0f, x1f, name, scale=8):
    y0, y1 = int(h*y0f), int(h*y1f)
    x0, x1 = int(w*x0f), int(w*x1f)
    region = card[y0:y1, x0:x1]
    big = cv2.resize(region, (region.shape[1]*scale, region.shape[0]*scale),
                     interpolation=cv2.INTER_LANCZOS4)
    # sharpen
    blur = cv2.GaussianBlur(big, (0,0), 2)
    sharp = cv2.addWeighted(big, 1.8, blur, -0.8, 0)
    path = f"zoom_{name}.jpg"
    cv2.imwrite(path, sharp, [cv2.IMWRITE_JPEG_QUALITY, 98])
    print(f"  Saved {path}  ({x1-x0}x{y1-y0} px -> {sharp.shape[1]}x{sharp.shape[0]} px)")
    return sharp

print("\nSaving ultra-zoom crops...")
# Full bottom third - everything below art
save_crop(0.84, 1.00, 0.00, 1.00, "full_bottom", scale=6)
# Bottom-left: collector number + stamp area (very tight)
save_crop(0.87, 0.99, 0.00, 0.55, "left_collector", scale=8)
# Bottom-right: copyright year area
save_crop(0.87, 0.99, 0.45, 1.00, "right_copyright", scale=8)
# Stamp center: the 40% center of the very bottom
save_crop(0.90, 0.99, 0.28, 0.72, "stamp_center", scale=10)
# Extreme bottom-left corner only (where The List stamp sits)
save_crop(0.91, 0.99, 0.00, 0.30, "corner_bl", scale=12)

# ── ask the vision model to read each region ───────────────────────────────────
print("\nAsking vision model to read bottom regions...")

try:
    from openai import OpenAI
    client = OpenAI(base_url="http://localhost:11434/v1", api_key="ollama")

    def encode(img):
        _, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 98])
        return base64.b64encode(buf.tobytes()).decode()

    def ask(imgs_bgr, question):
        content = []
        for img in imgs_bgr:
            content.append({"type":"image_url","image_url":{"url":f"data:image/jpeg;base64,{encode(img)}"}})
        content.append({"type":"text","text":question})
        resp = client.chat.completions.create(
            model="qwen2.5vl:7b",
            messages=[{"role":"user","content":content}],
            temperature=0.0, max_tokens=512,
        )
        return resp.choices[0].message.content or ""

    # Load saved images for display
    full_bot = cv2.imread("zoom_full_bottom.jpg")
    left_col = cv2.imread("zoom_left_collector.jpg")
    right_cop = cv2.imread("zoom_right_copyright.jpg")
    stamp_ctr = cv2.imread("zoom_stamp_center.jpg")
    corner    = cv2.imread("zoom_corner_bl.jpg")

    print("\n--- Q1: Full bottom strip description ---")
    ans1 = ask([full_bot],
        "This is the BOTTOM STRIP of an old-frame Magic: The Gathering card, highly zoomed. "
        "Please describe EVERYTHING you can read or see:\n"
        "1. Is there a planeswalker symbol stamp (oval/diamond with a hood-like shape) anywhere? "
        "   The List cards have a small planeswalker stamp in the very bottom center or left area.\n"
        "2. Is there a collector number printed (like '296' or 'MIR-296' or '305/332')?\n"
        "3. Is there a set code printed (like 'MIR' or 'CLB' or 'PLST')?\n"
        "4. What does the copyright line say exactly (especially the year)?\n"
        "5. Is there any small icon, symbol, or logo?\n"
        "Read every character of text you can see, no matter how small.")
    print(ans1)

    print("\n--- Q2: Bottom-left corner stamp and collector ---")
    ans2 = ask([left_col, corner],
        "These are EXTREME close-ups of the bottom-left corner of a Magic card. "
        "Look for:\n"
        "1. A planeswalker symbol stamp (The List cards have this — it looks like a person in a hood/cloak inside an oval). "
        "   Do you see any such stamp or icon?\n"
        "2. Any text like a collector number (digits) or set code?\n"
        "3. Any logo, icon, or special mark that wouldn't appear on an original 1996 Mirage card?\n"
        "Describe in detail what you see, and state whether this looks like an original 1996 Mirage printing or a newer reprint.")
    print(ans2)

    print("\n--- Q3: Copyright year ---")
    ans3 = ask([right_cop, full_bot],
        "Read the copyright line at the bottom of this Magic card. "
        "It should say something like '(C) 1996 Wizards of the Coast' or '(C) 1999 Wizards of the Coast'. "
        "What EXACT year appears in the copyright line? "
        "Also: is there any trademark symbol (TM or R) near the card name or anywhere else that would indicate a reprint?")
    print(ans3)

    print("\n--- Q4: Stamp center area ---")
    ans4 = ask([stamp_ctr],
        "This is the CENTER of the bottom of a Magic: The Gathering card. "
        "Is there a planeswalker symbol stamp here? The List reprints have a small icon here. "
        "Describe anything you see — any symbol, icon, watermark, or text.")
    print(ans4)

except Exception as e:
    print(f"Vision model error: {e}")

print("\nDone.")
